package com.abdullah.visionbridge.data.gemini

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Base64
import com.abdullah.visionbridge.capture.CaptureRuntime
import com.abdullah.visionbridge.data.diagnostics.DiagnosticHub
import com.abdullah.visionbridge.data.speech.BilingualTtsEngine
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
    private val tts: BilingualTtsEngine,
) {
    private data class LiveProfile(
        val model: String,
        val proactiveAudio: Boolean,
        val semanticSceneGate: Boolean,
        val mediaResolution: String,
        val thinkingLevel: String? = null,
        val thinkingBudget: Int? = null,
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
    @Volatile private var activeSpeechRate = 1.0f
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

    // LIVE_TURN_BACKPRESSURE_V35: submitFrame does not return when bytes merely leave the phone.
    // It stays suspended until Gemini closes the turn, allowing MediaProjectionService's existing
    // one-active/one-latest queue to provide real backpressure.
    @Volatile private var activeTurnCompletion: CompletableDeferred<Boolean>? = null
    @Volatile private var activeFirstAudio: CompletableDeferred<Boolean>? = null

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

        // LIVE_TEXT_LOCAL_TTS_V36: Gemini is the eye; Android TTS is the mouth. Do not
        // buy another cloud reading while the current Live text is still being spoken.
        if (settings.mode == AnalysisMode.TEXT_READING && tts.isLiveSpeechInProgress()) {
            DiagnosticHub.record(
                "LIVE_LOCAL_SPEECH_BACKPRESSURE",
                mapOf("mode" to settings.mode.name, "reason" to "current_live_text_still_speaking"),
            )
            return true
        }

        synchronized(sendLock) {
            if (lastMode != settings.mode) {
                lastMode = settings.mode
                lastFrameSentAtElapsedMs = 0L
                sceneProbeOutstanding = false
                sceneProbeStartedAtElapsedMs = 0L
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
            val setupAndEncodeStarted = SystemClock.elapsedRealtimeNanos()
            val encoded = async(Dispatchers.Default) { frameEncoder.encode(bitmap, settings) }

            val connected = connection.await()
            val image = encoded.await()
            val setupAndEncodeWallMs = (SystemClock.elapsedRealtimeNanos() - setupAndEncodeStarted) / 1_000_000.0
            if (!connected) {
                DiagnosticHub.record(
                    "LIVE_FRAME_FALLBACK",
                    mapOf("reason" to "setup_not_ready", "model" to profile.model),
                )
                return@coroutineScope false
            }

            val currentSocket = socket ?: return@coroutineScope false
            if (responseInFlight) {
                // This should be prevented by the service queue. Never supersede an audible answer
                // if another caller slips through; keep that answer intact and let the next capture
                // replace the pending slot instead.
                DiagnosticHub.record(
                    "LIVE_BACKPRESSURE_GUARD",
                    mapOf("reason" to "turn_already_in_flight", "mode" to settings.mode.name),
                )
                return@coroutineScope true
            }

            val supersedingActiveResponse = false
            val turnCompletion = CompletableDeferred<Boolean>()
            val firstAudio = CompletableDeferred<Boolean>()
            activeTurnCompletion = turnCompletion
            activeFirstAudio = firstAudio
            activeSpeechEnabled = settings.speechEnabled
            activeSpeechRate = settings.speechRate
            activeResponseMode = settings.mode
            firstAudioSeenForEpoch = Long.MIN_VALUE
            sceneProbeHadAudio = false
            staleAudioBlocked = false
            staleAudioPacketsBlocked = 0
            synchronized(transcriptLock) { transcript = StringBuilder() }

            if (settings.mode == AnalysisMode.TEXT_READING) {
                activeTurnEpoch = audioPlayer.beginTurn("new_live_text_turn")
            }

            val base64Started = SystemClock.elapsedRealtimeNanos()
            val imageBase64 = Base64.encodeToString(image.bytes, Base64.NO_WRAP)
            val base64Ms = (SystemClock.elapsedRealtimeNanos() - base64Started) / 1_000_000.0

            // Set this immediately around network submission, not before image preprocessing. This
            // makes frameToFirstAudioMs a real model/network number instead of hiding local work.
            activeTurnSentAtNanos = SystemClock.elapsedRealtimeNanos()
            responseInFlight = true
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

            synchronized(sendLock) {
                lastFrameSentAtElapsedMs = SystemClock.elapsedRealtime()
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
                    "encodeTotalMs" to image.totalMs,
                    "copyMs" to image.copyMs,
                    "hashMs" to image.hashMs,
                    "imageHash" to image.imageHash,
                    "setupAndEncodeWallMs" to setupAndEncodeWallMs,
                    "encodingMetricVersion" to 2,
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

            DiagnosticHub.record(
                "LIVE_TURN_WAIT_STARTED",
                mapOf("mode" to settings.mode.name, "epoch" to activeTurnEpoch, "model" to profile.model),
            )

            val turnTimeout = if (settings.mode == AnalysisMode.SCENE_DESCRIPTION) {
                SCENE_TURN_TIMEOUT_MS
            } else {
                TEXT_TURN_TIMEOUT_MS
            }
            val completed = withTimeoutOrNull(turnTimeout) { turnCompletion.await() } == true
            if (!completed) {
                DiagnosticHub.record(
                    "LIVE_TURN_WAIT_TIMEOUT",
                    mapOf("timeoutMs" to turnTimeout, "mode" to settings.mode.name, "model" to profile.model),
                )
                completeActiveTurn(false, "turn_wait_timeout")
                invalidateSocket("turn_wait_timeout")
                return@coroutineScope false
            }
            DiagnosticHub.record(
                "LIVE_TURN_WAIT_COMPLETED",
                mapOf("mode" to settings.mode.name, "epoch" to activeTurnEpoch, "model" to profile.model),
            )
            if (settings.mode == AnalysisMode.TEXT_READING) {
                DiagnosticHub.record(
                    "LIVE_TEXT_CONTEXT_RESET",
                    mapOf("reason" to "accuracy_fresh_context_per_text_turn", "model" to profile.model),
                )
                invalidateSocket("text_accuracy_context_reset")
            }
            true
        }
    }

    fun reportLiveRequiredFailure(reason: String) {
        runtime.error("فشل Gemini Live: $reason. لم يتم تشغيل أي مسار احتياطي.")
        DiagnosticHub.record(
            "LIVE_REQUIRED_FAILURE",
            mapOf("reason" to reason, "fallbackAllowed" to false),
        )
    }

    // SMART_TARGET_LIVE_STALE_GUARD_V380
    fun onVisualTargetChanged(interruptSpeech: Boolean) {
        val turnWasInFlight = responseInFlight
        visualGeneration += 1L
        synchronized(sendLock) {
            lastFrameSentAtElapsedMs = 0L
            // A target transition invalidates the old semantic probe. The active network turn is
            // still allowed to reach its normal boundary; the next frame starts after that gate.
            sceneProbeOutstanding = false
            sceneProbeStartedAtElapsedMs = 0L
        }

        // Never close or cancel the WebSocket here. Mark the old turn stale so its remaining
        // transcription is ignored, let Gemini emit the normal turn boundary, then release the
        // backpressure gate as a handled supersession rather than a Live failure.
        if (turnWasInFlight) {
            staleAudioBlocked = true
            staleAudioPacketsBlocked = 0
        }
        synchronized(transcriptLock) { transcript = StringBuilder() }

        if (interruptSpeech) {
            // Gemini PCM is intentionally not the audible path in this build. Stop the local Live
            // TTS that is actually being heard. Keeping this separate from the socket is what makes
            // immediate target switching safe.
            tts.supersedeLiveSpeech("smart_target_immediate_change")
            audioPlayer.interrupt("smart_target_immediate_change")
        }

        DiagnosticHub.record(
            "LIVE_VISUAL_TARGET_CHANGED",
            mapOf(
                "interruptSpeechSetting" to interruptSpeech,
                "smartTargetPolicy" to true,
                "activeTurnMarkedStale" to turnWasInFlight,
                "networkTurnCancelled" to false,
                "webSocketClosed" to false,
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
        val fingerprint = fingerprint("$apiKey|${profile.model}|${profile.proactiveAudio}|${profile.mediaResolution}|${profile.thinkingLevel}|${profile.thinkingBudget}")
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
            completeActiveTurn(false, "server_interrupted")
            audioPlayer.interrupt("live_server_interrupted")
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
                val part = partElement.jsonObject
                val textPart = part["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (textPart.isNotBlank() && !staleAudioBlocked) {
                    synchronized(transcriptLock) { transcript.append(textPart) }
                    DiagnosticHub.record(
                        "LIVE_TEXT_PART_RECEIVED",
                        mapOf(
                            "characters" to textPart.length,
                            "mode" to activeResponseMode?.name,
                            "model" to activeProfile?.model,
                        ),
                    )
                }
                val inlineData = part["inlineData"]?.jsonObject ?: return@forEach
                val mimeType = inlineData["mimeType"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val data = inlineData["data"]?.jsonPrimitive?.contentOrNull.orEmpty()
                // LIVE_REQUIRED_AUDIO_TRANSCRIPT_V361: AUDIO is requested only because the Live
                // protocol requires it. Native PCM is deliberately never played; the corresponding
                // outputAudioTranscription is the source of truth and local Android TTS speaks it.
                if (mimeType.startsWith("audio/") && data.isNotBlank()) {
                    DiagnosticHub.record(
                        "LIVE_NATIVE_AUDIO_DISCARDED",
                        mapOf("mimeType" to mimeType, "bytesBase64" to data.length, "mode" to activeResponseMode?.name),
                    )
                    return@forEach
                }
                if (!mimeType.startsWith("audio/pcm") || data.isBlank()) return@forEach

                if (staleAudioBlocked) {
                    staleAudioPacketsBlocked += 1
                    return@forEach
                }

                val scene = activeResponseMode == AnalysisMode.SCENE_DESCRIPTION
                if (firstAudioSeenForEpoch == Long.MIN_VALUE) {
                    activeFirstAudio?.let { if (!it.isCompleted) it.complete(true) }
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
            DiagnosticHub.record(
                "LIVE_AUDIO_TRANSCRIPT_DELTA",
                mapOf(
                    "characters" to delta.length,
                    "mode" to activeResponseMode?.name,
                    "model" to activeProfile?.model,
                ),
            )
        }

        if (serverContent["turnComplete"]?.jsonPrimitive?.contentOrNull == "true") {
            // If a newer frame was submitted while the previous answer was speaking, this boundary
            // belongs to the old answer. It is only a gate-opening signal, not completion of the
            // new frame. This prevents old transcript/audio being published under the new target.
            if (staleAudioBlocked) {
                releaseStaleBoundary("turn_complete")
                firstAudioSeenForEpoch = Long.MIN_VALUE
                synchronized(transcriptLock) { transcript = StringBuilder() }
                completeActiveTurn(true, "smart_target_superseded")
                return
            }

            responseInFlight = false
            activeFirstAudio?.let { if (!it.isCompleted) it.complete(false) }
            val scene = activeResponseMode == AnalysisMode.SCENE_DESCRIPTION
            val finalText = synchronized(transcriptLock) {
                transcript.toString().trim().also { transcript = StringBuilder() }
            }
            if (scene) {
                synchronized(sendLock) {
                    sceneProbeOutstanding = false
                    sceneProbeStartedAtElapsedMs = 0L
                }
            }
            val textNoReliableContent = !scene && (
                finalText.trim().equals("NO_TEXT", ignoreCase = true) ||
                    finalText.trim().equals("NO_CHANGE", ignoreCase = true)
            )
            val semanticNoChange = scene && finalText.trim().equals("NO_CHANGE", ignoreCase = true)
            if (textNoReliableContent) {
                DiagnosticHub.record(
                    "LIVE_TEXT_NO_RELIABLE_CONTENT",
                    mapOf(
                        "reason" to finalText.trim(),
                        "model" to activeProfile?.model,
                        "accuracyPolicy" to "OMIT_UNVERIFIED_DO_NOT_GUESS",
                    ),
                )
            } else if (semanticNoChange) {
                DiagnosticHub.record(
                    "LIVE_SEMANTIC_SILENCE",
                    mapOf(
                        "reason" to "explicit_no_change_marker",
                        "model" to activeProfile?.model,
                        "visualGeneration" to visualGeneration,
                    ),
                )
            } else if (finalText.isNotBlank()) {
                val turnLatencyMs = if (activeTurnSentAtNanos > 0L) {
                    (SystemClock.elapsedRealtimeNanos() - activeTurnSentAtNanos) / 1_000_000.0
                } else null
                runtime.result(
                    AnalysisResult(
                        text = finalText,
                        source = AnalysisSource.GEMINI,
                        language = if (finalText.any { it in '\u0600'..'\u06FF' }) "mixed" else "en",
                    )
                )
                DiagnosticHub.record(
                    "LIVE_TEXT_TURN_READY",
                    mapOf(
                        "characters" to finalText.length,
                        "mode" to activeResponseMode?.name,
                        "turnLatencyMs" to turnLatencyMs,
                    ),
                )
                if (activeSpeechEnabled) {
                    if (scene) tts.supersedeLiveSpeech("new_live_scene_text_ready")
                    tts.speakLiveResult(
                        text = finalText,
                        rate = activeSpeechRate,
                        interruptPrevious = false,
                        live = true,
                    )
                    DiagnosticHub.record(
                        "LIVE_LOCAL_TTS_DISPATCHED",
                        mapOf(
                            "characters" to finalText.length,
                            "rate" to activeSpeechRate,
                            "mode" to activeResponseMode?.name,
                            "voicePolicy" to "FEMALE_FIRST_LOCAL_TTS",
                        ),
                    )
                }
                DiagnosticHub.record(
                    "LIVE_TURN_COMPLETE",
                    mapOf(
                        "characters" to finalText.length,
                        "epoch" to activeTurnEpoch,
                        "mode" to activeResponseMode?.name,
                    ),
                )
            }
            completeActiveTurn(true, "turn_complete")
        }
    }

    private fun completeActiveTurn(success: Boolean, reason: String) {
        activeFirstAudio?.let { if (!it.isCompleted) it.complete(false) }
        activeTurnCompletion?.let { if (!it.isCompleted) it.complete(success) }
        activeFirstAudio = null
        activeTurnCompletion = null
        responseInFlight = false
        DiagnosticHub.record(
            "LIVE_TURN_GATE_RELEASED",
            mapOf("success" to success, "reason" to reason, "mode" to activeResponseMode?.name),
        )
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
                put("mediaResolution", profile.mediaResolution)
                put("thinkingConfig", buildJsonObject {
                    profile.thinkingLevel?.let { put("thinkingLevel", it) }
                })
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
                " بعد القراءة الحرفية فقط، يمكنك إضافة جملة واحدة تبدأ بكلمة الوصف:. صف فقط شيئاً مرئياً مباشرة في اللقطة الحالية ومكانه التقريبي. لا تستنتج نوع المنتج أو اسم الشيء من النص المقروء أو من شكل مألوف، ولا تصف ظلاماً أو ضبابية أو عدم وضوح إلا إذا كان ذلك حقيقة بصرية قاطعة. إذا لم تكن واثقاً من الوصف فلا تضفه. وإذا لم يوجد نص موثوق فلا تحاول تعويضه بوصف تخميني."
            } else ""
            if (settings.captureProfile == CaptureProfile.STABLE) {
                "MODE=TEXT_ACCURATE_V362. هذه اللقطة الحالية وحدها هي المصدر. اقرأ حرفياً فقط الأحرف والكلمات والأرقام التي تراها فعلاً في البكسلات الحالية. ممنوع تماماً التخمين أو إكمال كلمة أو رقم أو موديل أو معيار أو رمز شائع من الذاكرة أو من شكل الملصق، وممنوع إنشاء بدائل مثل X أو XX أو XYZ أو XXXXX. لا تستخدم أي نص من إطار أو رد سابق. إذا لم تستطع التحقق بصرياً من جزء فاحذفه ولا تقل غير واضح. إذا لم يوجد نص يمكن الوثوق به فقل NO_TEXT فقط. لا تصف الإضاءة أو الضبابية أو الظلام داخل جزء القراءة. تجاهل واجهة eSight.$descriptionTail"
            } else {
                "MODE=TEXT_FAST_V362. استخدم اللقطة الحالية وحدها. اقرأ فقط النص المرئي المؤكد حرفياً وبالترتيب. لا تكمل أنماطاً مألوفة ولا تخمن أرقاماً أو رموزاً أو موديلات ولا تستخدم محتوى من رد سابق. إذا لم يوجد نص موثوق فقل NO_TEXT فقط. تجاهل واجهة eSight.$descriptionTail"
            }
        }
        AnalysisMode.SCENE_DESCRIPTION -> when (settings.sceneDescriptionStyle) {
            SceneDescriptionStyle.BRIEF -> "MODE=SCENE_BRIEF_SEMANTIC. إذا كانت هذه أول لقطة وصف في الجلسة فأعد وصفاً قصيراً دائماً. بعد ذلك قارن المعنى بالمشهد السابق. تجاهل اهتزاز الكاميرا والالتفات والتكبير وتغير الإضاءة البسيط إذا بقي المحتوى نفسه. تشغيل النور الذي يكشف أشياء جديدة أو انطفاؤه الذي يخفيها تغير حقيقي. إذا لم يتغير المحتوى العملي فأعد بالضبط NO_CHANGE فقط. عند تغير حقيقي أعد وصفاً عربياً قصيراً يبدأ بالأهم، خصوصاً الخطر أو العائق أو الشخص أو الاتجاه أو النص المفيد. لا تخمن."
            SceneDescriptionStyle.COMPREHENSIVE -> "MODE=SCENE_COMPREHENSIVE_SEMANTIC. إذا كانت هذه أول لقطة وصف في الجلسة فأعد وصفاً واضحاً دائماً. بعد ذلك قارن المعنى لا البكسلات. تجاهل حركة الكاميرا والدوران والتكبير والتركيز وتغير الإضاءة الصغير إذا بقي المحتوى العملي نفسه. إذا كشف تشغيل النور محتوى كان مخفياً أو أخفى انطفاؤه محتوى كان ظاهراً فهذا تغير حقيقي. إذا لم يتغير شيء عملي فأعد بالضبط NO_CHANGE فقط. عند ظهور أو اختفاء أو تغير شيء مفيد أعد وصفاً عربياً يبدأ بالأهم ثم يكمل باختصار ووضوح بلا تخمين."
        }
    }

    private fun profileFor(settings: AppSettings): LiveProfile = when (settings.mode) {
        AnalysisMode.TEXT_READING -> LiveProfile(
            model = TEXT_LIVE_MODEL,
            proactiveAudio = false,
            semanticSceneGate = false,
            // LIVE_TEXT_ACCURACY_V362: dense text needs more visual detail than the old latency-first profile.
            mediaResolution = "MEDIA_RESOLUTION_HIGH",
            thinkingLevel = "MEDIUM",
        )
        AnalysisMode.SCENE_DESCRIPTION -> LiveProfile(
            model = TEXT_LIVE_MODEL,
            proactiveAudio = false,
            semanticSceneGate = true,
            mediaResolution = "MEDIA_RESOLUTION_LOW",
            thinkingLevel = "MINIMAL",
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
        completeActiveTurn(false, "socket_invalidated_$reason")
        staleAudioBlocked = false
        DiagnosticHub.record("LIVE_SOCKET_INVALIDATED", mapOf("reason" to reason))
    }

    // STALE_SOCKET_CALLBACK_GUARD_V35: a close callback for a replaced socket is harmless.
    private fun clearSocketIfCurrent(webSocket: WebSocket) {
        var clearedCurrent = false
        synchronized(socketLock) {
            if (socket === webSocket) {
                socket = null
                setupReady = null
                setupSucceeded = false
                connectionFingerprint = null
                activeProfile = null
                clearedCurrent = true
            }
        }
        if (clearedCurrent) {
            completeActiveTurn(false, "socket_cleared")
            staleAudioBlocked = false
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
        const val TEXT_FIRST_AUDIO_TIMEOUT_MS = 3_500L
        const val TEXT_TURN_TIMEOUT_MS = 12_000L
        const val SCENE_TURN_TIMEOUT_MS = 6_000L
        const val NORMAL_CLOSE_CODE = 1000
        val SAMPLE_RATE_REGEX = Regex("rate=(\\d+)", RegexOption.IGNORE_CASE)

        val SYSTEM_INSTRUCTION = """
            أنت VisionBridge، مساعد رؤية لحظي لمستخدم كفيف أو ضعيف البصر يشاهد بث eSight Go على الهاتف.
            أنشئ استجابة عربية طبيعية واضحة مباشرة بلا مقدمة أو Markdown، وانطق الإنجليزية والأرقام كما تظهر عند الحاجة.
            نفذ أحدث إطار ومهمة فقط. لا تخمن نصاً غير واضح أو هوية شخص أو مسافة دقيقة أو شيئاً خارج الإطار.
            في القراءة اقرأ البكسلات الحالية نفسها كاملة. إذا طُلبت جملة وصفية مع النص فاجعلها جملة واحدة بعد القراءة. في الوصف المنفصل تجاهل حركة الكاميرا، وإذا لم يتغير معنى المشهد فقل NO_CHANGE فقط.
        """.trimIndent()
    }
}
