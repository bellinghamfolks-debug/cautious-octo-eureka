package com.abdullah.visionbridge.domain.repository

import android.graphics.Bitmap
import com.abdullah.visionbridge.domain.model.AnalysisMode
import com.abdullah.visionbridge.domain.model.AnalysisResult

interface VisionAiRepository {
    suspend fun analyze(
        bitmap: Bitmap,
        mode: AnalysisMode,
        model: String,
        apiKey: String,
        forceCellular: Boolean,
    ): AnalysisResult
}
