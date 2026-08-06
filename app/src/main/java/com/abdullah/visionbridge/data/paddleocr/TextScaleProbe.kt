package com.abdullah.visionbridge.data.paddleocr

import com.abdullah.visionbridge.capture.vision.ImagePlane
import kotlin.math.abs

/**
 * Estimates how tall the text in a frame is, without recognising any of it.
 *
 * [AdaptiveReadingScale] needs a text height to solve for the right resolution, and after a
 * detection pass it has one for free. Two cases have no such measurement: the first frame of a new
 * subject, and cloud reading, where nothing local ever detects anything. Guessing in either case
 * means starting at the wrong resolution and spending frames climbing to the right one — which on a
 * distant sign is the difference between reading it and not.
 *
 * The measurement is direct rather than inferred from a constant. Horizontal text produces bands of
 * ink separated by bands of paper, so the profile of ink density down the frame is a square wave
 * whose high runs *are* the text lines. Their median height is the answer, in the working plane's
 * pixels, and scales back to the source by the ratio of the two.
 *
 * It is deliberately conservative: it reports nothing rather than a number it does not believe.
 * A wrong estimate is worse than none, because none falls back to a search that terminates.
 */
object TextScaleProbe {

    /**
     * [confidence] is how square the wave was — how cleanly the frame separated into ink bands and
     * paper gaps. Low confidence means the frame is a photograph rather than a page.
     */
    data class Estimate(
        val textHeightPixels: Float,
        val lineCount: Int,
        val confidence: Float,
    )

    /**
     * Estimates the median text line height in [plane]'s own pixels.
     *
     * @param sourceScale multiply the result by this to express it in the original capture's
     *   pixels, which is what the controller solves in.
     */
    fun estimate(plane: ImagePlane, sourceScale: Float = 1f): Estimate? {
        if (plane.width < MIN_SIZE || plane.height < MIN_SIZE) return null

        // Ink is where the image changes horizontally. Text has far more horizontal structure than
        // any natural surface, which is what makes this separable at all.
        val profile = FloatArray(plane.height)
        var total = 0.0
        for (y in 0 until plane.height) {
            var row = 0f
            for (x in 1 until plane.width - 1) {
                val gradient = abs(plane.luma(x + 1, y) - plane.luma(x - 1, y))
                if (gradient >= EDGE_THRESHOLD) row += 1f
            }
            profile[y] = row
            total += row
        }
        if (total <= 0.0) return null

        val peak = profile.max()
        if (peak < plane.width * MIN_PEAK_DENSITY) return null
        // Halfway between quiet and loud. Text pages are strongly bimodal here, which is exactly
        // why the threshold does not need to be clever.
        val threshold = peak * BAND_THRESHOLD

        val bands = ArrayList<Int>()
        var run = 0
        var inkRows = 0
        for (y in 0 until plane.height) {
            if (profile[y] >= threshold) {
                run++
                inkRows++
            } else {
                if (run in MIN_BAND..MAX_BAND) bands.add(run)
                run = 0
            }
        }
        if (run in MIN_BAND..MAX_BAND) bands.add(run)

        if (bands.size < MIN_BANDS) return null
        // Ink everywhere is a photograph, not a page: there are no gaps to have measured.
        val inkFraction = inkRows.toFloat() / plane.height
        if (inkFraction > MAX_INK_FRACTION) return null

        bands.sort()
        val median = bands[bands.size / 2].toFloat()

        // How consistent the bands were. Real text lines are near enough the same height; a random
        // spread means the bands were not lines.
        val spread = bands.map { abs(it - median) }.average().toFloat()
        val confidence = (1f - (spread / median).coerceIn(0f, 1f)) *
            (1f - (inkFraction / MAX_INK_FRACTION).coerceIn(0f, 1f))
        if (confidence < MIN_CONFIDENCE) return null

        return Estimate(
            textHeightPixels = median * sourceScale,
            lineCount = bands.size,
            confidence = confidence,
        )
    }

    private const val MIN_SIZE = 32

    /** Luminance step that counts as an edge, on a 0..255 plane. */
    private const val EDGE_THRESHOLD = 18f

    /** A row needs this fraction of its width in edges before the frame is worth profiling. */
    private const val MIN_PEAK_DENSITY = 0.04f

    private const val BAND_THRESHOLD = 0.45f

    /** A one-pixel band is noise; a band taller than this is a picture, not a line of text. */
    private const val MIN_BAND = 2
    private const val MAX_BAND = 120

    private const val MIN_BANDS = 3
    private const val MAX_INK_FRACTION = 0.72f
    private const val MIN_CONFIDENCE = 0.25f
}
