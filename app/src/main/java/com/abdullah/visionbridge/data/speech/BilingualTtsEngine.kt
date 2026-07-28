package com.abdullah.visionbridge.data.speech

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Ordered bilingual speech engine.
 *
 * TextToSpeech language is mutable engine-wide state. Queuing all Arabic and English segments
 * while changing that shared state immediately can synthesize queued utterances with the wrong
 * language or an unexpected order. This engine submits exactly one segment, waits for its
 * UtteranceProgressListener completion, then changes language for the next segment.
 */
class BilingualTtsEngine(context: Context) {
    private data class SpeechRequest(
        val text: String,
        val rate: Float,
        val generation: Long,
        val flushFirst: Boolean,
    )

    private val appContext = context.applicationContext
    private val ready = CompletableDeferred<Boolean>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val requests = Channel<SpeechRequest>(Channel.UNLIMITED)
    private val completions = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val generation = AtomicLong(0L)
    private val deduplicator = SpeechDeduplicator()

    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(appContext) { status ->
            val success = status == TextToSpeech.SUCCESS
            if (success) configureEngine()
            if (!ready.isCompleted) ready.complete(success)
        }

        scope.launch {
            for (request in requests) {
                if (request.generation == generation.get()) speakRequest(request)
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
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                utteranceId?.let { completions.remove(it)?.complete(Unit) }
            }

            @Deprecated("Deprecated by Android but still invoked by older engines")
            override fun onError(utteranceId: String?) {
                utteranceId?.let { completions.remove(it)?.complete(Unit) }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                utteranceId?.let { completions.remove(it)?.complete(Unit) }
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                utteranceId?.let { completions.remove(it)?.complete(Unit) }
            }
        })
    }

    suspend fun speak(
        text: String,
        urgent: Boolean = false,
        rate: Float = 1.0f,
        interruptPrevious: Boolean = false,
    ) {
        val novelText = deduplicator.filter(text, urgent) ?: return
        if (!ready.await()) throw IllegalStateException("تعذر تهيئة محرك النطق في الجهاز")

        withContext(Dispatchers.Main.immediate) {
            val requestGeneration = if (interruptPrevious) interruptInternal() else generation.get()
            requests.trySend(
                SpeechRequest(
                    text = novelText,
                    rate = rate.coerceIn(0.6f, 1.8f),
                    generation = requestGeneration,
                    flushFirst = interruptPrevious,
                )
            )
        }
    }

    /** Called as soon as the capture layer confirms that the user moved to a new visual target. */
    fun onVisualTargetChanged(enabled: Boolean) {
        if (!enabled) return
        scope.launch { interruptInternal() }
    }

    fun stop() {
        scope.launch { interruptInternal() }
    }

    fun resetHistory() = deduplicator.reset()

    private suspend fun speakRequest(request: SpeechRequest) {
        val engine = tts ?: return
        val segments = SpeechTextTools.segment(request.text)
        for ((index, segment) in segments.withIndex()) {
            if (request.generation != generation.get()) return

            val availability = engine.isLanguageAvailable(segment.language.locale)
            if (availability >= TextToSpeech.LANG_AVAILABLE) {
                engine.language = segment.language.locale
            }
            engine.setSpeechRate(request.rate)

            val utteranceId = "vision-${UUID.randomUUID()}"
            val completion = CompletableDeferred<Unit>()
            completions[utteranceId] = completion
            val result = engine.speak(
                segment.text,
                if (index == 0 && request.flushFirst) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                null,
                utteranceId,
            )
            if (result == TextToSpeech.ERROR) {
                completions.remove(utteranceId)
                continue
            }

            val completed = withTimeoutOrNull(MAX_UTTERANCE_WAIT_MS) {
                completion.await()
                true
            } ?: false
            completions.remove(utteranceId)
            if (!completed) {
                // A vendor TTS engine that omits progress callbacks must not freeze every later
                // sentence forever. Stop that utterance and allow the queue to continue.
                engine.stop()
            }
        }
    }

    /**
     * Invalidates active and queued requests. A generation token prevents the worker from
     * continuing the remaining segments of an old visual target after engine.stop().
     */
    private fun interruptInternal(): Long {
        val newGeneration = generation.incrementAndGet()
        while (requests.tryReceive().isSuccess) Unit
        completions.values.forEach { it.complete(Unit) }
        completions.clear()
        tts?.stop()
        return newGeneration
    }

    fun shutdown() {
        interruptInternal()
        requests.close()
        tts?.shutdown()
        tts = null
        scope.cancel()
    }

    private companion object {
        const val MAX_UTTERANCE_WAIT_MS = 60_000L
    }
}
