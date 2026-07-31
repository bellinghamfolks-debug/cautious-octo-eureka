package com.abdullah.visionbridge.data.paddleocr

import android.graphics.Bitmap
import com.abdullah.visionbridge.data.diagnostics.DiagnosticHub
import com.abdullah.visionbridge.data.diagnostics.DiagnosticTrace
import com.abdullah.visionbridge.domain.model.AnalysisMode
import com.abdullah.visionbridge.domain.model.AnalysisResult
import com.abdullah.visionbridge.domain.model.AnalysisSource
import com.abdullah.visionbridge.domain.model.CaptureProfile
import com.abdullah.visionbridge.domain.model.SceneDescriptionStyle
import com.abdullah.visionbridge.domain.repository.VisionAiRepository
import kotlinx.coroutines.currentCoroutineContext

/**
 * The on-device reader, behind the same interface as the cloud repository.
 *
 * It reads text and only text. PP-OCR is a text recognizer, not a vision-language model, so scene
 * description has no local implementation and is refused explicitly rather than answered with
 * something misleading. That is a real narrowing against the engine this replaces, and it buys
 * bounded, predictable work per frame instead of open-ended generation.
 */
class PaddleOcrVisionRepository(
    private val engine: PaddleOcrEngine,
) : VisionAiRepository {

    class SceneDescriptionUnsupportedException : IllegalStateException(
        "القارئ المحلي يقرأ النص فقط ولا يصف المشاهد. لوصف المشهد أوقف مفتاح القراءة المحلية."
    )

    override suspend fun analyzeStreaming(
        bitmap: Bitmap,
        mode: AnalysisMode,
        model: String,
        apiKey: String,
        forceCellular: Boolean,
        sceneDescriptionStyle: SceneDescriptionStyle,
        captureProfile: CaptureProfile,
        trustGateEnabled: Boolean,
        onSpeechChunk: suspend (text: String, urgent: Boolean) -> Unit,
    ): AnalysisResult {
        if (mode != AnalysisMode.TEXT_READING) throw SceneDescriptionUnsupportedException()

        val trace = currentCoroutineContext()[DiagnosticTrace]
        DiagnosticHub.record(
            "PPOCR_ANALYSIS_REQUESTED",
            trace.fieldsOrEmpty(
                mapOf(
                    "captureProfile" to captureProfile.name,
                    "engineLoaded" to engine.isLoaded,
                    "sourceWidth" to bitmap.width,
                    "sourceHeight" to bitmap.height,
                ),
            ),
        )

        engine.ensureLoaded().getOrThrow()
        val result = engine.read(bitmap)

        DiagnosticHub.record(
            "PPOCR_PAGE_READ",
            trace.fieldsOrEmpty(
                mapOf(
                    "text" to result.text,
                    "characters" to result.text.length,
                    "lineCount" to result.lineCount,
                    "meanConfidence" to result.confidence,
                ),
            ),
        )

        // A frame with nothing readable is ordinary use, not a fault. The coordinator publishes
        // nothing for an empty result and stays silent, which is the correct behaviour here.
        if (result.text.isBlank()) {
            return AnalysisResult(text = "", source = AnalysisSource.LOCAL_OCR, language = "none")
        }

        return AnalysisResult(
            text = result.text,
            source = AnalysisSource.LOCAL_OCR,
            language = detectLanguage(result.text),
            urgent = false,
        )
    }

    private fun detectLanguage(text: String): String {
        val arabic = BilingualLineSelector.arabicRatio(text)
        val latin = BilingualLineSelector.latinRatio(text)
        return when {
            arabic > 0f && latin > 0f && minOf(arabic, latin) >= MIXED_LANGUAGE_SHARE -> "mixed"
            arabic >= latin && arabic > 0f -> "ar"
            latin > 0f -> "en"
            else -> "und"
        }
    }

    private fun DiagnosticTrace?.fieldsOrEmpty(
        extra: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> = this?.fields(extra) ?: extra

    private companion object {
        const val MIXED_LANGUAGE_SHARE = 0.15f
    }
}
