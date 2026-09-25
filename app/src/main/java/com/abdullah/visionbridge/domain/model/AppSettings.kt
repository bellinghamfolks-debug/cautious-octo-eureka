package com.abdullah.visionbridge.domain.model

data class AppSettings(
    val mode: AnalysisMode = AnalysisMode.TEXT_READING,
    /** Single current Gemini identity. There is no user-selectable legacy model path. */
    val model: String = CURRENT_FRAME_MODEL,
    /**
     * Per-request cellular sockets and DNS; never changes process routing.
     *
     * A constant in this build: there is no control for it and the repository does not persist it,
     * because the live socket is held open across requests and a per-request binding cannot cover
     * it. Kept as a field because [com.abdullah.visionbridge.data.gemini.LiveTransportRouting]
     * still reads it, and it names the one condition that would take a frame off the live lane.
     */
    val forceCellular: Boolean = false,
    val speechEnabled: Boolean = true,
    /**
     * Strict verification on the per-frame request path: the model states its own confidence and
     * legibility before any text, and an uncertain reading is refused.
     *
     * A constant in this build. The repository neither stores nor accepts it, so nothing can turn
     * it on, and the path it governs is now only the fallback that runs when the live socket cannot
     * answer. It stays a field rather than being inlined because the verification machinery reads
     * it in a dozen places and flattening that is a change to the reading core, not to the UI.
     */
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
    /** Add one contextual sentence only after a grounded text reading. */
    val describeAlongsideText: Boolean = false,
    /** How much of the captured image the on-device reader uses. Speed against fine print. */
    val localReadingQuality: LocalReadingQuality = LocalReadingQuality.AUTO,
    /** Which mirrored-screen viewport is sent to OCR/Gemini. */
    val viewportMode: ViewportMode = ViewportMode.ESIGHT_TEXT_SAFE,
    /** Store the actual frame behind a failure in the diagnostic bundle. */
    val captureFailureEvidence: Boolean = false,
    val speechRate: Float = 1.0f,
) {
    companion object {
        const val CURRENT_FRAME_MODEL = "gemini-3.6-flash"
        const val FRAME_MODEL_LABEL = "Gemini 3.6 Flash"
        /** Compatibility surface for old UI/callers. It contains the current model only. */
        val SUPPORTED_MODELS = listOf(CURRENT_FRAME_MODEL)
        const val MIN_SPEECH_RATE = 0.6f
        const val MAX_SPEECH_RATE = 1.8f
    }
}
