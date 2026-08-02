package com.abdullah.visionbridge.domain.model

data class AppSettings(
    val mode: AnalysisMode = AnalysisMode.TEXT_READING,
    val model: String = DEFAULT_MODEL,
    val forceCellular: Boolean = false,
    val speechEnabled: Boolean = true,
    val trustGateEnabled: Boolean = false,
    val captureProfile: CaptureProfile = CaptureProfile.STABLE,
    val interruptSpeechOnVisualChange: Boolean = false,
    val sceneDescriptionStyle: SceneDescriptionStyle = SceneDescriptionStyle.COMPREHENSIVE,
    /** Read text with the on-device PP-OCR engine instead of Gemini. Describing stays cloud-only. */
    val useLocalOcr: Boolean = false,
    /** How much of the captured image the on-device reader uses. Speed against fine print. */
    val localReadingQuality: LocalReadingQuality = LocalReadingQuality.BALANCED,
    /**
     * Store the actual frame behind a failure in the diagnostic bundle.
     *
     * Off, and meant to be switched on only while reproducing a specific problem. Without pixels a
     * page that was not read cannot be told apart from a page that was read and discarded, and those
     * need opposite repairs — but a frame is whatever the user was looking at, so this is their
     * decision to make each time, not a default.
     */
    val captureFailureEvidence: Boolean = false,
    val speechRate: Float = 1.0f,
    val frameIntervalMs: Long = 700L,
    val cloudOcrIntervalMs: Long = 2_500L,
    val sceneIntervalMs: Long = 3_000L,
) {
    companion object {
        const val DEFAULT_MODEL = "gemini-3.6-flash"
        const val MIN_SPEECH_RATE = 0.6f
        const val MAX_SPEECH_RATE = 1.8f
        val SUPPORTED_MODELS = listOf(
            "gemini-3.6-flash",
            "gemini-3.5-flash",
            "gemini-3.5-flash-lite",
            "gemini-3.1-flash-lite",
        )
    }
}
