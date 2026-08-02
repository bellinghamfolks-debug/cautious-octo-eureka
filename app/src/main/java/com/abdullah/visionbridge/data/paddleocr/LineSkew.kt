package com.abdullah.visionbridge.data.paddleocr

import kotlin.math.abs
import kotlin.math.atan2

/**
 * Estimates how far a line of text is tilted, so the crop can be straightened before it is read.
 *
 * A page held in the hand is never quite level, and this pipeline reduces every detected region to
 * an axis-aligned rectangle. On a tilted line that rectangle is taller than the text, filled with
 * background and the tops of neighbouring lines, and the glyphs run diagonally across it. Feeding
 * that to a recognizer that expects a horizontal strip costs whole characters: a four degree tilt
 * turned "Rotated document line number 1" into "Rotated dument ineber" on a rendered page.
 *
 * The angle comes from the centres of the boxes making up the line, before they are merged, by
 * least squares. Nothing else in the frame is needed and no extra model pass is involved.
 */
object LineSkew {

    /** Below this there is nothing worth correcting, and rotating would only blur the crop. */
    const val MIN_CORRECTABLE_DEGREES = 1.0f

    /**
     * Above this the boxes are not a tilted line of text — they are a column of separate items that
     * happened to be grouped, and rotating by their slope would make things worse.
     */
    const val MAX_CORRECTABLE_DEGREES = 25.0f

    /** Fewer lines than this cannot establish a page's tilt with any confidence. */
    private const val MIN_LINES_FOR_PAGE_SKEW = 3

    /** How straight the stack of line centres must be before its slope is believed. */
    private const val MIN_CORRELATION = 0.90f

    /**
     * Estimates the rotation that would bring a whole page level, from the stack of its line boxes.
     *
     * Per-line fitting needs several boxes in the line, and the detector usually returns a tilted
     * line as a single blob, leaving nothing to fit. The lines themselves still carry the angle:
     * they are a rigid rotation of a vertical stack, so fitting horizontal position against vertical
     * position across the page recovers it.
     *
     * Pages whose lines are centred or ragged produce a stack that is not straight, so the fit is
     * only believed when the correlation is strong — a menu with lines of wildly different widths
     * must not be rotated on the strength of a coincidence.
     */
    fun pageDegrees(lines: List<TextBox>): Float = estimate(lines).degrees

    /**
     * The same estimate with the reasoning attached, so a diagnostic bundle can be argued with.
     *
     * A device bundle contains two page reads corrected by nearly 24°, on captures of phone UI
     * rather than paper, and there is no way to tell from the events alone whether those were real
     * tilts or a column of separate items that happened to line up. Recording the correlation, the
     * anchor and the line count behind each estimate is what makes that answerable next time.
     */
    fun estimate(lines: List<TextBox>): Estimate {
        if (lines.size < MIN_LINES_FOR_PAGE_SKEW) {
            return Estimate(0f, 0f, lines.size, "too_few_lines", 0f)
        }

        // Three ways a page can be aligned, and only the matching one gives a straight stack.
        // Centres line up when every line is the same width, which is true of a rendered test page
        // and of almost no real document: English prose is ragged on the right and aligns on the
        // left, Arabic is ragged on the left and aligns on the right. Measuring only centres meant
        // no real page was ever straightened, while the test page that suggested it worked was the
        // one layout where centres happen to align.
        val anchors = listOf<Pair<String, (TextBox) -> Float>>(
            "left" to { it.left.toFloat() },
            "right" to { it.right.toFloat() },
            "centre" to { it.centerX },
        )

        var best = 0f
        var bestCorrelation = 0f
        var bestAnchor = "none"
        for ((name, anchor) in anchors) {
            val (angle, correlation) = fit(lines, anchor)
            if (correlation > bestCorrelation) {
                bestCorrelation = correlation
                best = angle
                bestAnchor = name
            }
        }
        val rejected = when {
            bestCorrelation < MIN_CORRELATION -> "correlation_below_floor"
            abs(best) < MIN_CORRECTABLE_DEGREES -> "below_correctable_range"
            abs(best) > MAX_CORRECTABLE_DEGREES -> "above_correctable_range"
            else -> null
        }
        return Estimate(
            degrees = if (rejected == null) best else 0f,
            correlation = bestCorrelation,
            lineCount = lines.size,
            anchor = if (rejected == null) bestAnchor else rejected,
            measuredDegrees = best,
        )
    }

    /**
     * [degrees] is the correction actually applied — zero when the fit was not believed —
     * while [measuredDegrees] is what the fit said before that judgement.
     */
    data class Estimate(
        val degrees: Float,
        val correlation: Float,
        val lineCount: Int,
        val anchor: String,
        val measuredDegrees: Float,
    )

    /** @return the correction angle this anchor implies, and how straight its stack is. */
    private fun fit(lines: List<TextBox>, anchor: (TextBox) -> Float): Pair<Float, Float> {
        val n = lines.size
        var meanX = 0.0
        var meanY = 0.0
        for (box in lines) {
            meanX += anchor(box).toDouble()
            meanY += box.centerY.toDouble()
        }
        meanX /= n
        meanY /= n

        var covariance = 0.0
        var varianceX = 0.0
        var varianceY = 0.0
        for (box in lines) {
            val dx = anchor(box) - meanX
            val dy = box.centerY - meanY
            covariance += dx * dy
            varianceX += dx * dx
            varianceY += dy * dy
        }
        if (varianceY < 1e-3 || varianceX < 1e-3) return 0f to 0f

        val correlation = abs(covariance / kotlin.math.sqrt(varianceX * varianceY)).toFloat()
        // Negated because the returned value is the correction to apply, not the tilt observed.
        // Verified against a page tilted a known four degrees: the stack leans -4.02, so the crop
        // must be turned +4.02 to come level.
        val angle = -Math.toDegrees(atan2(covariance / varianceY, 1.0)).toFloat()
        return angle to correlation
    }

    /**
     * @return the rotation in degrees that would bring [line] level, counter-clockwise positive,
     * or zero when there is too little evidence or too much tilt to trust.
     */
    fun degrees(line: List<TextBox>): Float {
        if (line.size < 2) return 0f

        val n = line.size
        var sumX = 0.0
        var sumY = 0.0
        for (box in line) {
            sumX += box.centerX.toDouble()
            sumY += box.centerY.toDouble()
        }
        val meanX = sumX / n
        val meanY = sumY / n

        var covariance = 0.0
        var varianceX = 0.0
        for (box in line) {
            val dx = box.centerX - meanX
            val dy = box.centerY - meanY
            covariance += dx * dy
            varianceX += dx * dx
        }
        // All the boxes sit in one vertical stack: there is no horizontal run to fit a slope to.
        if (varianceX < 1e-3) return 0f

        // Screen coordinates grow downward, so text descending to the right has a positive slope
        // and must be turned counter-clockwise by that much to come level.
        val slope = covariance / varianceX
        val angle = Math.toDegrees(atan2(slope, 1.0)).toFloat()
        return if (abs(angle) < MIN_CORRECTABLE_DEGREES || abs(angle) > MAX_CORRECTABLE_DEGREES) {
            0f
        } else {
            angle
        }
    }
}
