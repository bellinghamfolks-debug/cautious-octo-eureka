package com.abdullah.visionbridge.data.vision

import android.graphics.Bitmap
import com.abdullah.visionbridge.data.diagnostics.DiagnosticHub
import com.abdullah.visionbridge.data.diagnostics.DiagnosticTrace
import com.abdullah.visionbridge.data.paddleocr.PaddleOcrEngine
import com.abdullah.visionbridge.data.paddleocr.PaddleOcrModelStore
import com.abdullah.visionbridge.domain.model.AnalysisMode
import com.abdullah.visionbridge.domain.model.AnalysisResult
import com.abdullah.visionbridge.domain.model.CaptureProfile
import com.abdullah.visionbridge.domain.model.SceneDescriptionStyle
import com.abdullah.visionbridge.domain.repository.SettingsRepository
import com.abdullah.visionbridge.domain.repository.VisionAiRepository
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first

/**
 * Sends text reading to whichever engine the user has selected, and scene description to the cloud.
 *
 * The switch is read per request, so turning it on or off takes effect on the next captured frame
 * without restarting capture.
 *
 * Scene description has no on-device implementation: the local engine is a text recognizer. Rather
 * than fail, describing falls through to the cloud, and the settings screen states that plainly so
 * the split is never a surprise. Text reading keeps the stricter rule — it never silently leaves
 * the device once local reading is on, because that choice is frequently a choice about where
 * screen content goes.
 */
class RoutingVisionRepository(
    private val cloud: VisionAiRepository,
    private val local: VisionAiRepository,
    private val localEngine: PaddleOcrEngine,
    private val modelStore: PaddleOcrModelStore,
    private val settingsRepository: SettingsRepository,
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
        val useLocalReading = settingsRepository.settings.first().useLocalOcr
        val useLocalEngine = useLocalReading && mode == AnalysisMode.TEXT_READING

        DiagnosticHub.record(
            "VISION_ENGINE_SELECTED",
            trace.fieldsOrEmpty(
                mapOf(
                    "engine" to if (useLocalEngine) "LOCAL_PPOCR" else "GEMINI_CLOUD",
                    "mode" to mode.name,
                    "localReadingEnabled" to useLocalReading,
                    "localModelInstalled" to modelStore.isReady,
                    "localModelLoaded" to localEngine.isLoaded,
                ),
            ),
        )

        if (!useLocalEngine) {
            return cloud.analyzeStreaming(
                bitmap = bitmap,
                mode = mode,
                model = model,
                apiKey = apiKey,
                forceCellular = forceCellular,
                sceneDescriptionStyle = sceneDescriptionStyle,
                captureProfile = captureProfile,
                trustGateEnabled = trustGateEnabled,
                onSpeechChunk = onSpeechChunk,
            )
        }

        if (!modelStore.isReady) throw PaddleOcrEngine.NotInstalledException(modelStore.missing())

        return local.analyzeStreaming(
            bitmap = bitmap,
            mode = mode,
            model = model,
            apiKey = apiKey,
            forceCellular = forceCellular,
            sceneDescriptionStyle = sceneDescriptionStyle,
            captureProfile = captureProfile,
            // The trust gate compares Gemini's own output against a second opinion. On-device
            // reading is the second opinion, so there is nothing here for it to check.
            trustGateEnabled = false,
            onSpeechChunk = onSpeechChunk,
        )
    }

    private fun DiagnosticTrace?.fieldsOrEmpty(
        extra: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> = this?.fields(extra) ?: extra
}
