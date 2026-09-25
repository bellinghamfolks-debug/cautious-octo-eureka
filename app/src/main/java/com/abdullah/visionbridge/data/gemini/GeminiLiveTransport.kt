package com.abdullah.visionbridge.data.gemini

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Base64
import com.abdullah.visionbridge.capture.CaptureRuntime
import com.abdullah.visionbridge.data.diagnostics.DiagnosticHub
import com.abdullah.visionbridge.data.diagnostics.DiagnosticTrace
import com.abdullah.visionbridge.data.speech.LivePcmAudioPlayer
import com.abdullah.visionbridge.domain.model.AnalysisMode
import com.abdullah.visionbridge.domain.model.AnalysisResult
import com.abdullah.visionbridge.domain.model.AnalysisSource
import com.abdullah.visionbridge.domain.model.AnalysisTurn
import com.abdullah.visionbridge.domain.model.AppSettings
import com.abdullah.visionbridge.domain.model.CaptureProfile
import com.abdullah.visionbridge.domain.model.SceneDescriptionStyle
import com.abdullah.visionbridge.domain.repository.ApiKeyStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Persistent Gemini Live transport.
 *
 * The WebSocket is session-scoped; visual turns remain immutable and frame-bound. Native model
 * PCM goes directly to [LivePcmAudioPlayer]. Android TTS is intentionally not in this path.
 */
class GeminiLiveTransport(
    private val runtime: CaptureRuntime,
    private val keys: ApiKeyStore,
    private val audioPlayer: LivePcmAudioPlayer,
) {
    private val gate = runtime.turnGate
    private val encoder = LiveFrameEncoder()
    private val submitMutex = Mutex()
    private val socketLock = Any()
    private val stateLock = Any()

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

    /** When the last handshake gave up, so a retry costs one attempt per interval, not per frame. */
    @Volatile private var lastSetupFailureAtElapsedMs = 0L
    @Volatile private var keyFingerprint: String? = null
    @Volatile private var transportSessionId = UUID.randomUUID().toString()
    @Volatile private var resumptionHandle: String? = null

    @Volatile private var activeTurn: AnalysisTurn? = null
    @Volatile private var activeEpoch = 0L
    @Volatile private var responseInFlight = false
    @Volatile private var staleBoundaryBlocked = false
    @Volatile private var firstAudioSeen = false
    @Volatile private var speechEnabled = true

    private var transcript = StringBuilder()
    private var lastReservedAtElapsedMs = 0L

    fun supports(settings: AppSettings): Boolean =
        !settings.forceCellular &&
            (settings.mode == AnalysisMode.SCENE_DESCRIPTION || !settings.useLocalOcr)

    suspend fun preconnect(settings: AppSettings) {
        if (!supports(settings)) return
        val apiKey = keys.get()?.takeIf { it.isNotBlank() } ?: return
        val started = SystemClock.elapsedRealtimeNanos()
        val connected = ensureConnected(apiKey)
        DiagnosticHub.record(
            "LIVE_PRECONNECT_COMPLETED",
            mapOf(
                "connected" to connected,
                "durationMs" to
                    (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0,
                "model" to MODEL,
            ),
        )
    }

    fun reserveFrame(settings: AppSettings): Boolean {
        if (!supports(settings)) return false
        val now = SystemClock.elapsedRealtime()
        synchronized(stateLock) {
            val currentGeneration = gate.generation()
            val sameGeneration = activeTurn?.visualGeneration == currentGeneration

            // A page gets one Live turn until the visual tracker proves that the target changed.
            // Re-sending the same page every second only interrupts its own native-audio reading.
            if (settings.mode == AnalysisMode.TEXT_READING && sameGeneration) return false

            // A scene may be sampled again after its short response completes, but never interrupt
            // an in-flight response merely because the one-second sampling clock fired.
            if (
                settings.mode == AnalysisMode.SCENE_DESCRIPTION &&
                responseInFlight &&
                sameGeneration
            ) {
                return false
            }

            if (
                lastReservedAtElapsedMs > 0L &&
                now - lastReservedAtElapsedMs < LIVE_VIDEO_INTERVAL_MS
            ) {
                return false
            }
            lastReservedAtElapsedMs = now
            return true
        }
    }

    /**
     * true = handled by Live (including obsolete while setup was running).
     * false = caller should use the frame-bound SSE fallback.
     */
    suspend fun submitFrame(
        bitmap: Bitmap,
        trace: DiagnosticTrace,
        settings: AppSettings,
    ): Boolean = submitMutex.withLock {
        if (!supports(settings)) return@withLock false
        val expectedGeneration = gate.generation()
        val apiKey = keys.get()?.takeIf { it.isNotBlank() } ?: return@withLock false

        return@withLock coroutineScope {
            val connection = async(Dispatchers.IO) { ensureConnected(apiKey) }
            val encodedTask = async(Dispatchers.Default) { encoder.encode(bitmap, settings) }
            val connected = connection.await()
            val encoded = encodedTask.await()

            if (!connected) {
                DiagnosticHub.record("LIVE_FRAME_FALLBACK", trace.fields(mapOf("reason" to "setup_not_ready")))
                return@coroutineScope false
            }
            if (gate.generation() != expectedGeneration) {
                DiagnosticHub.record("LIVE_FRAME_DROPPED", trace.fields(mapOf("reason" to "obsolete_during_setup")))
                return@coroutineScope true
            }

            val capture = AnalysisTurn(
                turnId = "LIVE-" + trace.traceId,
                traceId = trace.traceId,
                frameId = trace.frameId,
                visualGeneration = expectedGeneration,
                mode = settings.mode,
                capturedAt = trace.capturedAtEpochMs,
                capturedAtNanos = trace.capturedAtElapsedNanos,
                model = MODEL,
                promptVersion = PROMPT_VERSION,
                sessionId = transportSessionId,
            )
            val submittedAt = SystemClock.elapsedRealtimeNanos()
            val bound = capture.copy(submittedAtNanos = submittedAt, imageHash = encoded.imageHash)

            if (!gate.activate(capture) {
                    runtime.clearVisualResult()
                }
            ) {
                DiagnosticHub.record(
                    "LIVE_FRAME_DROPPED",
                    capture.fields() + mapOf("reason" to "turn_activation_rejected"),
                )
                return@coroutineScope true
            }
            if (!gate.bindSubmission(bound)) {
                gate.invalidate {
                    runtime.clearVisualResult()
                    audioPlayer.interrupt("live_bind_failed")
                }
                return@coroutineScope false
            }

            val currentSocket = socket
            if (currentSocket == null) {
                gate.invalidate { runtime.clearVisualResult() }
                return@coroutineScope false
            }

            val superseding: Boolean
            synchronized(stateLock) {
                superseding = responseInFlight
                activeTurn = bound
                activeEpoch = audioPlayer.beginTurn(
                    if (superseding) "live_turn_superseded" else "live_turn_started"
                )
                speechEnabled = settings.speechEnabled
                staleBoundaryBlocked = superseding
                firstAudioSeen = false
                transcript = StringBuilder()
                responseInFlight = true
            }

            val base64Started = SystemClock.elapsedRealtimeNanos()
            val imageBase64 = Base64.encodeToString(encoded.bytes, Base64.NO_WRAP)
            val base64Ms = (SystemClock.elapsedRealtimeNanos() - base64Started) / 1_000_000.0

            val videoSent = currentSocket.send(videoMessage(imageBase64))
            val turnSent = currentSocket.send(clientTurnMessage(instructionFor(settings)))
            if (!videoSent || !turnSent) {
                DiagnosticHub.record(
                    "LIVE_FRAME_FALLBACK",
                    bound.fields() + mapOf(
                        "reason" to "websocket_send_failed",
                        "videoSent" to videoSent,
                        "turnSent" to turnSent,
                    ),
                )
                invalidateSocket("send_failed")
                gate.invalidate {
                    runtime.clearVisualResult()
                    audioPlayer.interrupt("live_send_failed")
                }
                return@coroutineScope false
            }

            DiagnosticHub.record(
                "LIVE_FRAME_SENT",
                bound.fields() + mapOf(
                    "encodedBytes" to encoded.bytes.size,
                    "outputWidth" to encoded.width,
                    "outputHeight" to encoded.height,
                    "quality" to encoded.quality,
                    "encodeTotalMs" to encoded.totalMs,
                    "base64Ms" to base64Ms,
                    "nativeAudio" to settings.speechEnabled,
                    "supersedingActiveResponse" to superseding,
                    "epoch" to activeEpoch,
                ),
            )
            true
        }
    }

    fun onVisualTargetChanged(interruptSpeech: Boolean) {
        synchronized(stateLock) {
            // Keep the global one-frame-per-second Live video budget across target changes.
            // A target replacement may preempt audio, but it must not violate the transport limit.
            if (interruptSpeech) audioPlayer.interrupt("visual_target_changed")
        }
        DiagnosticHub.record(
            "LIVE_VISUAL_TARGET_CHANGED",
            mapOf("visualGeneration" to gate.generation(), "interruptSpeech" to interruptSpeech),
        )
    }

    fun stop(reason: String = "capture_stopped") {
        synchronized(stateLock) {
            lastReservedAtElapsedMs = 0L
            activeTurn = null
            responseInFlight = false
            staleBoundaryBlocked = false
            firstAudioSeen = false
            transcript = StringBuilder()
        }
        audioPlayer.interrupt(reason)
        invalidateSocket(reason)
        resumptionHandle = null
    }

    private suspend fun ensureConnected(apiKey: String): Boolean {
        val fingerprint = fingerprint(apiKey)
        val ready: CompletableDeferred<Boolean>
        synchronized(socketLock) {
            val existing = setupReady
            if (socket != null && setupSucceeded && keyFingerprint == fingerprint && existing != null) {
                return true
            }
            // A handshake that has already finished and failed must not be awaited again. Holding
            // on to it made one bad setup permanent: the same settled deferred answered false for
            // every frame of the session, so eleven frames over forty-five seconds each paid the
            // full timeout and went to SSE without a single retry. A failed attempt is discarded
            // here so the next frame opens a fresh socket, at most once per backoff.
            if (existing != null && existing.isCompleted && existing.getCompleted() != true) {
                val since = SystemClock.elapsedRealtime() - lastSetupFailureAtElapsedMs
                if (since < LIVE_SETUP_RETRY_INTERVAL_MS) return false
                socket?.close(NORMAL_CLOSE_CODE, "retry_live_setup")
                socket = null
                setupReady = null
                setupSucceeded = false
            }
            val current = setupReady
            if (socket != null && keyFingerprint == fingerprint && current != null) {
                ready = current
            } else {
                socket?.close(NORMAL_CLOSE_CODE, "replace_live_session")
                val deferred = CompletableDeferred<Boolean>()
                ready = deferred
                setupReady = deferred
                setupSucceeded = false
                keyFingerprint = fingerprint
                transportSessionId = UUID.randomUUID().toString()
                val request = Request.Builder().url("$LIVE_ENDPOINT?key=$apiKey").build()
                socket = client.newWebSocket(request, listener(fingerprint, deferred))
                DiagnosticHub.record(
                    "LIVE_SOCKET_CONNECTING",
                    mapOf("model" to MODEL, "transportSessionId" to transportSessionId),
                )
            }
        }
        val connected = withTimeoutOrNull(LIVE_SETUP_TIMEOUT_MS) { ready.await() } == true
        if (!connected) {
            // Settle the deferred so the branch above can see this attempt finished and failed,
            // rather than every later frame awaiting a handshake nobody is still running.
            if (!ready.isCompleted) ready.complete(false)
            synchronized(socketLock) { lastSetupFailureAtElapsedMs = SystemClock.elapsedRealtime() }
            DiagnosticHub.record(
                "LIVE_SETUP_FAILED",
                mapOf(
                    "model" to MODEL,
                    "timeoutMs" to LIVE_SETUP_TIMEOUT_MS,
                    "retryInMs" to LIVE_SETUP_RETRY_INTERVAL_MS,
                    "transportSessionId" to transportSessionId,
                ),
            )
        }
        return connected
    }

    private fun listener(
        expectedFingerprint: String,
        ready: CompletableDeferred<Boolean>,
    ): WebSocketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (!webSocket.send(setupMessage()) && !ready.isCompleted) ready.complete(false)
            DiagnosticHub.record(
                "LIVE_SOCKET_OPEN",
                mapOf("httpCode" to response.code, "protocol" to response.protocol.toString()),
            )
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (keyFingerprint == expectedFingerprint) handleServerMessage(text, ready)
        }

        /**
         * Gemini Live answers in binary frames, not text ones.
         *
         * Without this override OkHttp hands every server message to the no-op default and the
         * session is deaf while looking perfectly healthy: the handshake returns 101, the setup
         * payload is sent, and `setupComplete` arrives and is discarded. A field session on
         * 2026-09-25 shows exactly that shape — socket open at 0.7 s, preconnect giving up at
         * 4.0 s with connected=false, then eleven frames over forty-five seconds every one of
         * them falling back to SSE with `setup_not_ready`, and not one parse failure logged,
         * because the text handler was never reached at all.
         */
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (keyFingerprint == expectedFingerprint) {
                handleServerMessage(bytes.string(Charsets.UTF_8), ready)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!ready.isCompleted) ready.complete(false)
            clearSocketIfCurrent(webSocket)
            DiagnosticHub.record(
                "LIVE_SOCKET_FAILURE",
                mapOf("httpCode" to response?.code, "errorType" to t.javaClass.simpleName),
            )
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!ready.isCompleted) ready.complete(false)
            clearSocketIfCurrent(webSocket)
            DiagnosticHub.record("LIVE_SOCKET_CLOSED", mapOf("code" to code, "reason" to reason))
        }
    }

    private fun handleServerMessage(raw: String, ready: CompletableDeferred<Boolean>) {
        val root = runCatching { JSONObject(raw) }.getOrElse {
            DiagnosticHub.record("LIVE_JSON_PARSE_FAILURE", mapOf("characters" to raw.length))
            return
        }

        if (root.has("setupComplete")) {
            setupSucceeded = true
            if (!ready.isCompleted) ready.complete(true)
            DiagnosticHub.record(
                "LIVE_SETUP_COMPLETE",
                mapOf("model" to MODEL, "transportSessionId" to transportSessionId),
            )
        }
        if (root.has("error")) {
            DiagnosticHub.record(
                "LIVE_API_ERROR",
                mapOf("error" to root.optJSONObject("error")?.optString("message")),
            )
        }

        root.optJSONObject("sessionResumptionUpdate")?.let { update ->
            val resumable = update.optBoolean("resumable", false)
            val handle = update.optString("newHandle").takeIf { it.isNotBlank() }
            if (resumable && handle != null) resumptionHandle = handle
            DiagnosticHub.record(
                "LIVE_SESSION_RESUMPTION_UPDATE",
                mapOf(
                    "resumable" to resumable,
                    "hasHandle" to (handle != null),
                    "transportSessionId" to transportSessionId,
                ),
            )
        }

        root.optJSONObject("goAway")?.let { goAway ->
            DiagnosticHub.record(
                "LIVE_GO_AWAY",
                mapOf(
                    "timeLeft" to goAway.optString("timeLeft"),
                    "hasResumptionHandle" to !resumptionHandle.isNullOrBlank(),
                ),
            )
        }

        val server = root.optJSONObject("serverContent") ?: return

        val interruptedThisMessage = server.optBoolean("interrupted", false)
        if (interruptedThisMessage) {
            synchronized(stateLock) {
                staleBoundaryBlocked = false
                firstAudioSeen = false
                transcript = StringBuilder()
            }
            DiagnosticHub.record(
                "LIVE_TURN_INTERRUPTED",
                activeTurn?.fields().orEmpty() + mapOf("epoch" to activeEpoch),
            )
            // Audio/transcription carried in the same serverContent belongs to the interrupted
            // generation. Never relabel those bytes as the new active frame. A following server
            // message starts the new generation.
            return
        }

        val parts = server.optJSONObject("modelTurn")?.optJSONArray("parts")
        if (parts != null) {
            for (index in 0 until parts.length()) {
                val inline = parts.optJSONObject(index)?.optJSONObject("inlineData") ?: continue
                val mime = inline.optString("mimeType")
                val data = inline.optString("data")
                if (!mime.startsWith("audio/pcm") || data.isBlank()) continue

                val turn: AnalysisTurn
                val epoch: Long
                val allowed: Boolean
                synchronized(stateLock) {
                    val current = activeTurn
                    if (current == null) {
                        allowed = false
                        turn = DUMMY_TURN
                        epoch = activeEpoch
                    } else {
                        turn = current
                        epoch = activeEpoch
                        allowed = !staleBoundaryBlocked && gate.rejection(turn) == null
                    }
                }
                if (!allowed) continue

                if (!firstAudioSeen) {
                    firstAudioSeen = true
                    val now = SystemClock.elapsedRealtimeNanos()
                    DiagnosticHub.record(
                        "LIVE_FIRST_AUDIO_PACKET",
                        turn.fields() + mapOf(
                            "receivedAtElapsedNanos" to now,
                            "submitToFirstAudioMs" to
                                ((now - checkNotNull(turn.submittedAtNanos)) / 1_000_000.0),
                            "epoch" to epoch,
                        ),
                    )
                }
                if (speechEnabled) {
                    runCatching { Base64.decode(data, Base64.DEFAULT) }.getOrNull()?.let { bytes ->
                        audioPlayer.enqueue(epoch, bytes, sampleRateFromMime(mime))
                    }
                }
            }
        }

        val delta = server.optJSONObject("outputTranscription")?.optString("text").orEmpty()
        if (delta.isNotBlank()) {
            val turn: AnalysisTurn?
            val fullText: String
            synchronized(stateLock) {
                turn = activeTurn
                if (staleBoundaryBlocked || turn == null || gate.rejection(turn!!) != null) return
                transcript.append(delta)
                fullText = transcript.toString().trim()
            }
            if (fullText.isNotBlank() && turn != null) {
                runtime.result(
                    AnalysisResult(
                        text = fullText,
                        source = AnalysisSource.GEMINI,
                        language = if (fullText.any { it in '\u0600'..'\u06FF' }) "mixed" else "en",
                        turn = turn,
                    )
                )
                DiagnosticHub.record(
                    "LIVE_OUTPUT_TRANSCRIPTION",
                    turn.fields() + mapOf("characters" to fullText.length),
                )
            }
        }

        if (server.optBoolean("turnComplete", false)) {
            val wasBoundary: Boolean
            val turn: AnalysisTurn?
            synchronized(stateLock) {
                wasBoundary = staleBoundaryBlocked
                if (staleBoundaryBlocked) {
                    staleBoundaryBlocked = false
                    transcript = StringBuilder()
                    firstAudioSeen = false
                } else {
                    responseInFlight = false
                }
                turn = activeTurn
            }
            if (!wasBoundary && turn != null) {
                DiagnosticHub.record(
                    "LIVE_TURN_COMPLETE",
                    turn.fields() + mapOf("epoch" to activeEpoch),
                )
            }
        }
    }

    private fun setupMessage(): String {
        val resumption = JSONObject()
        resumptionHandle?.takeIf { it.isNotBlank() }?.let { resumption.put("handle", it) }
        return JSONObject()
            .put(
                "setup",
                JSONObject()
                    .put("model", "models/$MODEL")
                    .put(
                        "generationConfig",
                        JSONObject()
                            .put("responseModalities", JSONArray().put("AUDIO"))
                            // Native audio picks its language here, not from the prompt. Without
                            // a languageCode Live answers in English however the instruction is
                            // worded — which is what a field session on 2026-09-25 heard: scene
                            // descriptions spoken in English to an Arabic-speaking user, while
                            // the per-turn instruction said "in Arabic" the whole time.
                            .put("speechConfig", JSONObject().put("languageCode", SPOKEN_LANGUAGE)),
                    )
                    .put(
                        "systemInstruction",
                        JSONObject().put(
                            "parts",
                            JSONArray().put(JSONObject().put("text", SYSTEM_INSTRUCTION)),
                        ),
                    )
                    .put("outputAudioTranscription", JSONObject())
                    .put(
                        "contextWindowCompression",
                        JSONObject().put("slidingWindow", JSONObject()),
                    )
                    .put("sessionResumption", resumption),
            )
            .toString()
    }

    private fun videoMessage(base64: String): String =
        JSONObject()
            .put(
                "realtimeInput",
                JSONObject().put(
                    "video",
                    JSONObject().put("data", base64).put("mimeType", "image/jpeg"),
                ),
            )
            .toString()

    private fun clientTurnMessage(instruction: String): String =
        JSONObject()
            .put(
                "clientContent",
                JSONObject()
                    .put(
                        "turns",
                        JSONArray().put(
                            JSONObject()
                                .put("role", "user")
                                .put(
                                    "parts",
                                    JSONArray().put(JSONObject().put("text", instruction)),
                                ),
                        ),
                    )
                    .put("turnComplete", true),
            )
            .toString()

    private fun instructionFor(settings: AppSettings): String = when (settings.mode) {
        AnalysisMode.TEXT_READING -> {
            val tail = if (settings.describeAlongsideText) {
                " After the visible text, add one very short grounded scene sentence only if useful."
            } else {
                ""
            }
            if (settings.captureProfile == CaptureProfile.FAST_TEXT) {
                "Read the currently visible text immediately. Start with the first clear words. " +
                    "Preserve Arabic, English and numbers exactly; do not translate, repair, infer, " +
                    "or describe hidden text. If nothing is legible, stay silent.$tail"
            } else {
                "Read only literal text visible in the current image, in reading order, with no " +
                    "preamble. Preserve Arabic, English and numbers exactly. Never infer missing " +
                    "characters. If a word is not legible, say غير واضح and continue.$tail"
            }
        }
        AnalysisMode.SCENE_DESCRIPTION -> when (settings.sceneDescriptionStyle) {
            SceneDescriptionStyle.BRIEF ->
                "Describe the current visible scene in Arabic immediately, one short useful sentence. " +
                    "Put hazards, obstacles or important direction changes first. Do not guess."
            SceneDescriptionStyle.COMPREHENSIVE ->
                "Describe the current visible scene in Arabic. Start immediately with the most " +
                    "important visible fact, then objects, relative positions, paths and obstacles. " +
                    "Be concise and do not guess identity or exact distance."
        }
    }

    private fun sampleRateFromMime(mimeType: String): Int =
        SAMPLE_RATE_REGEX.find(mimeType)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?.takeIf { it in 8_000..96_000 }
            ?: LivePcmAudioPlayer.DEFAULT_SAMPLE_RATE_HZ

    private fun invalidateSocket(reason: String) {
        synchronized(socketLock) {
            socket?.close(NORMAL_CLOSE_CODE, reason)
            socket = null
            setupReady = null
            setupSucceeded = false
            keyFingerprint = null
        }
        synchronized(stateLock) {
            responseInFlight = false
            staleBoundaryBlocked = false
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
        synchronized(stateLock) {
            responseInFlight = false
            staleBoundaryBlocked = false
        }
    }

    private fun fingerprint(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .take(6)
            .joinToString("") { "%02x".format(it) }

    companion object {
        const val MODEL = "gemini-3.8-live"
        const val PROMPT_VERSION = "live-frame-bound-v1"
        private const val LIVE_ENDPOINT =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        private const val LIVE_SETUP_TIMEOUT_MS = 4_000L

        /** Long enough that a dead endpoint is not hammered, short enough to recover in a session. */
        private const val LIVE_SETUP_RETRY_INTERVAL_MS = 15_000L
        private const val LIVE_VIDEO_INTERVAL_MS = 1_000L
        private const val NORMAL_CLOSE_CODE = 1000
        private val SAMPLE_RATE_REGEX = Regex("rate=(\\d+)", RegexOption.IGNORE_CASE)

        private val DUMMY_TURN = AnalysisTurn(
            turnId = "dummy",
            traceId = "dummy",
            frameId = "dummy",
            visualGeneration = 0,
            mode = AnalysisMode.TEXT_READING,
            capturedAt = 0,
            capturedAtNanos = 0,
            model = MODEL,
            promptVersion = PROMPT_VERSION,
            sessionId = "dummy",
        )

        /** BCP-47 tag for the spoken answer. The user is Arabic-speaking; this is not a default. */
        private const val SPOKEN_LANGUAGE = "ar-XA"

        private const val SYSTEM_INSTRUCTION =
            "You are VisionBridge, a real-time visual accessibility assistant for a blind or " +
                "low-vision user who speaks Arabic. Always speak Arabic, including every " +
                "description, every explanation and every status remark. The one exception is " +
                "text you are transcribing from the image: read that exactly as written, in its " +
                "own script, without translating it. " +
                "Respond with useful content immediately and without a preamble. " +
                "Use only what is visibly supported by the current image. Never invent text, " +
                "identity, distance, or hidden details. Spoken output must be concise and clear."
    }
}
