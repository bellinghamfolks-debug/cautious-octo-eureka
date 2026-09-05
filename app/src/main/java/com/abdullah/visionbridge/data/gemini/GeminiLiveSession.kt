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
 * Cloud text uses Gemini 3.1 Live for its stronger current visual reading. Scene description uses
 * Gemini 2.5 Native Audio Live because it supports Proactive Audio: the model can inspect a new
 * frame and deliberately stay silent when the semantic scene has not changed. The local motion
 * tracker remains the cheap first gate; this session is the semantic second gate.
 */
class GeminiLiveSession(
    private val runtime: CaptureRuntime,
    private val audioPlayer: LivePcmAudioPlayer,
) {
    private data class LiveProfile(
        val model: String,
        val proactiveAudio: Boolean,
        val semanticSceneGate: Boolean,
    )

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
    @Volatile private var connectionFingerprint: String? = null
    @Volatile private var activeProfile: LiveProfile? = null
    @Volatile private var activeTurnEpoch = 0L
    @Volatile private var activeTurnSentAtNanos = 0L
    @Volatile private var firstAudioSeenForEpoch = Long.MIN_VALUE
    @Volatile private var activeSpeechEnabled = true
    @Volatile private var activeResponseMode: AnalysisMode? = null
    @Volatile private var lastMode: AnalysisMode? = null
    @Volatile private var visualGeneration = 0L
    @Volatile private var lastStableTextGenerationSent = Long.MIN_VALUE
    @Volatile private var sceneProbeOutstanding = false
    @Volatile private var sceneProbeStartedAtElapsedMs = 0L
    @Volatile private var sceneProbeHadAudio = false

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

        val profile = profileFor(settings)
        val now = SystemClock.elapsedRealtime()
        val generation = visualGeneration

        synchronized(sendLock) {
            if (lastMode != settings.mode) {
                lastMode = settings.mode
                lastFrameSentAtElapsedMs = 0L
                sceneProbeOutstanding = false
                sceneProbeStartedAtElapsedMs = 0L
            }

            // A stable page is read once per registered visual target. Camera tremor over the same
            // page must not keep restarting cloud speech. FAST_TEXT intentionally remains live for
            // captions, scrolling UIs and genuinely changing text.
            if (
                settings.mode == AnalysisMode.TEXT_READING &&
                settings.captureProfile == CaptureProfile.STABLE &&
                lastStableTextGenerationSent == generation
            ) {
                DiagnosticHub.record(
                    "LIVE_TEXT_SAME_TARGET_SUPPRESSED",
                    mapOf("visualGeneration" to generation),
                )
                return true
            }

            if (settings.mode == AnalysisMode.SCENE_DESCRIPTION && sceneProbeOutstanding) {
                val age = now - sceneProbeStartedAtElapsedMs
                if (age < SCENE_PROBE_TIMEOUT_MS) {
                    DiagnosticHub.record(
                        "LIVE_SEMANTIC_PROBE_COALESCED",
                        mapOf("ageMs" to age, "reason" to "previous_probe_in_flight"),
                    )
                    return true
                }
                DiagnosticHub.record(
                    "LIVE_SEMANTIC_PROBE_TIMEOUT",
                    mapOf("ageMs" to age),
                )
                sceneProbeOutstanding = false
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
            val connection = async(Dispatchers.IO) { ensureConnected(apiKey, profile) }
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
                DiagnosticHub.record(
                    "LIVE_FRAME_FALLBACK",
                    mapOf("reason" to "setup_not_ready", "model" to profile.model),
                )
                return@coroutineScope false
            }

            val currentSocket = socket ?: return@coroutineScope false
            activeTurnSentAtNanos = SystemClock.elapsedRealtimeNanos()
            activeSpeechEnabled = settings.speechEnabled
            activeResponseMode = settings.mode
            firstAudioSeenForEpoch = Long.MIN_VALUE
            sceneProbeHadAudio = false
            synchronized(transcriptLock) { transcript = StringBuilder() }

            // Text keeps the existing eager interruption behaviour. Scene description is different:
            // do not flush a valid description merely because pixels moved. With semantic gating we
            // wait until Gemini actually chooses to speak about a meaningful change, then the first
            // new audio chunk atomically replaces the previous scene audio.
            if (settings.mode == AnalysisMode.TEXT_READING) {
                activeTurnEpoch = audioPlayer.beginTurn()
            }

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
                        "model" to profile.model,
                    ),
                )
                invalidateSocket("send_failed")
                return@coroutineScope false
            }

            synchronized(sendLock) {
                lastFrameSentAtElapsedMs = SystemClock.elapsedRealtime()
                if (
                    settings.mode == AnalysisMode.TEXT_READING &&
                    settings.captureProfile == CaptureProfile.STABLE
                ) {
                    lastStableTextGenerationSent = generation
                }
                if (settings.mode == AnalysisMode.SCENE_DESCRIPTION) {
                    sceneProbeOutstanding = true
                    sceneProbeStartedAtElapsedMs = lastFrameSentAtElapsedMs
                }
            }

            DiagnosticHub.record(
                "LIVE_FRAME_SENT",
                mapOf(
                    "model" to profile.model,
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
                    "semanticGate" to profile.semanticSceneGate,
                    "proactiveAudio" to profile.proactiveAudio,
                    "visualGeneration" to generation,
                    "epoch" to activeTurnEpoch,
                ),
            )
            if (profile.semanticSceneGate) {
                DiagnosticHub.record(
                    "LIVE_SEMANTIC_PROBE_SENT",
                    mapOf("model" to profile.model, "visualGeneration" to generation),
                )
            }
            true
        }
    }

    fun onVisualTargetChanged(interruptSpeech: Boolean) {
        visualGeneration += 1L
        synchronized(sendLock) { lastFrameSentAtElapsedMs = 0L }
        synchronized(transcriptLock) { transcript = StringBuilder() }

        // In scene mode the local tracker is only a candidate generator. The AI semantic gate owns
        // the final decision, so a large camera move is not allowed to cut speech by itself.
        val scene = lastMode == AnalysisMode.SCENE_DESCRIPTION
        if (!scene && interruptSpeech) audioPlayer.interrupt("visual_target_changed")
        DiagnosticHub.record(
            "LIVE_VISUAL_TARGET_CHANGED",
            mapOf(
                "interruptSpeechSetting" to interruptSpeech,
                "audioInterruptDeferredToSemanticGate" to scene,
                "visualGeneration" to visualGeneration,
            ),
        )
    }

    fun reset() {
        synchronized(sendLock) {
            lastFrameSentAtElapsedMs = 0L
            lastMode = null
            lastStableTextGenerationSent = Long.MIN_VALUE
            sceneProbeOutstanding = false
            sceneProbeStartedAtElapsedMs = 0L
        }
        visualGeneration = 0L
        activeResponseMode = null
        synchronized(transcriptLock) { transcript = StringBuilder() }
        audioPlayer.interrupt("live_session_reset")
        invalidateSocket("session_reset")
    }

    fun stop() {
        synchronized(sendLock) {
            lastFrameSentAtElapsedMs = 0L
            sceneProbeOutstanding = false
            sceneProbeStartedAtElapsedMs = 0L
        }
        activeResponseMode = null
        synchronized(transcriptLock) { transcript = StringBuilder() }
        audioPlayer.interrupt("capture_stopped")
        invalidateSocket("capture_stopped")
    }

    private suspend fun ensureConnected(apiKey: String, profile: LiveProfile): Boolean {
        val fingerprint = fingerprint("$apiKey|${profile.model}|${profile.proactiveAudio}")
        val ready: CompletableDeferred<Boolean>
        synchronized(socketLock) {
            val existing = setupReady
            if (
                socket != null &&
                connectionFingerprint == fingerprint &&
                setupSucceeded &&
                existing != null
            ) {
                return true
            }
            if (socket != null && connectionFingerprint == fingerprint && existing != null) {
                ready = existing
            } else {
                socket?.close(NORMAL_CLOSE_CODE, "replace_live_session")
                val deferred = CompletableDeferred<Boolean>()
                ready = deferred
                setupReady = deferred
                setupSucceeded = false
                connectionFingerprint = fingerprint
                activeProfile = profile
                val url = LIVE_ENDPOINT.toHttpUrl().newBuilder()
                    .addQueryParameter("key", apiKey)
                    .build()
                socket = client.newWebSocket(
                    Request.Builder().url(url).build(),
                    createListener(fingerprint, profile, deferred),
                )
                DiagnosticHub.record(
                    "LIVE_SOCKET_CONNECTING",
                    mapOf(
                        "model" to profile.model,
                        "endpointHost" to url.host,
                        "proactiveAudio" to profile.proactiveAudio,
                    ),
                )
            }
        }
        return withTimeoutOrNull(LIVE_SETUP_TIMEOUT_MS) { ready.await() } == true
    }

    private fun createListener(
        expectedFingerprint: String,
        profile: LiveProfile,
        ready: CompletableDeferred<Boolean>,
    ): WebSocketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            DiagnosticHub.record(
                "LIVE_SOCKET_OPEN",
                mapOf(
                    "httpCode" to response.code,
                    "protocol" to response.protocol.toString(),
                    "model" to profile.model,
                ),
            )
            if (!webSocket.send(setupMessage(profile)) && !ready.isCompleted) ready.complete(false)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (connectionFingerprint == expectedFingerprint) handleServerMessage(text, ready)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            // The real-device bundle showed the socket opening four times but every server message
            // arriving through OkHttp's binary callback. Ignoring it meant setupComplete was never
            // observed and every frame fell back to 3.1.1. Gemini's WebSocket payload is JSON, so
            // decode UTF-8 binary frames and feed them into exactly the same parser as text frames.
            val decoded = runCatching { bytes.utf8() }.getOrNull()
            if (decoded != null && decoded.trimStart().startsWith("{")) {
                DiagnosticHub.record(
                    "LIVE_BINARY_JSON_RECEIVED",
                    mapOf("bytes" to bytes.size, "model" to profile.model),
                )
                if (connectionFingerprint == expectedFingerprint) {
                    handleServerMessage(decoded, ready)
                }
            } else {
                DiagnosticHub.record(
                    "LIVE_BINARY_NON_JSON_IGNORED",
                    mapOf("bytes" to bytes.size, "model" to profile.model),
                )
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!ready.isCompleted) ready.complete(false)
            clearSocketIfCurrent(webSocket)
            DiagnosticHub.record(
                "LIVE_SOCKET_CLOSED",
                mapOf("code" to code, "reason" to reason, "model" to profile.model),
            )
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!ready.isCompleted) ready.complete(false)
            clearSocketIfCurrent(webSocket)
            DiagnosticHub.failure(
                "LIVE_SOCKET_FAILURE",
                t,
                mapOf("httpCode" to response?.code, "model" to profile.model),
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
            DiagnosticHub.record(
                "LIVE_SETUP_COMPLETE",
                mapOf(
                    "model" to activeProfile?.model,
                    "proactiveAudio" to activeProfile?.proactiveAudio,
                ),
            )
        }
        root["error"]?.let { DiagnosticHub.record("LIVE_API_ERROR", mapOf("error" to it.toString())) }

        val serverContent = root["serverContent"]?.jsonObject ?: return
        if (serverContent["interrupted"]?.jsonPrimitive?.contentOrNull == "true") {
            // Do not flush scene audio here. A semantic probe may intentionally conclude that only
            // the camera moved and stay silent. Flush only if a genuinely new scene response starts.
            if (activeResponseMode == AnalysisMode.TEXT_READING) {
                activeTurnEpoch = audioPlayer.beginTurn()
            }
            firstAudioSeenForEpoch = Long.MIN_VALUE
            activeTurnSentAtNanos = SystemClock.elapsedRealtimeNanos()
            synchronized(transcriptLock) { transcript = StringBuilder() }
            DiagnosticHub.record(
                "LIVE_MODEL_INTERRUPTED",
                mapOf(
                    "newEpoch" to activeTurnEpoch,
                    "mode" to activeResponseMode?.name,
                    "sceneFlushDeferred" to (activeResponseMode == AnalysisMode.SCENE_DESCRIPTION),
                ),
            )
        }

        serverContent["modelTurn"]?.jsonObject
            ?.get("parts")?.jsonArray
            ?.forEach { partElement ->
                val inlineData = partElement.jsonObject["inlineData"]?.jsonObject ?: return@forEach
                val mimeType = inlineData["mimeType"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val data = inlineData["data"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (mimeType.startsWith("audio/pcm") && data.isNotBlank()) {
                    val scene = activeResponseMode == AnalysisMode.SCENE_DESCRIPTION
                    if (firstAudioSeenForEpoch == Long.MIN_VALUE) {
                        if (scene) {
                            activeTurnEpoch = audioPlayer.beginTurn()
                            sceneProbeHadAudio = true
                            DiagnosticHub.record(
                                "LIVE_SEMANTIC_CHANGE_CONFIRMED",
                                mapOf(
                                    "model" to activeProfile?.model,
                                    "visualGeneration" to visualGeneration,
                                ),
                            )
                        }
                        firstAudioSeenForEpoch = activeTurnEpoch
                        val sentAt = activeTurnSentAtNanos
                        DiagnosticHub.record(
                            "LIVE_FIRST_AUDIO_RECEIVED",
                            mapOf(
                                "epoch" to activeTurnEpoch,
                                "mode" to activeResponseMode?.name,
                                "frameToFirstAudioMs" to if (sentAt > 0L) {
                                    (SystemClock.elapsedRealtimeNanos() - sentAt) / 1_000_000.0
                                } else null,
                            ),
                        )
                    }
                    if (activeSpeechEnabled) {
                        runCatching { Base64.decode(data, Base64.DEFAULT) }.getOrNull()?.let { bytes ->
                            audioPlayer.enqueue(activeTurnEpoch, bytes)
                        }
                    }
                }
            }

        val delta = serverContent["outputTranscription"]?.jsonObject
            ?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty()
        if (delta.isNotBlank()) {
            synchronized(transcriptLock) { transcript.append(delta) }
        }

        if (serverContent["turnComplete"]?.jsonPrimitive?.contentOrNull == "true") {
            val scene = activeResponseMode == AnalysisMode.SCENE_DESCRIPTION
            val finalText = synchronized(transcriptLock) {
                transcript.toString().trim().also { transcript = StringBuilder() }
            }
            if (scene) {
                synchronized(sendLock) {
                    sceneProbeOutstanding = false
                    sceneProbeStartedAtElapsedMs = 0L
                }
                if (!sceneProbeHadAudio) {
                    DiagnosticHub.record(
                        "LIVE_SEMANTIC_SILENCE",
                        mapOf(
                            "reason" to "no_meaningful_scene_change",
                            "model" to activeProfile?.model,
                            "visualGeneration" to visualGeneration,
                        ),
                    )
                }
            }
            if (finalText.isNotBlank()) {
                runtime.result(
                    AnalysisResult(
                        text = finalText,
                        source = AnalysisSource.GEMINI,
                        language = if (finalText.any { it in '\u0600'..'\u06FF' }) "mixed" else "en",
                    )
                )
                DiagnosticHub.record(
                    "LIVE_TURN_COMPLETE",
                    mapOf(
                        "characters" to finalText.length,
                        "epoch" to activeTurnEpoch,
                        "mode" to activeResponseMode?.name,
                    ),
                )
            }
        }
    }

    private fun setupMessage(profile: LiveProfile): String = buildJsonObject {
        put("setup", buildJsonObject {
            put("model", "models/${profile.model}")
            put("generationConfig", buildJsonObject {
                put("responseModalities", buildJsonArray { add(JsonPrimitive("AUDIO")) })
                put("mediaResolution", "MEDIA_RESOLUTION_MEDIUM")
            })
            put("systemInstruction", buildJsonObject {
                put("parts", buildJsonArray { add(buildJsonObject { put("text", SYSTEM_INSTRUCTION) }) })
            })
            put("outputAudioTranscription", buildJsonObject { })
            if (profile.proactiveAudio) {
                put("proactivity", buildJsonObject { put("proactiveAudio", true) })
            }
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
            SceneDescriptionStyle.BRIEF -> "MODE=SCENE_BRIEF_SEMANTIC. قارن هذا الإطار دلالياً بالمشهد السابق في الجلسة. حركة النظارة أو الهاتف، الالتفات، الاهتزاز، تغير موضع الأشياء داخل الإطار بسبب حركة الكاميرا، التكبير، تغير الإضاءة أو التركيز أو الضبابية أو أزرار eSight ليست تغيراً في المحتوى. إذا لم يظهر أو يختف أو يتغير شيء مهم فعلياً للمستخدم، استخدم Proactive Audio وابق صامتاً تماماً. إذا تغير المحتوى الحقيقي، ابدأ فوراً بأهم تغير: خطر أو عائق أو اتجاه أو شخص أو شيء أو لافتة أو نص مفيد، في نحو 25 كلمة، بلا مقدمة ولا تخمين. أول مشهد في الجلسة يستحق الوصف."
            SceneDescriptionStyle.COMPREHENSIVE -> "MODE=SCENE_COMPREHENSIVE_SEMANTIC. قارن هذا الإطار دلالياً بالمشهد السابق في الجلسة، لا بالبكسلات. تجاهل حركة النظارة أو الهاتف، الدوران، الاهتزاز، pan/tilt، التكبير، تغير الإضاءة أو التركيز أو الضبابية وتغير موضع نفس الأشياء داخل الصورة، وكذلك واجهة eSight. إذا بقيت هوية المشهد ومحتواه العملي نفسه، استخدم Proactive Audio وابق صامتاً تماماً. تكلم فقط عندما يتغير شيء حقيقي ومفيد: ظهور أو اختفاء شخص أو مركبة أو عائق أو باب أو مسار أو جسم، تغير حالته أو موقعه العملي، أو تغير لافتة أو اسم أو سعر أو نص مهم. عند التغير ابدأ فوراً بجملة قصيرة عن الأهم ثم أكمل تدريجياً بلا تخمين. أول مشهد في الجلسة يستحق الوصف."
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

    private fun profileFor(settings: AppSettings): LiveProfile = when (settings.mode) {
        AnalysisMode.TEXT_READING -> LiveProfile(
            model = TEXT_LIVE_MODEL,
            proactiveAudio = false,
            semanticSceneGate = false,
        )
        AnalysisMode.SCENE_DESCRIPTION -> LiveProfile(
            model = SCENE_SEMANTIC_LIVE_MODEL,
            proactiveAudio = true,
            semanticSceneGate = true,
        )
    }

    private fun invalidateSocket(reason: String) {
        synchronized(socketLock) {
            socket?.close(NORMAL_CLOSE_CODE, reason)
            socket = null
            setupReady = null
            setupSucceeded = false
            connectionFingerprint = null
            activeProfile = null
        }
        DiagnosticHub.record("LIVE_SOCKET_INVALIDATED", mapOf("reason" to reason))
    }

    private fun clearSocketIfCurrent(webSocket: WebSocket) {
        synchronized(socketLock) {
            if (socket === webSocket) {
                socket = null
                setupReady = null
                setupSucceeded = false
                connectionFingerprint = null
                activeProfile = null
            }
        }
    }

    private fun fingerprint(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .take(6)
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val TEXT_LIVE_MODEL = "gemini-3.1-flash-live-preview"
        const val SCENE_SEMANTIC_LIVE_MODEL = "gemini-2.5-flash-native-audio-preview-12-2025"
        const val LIVE_ENDPOINT =
            "https://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        const val LIVE_SETUP_TIMEOUT_MS = 5_000L
        const val LIVE_VIDEO_INTERVAL_MS = 1_000L
        const val SCENE_PROBE_TIMEOUT_MS = 3_500L
        const val NORMAL_CLOSE_CODE = 1000

        val SYSTEM_INSTRUCTION = """
            أنت VisionBridge، مساعد رؤية لحظي لمستخدم كفيف أو ضعيف البصر يشاهد بث eSight Go على الهاتف.
            كل إطار يتبعه MODE. نفذ أحدث MODE فقط واعتبر الإطار الأحدث ناسخاً لأي مهمة بصرية أقدم.
            تكلم بالعربية الطبيعية مباشرة بلا مقدمة أو Markdown، وانطق الإنجليزية والأرقام كما تظهر عند الحاجة.
            ابدأ بأول معلومة مؤكدة ومفيدة فوراً ثم أكمل تدريجياً. لا تخمن نصاً غير واضح أو هوية شخص أو مسافة دقيقة أو سلامة طريق أو شيئاً خارج الإطار.
            في القراءة، اقرأ البكسلات الحالية نفسها.
            في أوضاع SCENE_*_SEMANTIC أنت أيضاً بوابة تغير دلالي: لا تعتبر حركة الكاميرا أو التكبير أو الإضاءة تغيراً في المحتوى، وابق صامتاً عندما لم يتغير شيء عملياً للمستخدم.
            في الوصف، ادمج النص المرئي المفيد داخل معنى وموقع الشيء بدلاً من قائمة منفصلة.
        """.trimIndent()
    }
}
