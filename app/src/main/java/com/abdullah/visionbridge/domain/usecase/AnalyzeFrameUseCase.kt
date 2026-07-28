package com.abdullah.visionbridge.domain.usecase

import android.graphics.Bitmap
import com.abdullah.visionbridge.domain.model.AnalysisMode
import com.abdullah.visionbridge.domain.model.AnalysisResult
import com.abdullah.visionbridge.domain.model.SceneDescriptionStyle
import com.abdullah.visionbridge.domain.repository.VisionAiRepository

class AnalyzeFrameUseCase(private val repository: VisionAiRepository) {
    suspend operator fun invoke(
        bitmap: Bitmap,
        mode: AnalysisMode,
        model: String,
        apiKey: String,
        forceCellular: Boolean,
        sceneDescriptionStyle: SceneDescriptionStyle,
        onSpeechChunk: suspend (text: String, urgent: Boolean) -> Unit,
    ): AnalysisResult = repository.analyzeStreaming(
        bitmap = bitmap,
        mode = mode,
        model = model,
        apiKey = apiKey,
        forceCellular = forceCellular,
        sceneDescriptionStyle = sceneDescriptionStyle,
        onSpeechChunk = onSpeechChunk,
    )
}
