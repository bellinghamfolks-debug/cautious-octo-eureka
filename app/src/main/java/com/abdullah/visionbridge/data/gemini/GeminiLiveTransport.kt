package com.abdullah.visionbridge.data.gemini

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Base64
import com.abdullah.visionbridge.capture.CaptureRuntime
import com.abdullah.visionbridge.data.diagnostics.DiagnosticHub
import com.abdullah.visionbridge.data.diagnostics.DiagnosticTrace
import com.abdullah.visionbridge.data.speech.LivePcmAudioPlayer
import com.abdullah.visionbridge.data.speech.LiveReadingSpeaker
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The live lane: one WebSocket to one pinned model, one frame per turn, one voice per mode.
 *
 * Both modes ask for AUDIO, because that is the only modality this account's Live models answer.
 * Describing plays the model's own speech, which is what makes a description live. Reading does
 * not: the page's exact characters come back as the arguments of `report_visible_text`, and the
 * app speaks them through [LiveReadingSpeaker] — the model's voice is never played while reading,
 * because in the 2026-09-26 16:33 session it read a 23-character summary and thereby stopped a
 * 747-character page from being spoken at all.
 *
 * Every decision about which model, what counts as an answer, who speaks and when a frame may go
 * out lives in [LiveTurnPolicy], where it is tested against the session that exposed it. This class
 * carries those decisions out and records what it did.
 */
class GeminiLiveTransport(
    private val runtime: CaptureRuntime,
    private val keys: ApiKeyStore,
    private val audioPlayer: LivePcmAudioPlayer,
    private val readingSpeaker: LiveReadingSpeaker,
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

    // region socket

    @Volatile private var socket: WebSocket? = null
    @Volatile private var setupReady: CompletableDeferred<Boolean>? = null
    @Volatile private var setupSucceeded = false

    /**
     * The configuration the open socket was set up with, as [socketProfile] spells it. The model,
     * the system instruction and the declared tools are fixed at the handshake, so switching
     * between reading and describing is a new socket rather than a different instruction.
     */
    @Volatile private var socketConfiguration: String? = null

    @Volatile private var lastSetupFailureAtElapsedMs = 0L
    @Volatile private var keyFingerprint: String? = null
    @Volatile private var transportSessionId = UUID.randomUUID().toString()
    @Volatile private var resumptionHandle: String? = null

    /**
     * Which configuration [resumptionHandle] belongs to. A handle replayed into a setup for another
     * model or mode does not resume anything; in the 2026-09-26 00:09 session it came back as
     * "(AUDIO, TEXT) is not supported" and then "session history not found" on every model.
     */
    @Volatile private var resumptionOwner: String? = null
    @Volatile private var suppressResumption = false

    /** Set when a refusal was correctable, so the corrected attempt does not wait out the interval. */
    @Volatile private var retryImmediately = false

    /**
     * Sockets this app closed itself.
     *
     * The server answers our own close with a close frame of its own, and reading that as a
     * verdict is how the 16:33 session came to log every deliberate model switch as a transport
     * error and throw away a good resumption handle each time.
     */
    private val closedByApp: MutableSet<WebSocket> = ConcurrentHashMap.newKeySet()

    /** Models that refused a spoken language code, re-opened without a speechConfig. */
    private val languageUnsupported: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // endregion

    // region model

    @Volatile private var activeModel = LiveTurnPolicy.PRIMARY_MODEL
    @Volatile private var onModelSinceElapsedMs = SystemClock.elapsedRealtime()

    /** Consecutive failures to hold a session open on [activeModel]; reset by `setupComplete`. */
    private val connectFailures = AtomicInteger(0)

    /** Models whose first answer this process has already recorded. */
    private val answeringModels: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // endregion

    // region turn

    @Volatile private var activeTurn: AnalysisTurn? = null
    @Volatile private var activeEpoch = 0L
    @Volatile private var activeMode = AnalysisMode.SCENE_DESCRIPTION
    @Volatile private var responseInFlight = false

    /**
     * Whether the last target change asked for the current answer to be cut off. A reading stays
     * true about the label it was taken from after the camera drifts, so it is allowed to finish
     * unless the change was strong enough to say otherwise.
     */
    @Volatile private var interruptRequested = false

    @Volatile private var firstAudioSeen = false
    @Volatile private var firstTextSeen = false
    @Volatile private var toolCallSeen = false
    @Volatile private var toolTextForTurn = false
    @Volatile private var modelAudioSuppressed = false
    @Volatile private var speechEnabled = true
    @Volatile private var speechRate = 1f
    @Volatile private var interruptOnChange = true
    @Volatile private var describeAlongside = false

    /** Whether the last finished reading turn found any text, which decides when to look again. */
    @Volatile private var lastAnswerHadText = false
    @Volatile private var consecutiveSilentTurns = 0

    private var transcript = StringBuilder()

    // endregion

    // region frames

    private var lastReservedAtElapsedMs = 0L
    @Volatile private var lastSentAtElapsedMs = 0L
    @Volatile private var sessionStartedAtElapsedMs = 0L
    @Volatile private var firstFrameSentThisSession = false

    /** The newest reserved frame; any older frame still waiting for the socket is dropped. */
    private val latestTicket = AtomicLong(0L)

    // endregion

    private val watchdogScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Cancelled when capture stops, so a turn the user ended is never scored as silence. */
    @Volatile private var watchdogJob: Job? = null

    fun supports(settings: AppSettings): Boolean = LiveTransportRouting.carriedByLive(settings)

    /** Whether this capture session has put a frame on the socket yet; see [onVisualTargetChanged]. */
    fun hasSentFrameThisSession(): Boolean = firstFrameSentThisSession

    /**
     * Opens the socket before the first frame needs it.
     *
     * Straight to the pinned model: no catalogue request, no ranking. In the 16:33 session model
     * discovery sat on this path and the first connect began 4.4 s into the session.
     */
    suspend fun preconnect(settings: AppSettings) {
        if (sessionStartedAtElapsedMs == 0L) sessionStartedAtElapsedMs = SystemClock.elapsedRealtime()
        if (!supports(settings)) return
        val apiKey = keys.get()?.takeIf { it.isNotBlank() } ?: return
        val started = SystemClock.elapsedRealtimeNanos()
        val connected = ensureConnected(apiKey, settings.mode)
        DiagnosticHub.record(
            "LIVE_PRECONNECT_COMPLETED",
            mapOf(
                "connected" to connected,
                "durationMs" to (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0,
                "model" to activeModel,
                "mode" to settings.mode.name,
            ),
        )
    }

    /**
     * Admits a frame to the live lane and returns its ticket, or null when it should not be sent.
     *
     * The ticket is what makes the lane coalesce: frames admitted while the socket is still
     * connecting, or while an earlier frame waits its turn, queue behind one another, and only the
     * newest of them is sent when the socket is free.
     */
    fun reserveFrame(settings: AppSettings): Long? {
        if (!supports(settings)) return null
        val now = SystemClock.elapsedRealtime()
        synchronized(stateLock) {
            val currentGeneration = gate.generation()
            val sameGeneration = activeTurn?.visualGeneration == currentGeneration
            val lastSent = lastSentAtElapsedMs.takeIf { it > 0L }

            if (
                sameGeneration &&
                !LiveTurnPolicy.resendsSameView(
                    mode = settings.mode,
                    inFlight = responseInFlight,
                    lastAnswerHadText = lastAnswerHadText,
                    sinceLastSentMs = lastSent?.let { now - it } ?: Long.MAX_VALUE,
                )
            ) {
                return null
            }

            // The target changed, but the policy that judged it said not to cut the reading.
            // Sending anyway would supersede the turn in flight and the user would hear half a
            // label. The bound stops a turn that never completes from holding the lane forever.
            if (
                settings.mode == AnalysisMode.TEXT_READING &&
                responseInFlight &&
                !interruptRequested &&
                lastSent != null &&
                now - lastSent < READING_COMPLETION_GRACE_MS
            ) {
                DiagnosticHub.record(
                    "LIVE_FRAME_DEFERRED",
                    mapOf(
                        "reason" to "reading_in_flight_and_change_did_not_request_interrupt",
                        "activeTurnId" to activeTurn?.turnId,
                        "activeGeneration" to activeTurn?.visualGeneration,
                        "currentGeneration" to currentGeneration,
                        "inFlightForMs" to (now - lastSent),
                    ),
                )
                return null
            }

            if (lastReservedAtElapsedMs > 0L && now - lastReservedAtElapsedMs < LIVE_VIDEO_INTERVAL_MS) {
                return null
            }
            if (!LiveTurnPolicy.frameMaySend(now, lastSent)) return null
            lastReservedAtElapsedMs = now
            return latestTicket.incrementAndGet()
        }
    }

    /**
     * true = handled by Live (sent, or dropped as superseded or obsolete).
     * false = caller should use the frame-bound SSE fallback.
     */
    suspend fun submitFrame(
        bitmap: Bitmap,
        trace: DiagnosticTrace,
        settings: AppSettings,
        ticket: Long,
    ): Boolean = submitMutex.withLock {
        if (!supports(settings)) return@withLock false
        // Checked again after every wait: a frame that queued behind the handshake or behind the
        // previous frame is no longer worth sending once a newer one is waiting behind it.
        if (dropIfSuperseded(ticket, trace, "before_connect")) return@withLock true
        val expectedGeneration = gate.generation()
        val apiKey = keys.get()?.takeIf { it.isNotBlank() } ?: return@withLock false

        return@withLock coroutineScope {
            val connection = async(Dispatchers.IO) { ensureConnected(apiKey, settings.mode) }
            val encodedTask = async(Dispatchers.Default) { encoder.encode(bitmap, settings) }
            val connected = connection.await()
            val encoded = encodedTask.await()

            if (!connected) {
                DiagnosticHub.record("LIVE_FRAME_FALLBACK", trace.fields(mapOf("reason" to "setup_not_ready")))
                return@coroutineScope false
            }
            if (dropIfSuperseded(ticket, trace, "during_setup")) return@coroutineScope true

            // Spacing is measured from the last frame that actually went out.
            val wait = lastSentAtElapsedMs.takeIf { it > 0L }
                ?.let { it + LiveTurnPolicy.MIN_FRAME_SPACING_MS - SystemClock.elapsedRealtime() }
                ?: 0L
            if (wait > 0L) {
                delay(wait)
                if (dropIfSuperseded(ticket, trace, "during_spacing")) return@coroutineScope true
            }

            if (gate.generation() != expectedGeneration) {
                DiagnosticHub.record("LIVE_FRAME_DROPPED", trace.fields(mapOf("reason" to "obsolete_during_setup")))
                return@coroutineScope true
            }

            val model = activeModel
            val capture = AnalysisTurn(
                turnId = "LIVE-" + trace.traceId,
                traceId = trace.traceId,
                frameId = trace.frameId,
                visualGeneration = expectedGeneration,
                mode = settings.mode,
                capturedAt = trace.capturedAtEpochMs,
                capturedAtNanos = trace.capturedAtElapsedNanos,
                model = model,
                promptVersion = PROMPT_VERSION,
                sessionId = transportSessionId,
            )
            val submittedAt = SystemClock.elapsedRealtimeNanos()
            val bound = capture.copy(submittedAtNanos = submittedAt, imageHash = encoded.imageHash)

            if (!gate.activate(capture) { runtime.clearVisualResult() }) {
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
            val epoch: Long
            synchronized(stateLock) {
                superseding = responseInFlight
                interruptRequested = false
                activeTurn = bound
                activeEpoch = audioPlayer.beginTurn(
                    if (superseding) "live_turn_superseded" else "live_turn_started"
                )
                epoch = activeEpoch
                activeMode = settings.mode
                speechEnabled = settings.speechEnabled
                speechRate = settings.speechRate
                interruptOnChange = settings.interruptSpeechOnVisualChange
                describeAlongside = settings.describeAlongsideText
                // What arrives next answers this frame. The model answers a burst of frames once,
                // from the newest image, and never closes the turn it abandoned: in the 17:28
                // session no superseded turn produced an `interrupted` or a turnComplete of its
                // own, so waiting for that boundary threw away the only answer that came.
                resetTurnOutputLocked()
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

            val sentAt = SystemClock.elapsedRealtime()
            val previousSentAt = lastSentAtElapsedMs
            lastSentAtElapsedMs = sentAt
            val firstOfSession = !firstFrameSentThisSession
            firstFrameSentThisSession = true
            DiagnosticHub.record(
                "LIVE_FRAME_SENT",
                bound.fields() + mapOf(
                    "model" to model,
                    "ticket" to ticket,
                    "encodedBytes" to encoded.bytes.size,
                    "outputWidth" to encoded.width,
                    "outputHeight" to encoded.height,
                    "quality" to encoded.quality,
                    "encodeTotalMs" to encoded.totalMs,
                    "base64Ms" to base64Ms,
                    "modelAudioPlayed" to
                        (LiveTurnPolicy.playsModelAudio(settings.mode) && settings.speechEnabled),
                    "readingTool" to LiveTurnPolicy.declaresReadingTool(settings.mode),
                    "supersedingActiveResponse" to superseding,
                    "firstFrameOfSession" to firstOfSession,
                    "sinceLastSentMs" to previousSentAt.takeIf { it > 0L }?.let { sentAt - it },
                    "sinceSessionStartMs" to
                        sessionStartedAtElapsedMs.takeIf { it > 0L }?.let { sentAt - it },
                    "epoch" to epoch,
                ),
            )
            runtime.liveStatus(
                model = model,
                streaming = true,
                exactText = if (settings.mode == AnalysisMode.TEXT_READING) null else false,
            )
            watchFirstAnswer(bound, epoch, model)
            true
        }
    }

    private fun dropIfSuperseded(ticket: Long, trace: DiagnosticTrace, stage: String): Boolean {
        val latest = latestTicket.get()
        if (LiveTurnPolicy.isNewest(ticket, latest)) return false
        DiagnosticHub.record(
            "LIVE_FRAME_DROPPED",
            trace.fields(
                mapOf(
                    "reason" to "superseded_by_newer_frame",
                    "stage" to stage,
                    "ticket" to ticket,
                    "latestTicket" to latest,
                ),
            ),
        )
        return true
    }

    /** Clears what the previous turn produced. Called with [stateLock] held. */
    private fun resetTurnOutputLocked() {
        firstAudioSeen = false
        firstTextSeen = false
        toolCallSeen = false
        toolTextForTurn = false
        modelAudioSuppressed = false
        transcript = StringBuilder()
    }

    /**
     * Frees the lane when a turn produces nothing, and reopens a socket that keeps doing so.
     *
     * Never a verdict on the model. A quiet turn is a slow turn; a run of them is a quiet socket.
     * Striking the model off for it is what cost the 16:33 session gemini-3.8-live at 9.19 s, 0.6 s
     * after that model had answered through the reading tool.
     */
    private fun watchFirstAnswer(turn: AnalysisTurn, epoch: Long, model: String) {
        watchdogJob?.cancel()
        watchdogJob = watchdogScope.launch {
            delay(LiveTurnPolicy.FIRST_ANSWER_DEADLINE_MS)
            val silentTurns: Int
            synchronized(stateLock) {
                if (activeEpoch != epoch) return@launch
                val answered = LiveTurnPolicy.answered(
                    audio = firstAudioSeen,
                    transcript = firstTextSeen || transcript.isNotEmpty(),
                    toolCall = toolCallSeen,
                )
                if (answered) return@launch
                consecutiveSilentTurns += 1
                silentTurns = consecutiveSilentTurns
                responseInFlight = false
                lastAnswerHadText = false
            }
            val action = LiveTurnPolicy.afterSilentTurn(silentTurns)
            DiagnosticHub.record(
                "LIVE_TURN_SILENT",
                turn.fields() + mapOf(
                    "model" to model,
                    "deadlineMs" to LiveTurnPolicy.FIRST_ANSWER_DEADLINE_MS,
                    "epoch" to epoch,
                    "consecutiveSilentTurns" to silentTurns,
                    "action" to action.name,
                    "ruledOut" to false,
                ),
            )
            if (action == LiveTurnPolicy.AfterSilence.RECONNECT_SAME_MODEL) {
                synchronized(stateLock) { consecutiveSilentTurns = 0 }
                DiagnosticHub.record(
                    "LIVE_RECONNECT_AFTER_SILENCE",
                    mapOf("model" to model, "silentTurns" to silentTurns),
                )
                invalidateSocket("silent_turns")
            }
        }
    }

    /** Records that the turn in flight was answered, on whichever channel. */
    private fun markAnswered(channel: String) {
        synchronized(stateLock) { consecutiveSilentTurns = 0 }
        val model = activeModel
        if (answeringModels.add(model)) {
            DiagnosticHub.record("LIVE_MODEL_ANSWERED", mapOf("model" to model, "channel" to channel))
        }
    }

    /**
     * A target change, acted on only once the session has a view to change from.
     *
     * Before the first frame the tracker has no settled reference, and in the 16:33 session it
     * declared three strong changes in the first half second — before any socket existed —
     * interrupting nothing but the start-up announcement.
     */
    fun onVisualTargetChanged(interruptSpeech: Boolean) {
        if (!LiveTurnPolicy.honoursTargetChange(firstFrameSentThisSession)) {
            DiagnosticHub.record(
                "LIVE_VISUAL_TARGET_CHANGE_IGNORED_WARMUP",
                mapOf("visualGeneration" to gate.generation(), "interruptSpeech" to interruptSpeech),
            )
            return
        }
        synchronized(stateLock) {
            if (interruptSpeech) audioPlayer.interrupt("visual_target_changed")
            interruptRequested = interruptSpeech
        }
        DiagnosticHub.record(
            "LIVE_VISUAL_TARGET_CHANGED",
            mapOf("visualGeneration" to gate.generation(), "interruptSpeech" to interruptSpeech),
        )
    }

    fun stop(reason: String = "capture_stopped") {
        watchdogJob?.cancel()
        watchdogJob = null
        synchronized(stateLock) {
            lastReservedAtElapsedMs = 0L
            activeTurn = null
            responseInFlight = false
            interruptRequested = false
            lastAnswerHadText = false
            consecutiveSilentTurns = 0
            resetTurnOutputLocked()
        }
        lastSentAtElapsedMs = 0L
        sessionStartedAtElapsedMs = 0L
        firstFrameSentThisSession = false
        audioPlayer.interrupt(reason)
        readingSpeaker.reset()
        invalidateSocket(reason)
        resumptionHandle = null
        resumptionOwner = null
    }

    // region connection

    /** Which model the next connection uses; see [LiveTurnPolicy.nextModel]. */
    private fun chooseModelLocked(now: Long): String {
        val next = LiveTurnPolicy.nextModel(
            current = activeModel,
            consecutiveConnectFailures = connectFailures.get(),
            onCurrentSinceMs = onModelSinceElapsedMs,
            nowMs = now,
        )
        if (next != activeModel) {
            DiagnosticHub.record(
                "LIVE_MODEL_SWITCHED",
                mapOf(
                    "from" to activeModel,
                    "to" to next,
                    "reason" to if (connectFailures.get() >= LiveTurnPolicy.CONNECT_FAILURES_BEFORE_SWITCH) {
                        "connect_failures"
                    } else {
                        "return_to_primary"
                    },
                    "connectFailures" to connectFailures.get(),
                ),
            )
            closeSocketLocked("live_model_changed")
            resumptionHandle = null
            resumptionOwner = null
            activeModel = next
            onModelSinceElapsedMs = now
            connectFailures.set(0)
        }
        return next
    }

    private suspend fun ensureConnected(apiKey: String, mode: AnalysisMode): Boolean {
        val fingerprint = fingerprint(apiKey)
        val ready: CompletableDeferred<Boolean>
        val model: String
        synchronized(socketLock) {
            val now = SystemClock.elapsedRealtime()
            model = chooseModelLocked(now)
            val profile = socketProfile(model, mode)
            if (socket != null && socketConfiguration != null && socketConfiguration != profile) {
                DiagnosticHub.record(
                    "LIVE_SOCKET_PROFILE_SWITCHED",
                    mapOf("from" to socketConfiguration, "to" to profile),
                )
                closeSocketLocked("session_configuration_changed")
            }
            val existing = setupReady
            if (socket != null && setupSucceeded && keyFingerprint == fingerprint && existing != null) {
                return true
            }
            // A handshake that finished and failed is discarded rather than awaited again, at most
            // once per retry interval unless the server named a correction.
            if (existing != null && existing.isCompleted && existing.getCompleted() != true) {
                val earned = retryImmediately
                retryImmediately = false
                if (!earned && now - lastSetupFailureAtElapsedMs < LiveTurnPolicy.SETUP_RETRY_INTERVAL_MS) {
                    return false
                }
                closeSocketLocked("retry_live_setup")
            }
            val current = setupReady
            if (socket != null && keyFingerprint == fingerprint && current != null) {
                ready = current
            } else {
                closeSocketLocked("replace_live_session")
                val deferred = CompletableDeferred<Boolean>()
                ready = deferred
                setupReady = deferred
                setupSucceeded = false
                socketConfiguration = profile
                keyFingerprint = fingerprint
                transportSessionId = UUID.randomUUID().toString()
                val attempt = Attempt()
                val request = Request.Builder().url("$LIVE_ENDPOINT?key=$apiKey").build()
                socket = client.newWebSocket(request, listener(fingerprint, model, mode, deferred, attempt))
                DiagnosticHub.record(
                    "LIVE_SOCKET_CONNECTING",
                    mapOf(
                        "model" to model,
                        "mode" to mode.name,
                        "transportSessionId" to transportSessionId,
                        "connectFailures" to connectFailures.get(),
                    ),
                )
                currentAttempt = attempt
            }
        }
        val connected = withTimeoutOrNull(LIVE_SETUP_TIMEOUT_MS) { ready.await() } == true
        if (!connected) {
            if (!ready.isCompleted) ready.complete(false)
            synchronized(socketLock) { lastSetupFailureAtElapsedMs = SystemClock.elapsedRealtime() }
            // The server's close frame, when there was one, already judged this attempt.
            currentAttempt?.failed(1)
            DiagnosticHub.record(
                "LIVE_SETUP_FAILED",
                mapOf(
                    "model" to model,
                    "timeoutMs" to LIVE_SETUP_TIMEOUT_MS,
                    "transportSessionId" to transportSessionId,
                    "connectFailures" to connectFailures.get(),
                ),
            )
        }
        return connected
    }

    /** One connection attempt, judged at most once whichever of its failure signals comes first. */
    private inner class Attempt {
        private val judged = AtomicBoolean(false)

        fun failed(weight: Int) {
            if (judged.compareAndSet(false, true)) connectFailures.addAndGet(weight)
        }

        /** The failure was a correction the server asked for, not a strike against the model. */
        fun excused() {
            judged.set(true)
        }
    }

    @Volatile private var currentAttempt: Attempt? = null

    private fun listener(
        expectedFingerprint: String,
        model: String,
        mode: AnalysisMode,
        ready: CompletableDeferred<Boolean>,
        attempt: Attempt,
    ): WebSocketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val payload = setupMessage(model, mode)
            suppressResumption = false
            if (!webSocket.send(payload) && !ready.isCompleted) ready.complete(false)
            DiagnosticHub.record(
                "LIVE_SOCKET_OPEN",
                mapOf("httpCode" to response.code, "protocol" to response.protocol.toString(), "model" to model),
            )
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (isCurrent(webSocket, expectedFingerprint)) handleServerMessage(text, ready, attempt)
        }

        /**
         * Gemini Live answers in binary frames. Without this override OkHttp hands every server
         * message to the no-op default and the session is deaf while looking healthy.
         */
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (isCurrent(webSocket, expectedFingerprint)) {
                handleServerMessage(bytes.string(Charsets.UTF_8), ready, attempt)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!ready.isCompleted) ready.complete(false)
            val byApp = closedByApp.remove(webSocket)
            if (!byApp) attempt.failed(1)
            clearSocketIfCurrent(webSocket)
            DiagnosticHub.record(
                "LIVE_SOCKET_FAILURE",
                mapOf(
                    "httpCode" to response?.code,
                    "errorType" to t.javaClass.simpleName,
                    "model" to model,
                    "closedByApp" to byApp,
                ),
            )
        }

        /**
         * The server's verdict, read the moment it is sent rather than when the setup deadline
         * expires — the refusal is spelled out in the close frame at a few hundred milliseconds.
         */
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            val byApp = webSocket in closedByApp
            DiagnosticHub.record(
                "LIVE_SOCKET_CLOSING",
                mapOf("code" to code, "reason" to reason, "model" to model, "closedByApp" to byApp),
            )
            if (!byApp) interpretClose(model, code, reason, attempt)
            if (!ready.isCompleted) ready.complete(false)
            webSocket.close(NORMAL_CLOSE_CODE, null)
            clearSocketIfCurrent(webSocket)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!ready.isCompleted) ready.complete(false)
            closedByApp.remove(webSocket)
            clearSocketIfCurrent(webSocket)
            DiagnosticHub.record("LIVE_SOCKET_CLOSED", mapOf("code" to code, "reason" to reason, "model" to model))
        }
    }

    /** Messages from a socket that has been replaced belong to a session nobody is listening to. */
    private fun isCurrent(webSocket: WebSocket, expectedFingerprint: String): Boolean =
        socket === webSocket && keyFingerprint == expectedFingerprint

    /**
     * Acts on what the server said when it closed a session this app did not close.
     *
     * A correction — a language the model will not speak, a resumption handle it no longer holds —
     * is applied and retried at once without counting against the model. A refusal counts enough
     * to move to the other pinned model. Anything else is one failure among the three allowed.
     */
    private fun interpretClose(model: String, code: Int, reason: String, attempt: Attempt) {
        val verdict = LiveCloseVerdict.of(code, reason)
        when {
            verdict == LiveCloseVerdict.LANGUAGE_UNSUPPORTED && languageUnsupported.add(model) -> {
                attempt.excused()
                dropResumption()
                retryImmediately = true
                DiagnosticHub.record(
                    "LIVE_SETUP_CORRECTION",
                    mapOf("model" to model, "verdict" to verdict.name, "code" to code),
                )
            }
            verdict == LiveCloseVerdict.STALE_RESUMPTION -> {
                attempt.excused()
                dropResumption()
                retryImmediately = true
                DiagnosticHub.record(
                    "LIVE_SETUP_CORRECTION",
                    mapOf("model" to model, "verdict" to verdict.name, "code" to code),
                )
            }
            verdict.provesIncapable -> {
                attempt.failed(LiveTurnPolicy.CONNECT_FAILURES_BEFORE_SWITCH)
                dropResumption()
                retryImmediately = true
                DiagnosticHub.record(
                    "LIVE_MODEL_REFUSED",
                    mapOf("model" to model, "verdict" to verdict.name, "code" to code, "reason" to reason),
                )
            }
            else -> {
                attempt.failed(1)
                dropResumption()
                DiagnosticHub.record(
                    "LIVE_TRANSPORT_ERROR",
                    mapOf(
                        "model" to model,
                        "verdict" to verdict.name,
                        "code" to code,
                        "connectFailures" to connectFailures.get(),
                    ),
                )
            }
        }
    }

    private fun dropResumption() {
        resumptionHandle = null
        resumptionOwner = null
        suppressResumption = true
    }

    // endregion

    // region server messages

    private fun handleServerMessage(raw: String, ready: CompletableDeferred<Boolean>, attempt: Attempt) {
        val root = runCatching { JSONObject(raw) }.getOrElse {
            DiagnosticHub.record("LIVE_JSON_PARSE_FAILURE", mapOf("characters" to raw.length))
            return
        }

        if (root.has("setupComplete")) {
            setupSucceeded = true
            connectFailures.set(0)
            attempt.excused()
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
            // A handle issued mid-generation is documented to lose data when resumed from, and the
            // server marks those as not resumable. Only a handle it vouches for is kept.
            if (resumable && handle != null) {
                resumptionHandle = handle
                resumptionOwner = socketConfiguration
            }
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
                    "willReconnectOnNextFrame" to true,
                ),
            )
            // The warning is the moment to move; by the close frame the handover is already late.
            synchronized(socketLock) {
                setupSucceeded = false
                setupReady = null
            }
            retryImmediately = true
        }

        root.optJSONObject("toolCall")?.let { handleToolCall(it) }

        root.optJSONObject("toolCallCancellation")?.let { cancellation ->
            DiagnosticHub.record(
                "LIVE_TOOL_CALL_CANCELLED",
                mapOf("ids" to cancellation.optJSONArray("ids")?.length()),
            )
        }

        val server = root.optJSONObject("serverContent") ?: return

        if (server.optBoolean("interrupted", false)) {
            synchronized(stateLock) { resetTurnOutputLocked() }
            DiagnosticHub.record(
                "LIVE_TURN_INTERRUPTED",
                activeTurn?.fields().orEmpty() + mapOf("epoch" to activeEpoch),
            )
            // Anything carried in the same message belongs to the generation that was cut off.
            return
        }

        server.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { handleAudio(it) }

        server.optJSONObject("outputTranscription")?.optString("text").orEmpty().let { delta ->
            if (delta.isNotBlank()) publishTranscript(delta)
        }

        if (server.optBoolean("turnComplete", false)) completeTurn()
    }

    private fun handleAudio(parts: JSONArray) {
        for (index in 0 until parts.length()) {
            val inline = parts.optJSONObject(index)?.optJSONObject("inlineData") ?: continue
            val mime = inline.optString("mimeType")
            val data = inline.optString("data")
            if (!mime.startsWith("audio/pcm") || data.isBlank()) continue

            val turn: AnalysisTurn
            val epoch: Long
            val first: Boolean
            synchronized(stateLock) {
                val current = activeTurn ?: return
                if (gate.rejection(current) != null) return
                turn = current
                epoch = activeEpoch
                first = !firstAudioSeen
                firstAudioSeen = true
            }
            if (first) {
                markAnswered("audio")
                val now = SystemClock.elapsedRealtimeNanos()
                val submitToFirstAudioMs = turn.submittedAtNanos?.let { (now - it) / 1_000_000.0 }
                if (LiveTurnPolicy.playsModelAudio(activeMode)) {
                    submitToFirstAudioMs?.let { runtime.liveStatus(firstAnswerMs = it) }
                }
                DiagnosticHub.record(
                    "LIVE_FIRST_AUDIO_PACKET",
                    turn.fields() + mapOf(
                        "receivedAtElapsedNanos" to now,
                        "submitToFirstAudioMs" to submitToFirstAudioMs,
                        "played" to LiveTurnPolicy.playsModelAudio(activeMode),
                        "epoch" to epoch,
                    ),
                )
            }
            if (!LiveTurnPolicy.playsModelAudio(activeMode)) {
                if (!modelAudioSuppressed) {
                    modelAudioSuppressed = true
                    DiagnosticHub.record(
                        "LIVE_MODEL_AUDIO_SUPPRESSED",
                        turn.fields() + mapOf(
                            "reason" to "reading_is_spoken_by_the_app",
                            "epoch" to epoch,
                        ),
                    )
                }
                continue
            }
            if (speechEnabled) {
                runCatching { Base64.decode(data, Base64.DEFAULT) }.getOrNull()?.let { bytes ->
                    audioPlayer.enqueue(epoch, bytes, sampleRateFromMime(mime))
                }
            }
        }
    }

    /**
     * Takes the page's own characters out of a function call, and answers the call.
     *
     * Any call counts as an answer, including one with no lines: that is the model saying it
     * looked and found nothing legible, which is an answer the watchdog must not mistake for
     * silence.
     */
    private fun handleToolCall(toolCall: JSONObject) {
        val calls = toolCall.optJSONArray("functionCalls") ?: return
        for (index in 0 until calls.length()) {
            val call = calls.optJSONObject(index) ?: continue
            val name = call.optString("name")
            val id = call.optString("id").takeIf { it.isNotBlank() }
            // Every call is answered, recognised or not; an unanswered call is a risk to the turn.
            socket?.send(LiveReadingTool.acknowledgement(id, name))
            if (name != LiveReadingTool.NAME) {
                DiagnosticHub.record("LIVE_TOOL_CALL_UNKNOWN", mapOf("name" to name))
                continue
            }
            val args = call.optJSONObject("args")
            val text = LiveReadingTool.textFrom(args)
            val scene = LiveReadingTool.sceneFrom(args)
            val turn: AnalysisTurn?
            synchronized(stateLock) {
                val current = activeTurn
                turn = current?.takeIf(::stillAnswerable)
                if (turn != null) toolCallSeen = true
            }
            DiagnosticHub.record(
                "LIVE_TOOL_TEXT_REPORTED",
                (activeTurn?.fields() ?: emptyMap()) + mapOf(
                    "model" to activeModel,
                    "characters" to text.length,
                    "lines" to (args?.optJSONArray(LiveReadingTool.LINES)?.length() ?: 0),
                    "unreadable" to (args?.optBoolean(LiveReadingTool.UNREADABLE) ?: false),
                    "sceneCharacters" to scene.length,
                    "hasId" to (id != null),
                    "stale" to (turn == null),
                ),
            )
            if (turn == null) continue
            markAnswered("tool")
            if (text.isNotBlank()) publishReportedText(turn, text, scene)
        }
    }

    /**
     * Publishes and speaks a reading that came back as tool arguments — the authoritative text of
     * the frame, which replaces anything the transcript had so far and is the only thing spoken.
     */
    private fun publishReportedText(turn: AnalysisTurn, text: String, scene: String) {
        val first: Boolean
        synchronized(stateLock) {
            if (activeTurn !== turn) return
            transcript = StringBuilder(text)
            toolTextForTurn = true
            first = !firstTextSeen
            firstTextSeen = true
        }
        runtime.liveStatus(exactText = true)
        if (first) recordFirstText(turn, "tool")
        val sceneTail = if (describeAlongside) scene else ""
        runtime.result(
            AnalysisResult(
                text = text,
                source = AnalysisSource.GEMINI,
                language = languageOf(text),
                sceneTail = sceneTail,
                turn = turn,
            )
        )
        if (speechEnabled) {
            readingSpeaker.speak(
                text = text,
                rate = speechRate,
                interruptPrevious = interruptOnChange,
                scene = sceneTail,
                fields = turn.fields() + mapOf("channel" to "tool", "model" to activeModel),
            )
        } else {
            DiagnosticHub.record(
                "LIVE_READING_SKIPPED",
                turn.fields() + mapOf("reason" to "speech_disabled", "characters" to text.length),
            )
        }
    }

    /**
     * Publishes the transcript of the model's speech as it grows.
     *
     * In a description this is the record of audio already playing. In a reading it is shown only
     * until the tool's exact text arrives, and spoken only if the tool never delivers — see
     * [completeTurn].
     */
    private fun publishTranscript(delta: String) {
        val turn: AnalysisTurn
        val fullText: String
        val first: Boolean
        synchronized(stateLock) {
            val current = activeTurn ?: return
            if (!stillAnswerable(current)) return
            if (toolTextForTurn) return
            transcript.append(delta)
            fullText = transcript.toString().trim()
            turn = current
            first = !firstTextSeen && fullText.isNotBlank()
            if (first) firstTextSeen = true
        }
        if (fullText.isBlank()) return
        if (first) {
            markAnswered("transcript")
            recordFirstText(turn, "transcript")
        }
        runtime.result(
            AnalysisResult(
                text = fullText,
                source = AnalysisSource.GEMINI,
                language = languageOf(fullText),
                turn = turn,
            )
        )
        DiagnosticHub.record(
            "LIVE_OUTPUT_TRANSCRIPTION",
            turn.fields() + mapOf("characters" to fullText.length, "deltaCharacters" to delta.length),
        )
    }

    private fun completeTurn() {
        val turn: AnalysisTurn?
        val fullText: String
        val toolText: Boolean
        val mode: AnalysisMode
        synchronized(stateLock) {
            fullText = transcript.toString().trim()
            toolText = toolTextForTurn
            mode = activeMode
            turn = activeTurn
            responseInFlight = false
            lastAnswerHadText = toolText || fullText.isNotBlank()
        }
        if (turn == null) return
        val fallback = LiveTurnPolicy.speaksTranscriptAtTurnEnd(mode, toolText, fullText.isNotBlank())
        if (fallback && speechEnabled && stillAnswerable(turn)) {
            DiagnosticHub.record(
                "LIVE_READING_FELL_BACK_TO_TRANSCRIPT",
                turn.fields() + mapOf("characters" to fullText.length, "model" to activeModel),
            )
            readingSpeaker.speak(
                text = fullText,
                rate = speechRate,
                interruptPrevious = interruptOnChange,
                fields = turn.fields() + mapOf("channel" to "transcript", "model" to activeModel),
            )
        }
        DiagnosticHub.record(
            "LIVE_TURN_COMPLETE",
            turn.fields() + mapOf(
                "epoch" to activeEpoch,
                "mode" to mode.name,
                "toolText" to toolText,
                "transcriptCharacters" to fullText.length,
            ),
        )
    }

    /**
     * Whether output for [turn] may still be used. A description expires the moment the view moves;
     * a reading does not — see [LiveTurnPolicy.keepsAnswerAfterViewMoved].
     */
    private fun stillAnswerable(turn: AnalysisTurn): Boolean =
        LiveTurnPolicy.keepsAnswerAfterViewMoved(turn.mode) || gate.rejection(turn) == null

    private fun recordFirstText(turn: AnalysisTurn, channel: String) {
        val now = SystemClock.elapsedRealtimeNanos()
        val elapsed = turn.submittedAtNanos?.let { (now - it) / 1_000_000.0 }
        if (!LiveTurnPolicy.playsModelAudio(activeMode)) elapsed?.let { runtime.liveStatus(firstAnswerMs = it) }
        DiagnosticHub.record(
            "LIVE_FIRST_TEXT",
            turn.fields() + mapOf(
                "receivedAtElapsedNanos" to now,
                "submitToFirstTextMs" to elapsed,
                "channel" to channel,
                "epoch" to activeEpoch,
            ),
        )
    }

    // endregion

    // region messages to the server

    private fun setupMessage(model: String, mode: AnalysisMode): String {
        val profile = socketProfile(model, mode)
        val resumption = JSONObject()
        // Only ever offered back to the exact configuration that issued it.
        if (resumptionOwner == profile && !suppressResumption) {
            resumptionHandle?.takeIf { it.isNotBlank() }?.let { resumption.put("handle", it) }
        }
        val generationConfig = JSONObject()
            .put("responseModalities", JSONArray().put(RESPONSE_MODALITY))
            // HIGH is the level Google names for reading dense text and small detail out of video
            // frames; without it the frame is tokenised at an undocumented default.
            .put("mediaResolution", MEDIA_RESOLUTION)
            .put("temperature", 0)
            .put("maxOutputTokens", MAX_OUTPUT_TOKENS)
        if (model !in languageUnsupported) {
            generationConfig.put("speechConfig", JSONObject().put("languageCode", SPOKEN_LANGUAGE))
        }
        val reading = LiveTurnPolicy.declaresReadingTool(mode)
        val setup = JSONObject()
            .put("model", "models/$model")
            .put("generationConfig", generationConfig)
            .put(
                "systemInstruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(
                        JSONObject().put(
                            "text",
                            if (reading) READING_SYSTEM_INSTRUCTION else SYSTEM_INSTRUCTION,
                        ),
                    ),
                ),
            )
            // A sibling of generationConfig. An audio-and-video session's context is capped at
            // about two minutes without it; the window exempts the system instruction.
            .put(
                "contextWindowCompression",
                JSONObject()
                    .put("triggerTokens", COMPRESSION_TRIGGER_TOKENS)
                    .put("slidingWindow", JSONObject().put("targetTokens", COMPRESSION_TARGET_TOKENS)),
            )
            // Must be present or the server never sends a resumption update at all.
            .put("sessionResumption", resumption)
            // The description's text, and the reading's fallback when the tool never delivers.
            .put("outputAudioTranscription", JSONObject())
        if (reading) setup.put("tools", LiveReadingTool.declaration())
        return JSONObject().put("setup", setup).toString()
    }

    /** Everything fixed at the handshake: a change to any of it is a new socket. */
    private fun socketProfile(model: String, mode: AnalysisMode): String = "$model/${mode.name}"

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
                                .put("parts", JSONArray().put(JSONObject().put("text", instruction))),
                        ),
                    )
                    .put("turnComplete", true),
            )
            .toString()

    private fun instructionFor(settings: AppSettings): String = when (settings.mode) {
        AnalysisMode.TEXT_READING -> {
            // Asked for as a real description, because "one very short sentence" produced exactly
            // that: every scene in the 17:28 session was 11 to 30 characters — "غرفة مظلمة" — and
            // the user's verdict was that the description was far too brief.
            val scene = if (settings.describeAlongsideText) {
                " Also set scene to a useful Arabic description in two or three sentences: what " +
                    "the object or document is, its colours, shape and layout, where the text " +
                    "sits on it, and anything around it that matters. Do not repeat the text itself."
            } else {
                ""
            }
            if (settings.captureProfile == CaptureProfile.FAST_TEXT) {
                "Call report_visible_text now with the text visible in this image, starting with " +
                    "the first clear line. Preserve Arabic, English and numbers exactly as " +
                    "written; do not translate, transliterate, repair or infer. If nothing is " +
                    "legible, call it with an empty lines array.$scene"
            } else {
                "Call report_visible_text with all of the literal text visible in this image, in " +
                    "reading order, one entry per line. Preserve Arabic, English and numbers " +
                    "exactly as written. Never infer missing characters. If nothing is legible, " +
                    "call it with an empty lines array.$scene"
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

    // endregion

    private fun languageOf(text: String): String =
        if (text.any { it in '؀'..'ۿ' }) "mixed" else "en"

    private fun sampleRateFromMime(mimeType: String): Int =
        SAMPLE_RATE_REGEX.find(mimeType)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?.takeIf { it in 8_000..96_000 }
            ?: LivePcmAudioPlayer.DEFAULT_SAMPLE_RATE_HZ

    /** Closes the open socket as the app's own decision. Called with [socketLock] held. */
    private fun closeSocketLocked(reason: String) {
        socket?.let { open ->
            closedByApp.add(open)
            open.close(NORMAL_CLOSE_CODE, reason)
        }
        socket = null
        setupReady = null
        setupSucceeded = false
        socketConfiguration = null
    }

    private fun invalidateSocket(reason: String) {
        synchronized(socketLock) {
            closeSocketLocked(reason)
            keyFingerprint = null
        }
        synchronized(stateLock) { responseInFlight = false }
        DiagnosticHub.record("LIVE_SOCKET_INVALIDATED", mapOf("reason" to reason))
    }

    private fun clearSocketIfCurrent(webSocket: WebSocket) {
        val wasCurrent: Boolean
        synchronized(socketLock) {
            wasCurrent = socket === webSocket
            if (wasCurrent) {
                socket = null
                setupReady = null
                setupSucceeded = false
                socketConfiguration = null
                keyFingerprint = null
            }
        }
        if (wasCurrent) {
            synchronized(stateLock) { responseInFlight = false }
        }
    }

    private fun fingerprint(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .take(6)
            .joinToString("") { "%02x".format(it) }

    companion object {
        const val PROMPT_VERSION = "live-frame-bound-v2"

        /** The only modality this account's Live models answer; see [LiveTurnPolicy.playsModelAudio]. */
        private const val RESPONSE_MODALITY = "AUDIO"
        private const val MEDIA_RESOLUTION = "MEDIA_RESOLUTION_HIGH"

        /**
         * Generous on purpose: the reported text is generated as ordinary output tokens, and a tight
         * ceiling truncates the page — build 48 lost every scene response to exactly that.
         */
        private const val MAX_OUTPUT_TOKENS = 8192
        private const val COMPRESSION_TRIGGER_TOKENS = 32_000
        private const val COMPRESSION_TARGET_TOKENS = 12_000

        private const val LIVE_ENDPOINT =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

        /**
         * How long a reading may hold the lane after the target moved on: long enough for a label
         * to finish, short enough that a turn which never completes cannot stall the session.
         */
        private const val READING_COMPLETION_GRACE_MS = 12_000L

        /** A first handshake can be slow; four seconds abandoned a model mid-handshake on 2026-09-25. */
        private const val LIVE_SETUP_TIMEOUT_MS = 8_000L
        private const val LIVE_VIDEO_INTERVAL_MS = 1_000L
        private const val NORMAL_CLOSE_CODE = 1000
        private val SAMPLE_RATE_REGEX = Regex("rate=(\\d+)", RegexOption.IGNORE_CASE)

        /** BCP-47 tag for the spoken answer. Both pinned models accept it. */
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
         * Reading: the tool carries the page and the app speaks it, so nothing here asks the model
         * to say anything. Build 64 asked for "one very short sentence" first, and that sentence
         * is what kept the 747-character page from ever being read.
         */
        private const val READING_SYSTEM_INSTRUCTION =
            "You are VisionBridge, a reading assistant for a blind or low-vision user who speaks " +
                "Arabic. Your voice is not played to the user: the app reads aloud exactly the text " +
                "you pass to the function report_visible_text, and nothing else. " +
                "For every image, call report_visible_text exactly once, before saying anything. " +
                "In lines, reproduce all of the visible text — the whole page, not a sample — " +
                "character for character in its original script: never translate, never " +
                "transliterate Latin words into Arabic letters or Arabic words into Latin letters, " +
                "keep every digit in the form printed whether Arabic-Indic or Latin, keep the " +
                "punctuation, and give one array entry per visual line from top to bottom. Where a " +
                "span is illegible write [؟] in its place rather than guessing, and set unreadable " +
                "to true. If no text is legible, call the function with an empty lines array. " +
                // The glasses draw their own indicators into the mirrored image — a zoom level
                // "1x" and a "0.6" at the edge — and in the 17:28 session they were read out as a
                // page of their own and tacked onto the end of a fridge label.
                "Ignore the viewing device's own on-screen indicators drawn over the camera image, " +
                "such as a zoom level like 1x or a lone small number at the edge of the frame. " +
                "Do not read the text aloud and do not summarise it; when the turn asks for a " +
                "description, it goes in the function's scene field, never in speech. After the " +
                "call, say nothing."
    }
}
