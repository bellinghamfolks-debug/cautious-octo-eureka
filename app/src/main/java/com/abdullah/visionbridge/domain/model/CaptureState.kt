package com.abdullah.visionbridge.domain.model

data class CaptureState(
    val isRunning: Boolean = false,
    val isProcessing: Boolean = false,
    val status: String = "متوقف",
    val lastResult: AnalysisResult? = null,
    val error: String? = null,
)
