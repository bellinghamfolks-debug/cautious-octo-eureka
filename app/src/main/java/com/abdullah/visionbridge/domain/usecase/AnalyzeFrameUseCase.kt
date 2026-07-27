package com.abdullah.visionbridge.domain.usecase

import android.graphics.Bitmap
import com.abdullah.visionbridge.domain.model.AnalysisMode
import com.abdullah.visionbridge.domain.model.AnalysisResult
import com.abdullah.visionbridge.domain.repository.VisionAiRepository

class AnalyzeFrameUseCase(private val repository: VisionAiRepository) {
    suspend operator fun invoke(
        bitmap: Bitmap,
        mode: AnalysisMode,
        model: String,
        apiKey: String,
        forceCellular: Boolean,
    ): AnalysisResult = repository.analyze(bitmap, mode, model, apiKey, forceCellular)
}
