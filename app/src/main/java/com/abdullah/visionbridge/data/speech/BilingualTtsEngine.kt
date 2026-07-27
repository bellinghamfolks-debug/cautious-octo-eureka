package com.abdullah.visionbridge.data.speech

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

class BilingualTtsEngine(context: Context) {
    private val appContext = context.applicationContext
    private val ready = CompletableDeferred<Boolean>()
    private val mutex = Mutex()
    private val deduplicator = SpeechDeduplicator()
    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(appContext) { status ->
            val success = status == TextToSpeech.SUCCESS
            if (success) {
                tts?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                tts?.setSpeechRate(1.0f)
            }
            if (!ready.isCompleted) ready.complete(success)
        }
    }

    suspend fun speak(text: String, urgent: Boolean = false) {
        if (!deduplicator.shouldSpeak(text, urgent)) return
        if (!ready.await()) throw IllegalStateException("تعذر تهيئة محرك النطق في الجهاز")

        mutex.withLock {
            withContext(Dispatchers.Main.immediate) {
                val engine = tts ?: return@withContext
                val segments = SpeechTextTools.segment(text)
                if (segments.isEmpty()) return@withContext
                if (urgent) engine.stop()

                segments.forEachIndexed { index, segment ->
                    val availability = engine.isLanguageAvailable(segment.language.locale)
                    if (availability >= TextToSpeech.LANG_AVAILABLE) {
                        engine.language = segment.language.locale
                    }
                    engine.speak(
                        segment.text,
                        if (index == 0 && urgent) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                        null,
                        "vision-${UUID.randomUUID()}",
                    )
                }
            }
        }
    }

    fun stop() = tts?.stop()

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
