package com.abdullah.visionbridge.domain.model

/**
 * How much of the captured image the on-device reader is allowed to use.
 *
 * This is the one real trade in the local engine, so it is the user's to make rather than a
 * constant buried in the pipeline. A phone captures 1220x2712; feeding the detector a 960-pixel
 * long edge throws away eight ninths of the pixels, which is invisible on a menu in large type and
 * fatal for a medicine label, a bank balance, or a sign across a room. Text 18 pixels tall on
 * screen arrives 6 pixels tall at [FAST] — no recognizer can read that, however good it is.
 *
 * Work grows with the pixel count, so the levels below are roughly 1x, 2x and 4x the detection cost
 * of each other. Recognition cost grows only with how much text is found.
 */
enum class LocalReadingQuality(
    /** Long edge the detector sees. The capture is scaled to this before detection. */
    val detectionLongEdge: Int,
    /**
     * Width cap for one line crop after it is scaled to the recognizer's fixed 48-pixel height.
     *
     * A crop wider than this is squeezed horizontally to fit, which distorts connected Arabic
     * letters badly. At 640 a full-width line was being squashed by up to 2.9x, so the caps rise
     * with quality until squashing effectively stops happening.
     */
    val recognitionMaxWidth: Int,
) {
    /** Today's behaviour, unchanged. Best for moving text, subtitles, and large menu type. */
    FAST(detectionLongEdge = 960, recognitionMaxWidth = 640),

    /** Roughly doubles the detector's view. The default: small text becomes readable. */
    BALANCED(detectionLongEdge = 1440, recognitionMaxWidth = 1280),

    /** Everything the capture has. For fine print, distant signs, and dense documents. */
    MAXIMUM(detectionLongEdge = 1920, recognitionMaxWidth = 2048),

    /**
     * Solved per frame instead of chosen.
     *
     * The three levels above are three points on one curve, and the curve has a closed form: a
     * detector finds text reliably at a known height in its own input, so the resolution that puts
     * the text there is `capture x targetHeight / textHeight`. The app measures the text height
     * every frame and evaluates it — see
     * [AdaptiveReadingScale][com.abdullah.visionbridge.data.paddleocr.AdaptiveReadingScale] — so a
     * label held close is read at the speed of FAST and a sign across a room at the reach of
     * MAXIMUM, without anyone deciding which case they are in.
     *
     * The fixed levels stay available. Someone who knows their situation better than a measurement
     * does should be able to say so, and taking that away would be its own regression.
     */
    AUTO(detectionLongEdge = 1440, recognitionMaxWidth = 1600);

    /** True when the resolution is solved per frame rather than fixed here. */
    val adaptive: Boolean get() = this == AUTO

    companion object {
        fun fromStored(value: String?): LocalReadingQuality =
            entries.firstOrNull { it.name == value } ?: AUTO
    }
}
