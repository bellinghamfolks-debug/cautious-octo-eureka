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

/** Persistent low-latency Gemini Live transport for cloud visual tasks. */
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
    private val frameEncoder = LiveFrameEncoder()
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

    // Gemini can still deliver the tail of turn N after frame N+1 has been submitted. The previous
    // implementation immediately re-labelled those bytes as N+1, which produced chopped/garbled
    // speech and even impossible 53 ms "latency" readings. While this flag is true, old audio and
    // transcription are discarded until Gemini emits the interruption/turn boundary.
    @Volatile private var responseInFlight = false
    @Volatile private var staleAudioBlocked = false
    @Volatile private var staleAudioPacketsBlocked = 0

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
                sceneProbeOutstanding = false
                DiagnosticHub.record("LIVE_SEMANTIC_PROBE_TIMEOUT", mapOf("ageMs" to age))
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
            val encodeStarted = SystemClock.elapsedRealtimeNanos()
            val encoded = async(Dispatchers.Default) { frameEncoder.encode(bitmap, settings) }

            val connected = connection.await()
            val image = encoded.await()
            val encodeTotalMs = (SystemClock.elapsedRealtimeNanos() - encodeStarted) / 1_000_000.0
            if (!connected) {
                DiagnosticHub.record(
                    "LIVE_FRAME_FALLBACK",
                    mapOf("reason" to "setup_not_ready", "model" to profile.model),
                )
                return@coroutineScope false
            }

            val currentSocket = socket ?: return@coroutineScope false
            val supersedingActiveResponse = responseInFlight
            activeSpeechEnabled = settings.speechEnabled
            activeResponseMode = settings.mode
            firstAudioSeenForEpoch = Long.MIN_VALUE
            sceneProbeHadAudio = false
            staleAudioBlocked = supersedingActiveResponse
            staleAudioPacketsBlocked = 0
            synchronized(transcriptLock) { transcript = StringBuilder() }

            if (settings.mode == AnalysisMode.TEXT_READING) {
                activeTurnEpoch = audioPlayer.beginTurn(
                    if (supersedingActiveResponse) "superseded_live_text_turn" else "new_live_text_turn"
                )
            }

            val base64Started = SystemClock.elapsedRealtimeNanos()
            val imageBase64 = Base64.encodeToString(image.bytes, Base64.NO_WRAP)
            val base64Ms = (SystemClock.elapsedRealtimeNanos() - base64Started) / 1_000_000.0

            // Set this immediately around network submission, not before image preprocessing. This
            // makes frameToFirstAudioMs a real model/network number instead of hiding local work.
            activeTurnSentAtNanos = SystemClock.elapsedRealtimeNanos()
            val videoSent = currentSocket.send(videoMessage(imageBase64, image.mimeType))
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

            responseInFlight = true
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
                    "outputWidth" to image.width,
                    "outputHeight" to image.height,
                    "format" to "JPEG_LIVE_FAST",
                    "quality" to image.quality,
                    "scaleMs" to image.scaleMs,
                    "compressionMs" to image.compressionMs,
                    "encodeTotalMs" to encodeTotalMs,
                    "base64Ms" to base64Ms,
                    "nativeAudio" to settings.speechEnabled,
                    "cloudTransport" to "LIVE_WEBSOCKET",
                    "semanticGate" to profile.semanticSceneGate,
                    "proactiveAudio" to profile.proactiveAudio,
                    "supersedingActiveResponse" to supersedingActiveResponse,
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
        responseInFlight = false
        staleAudioBlocked = false
        staleAudioPacketsBlocked = 0
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
        responseInFlight = false
        staleAudioBlocked = false
        staleAudioPacketsBlocked = 0
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
            val decoded = runCatching { bytes.utf8() }.getOrNull()
            if (decoded != null && decoded.trimStart().startsWith("{")) {
                DiagnosticHub.record(
                    "LIVE_BINARY_JSON_RECEIVED",
                    mapOf("bytes" to bytes.size, "model" to profile.model),
                )
                if (connectionFingerprint == expectedFingerprint) handleServerMessage(decoded, ready)
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
            releaseStaleBoundary("interrupted")
            firstAudioSeenForEpoch = Long.MIN_VALUE
            synchronized(transcriptLock) { transcript = StringBuilder() }
            DiagnosticHub.record(
                "LIVE_MODEL_INTERRUPTED",
                mapOf(
                    "epoch" to activeTurnEpoch,
                    "mode" to activeResponseMode?.name,
                    "frameTimerPreserved" to true,
                ),
            )
        }

        serverContent["modelTurn"]?.jsonObject
            ?.get("parts")?.jsonArray
            ?.forEach { partElement ->
                val inlineData = partElement.jsonObject["inlineData"]?.jsonObject ?: return@forEach
                val mimeType = inlineData["mimeType"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val data = inlineData["data"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (!mimeType.startsWith("audio/pcm") || data.isBlank()) return@forEach

                if (staleAudioBlocked) {
                    staleAudioPacketsBlocked += 1
                    return@forEach
                }

                val scene = activeResponseMode == AnalysisMode.SCENE_DESCRIPTION
                if (firstAudioSeenForEpoch == Long.MIN_VALUE) {
                    if (scene) {
                        activeTurnEpoch = audioPlayer.beginTurn("semantic_change_confirmed")
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
                            "sampleRateHz" to sampleRateFromMime(mimeType),
                            "frameToFirstAudioMs" to if (sentAt > 0L) {
                                (SystemClock.elapsedRealtimeNanos() - sentAt) / 1_000_000.0
                            } else null,
                        ),
                    )
                }
                if (activeSpeechEnabled) {
                    runCatching { Base64.decode(data, Base64.DEFAULT) }.getOrNull()?.let { bytes ->
                        audioPlayer.enqueue(activeTurnEpoch, bytes, sampleRateFromMime(mimeType))
                    }
                }
            }

        val delta = serverContent["outputTranscription"]?.jsonObject
            ?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty()
        if (delta.isNotBlank() && !staleAudioBlocked) {
            synchronized(transcriptLock) { transcript.append(delta) }
        }

        if (serverContent["turnComplete"]?.jsonPrimitive?.contentOrNull == "true") {
            // If a newer frame was submitted while the previous answer was speaking, this boundary
            // belongs to the old answer. It is only a gate-opening signal, not completion of the
            // new frame. This prevents old transcript/audio being published under the new target.
            if (staleAudioBlocked) {
                releaseStaleBoundary("turn_complete")
                firstAudioSeenForEpoch = Long.MIN_VALUE
                synchronized(transcriptLock) { transcript = StringBuilder() }
                return
            }

            responseInFlight = false
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

    private fun releaseStaleBoundary(reason: String) {
        if (!staleAudioBlocked) return
        val blocked = staleAudioPacketsBlocked
        staleAudioBlocked = false
        staleAudioPacketsBlocked = 0
        DiagnosticHub.record(
            "LIVE_STALE_AUDIO_BOUNDARY",
            mapOf("reason" to reason, "blockedPackets" to blocked),
        )
    }

    private fun sampleRateFromMime(mimeType: String): Int {
        val match = SAMPLE_RATE_REGEX.find(mimeType)?.groupValues?.getOrNull(1)?.toIntOrNull()
        return match?.takeIf { it in 8_000..96_000 } ?: LivePcmAudioPlayer.DEFAULT_SAMPLE_RATE_HZ
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

    private fun videoMessage(base64: String, mimeType: String): String =
        buildJsonObject {
            put("realtimeInput", buildJsonObject {
                put("video", buildJsonObject {
                    put("data", base64)
                    put("mimeType", mimeType)
                })
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
                "MODE=TEXT_ACCURATE. اقرأ النص الظاهر الآن مباشرة وبهدوء ووضوح. ابدأ بأول سطر واضح بلا مقدمة، ولا تصحح أو تكمل أو تترجم. إذا تعذرت كلمة قل غير واضح. تجاهل واجهة eSight.$descriptionTail"
            } else {
                "MODE=TEXT_FAST. اقرأ فوراً أول عبارة واضحة ثم أكمل النص المرئي فقط. لا تنتظر الصفحة كاملة ولا تشرح أو تترجم أو تتوقع حروفاً غير ظاهرة. تجاهل واجهة eSight.$descriptionTail"
            }
        }
        AnalysisMode.SCENE_DESCRIPTION -> when (settings.sceneDescriptionStyle) {
            SceneDescriptionStyle.BRIEF -> "MODE=SCENE_BRIEF_SEMANTIC. قارن المعنى بالمشهد السابق. اهتزاز الكاميرا أو الالتفات أو التكبير أو الإضاءة ليس تغيراً. إن لم يتغير شيء مهم فابق صامتاً تماماً. عند تغير حقيقي اذكر أهم تغير أولاً بجملة قصيرة واضحة، خصوصاً الخطر أو العائق أو الشخص أو الاتجاه أو النص المفيد. لا تخمن."
            SceneDescriptionStyle.COMPREHENSIVE -> "MODE=SCENE_COMPREHENSIVE_SEMANTIC. قارن المعنى بالمشهد السابق لا البكسلات. تجاهل حركة الكاميرا والدوران والتكبير والإضاءة والتركيز وواجهة eSight. ابق صامتاً إذا بقي المحتوى العملي نفسه. تكلم فقط عند ظهور أو اختفاء أو تغير شيء حقيقي ومفيد، وابدأ بالأهم ثم أكمل باختصار ووضوح بلا تخمين."
        }
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
        responseInFlight = false
        staleAudioBlocked = false
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
        responseInFlight = false
        staleAudioBlocked = false
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
        val SAMPLE_RATE_REGEX = Regex("rate=(\\d+)", RegexOption.IGNORE_CASE)

        val SYSTEM_INSTRUCTION = """
            أنت VisionBridge، مساعد رؤية لحظي لمستخدم كفيف أو ضعيف البصر يشاهد بث eSight Go على الهاتف.
            تكلم بالعربية الطبيعية الواضحة مباشرة بلا مقدمة أو Markdown، وانطق الإنجليزية والأرقام كما تظهر عند الحاجة.
            نفذ أحدث إطار ومهمة فقط. لا تخمن نصاً غير واضح أو هوية شخص أو مسافة دقيقة أو شيئاً خارج الإطار.
            في القراءة اقرأ البكسلات الحالية نفسها. في الوصف تجاهل حركة الكاميرا وابق صامتاً إذا لم يتغير معنى المشهد.
        """.trimIndent()
    }
}
