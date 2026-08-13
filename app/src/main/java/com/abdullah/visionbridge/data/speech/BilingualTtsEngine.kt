package com.abdullah.visionbridge.data.speech

import android.content.Context
import android.media.AudioAttributes
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.abdullah.visionbridge.data.diagnostics.DiagnosticHub
import com.abdullah.visionbridge.data.diagnostics.DiagnosticTrace
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Ordered bilingual speech built around whole readings rather than independent blocks.
 *
 * A reading is one document the user is being read: it is opened once, fed blocks in visual order,
 * and drained to completion without losing anything in the middle. The previous implementation used
 * a bounded eight-slot queue that discarded the *oldest* pending block on overflow, so a dense page
 * lost the very text that was next in line to be spoken. Blocks are now queued without a ceiling and
 * are only ever discarded together, when a genuinely different reading supersedes this one.
 *
 * Content de-duplication does not belong here either. It lives one level up in [ReadingLedger],
 * which decides whether a whole page deserves to be read, so a retry of a half-heard page can no
 * longer have most of its blocks silenced individually.
 */
class BilingualTtsEngine(context: Context) {
    private data class SpeechRequest(
        val text: String,
        val rate: Float,
        val generation: Long,
        val readingId: Long,
        val blockIndex: Int,
        val flushFirst: Boolean,
        val trace: DiagnosticTrace?,
        val enqueuedAtElapsedNanos: Long,
        /**
         * Which live description this block belongs to, or [NOT_LIVE] for speech that keeps its
         * value however long it waits. See [supersedeLiveSpeech].
         */
        val liveGeneration: Long = NOT_LIVE,
    )

    private class UtteranceState(
        val completion: CompletableDeferred<SpeechOutcome>,
        val trace: DiagnosticTrace?,
        val text: String,
        val language: String,
        val requestGeneration: Long,
        val enqueuedAtElapsedNanos: Long,
        val submittedAtElapsedNanos: Long,
    ) {
        @Volatile
        var startedAtElapsedNanos: Long = 0L
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val utterances = ConcurrentHashMap<String, UtteranceState>()
    private val generation = AtomicLong(0L)
    private val engineEpoch = AtomicLong(0L)
    private val readingSequence = AtomicLong(0L)
    private val activeReadingId = AtomicLong(NO_READING)
    private val outstandingBlocks = AtomicInteger(0)
    private val noticeDeduplicator = SpeechDeduplicator()
    private val engineLock = Any()

    /**
     * Told what became of every block of every reading, so the layer that decides what still needs
     * saying learns it from the engine rather than from the act of queueing.
     */
    @Volatile
    private var blockDeliveryListener: ((Long, Int, SpeechOutcome) -> Unit)? = null

    @Volatile
    private var readingOpen = false

    @Volatile
    private var ready = CompletableDeferred<Boolean>()

    @Volatile
    private var tts: TextToSpeech? = null

    @Volatile
    private var consecutiveTimeouts = 0

    /**
     * Which live description is current. Blocks stamped with an older one are dropped rather than
     * spoken; see [supersedeLiveSpeech] for why that is not the same as throwing away speech the
     * user was promised.
     */
    private val liveGeneration = AtomicLong(1L)

    /**
     * Live blocks queued but not yet resolved. A reading has [outstandingBlocks] for this; a scene
     * had nothing, which is why nothing upstream could tell that a description was still being
     * spoken and stop buying the next one.
     */
    private val liveBlocksOutstanding = AtomicInteger(0)

    /**
     * Unbounded on purpose. Backpressure is applied one level up by refusing to analyze a page that
     * is still being read, not by throwing away speech the user has already been promised.
     *
     * That reasoning holds for a page and fails for a live scene, which is why live blocks carry a
     * generation. A page waits as well at minute two as at second two; a room does not.
     */
    private val requests = Channel<SpeechRequest>(Channel.UNLIMITED)

    init {
        initializeEngine("initialization")
        scope.launch {
            for (request in requests) {
                val outcome = if (request.isStaleLiveDescription()) {
                    DiagnosticHub.record(
                        "TTS_REQUEST_DROPPED",
                        request.trace.fieldsOrEmpty(
                            mapOf(
                                "reason" to "superseded_by_newer_scene",
                                "text" to request.text,
                                "requestLiveGeneration" to request.liveGeneration,
                                "currentLiveGeneration" to liveGeneration.get(),
                                "queuedForMs" to
                                    (SystemClock.elapsedRealtimeNanos() - request.enqueuedAtElapsedNanos) /
                                    1_000_000.0,
                            ),
                        ),
                    )
                    SpeechOutcome.SUPERSEDED_BEFORE_START
                } else if (request.generation == generation.get()) {
                    speakRequest(request)
                } else {
                    DiagnosticHub.record(
                        "TTS_REQUEST_DROPPED",
                        request.trace.fieldsOrEmpty(
                            mapOf(
                                "reason" to "superseded_reading_before_worker",
                                "text" to request.text,
                                "requestGeneration" to request.generation,
                                "currentGeneration" to generation.get(),
                                "readingId" to request.readingId,
                            ),
                        ),
                    )
                    SpeechOutcome.SUPERSEDED_BEFORE_START
                }
                if (request.readingId != NO_READING) {
                    reportBlock(request, outcome)
                    releaseBlock()
                }
                if (request.liveGeneration != NOT_LIVE) {
                    liveBlocksOutstanding.updateAndGet { if (it > 0) it - 1 else 0 }
                }
            }
        }
    }

    /** Installs the listener that learns what the user actually heard. */
    fun onBlockDelivered(listener: (readingId: Long, blockIndex: Int, outcome: SpeechOutcome) -> Unit) {
        blockDeliveryListener = listener
    }

    private fun reportBlock(request: SpeechRequest, outcome: SpeechOutcome) =
        reportBlock(request.readingId, request.blockIndex, request.text, request.trace, outcome)

    private fun reportBlock(
        readingId: Long,
        blockIndex: Int,
        text: String,
        trace: DiagnosticTrace?,
        outcome: SpeechOutcome,
    ) {
        if (readingId == NO_READING) return
        DiagnosticHub.record(
            "TTS_BLOCK_OUTCOME",
            trace.fieldsOrEmpty(
                mapOf(
                    "readingId" to readingId,
                    "blockIndex" to blockIndex,
                    "outcome" to outcome.name,
                    "text" to text,
                ),
            ),
        )
        runCatching { blockDeliveryListener?.invoke(readingId, blockIndex, outcome) }
            .onFailure { DiagnosticHub.failure("TTS_BLOCK_DELIVERY_LISTENER", it) }
    }

    // region reading lifecycle

    /**
     * Opens a reading and returns its id. Blocks queued against an older reading stop being spoken
     * as soon as a new one opens, which is the only place speech is deliberately discarded.
     */
    suspend fun beginReading(interruptPrevious: Boolean): Long = withContext(Dispatchers.Main.immediate) {
        val readingId = readingSequence.incrementAndGet()
        if (interruptPrevious) {
            // Only an interrupting reading clears the counter. A continuation queues behind speech
            // that is still playing, and that speech is still owed to the user.
            interruptInternal("new_reading_started")
            outstandingBlocks.set(0)
        }
        activeReadingId.set(readingId)
        readingOpen = true
        DiagnosticHub.record(
            "TTS_READING_STARTED",
            mapOf(
                "readingId" to readingId,
                "interruptPrevious" to interruptPrevious,
                "generation" to generation.get(),
            ),
        )
        readingId
    }

    /**
     * Queues block [blockIndex] of [readingId] in visual order. Nothing is dropped for capacity.
     *
     * Every call reports an outcome exactly once, including the calls that never reach the engine,
     * so the reading's accounting always closes and a page can never be left in limbo — recorded
     * neither as heard nor as owed.
     */
    suspend fun speakReadingBlock(readingId: Long, blockIndex: Int, text: String, rate: Float) {
        val trace = currentCoroutineContext()[DiagnosticTrace]
        if (!DocumentSpeechPolicy.isSpeakable(text)) {
            // Nothing to hear, so nothing is owed: treat it as delivered rather than let it block
            // the delivered prefix of everything after it.
            reportBlock(readingId, blockIndex, text, trace, SpeechOutcome.COMPLETED)
            return
        }
        if (readingId != activeReadingId.get()) {
            DiagnosticHub.record(
                "TTS_REQUEST_DROPPED",
                trace.fieldsOrEmpty(
                    mapOf(
                        "reason" to "superseded_reading",
                        "text" to text,
                        "readingId" to readingId,
                        "activeReadingId" to activeReadingId.get(),
                    ),
                ),
            )
            reportBlock(readingId, blockIndex, text, trace, SpeechOutcome.SUPERSEDED_BEFORE_START)
            return
        }
        outstandingBlocks.incrementAndGet()
        enqueue(
            text = text.trim(),
            rate = rate,
            interruptPrevious = false,
            readingId = readingId,
            blockIndex = blockIndex,
            trace = trace,
        )
    }

    /** Marks the end of the stream for [readingId]; queued blocks still drain to completion. */
    fun finishReading(readingId: Long) {
        if (readingId != activeReadingId.get()) return
        readingOpen = false
        DiagnosticHub.record(
            "TTS_READING_FINISHED",
            mapOf("readingId" to readingId, "outstandingBlocks" to outstandingBlocks.get()),
        )
    }

    /** True while the user is still owed audio from the current reading. */
    fun isReadingInProgress(): Boolean =
        activeReadingId.get() != NO_READING && (readingOpen || outstandingBlocks.get() > 0)

    /** True while blocks of the current live description are still queued or being spoken. */
    fun isLiveDescriptionInProgress(): Boolean = liveBlocksOutstanding.get() > 0

    /** Retires one queued block, never dropping the counter below zero after an interrupt. */
    private fun releaseBlock() {
        outstandingBlocks.updateAndGet { current -> if (current > 0) current - 1 else 0 }
    }

    // endregion

    /**
     * Retires every live description block that has not started yet.
     *
     * A scene description is a statement about where the user is standing *now*, and it is the one
     * kind of speech this app produces that expires. A field session on 2026-08-13 shows what
     * happens without this: the model produced a full description every 4.0 seconds, speaking one
     * took roughly 20, and because "let speech finish" was on, each new description queued behind
     * the last. The backlog grew all session. The user was hearing rooms they had left a median of
     * **96 seconds** earlier, and up to 159 — walking through a kitchen while being told about a
     * fridge two minutes behind them.
     *
     * Dropping those blocks is not discarding speech the user was promised; it is declining to tell
     * a blind person that a wall is in front of them when it is not. What is already being spoken
     * is never cut — the sentence in the air finishes — so this remains compatible with the setting
     * that asks speech not to be interrupted. It only means the queue behind that sentence holds
     * the newest description rather than every description since the session began.
     *
     * The prompt orders a description by importance, hazard first, so the part a supersession drops
     * is the part that mattered least.
     */
    fun supersedeLiveSpeech(reason: String) {
        val retired = liveGeneration.incrementAndGet()
        DiagnosticHub.record(
            "LIVE_SPEECH_SUPERSEDED",
            mapOf("reason" to reason, "newLiveGeneration" to retired),
        )
    }

    private fun SpeechRequest.isStaleLiveDescription(): Boolean =
        liveGeneration != NOT_LIVE && liveGeneration < this@BilingualTtsEngine.liveGeneration.get()

    /**
     * One-shot content speech that is not part of a streamed reading, such as local OCR output.
     *
     * [live] marks a block as belonging to the current scene description, which expires when a
     * newer one arrives. Everything else waits its turn however long that takes.
     */
    suspend fun speak(
        text: String,
        urgent: Boolean = false,
        rate: Float = 1.0f,
        interruptPrevious: Boolean = false,
        live: Boolean = false,
    ) {
        if (!DocumentSpeechPolicy.isSpeakable(text)) return
        val trace = currentCoroutineContext()[DiagnosticTrace]
        val novelText = noticeDeduplicator.filter(text, urgent)
        if (novelText == null) {
            DiagnosticHub.record(
                "TTS_TEXT_DEDUPLICATED",
                trace.fieldsOrEmpty(mapOf("text" to text, "urgent" to urgent)),
            )
            return
        }
        enqueue(
            text = novelText,
            rate = rate,
            interruptPrevious = interruptPrevious,
            readingId = NO_READING,
            trace = trace,
            liveGeneration = if (live) liveGeneration.get() else NOT_LIVE,
        )
    }

    /** Status speech intentionally bypasses content deduplication. */
    suspend fun speakFeedback(
        text: String,
        rate: Float = 1.0f,
        interruptPrevious: Boolean = true,
    ) {
        if (text.isBlank()) return
        enqueue(
            text = text.trim(),
            rate = rate,
            interruptPrevious = interruptPrevious,
            readingId = NO_READING,
            trace = currentCoroutineContext()[DiagnosticTrace],
        )
    }

    private fun initializeEngine(reason: String) {
        synchronized(engineLock) {
            val epoch = engineEpoch.incrementAndGet()
            val initialization = CompletableDeferred<Boolean>()
            ready = initialization
            val started = SystemClock.elapsedRealtimeNanos()

            tts?.let { previous ->
                runCatching { previous.stop() }
                runCatching { previous.shutdown() }
            }
            tts = null

            DiagnosticHub.record(
                "TTS_INITIALIZATION_STARTED",
                mapOf("reason" to reason, "engineEpoch" to epoch),
            )

            val holder = arrayOfNulls<TextToSpeech>(1)
            val engine = TextToSpeech(appContext) { status ->
                val created = holder[0]
                if (epoch != engineEpoch.get()) {
                    created?.shutdown()
                    if (!initialization.isCompleted) initialization.complete(false)
                    return@TextToSpeech
                }

                val success = status == TextToSpeech.SUCCESS && created != null
                if (success) {
                    configureEngine(created!!)
                    tts = created
                } else {
                    created?.shutdown()
                    tts = null
                }
                DiagnosticHub.record(
                    "TTS_INITIALIZATION_COMPLETED",
                    mapOf(
                        "success" to success,
                        "status" to status,
                        "reason" to reason,
                        "engineEpoch" to epoch,
                        "durationMs" to
                            (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0,
                    ),
                )
                if (!initialization.isCompleted) initialization.complete(success)
            }
            holder[0] = engine
            tts = engine
        }
    }

    private fun configureEngine(engine: TextToSpeech) {
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                val id = utteranceId ?: return
                val state = utterances[id] ?: return
                state.startedAtElapsedNanos = SystemClock.elapsedRealtimeNanos()
                DiagnosticHub.record(
                    "TTS_UTTERANCE_STARTED",
                    state.trace.fieldsOrEmpty(
                        mapOf(
                            "utteranceId" to id,
                            "text" to state.text,
                            "language" to state.language,
                            "requestGeneration" to state.requestGeneration,
                            "queueWaitMs" to
                                (state.startedAtElapsedNanos - state.enqueuedAtElapsedNanos) / 1_000_000.0,
                            "submitToStartMs" to
                                (state.startedAtElapsedNanos - state.submittedAtElapsedNanos) / 1_000_000.0,
                        ),
                    ),
                )
            }

            override fun onDone(utteranceId: String?) {
                completeUtterance(utteranceId, "TTS_UTTERANCE_DONE", SpeechOutcome.COMPLETED)
            }

            @Deprecated("Deprecated by Android but still invoked by older engines")
            override fun onError(utteranceId: String?) {
                completeUtterance(
                    utteranceId,
                    "TTS_UTTERANCE_ERROR",
                    SpeechOutcome.FAILED,
                    mapOf("errorCode" to "legacy"),
                )
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                completeUtterance(
                    utteranceId,
                    "TTS_UTTERANCE_ERROR",
                    SpeechOutcome.FAILED,
                    mapOf("errorCode" to errorCode),
                )
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                completeUtterance(
                    utteranceId,
                    "TTS_UTTERANCE_STOPPED",
                    SpeechOutcome.INTERRUPTED,
                    mapOf("interrupted" to interrupted),
                )
            }
        })
    }

    private fun completeUtterance(
        utteranceId: String?,
        event: String,
        outcome: SpeechOutcome,
        extra: Map<String, Any?> = emptyMap(),
    ) {
        val id = utteranceId ?: return
        val state = utterances.remove(id) ?: return
        val now = SystemClock.elapsedRealtimeNanos()
        val startedAt = state.startedAtElapsedNanos
        DiagnosticHub.record(
            event,
            state.trace.fieldsOrEmpty(
                mapOf(
                    "utteranceId" to id,
                    "text" to state.text,
                    "language" to state.language,
                    "requestGeneration" to state.requestGeneration,
                    "totalQueueToTerminalMs" to
                        (now - state.enqueuedAtElapsedNanos) / 1_000_000.0,
                    "speechDurationMs" to if (startedAt > 0L) {
                        (now - startedAt) / 1_000_000.0
                    } else {
                        null
                    },
                    "outcome" to outcome.name,
                ) + extra,
            ),
        )
        state.completion.complete(outcome)
    }

    private suspend fun enqueue(
        text: String,
        rate: Float,
        interruptPrevious: Boolean,
        readingId: Long,
        blockIndex: Int = NO_BLOCK,
        trace: DiagnosticTrace?,
        liveGeneration: Long = NOT_LIVE,
    ) {
        val readyStarted = SystemClock.elapsedRealtimeNanos()
        if (!ensureEngineReady(trace, text)) {
            if (readingId != NO_READING) {
                reportBlock(readingId, blockIndex, text, trace, SpeechOutcome.FAILED)
                releaseBlock()
            }
            return
        }
        val afterReady = SystemClock.elapsedRealtimeNanos()

        withContext(Dispatchers.Main.immediate) {
            val requestGeneration = if (interruptPrevious) {
                interruptInternal("new_interrupting_request")
            } else {
                generation.get()
            }
            val enqueuedAt = SystemClock.elapsedRealtimeNanos()
            if (liveGeneration != NOT_LIVE) liveBlocksOutstanding.incrementAndGet()
            val request = SpeechRequest(
                text = text,
                rate = rate.coerceIn(0.6f, 1.8f),
                generation = requestGeneration,
                readingId = readingId,
                blockIndex = blockIndex,
                flushFirst = interruptPrevious,
                liveGeneration = liveGeneration,
                trace = trace,
                enqueuedAtElapsedNanos = enqueuedAt,
            )
            val accepted = requests.trySend(request).isSuccess
            DiagnosticHub.record(
                "TTS_REQUEST_ENQUEUED",
                trace.fieldsOrEmpty(
                    mapOf(
                        "text" to text,
                        "rate" to request.rate,
                        "generation" to requestGeneration,
                        "readingId" to readingId,
                        "flushFirst" to interruptPrevious,
                        "accepted" to accepted,
                        "waitForEngineReadyMs" to
                            (afterReady - readyStarted) / 1_000_000.0,
                    ),
                ),
            )
            if (!accepted && readingId != NO_READING) {
                reportBlock(readingId, blockIndex, text, trace, SpeechOutcome.FAILED)
                releaseBlock()
            }
        }
    }

    private suspend fun ensureEngineReady(trace: DiagnosticTrace?, text: String): Boolean {
        val firstReady = ready
        val first = withTimeoutOrNull(ENGINE_READY_TIMEOUT_MS) { firstReady.await() } == true
        if (first && tts != null) return true

        DiagnosticHub.record(
            "TTS_ENGINE_RECOVERY",
            trace.fieldsOrEmpty(
                mapOf(
                    "reason" to "engine_ready_timeout_or_failure",
                    "text" to text,
                    "timeoutMs" to ENGINE_READY_TIMEOUT_MS,
                ),
            ),
        )
        withContext(Dispatchers.Main.immediate) {
            initializeEngine("ready_recovery")
        }
        val retryReady = ready
        val recovered = withTimeoutOrNull(ENGINE_READY_TIMEOUT_MS) { retryReady.await() } == true
        if (!recovered || tts == null) {
            DiagnosticHub.record(
                "TTS_REQUEST_DROPPED",
                trace.fieldsOrEmpty(
                    mapOf(
                        "reason" to "engine_unavailable_after_recovery",
                        "text" to text,
                    ),
                ),
            )
            return false
        }
        return true
    }

    fun onVisualTargetChanged(enabled: Boolean) {
        if (!enabled) return
        scope.launch {
            activeReadingId.set(NO_READING)
            readingOpen = false
            outstandingBlocks.set(0)
            interruptInternal("visual_target_changed")
        }
    }

    fun stop() {
        scope.launch {
            activeReadingId.set(NO_READING)
            readingOpen = false
            outstandingBlocks.set(0)
            interruptInternal(EXPLICIT_STOP_REASON)
        }
    }

    /**
     * Speaks an operational notice from the engine's own scope, so it survives the death of
     * whatever component asked for it.
     *
     * The capture service calls this as it stops: a projection Android has taken away leaves a
     * blind user holding glasses that have silently stopped working, and a message on a screen is
     * not an answer to that.
     */
    fun speakUrgentNotice(text: String, rate: Float = 1.0f) {
        if (text.isBlank()) return
        scope.launch { speakFeedback(text = text, rate = rate, interruptPrevious = true) }
    }

    fun resetHistory() {
        noticeDeduplicator.reset()
        DiagnosticHub.record("TTS_DEDUPLICATION_HISTORY_RESET")
    }

    /**
     * Speaks one request and reports what became of it.
     *
     * The return value is the whole point: a segment that was stopped, errored or timed out means
     * the user did not hear this block, and everything downstream needs to know that rather than
     * infer success from the absence of a crash.
     */
    private suspend fun speakRequest(request: SpeechRequest): SpeechOutcome {
        if (!ensureEngineReady(request.trace, request.text)) return SpeechOutcome.FAILED
        val engine = tts ?: return SpeechOutcome.FAILED
        val segments = SpeechTextTools.segment(request.text)
        DiagnosticHub.record(
            "TTS_SEGMENTATION_COMPLETED",
            request.trace.fieldsOrEmpty(
                mapOf(
                    "originalText" to request.text,
                    "segmentCount" to segments.size,
                    "segments" to segments.map {
                        mapOf("text" to it.text, "language" to it.language.name)
                    },
                    "generation" to request.generation,
                    "readingId" to request.readingId,
                ),
            ),
        )

        for ((index, segment) in segments.withIndex()) {
            if (request.generation != generation.get()) {
                DiagnosticHub.record(
                    "TTS_SEGMENT_DROPPED",
                    request.trace.fieldsOrEmpty(
                        mapOf(
                            "reason" to "generation_changed",
                            "text" to segment.text,
                            "segmentIndex" to index,
                            "requestGeneration" to request.generation,
                            "currentGeneration" to generation.get(),
                        ),
                    ),
                )
                return SpeechOutcome.INTERRUPTED
            }

            val availability = engine.isLanguageAvailable(segment.language.locale)
            if (availability >= TextToSpeech.LANG_AVAILABLE) {
                engine.language = segment.language.locale
            }
            engine.setSpeechRate(request.rate)

            val utteranceId = "vision-${UUID.randomUUID()}"
            val completion = CompletableDeferred<SpeechOutcome>()
            val submittedAt = SystemClock.elapsedRealtimeNanos()
            utterances[utteranceId] = UtteranceState(
                completion = completion,
                trace = request.trace,
                text = segment.text,
                language = segment.language.name,
                requestGeneration = request.generation,
                enqueuedAtElapsedNanos = request.enqueuedAtElapsedNanos,
                submittedAtElapsedNanos = submittedAt,
            )
            DiagnosticHub.record(
                "TTS_UTTERANCE_SUBMITTING",
                request.trace.fieldsOrEmpty(
                    mapOf(
                        "utteranceId" to utteranceId,
                        "text" to segment.text,
                        "language" to segment.language.name,
                        "languageAvailability" to availability,
                        "segmentIndex" to index,
                        "segmentCount" to segments.size,
                        "rate" to request.rate,
                        "queueMode" to if (index == 0 && request.flushFirst) "FLUSH" else "ADD",
                    ),
                ),
            )
            val result = engine.speak(
                segment.text,
                if (index == 0 && request.flushFirst) {
                    TextToSpeech.QUEUE_FLUSH
                } else {
                    TextToSpeech.QUEUE_ADD
                },
                null,
                utteranceId,
            )
            DiagnosticHub.record(
                "TTS_ENGINE_SPEAK_RETURNED",
                request.trace.fieldsOrEmpty(
                    mapOf(
                        "utteranceId" to utteranceId,
                        "result" to result,
                        "success" to (result != TextToSpeech.ERROR),
                        "submitCallMs" to
                            (SystemClock.elapsedRealtimeNanos() - submittedAt) / 1_000_000.0,
                    ),
                ),
            )
            if (result == TextToSpeech.ERROR) {
                utterances.remove(utteranceId)?.completion?.complete(SpeechOutcome.FAILED)
                consecutiveTimeouts++
                recoverEngineIfNeeded("speak_returned_error", request.trace)
                return SpeechOutcome.FAILED
            }

            val timeoutMs = utteranceTimeoutMs(segment.text)
            val outcome = withTimeoutOrNull(timeoutMs) { completion.await() }
            utterances.remove(utteranceId)
            if (outcome == null) {
                consecutiveTimeouts++
                DiagnosticHub.record(
                    "TTS_UTTERANCE_TIMEOUT",
                    request.trace.fieldsOrEmpty(
                        mapOf(
                            "utteranceId" to utteranceId,
                            "text" to segment.text,
                            "timeoutMs" to timeoutMs,
                            "consecutiveTimeouts" to consecutiveTimeouts,
                        ),
                    ),
                )
                engine.stop()
                recoverEngineIfNeeded("utterance_timeout", request.trace)
                return SpeechOutcome.FAILED
            }
            // One silenced segment silences the rest of the block: the words after it were never
            // spoken either, whatever the engine goes on to report about them.
            if (!outcome.delivered) return outcome
            consecutiveTimeouts = 0
        }
        return SpeechOutcome.COMPLETED
    }

    private fun recoverEngineIfNeeded(reason: String, trace: DiagnosticTrace?) {
        if (consecutiveTimeouts < TIMEOUTS_BEFORE_ENGINE_RESTART) return
        consecutiveTimeouts = 0
        DiagnosticHub.record(
            "TTS_ENGINE_RECOVERY",
            trace.fieldsOrEmpty(
                mapOf(
                    "reason" to reason,
                    "action" to "recreate_engine",
                ),
            ),
        )
        initializeEngine(reason)
    }

    /** Invalidates active and queued requests and records every discarded item. */
    private fun interruptInternal(reason: String): Long {
        val oldGeneration = generation.get()
        val newGeneration = generation.incrementAndGet()
        val discardedOutcome = if (reason == EXPLICIT_STOP_REASON) {
            SpeechOutcome.CANCELLED_BY_USER
        } else {
            SpeechOutcome.INTERRUPTED
        }
        var removedRequests = 0
        while (true) {
            val request = requests.tryReceive().getOrNull() ?: break
            removedRequests++
            DiagnosticHub.record(
                "TTS_REQUEST_DROPPED",
                request.trace.fieldsOrEmpty(
                    mapOf(
                        "reason" to reason,
                        "text" to request.text,
                        "requestGeneration" to request.generation,
                        "newGeneration" to newGeneration,
                    ),
                ),
            )
            // A block pulled off the queue here never reaches the worker, so this is its only
            // chance to be accounted for. Without it the reading never settles and the page is
            // left neither heard nor owed.
            if (request.readingId != NO_READING) {
                reportBlock(request, discardedOutcome)
                releaseBlock()
            }
            // Same reasoning for a live block: pulled off here it never reaches the worker, and an
            // unbalanced counter would leave the coordinator believing a description is still being
            // spoken and refusing to buy the next one — silence, from a bookkeeping slip.
            if (request.liveGeneration != NOT_LIVE) {
                liveBlocksOutstanding.updateAndGet { if (it > 0) it - 1 else 0 }
            }
        }
        val activeCount = utterances.size
        utterances.forEach { (id, state) ->
            DiagnosticHub.record(
                "TTS_UTTERANCE_INTERRUPTED",
                state.trace.fieldsOrEmpty(
                    mapOf(
                        "utteranceId" to id,
                        "text" to state.text,
                        "reason" to reason,
                        "newGeneration" to newGeneration,
                    ),
                ),
            )
            state.completion.complete(discardedOutcome)
        }
        utterances.clear()
        outstandingBlocks.set(0)
        val stopResult = tts?.stop()
        DiagnosticHub.record(
            "TTS_QUEUE_INTERRUPTED",
            mapOf(
                "reason" to reason,
                "oldGeneration" to oldGeneration,
                "newGeneration" to newGeneration,
                "removedQueuedRequests" to removedRequests,
                "activeUtterances" to activeCount,
                "engineStopResult" to stopResult,
            ),
        )
        return newGeneration
    }

    fun shutdown() {
        interruptInternal("engine_shutdown")
        requests.close()
        synchronized(engineLock) {
            engineEpoch.incrementAndGet()
            tts?.shutdown()
            tts = null
            if (!ready.isCompleted) ready.complete(false)
        }
        scope.cancel()
        DiagnosticHub.record("TTS_ENGINE_SHUTDOWN")
    }

    private fun utteranceTimeoutMs(text: String): Long =
        (BASE_UTTERANCE_TIMEOUT_MS + text.length * TIMEOUT_PER_CHARACTER_MS)
            .coerceIn(MIN_UTTERANCE_TIMEOUT_MS, MAX_UTTERANCE_TIMEOUT_MS)

    private fun DiagnosticTrace?.fieldsOrEmpty(
        extra: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> = this?.fields(extra) ?: extra

    private companion object {
        const val NO_READING = 0L

        /** Block index for speech that is not part of a reading, such as a status notice. */
        const val NO_BLOCK = -1

        /** Speech that does not expire: a page, a notice, an error. Only a live scene does. */
        const val NOT_LIVE = 0L

        /** The one interrupt reason that is the user's own decision rather than a new target. */
        const val EXPLICIT_STOP_REASON = "explicit_stop"
        const val ENGINE_READY_TIMEOUT_MS = 8_000L
        const val TIMEOUTS_BEFORE_ENGINE_RESTART = 2
        const val BASE_UTTERANCE_TIMEOUT_MS = 8_000L
        const val TIMEOUT_PER_CHARACTER_MS = 170L
        const val MIN_UTTERANCE_TIMEOUT_MS = 12_000L
        const val MAX_UTTERANCE_TIMEOUT_MS = 45_000L
    }
}
