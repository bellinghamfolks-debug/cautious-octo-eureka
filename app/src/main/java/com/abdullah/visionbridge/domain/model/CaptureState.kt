package com.abdullah.visionbridge.domain.model

data class CaptureState(
    val isRunning: Boolean = false,
    val isProcessing: Boolean = false,
    val status: String = "جاهز",
    val lastResult: AnalysisResult? = null,
    val error: String? = null,
    /**
     * What the transport is doing, or null before the first answer of a session.
     *
     * The app could always say whether it was running; it could never say how it was running, which
     * is the part that decides whether the text can be trusted and how long it takes to arrive.
     */
    val live: LiveStatus? = null,
)
