package com.abdullah.visionbridge.data.vision

import android.graphics.Bitmap
import com.abdullah.visionbridge.data.diagnostics.DiagnosticHub
import com.abdullah.visionbridge.data.diagnostics.DiagnosticTrace
import com.abdullah.visionbridge.data.localvlm.LocalVlmEngine
import com.abdullah.visionbridge.data.localvlm.LocalVlmModelStore
import com.abdullah.visionbridge.domain.model.AnalysisMode
import com.abdullah.visionbridge.domain.model.AnalysisResult
import com.abdullah.visionbridge.domain.model.CaptureProfile
import com.abdullah.visionbridge.domain.model.SceneDescriptionStyle
import com.abdullah.visionbridge.domain.repository.SettingsRepository
import com.abdullah.visionbridge.domain.repository.VisionAiRepository
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first

/**
 * Sends both Read and Describe to whichever engine the user has selected.
 *
 * The switch is read per request rather than cached, so toggling it takes effect
 * on the next captured frame without restarting capture.
 *
 * There is deliberately no silent fallback from local to cloud. Choosing the
 * on-device engine is frequently a choice about where screen content goes, and
 * quietly uploading a frame because a model file was missing would break that
 * expectation without the user ever being told. A missing or unloadable model
 * raises an actionable error instead, which the coordinator speaks.
 */
class RoutingVisionRepository(
    private val cloud: VisionAiRepository,
    private val local: VisionAiRepository,
    private val localEngine: LocalVlmEngine,
    private val modelStore: LocalVlmModelStore,
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
        val useLocal = settingsRepository.settings.first().useLocalVlm

        DiagnosticHub.record(
            "VISION_ENGINE_SELECTED",
            trace.fieldsOrEmpty(
                mapOf(
                    "engine" to if (useLocal) "LOCAL_VLM" else "GEMINI_CLOUD",
                    "mode" to mode.name,
                    "localModelInstalled" to modelStore.isReady,
                    "localModelLoaded" to localEngine.isLoaded,
                ),
            ),
        )

        if (!useLocal) {
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

        if (!modelStore.isReady) throw LocalVlmEngine.NotInstalledException()

        return local.analyzeStreaming(
            bitmap = bitmap,
            mode = mode,
            model = model,
            apiKey = apiKey,
            forceCellular = forceCellular,
            sceneDescriptionStyle = sceneDescriptionStyle,
            captureProfile = captureProfile,
            // The trust gate compares Gemini's Latin tokens against ML Kit output
            // from the same frame. It is a cloud-specific check and does not apply
            // to a local transcription, which never leaves the device.
            trustGateEnabled = false,
            onSpeechChunk = onSpeechChunk,
        )
    }

    private fun DiagnosticTrace?.fieldsOrEmpty(
        extra: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> = this?.fields(extra) ?: extra
}
