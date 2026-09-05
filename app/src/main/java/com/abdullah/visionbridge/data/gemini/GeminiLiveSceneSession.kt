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

/** Persistent Gemini Live lane for scene description only.
 *
 * Text reading remains owned by the 3.1.1 PP-OCR pipeline. This class never invokes, replaces or
 * suppresses local OCR. It exists only to make Describe behave like a live visual channel: a warm
 * WebSocket, direct native audio, bounded freshness and a legacy fallback when Live is unavailable.
 */
class GeminiLiveSceneSession(
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

    private var transcript = StringBuilder()
    private var lastFrameSentAtElapsedMs = 0L

    suspend fun submitScene(bitmap: Bitmap, settings: AppSettings, apiKey: String): Boolean {
        if (settings.mode != AnalysisMode.SCENE_DESCRIPTION) return false
        if (apiKey.isBlank()) return false

        // 3.1.1 has a real Android Network lease for Force Cellular in the legacy repository. Until
        // Live owns the same lease, falling back is safer than silently violating that setting.
        if (settings.forceCellular) {
            DiagnosticHub.record(
                "LIVE_SCENE_FALLBACK",
                mapOf("reason" to "force_cellular_requires_legacy_bound_network"),
            )
            return false
        }

        val now = SystemClock.elapsedRealtime()
        synchronized(sendLock) {
            val elapsed = now - lastFrameSentAtElapsedMs
            if (lastFrameSentAtElapsedMs > 0L && elapsed < LIVE_VIDEO_INTERVAL_MS) {
                DiagnosticHub.record(
                    "LIVE_SCENE_FRAME_DROPPED",
                    mapOf(
                        "reason" to "live_video_one_fps_limit",
                        "elapsedMs" to elapsed,
                        "minimumMs" to LIVE_VIDEO_INTERVAL_MS,
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
                    mode = AnalysisMode.SCENE_DESCRIPTION,
                    captureProfile = settings.captureProfile,
                    sceneDescriptionStyle = settings.sceneDescriptionStyle,
                )
            }

            val connected = connection.await()
            val image = encoded.await()
            if (!connected) {
                DiagnosticHub.record("LIVE_SCENE_FALLBACK", mapOf("reason" to "setup_not_ready"))
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
            val base64 = Base64.encodeToString(image.bytes, Base64.NO_WRAP)
            val videoSent = currentSocket.send(
                videoMessage(base64, image.mimeType, liveMediaResolution(settings.sceneDescriptionStyle))
            )
            val instructionSent = currentSocket.send(
                realtimeTextMessage(instructionFor(settings.sceneDescriptionStyle))
            )
            if (!videoSent || !instructionSent) {
                DiagnosticHub.record(
                    "LIVE_SCENE_FALLBACK",
                    mapOf(
                        "reason" to "websocket_send_failed",
                        "videoSent" to videoSent,
                        "instructionSent" to instructionSent,
                    ),
                )
                invalidateSocket("send_failed")
                return@coroutineScope false
            }

            synchronized(sendLock) { lastFrameSentAtElapsedMs = SystemClock.elapsedRealtime() }
            DiagnosticHub.record(
                "LIVE_SCENE_FRAME_SENT",
                mapOf(
                    "model" to LIVE_MODEL,
                    "sceneStyle" to settings.sceneDescriptionStyle.name,
                    "encodedBytes" to image.bytes.size,
                    "outputWidth" to image.outputWidth,
                    "outputHeight" to image.outputHeight,
                    "format" to image.format,
                    "quality" to image.quality,
                    "base64Ms" to
                        (SystemClock.elapsedRealtimeNanos() - base64Started) / 1_000_000.0,
                    "nativeAudio" to settings.speechEnabled,
                    "localOcrPreserved" to true,
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
        DiagnosticHub.record(
            "LIVE_SCENE_TARGET_CHANGED",
            mapOf("interruptSpeechSetting" to interruptSpeech),
        )
    }

    fun reset() {
        synchronized(sendLock) { lastFrameSentAtElapsedMs = 0L }
        synchronized(transcriptLock) { transcript = StringBuilder() }
        audioPlayer.interrupt("live_scene_reset")
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
                socket?.close(NORMAL_CLOSE_CODE, "replace_live_scene_session")
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
                    "LIVE_SCENE_SOCKET_CONNECTING",
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
                "LIVE_SCENE_SOCKET_OPEN",
                mapOf("httpCode" to response.code, "protocol" to response.protocol.toString()),
            )
            if (!webSocket.send(setupMessage()) && !ready.isCompleted) ready.complete(false)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (keyFingerprint == expectedFingerprint) handleServerMessage(text, ready)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            DiagnosticHub.record("LIVE_SCENE_BINARY_MESSAGE_IGNORED", mapOf("bytes" to bytes.size))
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!ready.isCompleted) ready.complete(false)
            clearSocketIfCurrent(webSocket)
            DiagnosticHub.record("LIVE_SCENE_SOCKET_CLOSED", mapOf("code" to code, "reason" to reason))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!ready.isCompleted) ready.complete(false)
            clearSocketIfCurrent(webSocket)
            DiagnosticHub.failure(
                "LIVE_SCENE_SOCKET_FAILURE",
                t,
                mapOf("httpCode" to response?.code, "model" to LIVE_MODEL),
            )
        }
    }

    private fun handleServerMessage(raw: String, ready: CompletableDeferred<Boolean>) {
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrElse { error ->
            DiagnosticHub.failure("LIVE_SCENE_JSON_PARSE", error, mapOf("characters" to raw.length))
            return
        }

        if (root.containsKey("setupComplete")) {
            setupSucceeded = true
            if (!ready.isCompleted) ready.complete(true)
            DiagnosticHub.record("LIVE_SCENE_SETUP_COMPLETE", mapOf("model" to LIVE_MODEL))
        }

        root["error"]?.let {
            DiagnosticHub.record("LIVE_SCENE_API_ERROR", mapOf("error" to it.toString()))
        }

        val serverContent = root["serverContent"]?.jsonObject ?: return
        if (serverContent["interrupted"]?.jsonPrimitive?.contentOrNull == "true") {
            activeTurnEpoch = audioPlayer.beginTurn()
            firstAudioSeenForEpoch = Long.MIN_VALUE
            activeTurnSentAtNanos = SystemClock.elapsedRealtimeNanos()
            synchronized(transcriptLock) { transcript = StringBuilder() }
            DiagnosticHub.record("LIVE_SCENE_MODEL_INTERRUPTED", mapOf("newEpoch" to activeTurnEpoch))
        }

        val epoch = activeTurnEpoch
        serverContent["modelTurn"]?.jsonObject
            ?.get("parts")
            ?.jsonArray
            ?.forEach { partElement ->
                val inlineData = partElement.jsonObject["inlineData"]?.jsonObject ?: return@forEach
                val mimeType = inlineData["mimeType"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val data = inlineData["data"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (mimeType.startsWith("audio/pcm") && data.isNotBlank()) {
                    if (firstAudioSeenForEpoch != epoch) {
                        firstAudioSeenForEpoch = epoch
                        val sentAt = activeTurnSentAtNanos
                        DiagnosticHub.record(
                            "LIVE_SCENE_FIRST_AUDIO_RECEIVED",
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
                    "LIVE_SCENE_TURN_COMPLETE",
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
                put("parts", buildJsonArray {
                    add(buildJsonObject { put("text", SYSTEM_INSTRUCTION) })
                })
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

    private fun instructionFor(style: SceneDescriptionStyle): String = when (style) {
        SceneDescriptionStyle.BRIEF -> """
            MODE=SCENE_BRIEF. هذا أحدث إطار من النظارة. اترك أي وصف أقدم وابدأ فوراً بأهم معلومة مرئية الآن.
            أعط الأولوية للخطر أو العائق الواضح، ثم الاتجاه أو الشخص أو الشيء المهم. ادمج النص المرئي المفيد داخل السياق الطبيعي، مثل: أمامك باب مكتوب عليه خروج.
            تجاهل واجهة eSight وأزرار الهاتف ومؤشرات الزوم ما لم تكن هي الهدف. لا تتجاوز نحو 25 كلمة، ولا تخمن هوية أو مسافة أو شيئاً خارج الصورة.
        """.trimIndent()
        SceneDescriptionStyle.COMPREHENSIVE -> """
            MODE=SCENE_COMPREHENSIVE. هذا أحدث إطار من النظارة وهو أعلى أولوية من أي وصف سابق.
            ابدأ فوراً بجملة أولى قصيرة ومفيدة، ثم أكمل تدريجياً: خطر أو عائق ظاهر، الاتجاه والمسار، الأشخاص والأشياء، ثم التفاصيل المهمة.
            اجعل النص المرئي جزءاً من فهم المكان: قل مثلاً على اليمين صيدلية وعلى اللوحة مكتوب النهدي، ولا تفصل النص في قائمة مستقلة.
            اقرأ الاسم أو الرقم أو السعر إذا كان واضحاً ومفيداً. تجاهل كروم eSight والكاميرا ومؤشرات الزوم والأزرار. لا تخمن هوية شخص أو مسافة دقيقة أو سلامة طريق أو معلومات غير مرئية.
        """.trimIndent()
    }

    private fun liveMediaResolution(style: SceneDescriptionStyle): String =
        if (style == SceneDescriptionStyle.BRIEF) "MEDIA_RESOLUTION_LOW" else "MEDIA_RESOLUTION_MEDIUM"

    private fun invalidateSocket(reason: String) {
        synchronized(socketLock) {
            socket?.close(NORMAL_CLOSE_CODE, reason)
            socket = null
            setupReady = null
            setupSucceeded = false
            keyFingerprint = null
        }
        DiagnosticHub.record("LIVE_SCENE_SOCKET_INVALIDATED", mapOf("reason" to reason))
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
            أنت VisionBridge، مساعد رؤية لحظي لمستخدم كفيف أو ضعيف البصر يشاهد بث eSight Go على شاشة الهاتف.
            صف فقط ما يظهر في أحدث إطار. أحدث إطار يلغي أي وصف أقدم.
            تكلم بالعربية الطبيعية مباشرة دون مقدمة أو Markdown. السرعة أولوية: ابدأ بأول حقيقة مؤكدة ومفيدة فوراً ثم أكمل إن بقيت التفاصيل نافعة.
            ركز على العوائق والاتجاه والأشخاص والأشياء المهمة، وادمج النص المرئي الواضح في مكانه ومعناه داخل المشهد.
            لا تخمن نصاً غير واضح، ولا هوية شخص، ولا مسافة دقيقة، ولا سلامة طريق، ولا شيئاً خارج الإطار.
        """.trimIndent()
    }
}
