package com.abdullah.visionbridge.domain.model

data class AppSettings(
    val mode: AnalysisMode = AnalysisMode.TEXT_READING,
    /** Single current Gemini identity. There is no user-selectable legacy model path. */
    val model: String = CURRENT_FRAME_MODEL,
    /** Retained for migration/API compatibility. Live-only builds keep this false. */
    val forceCellular: Boolean = false,
    val speechEnabled: Boolean = true,
    /** Legacy cloud trust gate. Live Accuracy Guard is always enabled instead. */
    val trustGateEnabled: Boolean = false,
    val captureProfile: CaptureProfile = CaptureProfile.STABLE,
    /**
     * Smart Target Interruption. When enabled, only a strong motion-compensated target replacement
     * cuts speech immediately. A confirmed but borderline replacement lets the current phrase end.
     * Camera shake, zoom, rotation and lighting changes never interrupt on their own.
     */
    val interruptSpeechOnVisualChange: Boolean = true,
    val sceneDescriptionStyle: SceneDescriptionStyle = SceneDescriptionStyle.COMPREHENSIVE,
    /** Read text with the on-device PP-OCR engine instead of Gemini. Describing stays cloud-only. */
    val useLocalOcr: Boolean = false,
    /** Add one verified sentence about the visible subject after a Gemini Live text reading. */
    val describeAlongsideText: Boolean = false,
    /** How much of the captured image the on-device reader uses. Speed against fine print. */
    val localReadingQuality: LocalReadingQuality = LocalReadingQuality.AUTO,
    /** Which mirrored-screen viewport is sent to OCR/Gemini. */
    val viewportMode: ViewportMode = ViewportMode.ESIGHT_TEXT_SAFE,
    /** Store the actual frame behind a failure in the diagnostic bundle. */
    val captureFailureEvidence: Boolean = false,
    val speechRate: Float = 1.0f,
    val frameIntervalMs: Long = 700L,
    val cloudOcrIntervalMs: Long = 2_500L,
    val sceneIntervalMs: Long = 3_000L,
) {
    companion object {
        const val CURRENT_FRAME_MODEL = "gemini-3.6-flash"
        const val CURRENT_LIVE_MODEL = "gemini-3.1-flash-live-preview"
        const val LIVE_MODEL_LABEL = "Gemini 3.1 Flash Live"
        /** Compatibility surface for old UI/callers. It contains the current model only. */
        val SUPPORTED_MODELS = listOf(CURRENT_LIVE_MODEL)
        const val MIN_SPEECH_RATE = 0.6f
        const val MAX_SPEECH_RATE = 1.8f
    }
}
