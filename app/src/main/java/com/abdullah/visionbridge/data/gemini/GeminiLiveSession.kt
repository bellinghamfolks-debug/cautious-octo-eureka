package com.abdullah.visionbridge.data.gemini

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Base64
import com.abdullah.visionbridge.capture.CaptureRuntime
import com.abdullah.visionbridge.data.diagnostics.DiagnosticHub
import com.abdullah.visionbridge.data.speech.LivePcmAudioPlayer
import com.abdullah.visionbridge.domain.model.AnalysisMode
import com.abdullah.visionbridge.domain.model.AnalysisResult
import com.abdullah.visionbridge.domain.model.AnalysisSource
import com.abdullah.visionbridge.domain.model.AppSettings
import com.abdullah.visionbridge.domain.model.CaptureProfile
import com.abdullah.visionbridge.domain.model.SceneDescriptionStyle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Persistent Gemini Live transport for every cloud visual task.
 *
 * Local text reading remains PP-OCR. Once the user chooses cloud reading, however, TEXT_READING and
 * SCENE_DESCRIPTION both use this warm WebSocket and native streaming audio. The legacy 3.1.1 cloud
 * path is kept only as a failure fallback.
 */
class GeminiLiveSession(
    private val runtime: CaptureRuntime,
    private val audioPlayer: LivePcmAudioPlayer,
) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val imageEnhancer = TextImageEnhancer()
    private val socketLock = Any()
    private val transcriptLock = Any()
    private val sendLock = Any()

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var setupReady: CompletableDeferred<Boolean>? = null
    @Volatile private var setupSucceeded = false
    @Volatile private var keyFingerprint: String? = null
    @Volatile private var activeTurnEpoch = 0L
    @Volatile private var activeTurnSentAtNanos = 0L
    @Volatile private var firstAudioSeenForEpoch = Long.MIN_VALUE
    @Volatile private var activeSpeechEnabled = true
    @Volatile private var lastMode: AnalysisMode? = null

    private var transcript = StringBuilder()
    private var lastFrameSentAtElapsedMs = 0L

    suspend fun submitFrame(bitmap: Bitmap, settings: AppSettings, apiKey: String): Boolean {
        if (apiKey.isBlank()) return false
        if (settings.forceCellular) {
            DiagnosticHub.record(
                "LIVE_FRAME_FALLBACK",
                mapOf("reason" to "force_cellular_requires_legacy_bound_network"),
            )
            return false
        }

        val now = SystemClock.elapsedRealtime()
        synchronized(sendLock) {
            if (lastMode != settings.mode) {
                lastMode = settings.mode
                lastFrameSentAtElapsedMs = 0L
            }
            val elapsed = now - lastFrameSentAtElapsedMs
            if (lastFrameSentAtElapsedMs > 0L && elapsed < LIVE_VIDEO_INTERVAL_MS) {
                DiagnosticHub.record(
                    "LIVE_FRAME_DROPPED",
                    mapOf(
                        "reason" to "live_video_one_fps_limit",
                        "elapsedMs" to elapsed,
                        "minimumMs" to LIVE_VIDEO_INTERVAL_MS,
                        "mode" to settings.mode.name,
                    ),
                )
                return true
            }
        }

        return coroutineScope {
            val connection = async(Dispatchers.IO) { ensureConnected(apiKey) }
            val encoded = async(Dispatchers.Default) {
                imageEnhancer.prepare(
                    source = bitmap,
                    mode = settings.mode,
                    captureProfile = settings.captureProfile,
                    sceneDescriptionStyle = settings.sceneDescriptionStyle,
                )
            }

            val connected = connection.await()
            val image = encoded.await()
            if (!connected) {
                DiagnosticHub.record("LIVE_FRAME_FALLBACK", mapOf("reason" to "setup_not_ready"))
                return@coroutineScope false
            }

            val currentSocket = socket ?: return@coroutineScope false
            val epoch = audioPlayer.beginTurn()
            activeTurnEpoch = epoch
            activeTurnSentAtNanos = SystemClock.elapsedRealtimeNanos()
            firstAudioSeenForEpoch = Long.MIN_VALUE
            activeSpeechEnabled = settings.speechEnabled
            synchronized(transcriptLock) { transcript = StringBuilder() }

            val base64Started = SystemClock.elapsedRealtimeNanos()
            val imageBase64 = Base64.encodeToString(image.bytes, Base64.NO_WRAP)
            val videoSent = currentSocket.send(
                videoMessage(imageBase64, image.mimeType, liveMediaResolution(settings))
            )
            val instructionSent = currentSocket.send(realtimeTextMessage(instructionFor(settings)))
            if (!videoSent || !instructionSent) {
                DiagnosticHub.record(
                    "LIVE_FRAME_FALLBACK",
                    mapOf(
                        "reason" to "websocket_send_failed",
                        "videoSent" to videoSent,
                        "instructionSent" to instructionSent,
                        "mode" to settings.mode.name,
                    ),
                )
                invalidateSocket("send_failed")
                return@coroutineScope false
            }

            synchronized(sendLock) { lastFrameSentAtElapsedMs = SystemClock.elapsedRealtime() }
            DiagnosticHub.record(
                "LIVE_FRAME_SENT",
                mapOf(
                    "model" to LIVE_MODEL,
                    "mode" to settings.mode.name,
                    "captureProfile" to settings.captureProfile.name,
                    "sceneStyle" to settings.sceneDescriptionStyle.name,
                    "encodedBytes" to image.bytes.size,
                    "outputWidth" to image.outputWidth,
                    "outputHeight" to image.outputHeight,
                    "format" to image.format,
                    "quality" to image.quality,
                    "base64Ms" to
                        (SystemClock.elapsedRealtimeNanos() - base64Started) / 1_000_000.0,
                    "nativeAudio" to settings.speechEnabled,
                    "cloudTransport" to "LIVE_WEBSOCKET",
                    "epoch" to epoch,
                ),
            )
            true
        }
    }

    fun onVisualTargetChanged(interruptSpeech: Boolean) {
        synchronized(sendLock) { lastFrameSentAtElapsedMs = 0L }
        synchronized(transcriptLock) { transcript = StringBuilder() }
        if (interruptSpeech) audioPlayer.interrupt("visual_target_changed")
        DiagnosticHub.record("LIVE_VISUAL_TARGET_CHANGED", mapOf("interruptSpeech" to interruptSpeech))
    }

    fun reset() {
        synchronized(sendLock) {
            lastFrameSentAtElapsedMs = 0L
            lastMode = null
        }
        synchronized(transcriptLock) { transcript = StringBuilder() }
        audioPlayer.interrupt("live_session_reset")
        invalidateSocket("session_reset")
    }

    fun stop() {
        synchronized(sendLock) { lastFrameSentAtElapsedMs = 0L }
        synchronized(transcriptLock) { transcript = StringBuilder() }
        audioPlayer.interrupt("capture_stopped")
        invalidateSocket("capture_stopped")
    }

    private suspend fun ensureConnected(apiKey: String): Boolean {
        val fingerprint = fingerprint(apiKey)
        val ready: CompletableDeferred<Boolean>
        synchronized(socketLock) {
            val existing = setupReady
            if (socket != null && keyFingerprint == fingerprint && setupSucceeded && existing != null) {
                return true
            }
            if (socket != null && keyFingerprint == fingerprint && existing != null) {
                ready = existing
            } else {
                socket?.close(NORMAL_CLOSE_CODE, "replace_live_session")
                val deferred = CompletableDeferred<Boolean>()
                ready = deferred
                setupReady = deferred
                setupSucceeded = false
                keyFingerprint = fingerprint
                val url = LIVE_ENDPOINT.toHttpUrl().newBuilder()
                    .addQueryParameter("key", apiKey)
                    .build()
                socket = client.newWebSocket(
                    Request.Builder().url(url).build(),
                    createListener(fingerprint, deferred),
                )
                DiagnosticHub.record(
                    "LIVE_SOCKET_CONNECTING",
                    mapOf("model" to LIVE_MODEL, "endpointHost" to url.host),
                )
            }
        }
        return withTimeoutOrNull(LIVE_SETUP_TIMEOUT_MS) { ready.await() } == true
    }

    private fun createListener(
        expectedFingerprint: String,
        ready: CompletableDeferred<Boolean>,
    ): WebSocketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            DiagnosticHub.record(
                "LIVE_SOCKET_OPEN",
                mapOf("httpCode" to response.code, "protocol" to response.protocol.toString()),
            )
            if (!webSocket.send(setupMessage()) && !ready.isCompleted) ready.complete(false)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (keyFingerprint == expectedFingerprint) handleServerMessage(text, ready)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            DiagnosticHub.record("LIVE_BINARY_MESSAGE_IGNORED", mapOf("bytes" to bytes.size))
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!ready.isCompleted) ready.complete(false)
            clearSocketIfCurrent(webSocket)
            DiagnosticHub.record("LIVE_SOCKET_CLOSED", mapOf("code" to code, "reason" to reason))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!ready.isCompleted) ready.complete(false)
            clearSocketIfCurrent(webSocket)
            DiagnosticHub.failure(
                "LIVE_SOCKET_FAILURE",
                t,
                mapOf("httpCode" to response?.code, "model" to LIVE_MODEL),
            )
        }
    }

    private fun handleServerMessage(raw: String, ready: CompletableDeferred<Boolean>) {
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrElse { error ->
            DiagnosticHub.failure("LIVE_JSON_PARSE", error, mapOf("characters" to raw.length))
            return
        }

        if (root.containsKey("setupComplete")) {
            setupSucceeded = true
            if (!ready.isCompleted) ready.complete(true)
            DiagnosticHub.record("LIVE_SETUP_COMPLETE", mapOf("model" to LIVE_MODEL))
        }
        root["error"]?.let { DiagnosticHub.record("LIVE_API_ERROR", mapOf("error" to it.toString())) }

        val serverContent = root["serverContent"]?.jsonObject ?: return
        if (serverContent["interrupted"]?.jsonPrimitive?.contentOrNull == "true") {
            activeTurnEpoch = audioPlayer.beginTurn()
            firstAudioSeenForEpoch = Long.MIN_VALUE
            activeTurnSentAtNanos = SystemClock.elapsedRealtimeNanos()
            synchronized(transcriptLock) { transcript = StringBuilder() }
            DiagnosticHub.record("LIVE_MODEL_INTERRUPTED", mapOf("newEpoch" to activeTurnEpoch))
        }

        val epoch = activeTurnEpoch
        serverContent["modelTurn"]?.jsonObject
            ?.get("parts")?.jsonArray
            ?.forEach { partElement ->
                val inlineData = partElement.jsonObject["inlineData"]?.jsonObject ?: return@forEach
                val mimeType = inlineData["mimeType"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val data = inlineData["data"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (mimeType.startsWith("audio/pcm") && data.isNotBlank()) {
                    if (firstAudioSeenForEpoch != epoch) {
                        firstAudioSeenForEpoch = epoch
                        val sentAt = activeTurnSentAtNanos
                        DiagnosticHub.record(
                            "LIVE_FIRST_AUDIO_RECEIVED",
                            mapOf(
                                "epoch" to epoch,
                                "frameToFirstAudioMs" to if (sentAt > 0L) {
                                    (SystemClock.elapsedRealtimeNanos() - sentAt) / 1_000_000.0
                                } else null,
                            ),
                        )
                    }
                    if (activeSpeechEnabled) {
                        runCatching { Base64.decode(data, Base64.DEFAULT) }.getOrNull()?.let { bytes ->
                            audioPlayer.enqueue(epoch, bytes)
                        }
                    }
                }
            }

        val delta = serverContent["outputTranscription"]?.jsonObject
            ?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty()
        if (delta.isNotBlank()) {
            synchronized(transcriptLock) {
                if (epoch == activeTurnEpoch) transcript.append(delta)
            }
        }

        if (serverContent["turnComplete"]?.jsonPrimitive?.contentOrNull == "true") {
            val finalText = synchronized(transcriptLock) {
                transcript.toString().trim().also { transcript = StringBuilder() }
            }
            if (finalText.isNotBlank() && epoch == activeTurnEpoch) {
                runtime.result(
                    AnalysisResult(
                        text = finalText,
                        source = AnalysisSource.GEMINI,
                        language = if (finalText.any { it in '\u0600'..'\u06FF' }) "mixed" else "en",
                    )
                )
                DiagnosticHub.record(
                    "LIVE_TURN_COMPLETE",
                    mapOf("characters" to finalText.length, "epoch" to epoch),
                )
            }
        }
    }

    private fun setupMessage(): String = buildJsonObject {
        put("setup", buildJsonObject {
            put("model", "models/$LIVE_MODEL")
            put("generationConfig", buildJsonObject {
                put("responseModalities", buildJsonArray { add(JsonPrimitive("AUDIO")) })
                put("mediaResolution", "MEDIA_RESOLUTION_MEDIUM")
            })
            put("systemInstruction", buildJsonObject {
                put("parts", buildJsonArray { add(buildJsonObject { put("text", SYSTEM_INSTRUCTION) }) })
            })
            put("outputAudioTranscription", buildJsonObject { })
        })
    }.toString()

    private fun videoMessage(base64: String, mimeType: String, mediaResolution: String): String =
        buildJsonObject {
            put("realtimeInput", buildJsonObject {
                put("video", buildJsonObject {
                    put("data", base64)
                    put("mimeType", mimeType)
                })
                put("mediaResolution", mediaResolution)
            })
        }.toString()

    private fun realtimeTextMessage(text: String): String = buildJsonObject {
        put("realtimeInput", buildJsonObject { put("text", text) })
    }.toString()

    private fun instructionFor(settings: AppSettings): String = when (settings.mode) {
        AnalysisMode.TEXT_READING -> {
            val descriptionTail = if (settings.describeAlongsideText) {
                " بعد قراءة النص، أضف جملة قصيرة جداً تضع النص في سياقه المكاني إن بقي المشهد نفسه، دون إعادة النص."
            } else ""
            if (settings.captureProfile == CaptureProfile.STABLE) {
                "MODE=TEXT_ACCURATE. اقرأ فوراً كل النص العربي والإنجليزي الظاهر فعلياً بالترتيب البصري. ابدأ بأول سطر واضح دون مقدمة. لا تصحح ولا تكمل كلمة ناقصة ولا تترجم. إذا تعذر لفظ كلمة قل غير واضح. تجاهل واجهة eSight وأزرار الكاميرا.$descriptionTail"
            } else {
                "MODE=TEXT_FAST. ابدأ النطق فوراً من أول عبارة واضحة في الإطار الحالي، ثم أكمل ما يظهر بوضوح. لا تنتظر اكتمال الصفحة ولا تشرح ولا تترجم ولا تتوقع حروفاً غير مرئية. تجاهل واجهة eSight وأزرار الكاميرا.$descriptionTail"
            }
        }
        AnalysisMode.SCENE_DESCRIPTION -> when (settings.sceneDescriptionStyle) {
            SceneDescriptionStyle.BRIEF -> "MODE=SCENE_BRIEF. هذا أحدث إطار. ابدأ فوراً بأهم شيء يحتاج المستخدم معرفته الآن. أعط الأولوية للخطر أو العائق والاتجاه، ثم الشيء أو الشخص المهم، وادمج النص البارز داخل السياق الطبيعي. تجاهل واجهة eSight. نحو 25 كلمة كحد تقريبي، ولا تخمن."
            SceneDescriptionStyle.COMPREHENSIVE -> "MODE=SCENE_COMPREHENSIVE. هذا أحدث إطار وأعلى أولوية. ابدأ فوراً بجملة أولى قصيرة ومفيدة، ثم أكمل تدريجياً: خطر أو عائق، الاتجاه والمسار، الأشخاص والأشياء، ثم التفاصيل. ادمج النص المرئي الواضح في موقعه ومعناه، واقرأ الأسماء والأرقام والأسعار المفيدة. تجاهل واجهة eSight ولا تخمن هوية أو مسافة أو معلومات خارج الصورة."
        }
    }

    private fun liveMediaResolution(settings: AppSettings): String = when (settings.mode) {
        AnalysisMode.TEXT_READING -> if (settings.captureProfile == CaptureProfile.STABLE) {
            "MEDIA_RESOLUTION_HIGH"
        } else "MEDIA_RESOLUTION_MEDIUM"
        AnalysisMode.SCENE_DESCRIPTION -> if (settings.sceneDescriptionStyle == SceneDescriptionStyle.BRIEF) {
            "MEDIA_RESOLUTION_LOW"
        } else "MEDIA_RESOLUTION_MEDIUM"
    }

    private fun invalidateSocket(reason: String) {
        synchronized(socketLock) {
            socket?.close(NORMAL_CLOSE_CODE, reason)
            socket = null
            setupReady = null
            setupSucceeded = false
            keyFingerprint = null
        }
        DiagnosticHub.record("LIVE_SOCKET_INVALIDATED", mapOf("reason" to reason))
    }

    private fun clearSocketIfCurrent(webSocket: WebSocket) {
        synchronized(socketLock) {
            if (socket === webSocket) {
                socket = null
                setupReady = null
                setupSucceeded = false
                keyFingerprint = null
            }
        }
    }

    private fun fingerprint(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .take(6)
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val LIVE_MODEL = "gemini-3.1-flash-live-preview"
        const val LIVE_ENDPOINT =
            "https://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        const val LIVE_SETUP_TIMEOUT_MS = 5_000L
        const val LIVE_VIDEO_INTERVAL_MS = 1_000L
        const val NORMAL_CLOSE_CODE = 1000

        val SYSTEM_INSTRUCTION = """
            أنت VisionBridge، مساعد رؤية لحظي لمستخدم كفيف أو ضعيف البصر يشاهد بث eSight Go على الهاتف.
            كل إطار يتبعه MODE. نفذ أحدث MODE فقط واعتبر الإطار الأحدث ناسخاً لأي مهمة بصرية أقدم.
            تكلم بالعربية الطبيعية مباشرة بلا مقدمة أو Markdown، وانطق الإنجليزية والأرقام كما تظهر عند الحاجة.
            ابدأ بأول معلومة مؤكدة ومفيدة فوراً ثم أكمل تدريجياً. لا تخمن نصاً غير واضح أو هوية شخص أو مسافة دقيقة أو سلامة طريق أو شيئاً خارج الإطار.
            في القراءة، اقرأ البكسلات الحالية نفسها. في الوصف، ادمج النص المرئي المفيد داخل معنى وموقع الشيء بدلاً من قائمة منفصلة.
        """.trimIndent()
    }
}
