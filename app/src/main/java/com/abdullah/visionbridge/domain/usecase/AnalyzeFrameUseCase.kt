package com.abdullah.visionbridge.domain.usecase

import android.graphics.Bitmap
import com.abdullah.visionbridge.domain.model.AnalysisMode
import com.abdullah.visionbridge.domain.model.AnalysisResult
import com.abdullah.visionbridge.domain.model.CaptureProfile
import com.abdullah.visionbridge.domain.model.SceneDescriptionStyle
import com.abdullah.visionbridge.domain.repository.VisionAiRepository

internal const val LOW_LATENCY_VISION_MODEL = "gemini-3.5-flash-lite"

/**
 * VisionBridge routes by user intent rather than blindly using the largest model everywhere.
 * FAST_TEXT and BRIEF scene description are latency lanes and always use Flash-Lite. Stable text
 * and comprehensive scene analysis keep the selected model, whose default is Gemini 3.8 Flash.
 */
internal fun selectVisionModel(
    requestedModel: String,
    mode: AnalysisMode,
    captureProfile: CaptureProfile,
    sceneDescriptionStyle: SceneDescriptionStyle,
    trustGateEnabled: Boolean,
): String = when {
    mode == AnalysisMode.TEXT_READING && captureProfile == CaptureProfile.FAST_TEXT ->
        LOW_LATENCY_VISION_MODEL
    mode == AnalysisMode.SCENE_DESCRIPTION &&
        sceneDescriptionStyle == SceneDescriptionStyle.BRIEF -> LOW_LATENCY_VISION_MODEL
    else -> requestedModel
}

class AnalyzeFrameUseCase(private val repository: VisionAiRepository) {
    suspend operator fun invoke(
        bitmap: Bitmap,
        mode: AnalysisMode,
        model: String,
        apiKey: String,
        forceCellular: Boolean,
        sceneDescriptionStyle: SceneDescriptionStyle,
        captureProfile: CaptureProfile,
        trustGateEnabled: Boolean,
        onSpeechChunk: suspend (text: String, urgent: Boolean) -> Unit,
    ): AnalysisResult = repository.analyzeStreaming(
        bitmap = bitmap,
        mode = mode,
        model = selectVisionModel(
            requestedModel = model,
            mode = mode,
            captureProfile = captureProfile,
            sceneDescriptionStyle = sceneDescriptionStyle,
            trustGateEnabled = trustGateEnabled,
        ),
        apiKey = apiKey,
        forceCellular = forceCellular,
        sceneDescriptionStyle = sceneDescriptionStyle,
        captureProfile = captureProfile,
        trustGateEnabled = trustGateEnabled,
        onSpeechChunk = onSpeechChunk,
    )
}
