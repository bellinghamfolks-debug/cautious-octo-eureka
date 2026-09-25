package com.abdullah.visionbridge.data.gemini

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Base64
import com.abdullah.visionbridge.capture.CaptureRuntime
import com.abdullah.visionbridge.data.diagnostics.DiagnosticHub
import com.abdullah.visionbridge.data.diagnostics.DiagnosticTrace
import com.abdullah.visionbridge.data.speech.BilingualTtsEngine
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
 * The WebSocket is session-scoped; visual turns remain immutable and frame-bound.
 *
 * Which answer the session asks for depends on the mode, and [LiveResponseMode] carries the reason.
 * Describing a scene asks for AUDIO, and native model PCM goes directly to [LivePcmAudioPlayer].
 * Reading asks for TEXT, because a transcript of synthesised speech is not a transcription of a
 * page, and the literal text is spoken through [BilingualTtsEngine] instead. The modality is fixed
 * at setup, so changing mode reopens the socket.
 */
class GeminiLiveTransport(
    private val runtime: CaptureRuntime,
    private val keys: ApiKeyStore,
    private val audioPlayer: LivePcmAudioPlayer,
    private val tts: BilingualTtsEngine,
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

    /**
     * What the open socket was set up to answer with. `responseModalities` is part of the setup
     * payload and cannot be changed on a live session, so switching between reading and describing
     * means a new socket rather than a different instruction.
     */
    @Volatile private var socketResponseMode: LiveResponseMode? = null

    /** When the last handshake gave up, so a retry costs one attempt per interval, not per frame. */
    @Volatile private var lastSetupFailureAtElapsedMs = 0L
    @Volatile private var keyFingerprint: String? = null
    @Volatile private var transportSessionId = UUID.randomUUID().toString()
    @Volatile private var resumptionHandle: String? = null

    @Volatile private var activeTurn: AnalysisTurn? = null
    @Volatile private var activeEpoch = 0L
    @Volatile private var responseInFlight = false

    /**
     * Whether the last target change asked for the current answer to be cut off.
     *
     * A reading and a scene description expire differently. A transcription stays true about the
     * object it was taken from even after the camera drifts, so finishing it costs the user
     * nothing and cutting it costs them the rest of the label. A field session on 2026-09-25
     * shows what that cost: of twenty-one frames sent, eight produced speech, and six of those
     * eight were cut off mid-answer by the next frame — the visual generation had moved on while
     * the model was still reading a perfume bottle held in the hand.
     */
    @Volatile private var interruptRequested = false
    @Volatile private var staleBoundaryBlocked = false
    @Volatile private var firstAudioSeen = false

    /**
     * The reading path produces no audio packet, so `LIVE_FIRST_AUDIO_PACKET` cannot measure it.
     * This is the same measurement on the channel a reading actually arrives by: how long after
     * the frame went out the user had a first word.
     */
    @Volatile private var firstTextSeen = false
    @Volatile private var speechEnabled = true
    @Volatile private var speechRate = 1f

    /** The response mode of the turn currently being answered, which the parser dispatches on. */
    @Volatile private var activeResponseMode = LiveResponseMode.NATIVE_AUDIO

    private var transcript = StringBuilder()

    /** Holds literal model text until a clause is complete, for the reading path only. */
    private val speechBuffer = SpokenTextBuffer()
    private var lastReservedAtElapsedMs = 0L

    /** Watches for a turn that is answered by nothing; see [FIRST_TOKEN_DEADLINE_MS]. */
    private val watchdogScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * The deadline watching the turn in flight, cancelled when capture stops.
     *
     * Without that cancellation a session the user ended while a turn was still open would be
     * scored as silence, and a model that had done nothing wrong would be ruled out for the rest
     * of the process.
     */
    @Volatile private var watchdogJob: Job? = null

    /** The model the open socket is talking to, which is chosen per modality, not fixed. */
    @Volatile private var activeModel = LiveModelDirectory.NATIVE_AUDIO_MODEL

    /** Live-capable model names as the API reports them; discovered once, then reused. */
    @Volatile private var discoveredModels: List<String>? = null

    /**
     * Models that accepted a setup for a modality and then answered a turn with nothing.
     *
     * This is how a native-audio model is recognised in practice rather than by its name: it takes
     * `responseModalities: ["TEXT"]`, completes the handshake, and produces no token. Recorded per
     * session so the same dead combination is never tried twice, and never negative for a model
     * that has actually answered.
     */
    private val unanswering = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>(),
    )

    /** Modalities already reported as having no model left, so that is said once, not per frame. */
    private val exhaustedModality = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>(),
    )

    fun supports(settings: AppSettings): Boolean = LiveTransportRouting.carriedByLive(settings)

    suspend fun preconnect(settings: AppSettings) {
        if (!supports(settings)) return
        val apiKey = keys.get()?.takeIf { it.isNotBlank() } ?: return
        val started = SystemClock.elapsedRealtimeNanos()
        val responseMode = LiveResponseMode.of(settings.mode)
        val connected = ensureConnected(apiKey, responseMode)
        DiagnosticHub.record(
            "LIVE_PRECONNECT_COMPLETED",
            mapOf(
                "connected" to connected,
                "durationMs" to
                    (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0,
                "model" to activeModel,
                "responseMode" to responseMode.name,
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

            // The target did change, but the policy that judged it said not to cut the speech.
            // Dispatching anyway supersedes the turn in flight and the user hears half a label,
            // which is the same cancellation the policy had just declined to make. The reading is
            // allowed to finish; the newest frame is still the one sent when it does, and the
            // bound stops a turn that never completes from holding the lane forever.
            if (
                settings.mode == AnalysisMode.TEXT_READING &&
                responseInFlight &&
                !interruptRequested &&
                now - lastReservedAtElapsedMs < READING_COMPLETION_GRACE_MS
            ) {
                DiagnosticHub.record(
                    "LIVE_FRAME_DEFERRED",
                    mapOf(
                        "reason" to "reading_in_flight_and_change_did_not_request_interrupt",
                        "activeTurnId" to activeTurn?.turnId,
                        "activeGeneration" to activeTurn?.visualGeneration,
                        "currentGeneration" to currentGeneration,
                        "inFlightForMs" to (now - lastReservedAtElapsedMs),
                    ),
                )
                return false
            }

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

        val responseMode = LiveResponseMode.of(settings.mode)
        return@withLock coroutineScope {
            val connection = async(Dispatchers.IO) { ensureConnected(apiKey, responseMode) }
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
                model = activeModel,
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
                // Consumed here: the decision belonged to the change that has now been acted on.
                interruptRequested = false
                activeTurn = bound
                activeEpoch = audioPlayer.beginTurn(
                    if (superseding) "live_turn_superseded" else "live_turn_started"
                )
                speechEnabled = settings.speechEnabled
                speechRate = settings.speechRate
                activeResponseMode = responseMode
                staleBoundaryBlocked = superseding
                firstAudioSeen = false
                firstTextSeen = false
                transcript = StringBuilder()
                speechBuffer.reset()
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
                    "responseMode" to responseMode.name,
                    "responseModality" to responseMode.modality,
                    "nativeAudio" to (responseMode.speaksItself && settings.speechEnabled),
                    "supersedingActiveResponse" to superseding,
                    "epoch" to activeEpoch,
                ),
            )
            watchFirstToken(bound, activeEpoch, activeModel, responseMode)
            true
        }
    }

    /**
     * Gives this turn a deadline to produce its first token, and rules the model out if it does not.
     *
     * A Live turn that is answered by nothing used to be invisible until the socket's own read
     * timed out. The 2026-09-25 18:24 session spent thirty seconds that way and then handed every
     * later frame a four-second setup timeout on top, so a single unanswerable combination cost
     * the whole session. Nothing in the protocol reports it — there is no error, no turnComplete,
     * no close — so the only way to know is to stop waiting.
     *
     * The deadline is generous next to a first token (measured at 155-332 ms on the SSE path) and
     * short next to a session. It is paid once per model per modality, because the verdict is
     * remembered.
     */
    private fun watchFirstToken(
        turn: AnalysisTurn,
        epoch: Long,
        model: String,
        responseMode: LiveResponseMode,
    ) {
        watchdogJob?.cancel()
        watchdogJob = watchdogScope.launch {
            delay(FIRST_TOKEN_DEADLINE_MS)
            val answered = synchronized(stateLock) {
                // A newer turn owns the lane now, so this one's silence proves nothing.
                if (activeEpoch != epoch) return@launch
                firstAudioSeen || firstTextSeen || transcript.isNotEmpty()
            }
            if (answered) return@launch
            DiagnosticHub.record(
                "LIVE_TURN_SILENT",
                turn.fields() + mapOf(
                    "model" to model,
                    "modality" to responseMode.modality,
                    "deadlineMs" to FIRST_TOKEN_DEADLINE_MS,
                    "epoch" to epoch,
                ),
            )
            ruleOut(model, responseMode, "no_first_token_within_deadline")
        }
    }

    fun onVisualTargetChanged(interruptSpeech: Boolean) {
        synchronized(stateLock) {
            // Keep the global one-frame-per-second Live video budget across target changes.
            // A target replacement may preempt audio, but it must not violate the transport limit.
            if (interruptSpeech) audioPlayer.interrupt("visual_target_changed")
            // Remembered so the next frame knows whether it is allowed to cut a reading short.
            // The smart-target policy already decides this; it was simply not consulted again at
            // the moment the replacement frame was dispatched.
            interruptRequested = interruptSpeech
        }
        DiagnosticHub.record(
            "LIVE_VISUAL_TARGET_CHANGED",
            mapOf("visualGeneration" to gate.generation(), "interruptSpeech" to interruptSpeech),
        )
    }

    fun stop(reason: String = "capture_stopped") {
        // Before anything else: a turn the user ended is not a turn the model failed to answer.
        watchdogJob?.cancel()
        watchdogJob = null
        synchronized(stateLock) {
            lastReservedAtElapsedMs = 0L
            activeTurn = null
            responseInFlight = false
            interruptRequested = false
            staleBoundaryBlocked = false
            firstAudioSeen = false
            firstTextSeen = false
            transcript = StringBuilder()
            speechBuffer.reset()
        }
        audioPlayer.interrupt(reason)
        invalidateSocket(reason)
        resumptionHandle = null
    }

    /**
     * The best Live model still worth trying for [responseMode], or null when none is left.
     *
     * Candidates come from the API rather than from a name written here, because the right name
     * cannot be known from inside the app: model ids change, and a wrong guess costs a broken
     * build to discover — which is exactly what build 53 cost. Models that have already answered
     * this modality with nothing are removed, so each dead combination is paid for once.
     */
    private suspend fun chooseModel(apiKey: String, responseMode: LiveResponseMode): String? {
        val ordered = LiveModelDirectory.ordered(discoverModels(apiKey), responseMode)
        val chosen = ordered.firstOrNull { "$it/${responseMode.modality}" !in unanswering }
        // Said once. Every later frame takes the same decision, and a line per frame would bury
        // the rest of the bundle under the one fact the reader already has.
        if (chosen == null && exhaustedModality.add(responseMode.modality)) {
            DiagnosticHub.record(
                "LIVE_NO_MODEL_AVAILABLE",
                mapOf(
                    "modality" to responseMode.modality,
                    "candidates" to ordered.size,
                    "ruledOut" to unanswering.size,
                    "fallsBackTo" to "FRAME_BOUND_SSE",
                ),
            )
        }
        return chosen
    }

    /**
     * Live-capable model names, asked for once per process and then reused.
     *
     * Any failure here is not fatal: the list falls back to the model this app has always used,
     * which is the right one for audio and merely the last resort for text.
     */
    private suspend fun discoverModels(apiKey: String): List<String> {
        discoveredModels?.let { return it }
        val fallback = listOf(LiveModelDirectory.NATIVE_AUDIO_MODEL)
        val names: List<String> = withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url("$MODELS_ENDPOINT?key=$apiKey&pageSize=$MODEL_PAGE_SIZE")
                    .build()
                client.newBuilder().readTimeout(MODEL_DISCOVERY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .build()
                    .newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@use emptyList<String>()
                        val body = response.body?.string().orEmpty()
                        val models = JSONObject(body).optJSONArray("models")
                            ?: return@use emptyList<String>()
                        val found = mutableListOf<String>()
                        for (index in 0 until models.length()) {
                            val entry = models.optJSONObject(index) ?: continue
                            val methods = entry.optJSONArray("supportedGenerationMethods")
                            var live = false
                            for (method in 0 until (methods?.length() ?: 0)) {
                                if (methods?.optString(method) == "bidiGenerateContent") live = true
                            }
                            if (!live) continue
                            val name = LiveModelDirectory.shortName(entry.optString("name"))
                            if (name.isNotBlank()) found.add(name)
                        }
                        found.toList()
                    }
            }.getOrDefault(emptyList())
        }
        val resolved = if (names.isEmpty()) fallback else names
        discoveredModels = resolved
        DiagnosticHub.record(
            "LIVE_MODELS_DISCOVERED",
            mapOf(
                "count" to resolved.size,
                "discovered" to names.isNotEmpty(),
                "models" to resolved.joinToString(","),
            ),
        )
        return resolved
    }

    /** Records that [model] cannot answer [responseMode], and drops the socket serving it. */
    private fun ruleOut(model: String, responseMode: LiveResponseMode, reason: String) {
        if (!unanswering.add("$model/${responseMode.modality}")) return
        DiagnosticHub.record(
            "LIVE_MODEL_CANNOT_ANSWER",
            mapOf(
                "model" to model,
                "modality" to responseMode.modality,
                "reason" to reason,
                "deadlineMs" to FIRST_TOKEN_DEADLINE_MS,
            ),
        )
        invalidateSocket(reason)
    }

    private suspend fun ensureConnected(apiKey: String, responseMode: LiveResponseMode): Boolean {
        val fingerprint = fingerprint(apiKey)
        val model = chooseModel(apiKey, responseMode) ?: return false
        val ready: CompletableDeferred<Boolean>
        synchronized(socketLock) {
            // A model that took this modality and then answered nothing is not talked to again.
            // The next frame opens a socket to the next candidate instead of retrying the silence.
            if (socket != null && activeModel != model) {
                DiagnosticHub.record(
                    "LIVE_MODEL_SWITCHED",
                    mapOf("from" to activeModel, "to" to model, "modality" to responseMode.modality),
                )
                socket?.close(NORMAL_CLOSE_CODE, "live_model_changed")
                socket = null
                setupReady = null
                setupSucceeded = false
                socketResponseMode = null
                resumptionHandle = null
            }
            activeModel = model
            // The modality is part of the setup handshake. A session opened to speak cannot be
            // asked to write instead, so a mode change is a new socket, not a new instruction.
            if (socket != null && socketResponseMode != null && socketResponseMode != responseMode) {
                DiagnosticHub.record(
                    "LIVE_SOCKET_MODE_SWITCHED",
                    mapOf(
                        "from" to socketResponseMode?.name,
                        "to" to responseMode.name,
                        "modality" to responseMode.modality,
                    ),
                )
                socket?.close(NORMAL_CLOSE_CODE, "response_modality_changed")
                socket = null
                setupReady = null
                setupSucceeded = false
                socketResponseMode = null
                resumptionHandle = null
            }
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
                socketResponseMode = responseMode
                keyFingerprint = fingerprint
                transportSessionId = UUID.randomUUID().toString()
                val request = Request.Builder().url("$LIVE_ENDPOINT?key=$apiKey").build()
                socket = client.newWebSocket(request, listener(fingerprint, responseMode, deferred))
                DiagnosticHub.record(
                    "LIVE_SOCKET_CONNECTING",
                    mapOf(
                        "model" to model,
                        "transportSessionId" to transportSessionId,
                        "responseMode" to responseMode.name,
                        "modality" to responseMode.modality,
                    ),
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
                    "model" to activeModel,
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
        responseMode: LiveResponseMode,
        ready: CompletableDeferred<Boolean>,
    ): WebSocketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (!webSocket.send(setupMessage(responseMode)) && !ready.isCompleted) ready.complete(false)
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
                mapOf("model" to activeModel, "transportSessionId" to transportSessionId),
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
                firstTextSeen = false
                transcript = StringBuilder()
                // Held-back text belongs to the generation that was just cut off. Speaking it now
                // would attach the tail of an abandoned reading to the one replacing it.
                speechBuffer.reset()
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
            // A TEXT session carries the answer itself here, which for a reading is the page's own
            // characters rather than a transcript of a voice reading them out. An audio session is
            // answered by `outputTranscription` below; taking a stray text part from one as well
            // would publish the same sentence down both paths and speak it twice.
            if (activeResponseMode.carriesLiteralText) {
                val literal = buildString {
                    for (index in 0 until parts.length()) {
                        append(parts.optJSONObject(index)?.optString("text").orEmpty())
                    }
                }
                if (literal.isNotEmpty()) publishText(literal, fromLiteralModelText = true)
            }

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

        server.optJSONObject("outputTranscription")?.optString("text").orEmpty().let { delta ->
            if (delta.isNotBlank()) publishText(delta, fromLiteralModelText = false)
        }

        if (server.optBoolean("turnComplete", false)) {
            val wasBoundary: Boolean
            val turn: AnalysisTurn?
            val remainder: String
            val fullText: String
            synchronized(stateLock) {
                wasBoundary = staleBoundaryBlocked
                fullText = transcript.toString().trim()
                // Whatever is still held back is the end of the answer, and a clause that never
                // got its full stop is still the user's text. Say it rather than lose it.
                remainder = if (staleBoundaryBlocked) "".also { speechBuffer.reset() } else speechBuffer.flush()
                if (staleBoundaryBlocked) {
                    staleBoundaryBlocked = false
                    transcript = StringBuilder()
                    firstAudioSeen = false
                    firstTextSeen = false
                } else {
                    responseInFlight = false
                }
                turn = activeTurn
            }
            if (!wasBoundary && turn != null) {
                if (remainder.isNotBlank()) speak(turn, fullText, remainder)
                DiagnosticHub.record(
                    "LIVE_TURN_COMPLETE",
                    turn.fields() + mapOf(
                        "epoch" to activeEpoch,
                        "responseMode" to activeResponseMode.name,
                    ),
                )
            }
        }
    }

    /**
     * Publishes one growing answer, whichever channel it arrived on.
     *
     * Both channels stream a fragment at a time and both accumulate into the same transcript, so
     * the displayed text is always the whole answer so far. The difference is what is done with
     * the fragment: literal model text is the reading, so it is also spoken, a clause at a time;
     * a speech transcript is the record of audio the model is already playing, so speaking it
     * again would say everything twice.
     */
    private fun publishText(delta: String, fromLiteralModelText: Boolean) {
        var turn: AnalysisTurn? = null
        var fullText = ""
        var toSpeak = ""
        synchronized(stateLock) {
            val current = activeTurn
            if (staleBoundaryBlocked || current == null || gate.rejection(current) != null) return
            transcript.append(delta)
            fullText = transcript.toString().trim()
            toSpeak = if (fromLiteralModelText) speechBuffer.take(delta) else ""
            turn = current
        }
        val bound = turn ?: return
        if (fullText.isBlank()) return
        if (!firstTextSeen) {
            firstTextSeen = true
            val now = SystemClock.elapsedRealtimeNanos()
            DiagnosticHub.record(
                "LIVE_FIRST_TEXT",
                bound.fields() + mapOf(
                    "receivedAtElapsedNanos" to now,
                    "submitToFirstTextMs" to
                        bound.submittedAtNanos?.let { (now - it) / 1_000_000.0 },
                    "responseMode" to activeResponseMode.name,
                    "epoch" to activeEpoch,
                ),
            )
        }
        runtime.result(
            AnalysisResult(
                text = fullText,
                source = AnalysisSource.GEMINI,
                language = if (fullText.any { it in '\u0600'..'\u06FF' }) "mixed" else "en",
                turn = bound,
            )
        )
        if (toSpeak.isNotBlank()) speak(bound, fullText, toSpeak)
        DiagnosticHub.record(
            if (fromLiteralModelText) "LIVE_MODEL_TEXT" else "LIVE_OUTPUT_TRANSCRIPTION",
            bound.fields() + mapOf(
                "characters" to fullText.length,
                "deltaCharacters" to delta.length,
                "spokenCharacters" to toSpeak.length,
            ),
        )
    }

    /**
     * Speaks a completed piece of a reading through the app's own bilingual engine.
     *
     * The result carries the whole reading so far and [delta] is the part not yet said, which is
     * the same shape the frame-bound path uses: one identity per reading, spoken as a growing
     * prefix rather than as a series of unrelated utterances.
     */
    private fun speak(turn: AnalysisTurn, fullText: String, delta: String) {
        if (!speechEnabled || gate.rejection(turn) != null) return
        tts.speakTurn(
            AnalysisResult(
                text = fullText,
                source = AnalysisSource.GEMINI,
                language = if (fullText.any { it in '\u0600'..'\u06FF' }) "mixed" else "en",
                turn = turn,
            ),
            speechRate,
            "READ_TEXT",
            spokenText = delta,
        )
    }

    private fun setupMessage(responseMode: LiveResponseMode): String {
        val resumption = JSONObject()
        resumptionHandle?.takeIf { it.isNotBlank() }?.let { resumption.put("handle", it) }
        val generationConfig = JSONObject()
            .put("responseModalities", JSONArray().put(responseMode.modality))
        if (responseMode.speaksItself) {
            // Native audio picks its language here, not from the prompt. Without a languageCode
            // Live answers in English however the instruction is worded — which is what a field
            // session on 2026-09-25 heard: scene descriptions spoken in English to an
            // Arabic-speaking user, while the per-turn instruction said "in Arabic" the whole time.
            generationConfig.put(
                "speechConfig",
                JSONObject().put("languageCode", SPOKEN_LANGUAGE),
            )
        }
        val setup = JSONObject()
            .put("model", "models/$activeModel")
            .put("generationConfig", generationConfig)
            .put(
                "systemInstruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(
                        JSONObject().put(
                            "text",
                            if (responseMode.carriesLiteralText) {
                                READING_SYSTEM_INSTRUCTION
                            } else {
                                SYSTEM_INSTRUCTION
                            },
                        ),
                    ),
                ),
            )
            .put("contextWindowCompression", JSONObject().put("slidingWindow", JSONObject()))
            .put("sessionResumption", resumption)
        // Only meaningful for a session that produces audio; a TEXT session has nothing to
        // transcribe, and the text it returns is the answer itself.
        if (responseMode.speaksItself) setup.put("outputAudioTranscription", JSONObject())
        return JSONObject().put("setup", setup).toString()
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
            // This session writes rather than speaks, so the instruction says output, not say.
            if (settings.captureProfile == CaptureProfile.FAST_TEXT) {
                "Write out the currently visible text immediately, starting with the first clear " +
                    "words. Preserve Arabic, English and numbers exactly as written; do not " +
                    "translate, transliterate, repair, infer, or describe hidden text. If nothing " +
                    "is legible, output nothing.$tail"
            } else {
                "Write out only the literal text visible in the current image, in reading order, " +
                    "keeping its line breaks and with no preamble. Preserve Arabic, English and " +
                    "numbers exactly as written. Never infer missing characters. If a word is not " +
                    "legible, write غير واضح and continue.$tail"
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
            socketResponseMode = null
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
                socketResponseMode = null
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
        /** The default, and the one that has always served audio; text may be served elsewhere. */
        const val MODEL = LiveModelDirectory.NATIVE_AUDIO_MODEL
        const val PROMPT_VERSION = "live-frame-bound-v1"

        /** Where the Live-capable models are asked for, rather than assumed. */
        private const val MODELS_ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models"
        private const val MODEL_PAGE_SIZE = 200
        private const val MODEL_DISCOVERY_TIMEOUT_SECONDS = 6L

        /**
         * How long a turn may produce nothing before its model is judged unable to answer.
         *
         * A first token costs 155-332 ms on the measured SSE path, so three seconds is a wide
         * margin for a slow one and a short wait next to the thirty-second socket timeout that
         * used to be the only signal.
         */
        private const val FIRST_TOKEN_DEADLINE_MS = 3_000L
        private const val LIVE_ENDPOINT =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        /**
         * How long a reading may hold the lane after the target moved on. Long enough for a label
         * to finish — the longest useful turn measured in the field was about ten seconds — and
         * short enough that a turn which never completes cannot stall the session.
         */
        private const val READING_COMPLETION_GRACE_MS = 12_000L

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

        /**
         * The reading session writes instead of speaking, so nothing here is about a voice. What
         * it has to protect is the opposite of a description: the answer is the page's own words,
         * in the page's own scripts, and any rewriting of them is a wrong answer however fluent.
         */
        private const val READING_SYSTEM_INSTRUCTION =
            "You are VisionBridge, a real-time reading assistant for a blind or low-vision user. " +
                "Your entire output is the literal text visible in the current image, written out " +
                "exactly as it appears: the same script, the same digits, the same punctuation, " +
                "the same line breaks and reading order. Never translate, transliterate, " +
                "summarise, correct spelling, expand abbreviations, or write a number as words. " +
                "Arabic stays Arabic and Latin stays Latin, including where both appear in one " +
                "line. Never infer a character you cannot see; write غير واضح in place of an " +
                "illegible word and carry on. Add no preamble and no commentary. Only when the " +
                "turn's own instruction explicitly asks for a closing scene sentence may one " +
                "follow the text, written in Arabic; otherwise write nothing after it. If there " +
                "is no legible text, output nothing at all."
    }
}
