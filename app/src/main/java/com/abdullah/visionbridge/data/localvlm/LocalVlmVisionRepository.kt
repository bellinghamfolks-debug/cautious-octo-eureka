package com.abdullah.visionbridge.data.localvlm

import android.graphics.Bitmap
import com.abdullah.visionbridge.data.diagnostics.DiagnosticHub
import com.abdullah.visionbridge.data.diagnostics.DiagnosticTrace
import com.abdullah.visionbridge.data.gemini.StreamingSpeechBuffer
import com.abdullah.visionbridge.domain.model.AnalysisMode
import com.abdullah.visionbridge.domain.model.AnalysisResult
import com.abdullah.visionbridge.domain.model.AnalysisSource
import com.abdullah.visionbridge.domain.model.CaptureProfile
import com.abdullah.visionbridge.domain.model.SceneDescriptionStyle
import com.abdullah.visionbridge.domain.repository.VisionAiRepository
import kotlinx.coroutines.currentCoroutineContext

/**
 * Runs both features on device through the local VLM, behind the same interface
 * as the cloud repository.
 *
 * Implementing [VisionAiRepository] rather than adding a parallel path is what
 * keeps the rest of the app unchanged: the reading ledger still decides what is
 * worth speaking, the ordered speech queue still delivers it, and the capture
 * coordinator cannot tell which engine produced the text.
 */
class LocalVlmVisionRepository(
    private val engine: LocalVlmEngine,
) : VisionAiRepository {

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
        val trace = currentCoroutineContext()[DiagnosticTrace]
        DiagnosticHub.record(
            "LOCAL_VLM_ANALYSIS_REQUESTED",
            trace.fieldsOrEmpty(
                mapOf(
                    "mode" to mode.name,
                    "sceneDescriptionStyle" to sceneDescriptionStyle.name,
                    "captureProfile" to captureProfile.name,
                    "engineLoaded" to engine.isLoaded,
                ),
            ),
        )

        engine.ensureLoaded().getOrThrow()

        val prompt = LocalVlmPrompts.build(mode, sceneDescriptionStyle)
        val loopGuard = VlmLoopGuard()
        val accumulated = StringBuilder()

        // Streaming only benefits scene description, where the first sentence is
        // the warning. Text reading is delivered as a whole page by the
        // coordinator, so blocks produced here would be discarded anyway.
        val speechBuffer = if (mode == AnalysisMode.SCENE_DESCRIPTION) {
            StreamingSpeechBuffer(StreamingSpeechBuffer.Profile.RESPONSIVE)
        } else {
            null
        }
        val pendingBlocks = mutableListOf<String>()
        var loopDetected = false

        val raw = engine.generate(
            bitmap = bitmap,
            prompt = prompt,
            maxTokens = LocalVlmPrompts.maxTokens(mode, sceneDescriptionStyle),
            temperature = LocalVlmPrompts.temperature(mode),
            longEdgePixels = if (mode == AnalysisMode.TEXT_READING) {
                LocalVlmEngine.READ_LONG_EDGE_PIXELS
            } else {
                LocalVlmEngine.DESCRIBE_LONG_EDGE_PIXELS
            },
        ) { fragment ->
            accumulated.append(fragment)
            speechBuffer?.append(fragment, urgent = false)?.let(pendingBlocks::addAll)

            if (loopGuard.hasDegenerated(accumulated.toString())) {
                loopDetected = true
                DiagnosticHub.record(
                    "LOCAL_VLM_LOOP_DETECTED",
                    trace.fieldsOrEmpty(
                        mapOf(
                            "characters" to accumulated.length,
                            "mode" to mode.name,
                            "action" to "generation_stopped",
                        ),
                    ),
                )
                false
            } else {
                true
            }
        }

        // Speech blocks are emitted after generation rather than inside the JNI
        // callback: suspending there would stall the native decode loop for the
        // whole duration of an utterance.
        if (speechBuffer != null) {
            (pendingBlocks + speechBuffer.finish()).forEach { block ->
                val safe = VlmOutputSanitizer.sanitize(block, collapseLines = false)
                if (safe.isNotBlank()) onSpeechChunk(safe, false)
            }
        }

        val cleaned = VlmOutputSanitizer.sanitize(
            raw = raw,
            collapseLines = mode == AnalysisMode.TEXT_READING,
        )
        DiagnosticHub.record(
            "LOCAL_VLM_OUTPUT_SANITIZED",
            trace.fieldsOrEmpty(
                mapOf(
                    "rawCharacters" to raw.length,
                    "cleanCharacters" to cleaned.length,
                    "rawText" to raw,
                    "text" to cleaned,
                    "loopDetected" to loopDetected,
                ),
            ),
        )

        if (LocalVlmPrompts.isNoContentAnswer(cleaned)) {
            DiagnosticHub.record(
                "LOCAL_VLM_REPORTED_NO_CONTENT",
                trace.fieldsOrEmpty(mapOf("mode" to mode.name)),
            )
            if (mode == AnalysisMode.SCENE_DESCRIPTION) {
                throw IllegalStateException("تعذر وصف المشهد من الصورة الحالية")
            }
            return AnalysisResult(text = "", source = AnalysisSource.LOCAL_VLM, language = "none")
        }

        return AnalysisResult(
            text = cleaned,
            source = AnalysisSource.LOCAL_VLM,
            language = detectLanguage(cleaned),
            // The local model is not asked to classify urgency: small quantized
            // models get that judgement wrong often enough that a false urgent
            // flag would interrupt speech for nothing.
            urgent = false,
        )
    }

    private fun detectLanguage(text: String): String {
        val arabic = text.count { it in '؀'..'ۿ' || it in 'ݐ'..'ݿ' }
        val latin = text.count { it in 'A'..'Z' || it in 'a'..'z' }
        val total = arabic + latin
        if (total == 0) return "und"
        val minorityShare = minOf(arabic, latin).toDouble() / total
        return when {
            minorityShare >= MIXED_LANGUAGE_SHARE -> "mixed"
            arabic >= latin -> "ar"
            else -> "en"
        }
    }

    private fun DiagnosticTrace?.fieldsOrEmpty(
        extra: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> = this?.fields(extra) ?: extra

    private companion object {
        const val MIXED_LANGUAGE_SHARE = 0.15
    }
}
