package com.abdullah.visionbridge.capture

import android.graphics.Bitmap
import com.abdullah.visionbridge.data.ocr.LocalTextRecognizer
import com.abdullah.visionbridge.data.speech.BilingualTtsEngine
import com.abdullah.visionbridge.data.speech.SpeechTextTools
import com.abdullah.visionbridge.domain.model.AnalysisMode
import com.abdullah.visionbridge.domain.model.AnalysisResult
import com.abdullah.visionbridge.domain.model.AnalysisSource
import com.abdullah.visionbridge.domain.repository.ApiKeyStore
import com.abdullah.visionbridge.domain.repository.SettingsRepository
import com.abdullah.visionbridge.domain.usecase.AnalyzeFrameUseCase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class FrameAnalysisCoordinator(
    private val settingsRepository: SettingsRepository,
    private val apiKeyStore: ApiKeyStore,
    private val localTextRecognizer: LocalTextRecognizer,
    private val analyzeFrame: AnalyzeFrameUseCase,
    private val tts: BilingualTtsEngine,
    private val runtime: CaptureRuntime,
) {
    private val mutex = Mutex()
    private var lastCloudOcrAt = 0L
    private var lastSceneAt = 0L

    suspend fun process(bitmap: Bitmap) = mutex.withLock {
        val settings = settingsRepository.settings.first()
        runtime.processing(true)
        try {
            when (settings.mode) {
                AnalysisMode.TEXT_READING -> processText(bitmap, settings)
                AnalysisMode.SCENE_DESCRIPTION -> processScene(bitmap, settings)
            }
        } catch (error: Throwable) {
            runtime.error(error.userMessage())
        } finally {
            runtime.processing(false)
        }
    }

    private suspend fun processText(bitmap: Bitmap, settings: com.abdullah.visionbridge.domain.model.AppSettings) {
        var localText = ""
        if (settings.localOcrEnabled) {
            localText = runCatching { localTextRecognizer.recognize(bitmap) }.getOrDefault("")
            if (localText.isNotBlank()) {
                publish(
                    AnalysisResult(
                        text = localText,
                        source = AnalysisSource.LOCAL_OCR,
                        language = if (localText.any { it in '\u0600'..'\u06FF' }) "mixed" else "en",
                    ),
                    settings.speechEnabled,
                )
            }
        }

        val now = System.currentTimeMillis()
        val interval = if (localText.isBlank()) 1_200L else settings.cloudOcrIntervalMs
        if (now - lastCloudOcrAt < interval) return
        lastCloudOcrAt = now

        val key = apiKeyStore.get() ?: return
        val result = analyzeFrame(
            bitmap = bitmap,
            mode = AnalysisMode.TEXT_READING,
            model = settings.model,
            apiKey = key,
            forceCellular = settings.forceCellular,
        )
        if (result.text.isNotBlank()) {
            // ML Kit may already have spoken the English portion. Gemini remains responsible for
            // Arabic and any genuinely new English content, but must not echo the local result.
            val speechText = SpeechTextTools.cloudDeltaAgainstLocal(result.text, localText)
            publish(result, settings.speechEnabled, speechText)
        }
    }

    private suspend fun processScene(bitmap: Bitmap, settings: com.abdullah.visionbridge.domain.model.AppSettings) {
        val now = System.currentTimeMillis()
        if (now - lastSceneAt < settings.sceneIntervalMs) return
        lastSceneAt = now
        val key = apiKeyStore.get()
            ?: throw IllegalStateException("أدخل مفتاح Gemini أولاً لاستخدام وصف المشهد")
        val result = analyzeFrame(
            bitmap = bitmap,
            mode = AnalysisMode.SCENE_DESCRIPTION,
            model = settings.model,
            apiKey = key,
            forceCellular = settings.forceCellular,
        )
        if (result.text.isNotBlank()) publish(result, settings.speechEnabled)
    }

    private suspend fun publish(
        result: AnalysisResult,
        speechEnabled: Boolean,
        speechText: String = result.text,
    ) {
        runtime.result(result)
        if (speechEnabled && speechText.isNotBlank()) tts.speak(speechText, result.urgent)
    }

    fun reset() {
        lastCloudOcrAt = 0L
        lastSceneAt = 0L
        tts.resetHistory()
    }

    private fun Throwable.userMessage(): String = when (this) {
        is kotlinx.coroutines.CancellationException -> throw this
        else -> message?.takeIf { it.isNotBlank() } ?: "تعذر تحليل إطار الشاشة"
    }
}
