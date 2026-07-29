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
import java.util.concurrent.atomic.AtomicLong

/**
 * Ordered bilingual speech engine with an instrumented utterance queue.
 * Each request keeps the originating visual-frame trace until Android reports start, completion,
 * interruption or failure.
 */
class BilingualTtsEngine(context: Context) {
    private data class SpeechRequest(
        val text: String,
        val rate: Float,
        val generation: Long,
        val flushFirst: Boolean,
        val trace: DiagnosticTrace?,
        val enqueuedAtElapsedNanos: Long,
    )

    private class UtteranceState(
        val completion: CompletableDeferred<Unit>,
        val trace: DiagnosticTrace?,
        val text: String,
        val language: String,
        val requestGeneration: Long,
        val enqueuedAtElapsedNanos: Long,
        val submittedAtElapsedNanos: Long,
    ) {
        @Volatile var startedAtElapsedNanos: Long = 0L
    }

    private val appContext = context.applicationContext
    private val ready = CompletableDeferred<Boolean>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val requests = Channel<SpeechRequest>(Channel.UNLIMITED)
    private val utterances = ConcurrentHashMap<String, UtteranceState>()
    private val generation = AtomicLong(0L)
    private val deduplicator = SpeechDeduplicator()

    private var tts: TextToSpeech? = null

    init {
        val initStarted = SystemClock.elapsedRealtimeNanos()
        DiagnosticHub.record("TTS_INITIALIZATION_STARTED")
        tts = TextToSpeech(appContext) { status ->
            val success = status == TextToSpeech.SUCCESS
            if (success) configureEngine()
            DiagnosticHub.record(
                "TTS_INITIALIZATION_COMPLETED",
                mapOf(
                    "success" to success,
                    "status" to status,
                    "durationMs" to (SystemClock.elapsedRealtimeNanos() - initStarted) / 1_000_000.0,
                ),
            )
            if (!ready.isCompleted) ready.complete(success)
        }

        scope.launch {
            for (request in requests) {
                if (request.generation == generation.get()) {
                    speakRequest(request)
                } else {
                    DiagnosticHub.record(
                        "TTS_REQUEST_DROPPED",
                        request.trace.fieldsOrEmpty(
                            mapOf(
                                "reason" to "stale_generation_before_worker",
                                "text" to request.text,
                                "requestGeneration" to request.generation,
                                "currentGeneration" to generation.get(),
                            ),
                        ),
                    )
                }
            }
        }
    }

    private fun configureEngine() {
        tts?.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
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
                            "queueWaitMs" to (state.startedAtElapsedNanos - state.enqueuedAtElapsedNanos) / 1_000_000.0,
                            "submitToStartMs" to (state.startedAtElapsedNanos - state.submittedAtElapsedNanos) / 1_000_000.0,
                        ),
                    ),
                )
            }

            override fun onDone(utteranceId: String?) {
                completeUtterance(utteranceId, "TTS_UTTERANCE_DONE")
            }

            @Deprecated("Deprecated by Android but still invoked by older engines")
            override fun onError(utteranceId: String?) {
                completeUtterance(utteranceId, "TTS_UTTERANCE_ERROR", mapOf("errorCode" to "legacy"))
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                completeUtterance(utteranceId, "TTS_UTTERANCE_ERROR", mapOf("errorCode" to errorCode))
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                completeUtterance(
                    utteranceId,
                    "TTS_UTTERANCE_STOPPED",
                    mapOf("interrupted" to interrupted),
                )
            }
        })
    }

    private fun completeUtterance(
        utteranceId: String?,
        event: String,
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
                    "totalQueueToTerminalMs" to (now - state.enqueuedAtElapsedNanos) / 1_000_000.0,
                    "speechDurationMs" to if (startedAt > 0L) (now - startedAt) / 1_000_000.0 else null,
                ) + extra,
            ),
        )
        state.completion.complete(Unit)
    }

    suspend fun speak(
        text: String,
        urgent: Boolean = false,
        rate: Float = 1.0f,
        interruptPrevious: Boolean = false,
    ) {
        val trace = currentCoroutineContext()[DiagnosticTrace]
        val novelText = deduplicator.filter(text, urgent)
        if (novelText == null) {
            DiagnosticHub.record(
                "TTS_TEXT_DEDUPLICATED",
                trace.fieldsOrEmpty(mapOf("text" to text, "urgent" to urgent)),
            )
            return
        }
        enqueue(novelText, rate, interruptPrevious, trace)
    }

    /** Status speech intentionally bypasses content deduplication. */
    suspend fun speakFeedback(
        text: String,
        rate: Float = 1.0f,
        interruptPrevious: Boolean = true,
    ) {
        if (text.isBlank()) return
        enqueue(text.trim(), rate, interruptPrevious, currentCoroutineContext()[DiagnosticTrace])
    }

    private suspend fun enqueue(
        text: String,
        rate: Float,
        interruptPrevious: Boolean,
        trace: DiagnosticTrace?,
    ) {
        val readyStarted = SystemClock.elapsedRealtimeNanos()
        if (!ready.await()) {
            val error = IllegalStateException("تعذر تهيئة محرك النطق في الجهاز")
            DiagnosticHub.failure("TTS_NOT_READY", error, trace.fieldsOrEmpty(mapOf("text" to text)))
            throw error
        }
        val afterReady = SystemClock.elapsedRealtimeNanos()

        withContext(Dispatchers.Main.immediate) {
            val requestGeneration = if (interruptPrevious) interruptInternal("new_interrupting_request") else generation.get()
            val enqueuedAt = SystemClock.elapsedRealtimeNanos()
            val request = SpeechRequest(
                text = text,
                rate = rate.coerceIn(0.6f, 1.8f),
                generation = requestGeneration,
                flushFirst = interruptPrevious,
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
                        "flushFirst" to interruptPrevious,
                        "accepted" to accepted,
                        "waitForEngineReadyMs" to (afterReady - readyStarted) / 1_000_000.0,
                    ),
                ),
            )
        }
    }

    fun onVisualTargetChanged(enabled: Boolean) {
        if (!enabled) return
        scope.launch { interruptInternal("visual_target_changed") }
    }

    fun stop() {
        scope.launch { interruptInternal("explicit_stop") }
    }

    fun resetHistory() {
        deduplicator.reset()
        DiagnosticHub.record("TTS_DEDUPLICATION_HISTORY_RESET")
    }

    private suspend fun speakRequest(request: SpeechRequest) {
        val engine = tts ?: run {
            DiagnosticHub.record(
                "TTS_REQUEST_DROPPED",
                request.trace.fieldsOrEmpty(mapOf("reason" to "engine_null", "text" to request.text)),
            )
            return
        }
        val segments = SpeechTextTools.segment(request.text)
        DiagnosticHub.record(
            "TTS_SEGMENTATION_COMPLETED",
            request.trace.fieldsOrEmpty(
                mapOf(
                    "originalText" to request.text,
                    "segmentCount" to segments.size,
                    "segments" to segments.map { mapOf("text" to it.text, "language" to it.language.name) },
                    "generation" to request.generation,
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
                return
            }

            val availability = engine.isLanguageAvailable(segment.language.locale)
            if (availability >= TextToSpeech.LANG_AVAILABLE) {
                engine.language = segment.language.locale
            }
            engine.setSpeechRate(request.rate)

            val utteranceId = "vision-${UUID.randomUUID()}"
            val completion = CompletableDeferred<Unit>()
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
                if (index == 0 && request.flushFirst) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
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
                        "submitCallMs" to (SystemClock.elapsedRealtimeNanos() - submittedAt) / 1_000_000.0,
                    ),
                ),
            )
            if (result == TextToSpeech.ERROR) {
                val state = utterances.remove(utteranceId)
                state?.completion?.complete(Unit)
                continue
            }

            val completed = withTimeoutOrNull(MAX_UTTERANCE_WAIT_MS) {
                completion.await()
                true
            } ?: false
            utterances.remove(utteranceId)
            if (!completed) {
                DiagnosticHub.record(
                    "TTS_UTTERANCE_TIMEOUT",
                    request.trace.fieldsOrEmpty(
                        mapOf(
                            "utteranceId" to utteranceId,
                            "text" to segment.text,
                            "timeoutMs" to MAX_UTTERANCE_WAIT_MS,
                        ),
                    ),
                )
                engine.stop()
            }
        }
    }

    /** Invalidates active and queued requests and records every discarded item. */
    private fun interruptInternal(reason: String): Long {
        val oldGeneration = generation.get()
        val newGeneration = generation.incrementAndGet()
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
            state.completion.complete(Unit)
        }
        utterances.clear()
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
        tts?.shutdown()
        tts = null
        scope.cancel()
        DiagnosticHub.record("TTS_ENGINE_SHUTDOWN")
    }

    private fun DiagnosticTrace?.fieldsOrEmpty(extra: Map<String, Any?> = emptyMap()): Map<String, Any?> =
        this?.fields(extra) ?: extra

    private companion object {
        const val MAX_UTTERANCE_WAIT_MS = 60_000L
    }
}
