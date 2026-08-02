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
    fun pageDegrees(lines: List<TextBox>): Float {
        if (lines.size < MIN_LINES_FOR_PAGE_SKEW) return 0f

        val n = lines.size
        var meanX = 0.0
        var meanY = 0.0
        for (box in lines) {
            meanX += box.centerX.toDouble()
            meanY += box.centerY.toDouble()
        }
        meanX /= n
        meanY /= n

        var covariance = 0.0
        var varianceX = 0.0
        var varianceY = 0.0
        for (box in lines) {
            val dx = box.centerX - meanX
            val dy = box.centerY - meanY
            covariance += dx * dy
            varianceX += dx * dx
            varianceY += dy * dy
        }
        if (varianceY < 1e-3 || varianceX < 1e-3) return 0f

        val correlation = covariance / kotlin.math.sqrt(varianceX * varianceY)
        if (abs(correlation) < MIN_CORRELATION) return 0f

        // How far the stack leans horizontally as it descends, negated because the returned value
        // is the correction to apply, not the tilt observed. Verified against a page tilted a known
        // four degrees: the stack leans -4.02, so the crop must be turned +4.02 to come level.
        val lean = covariance / varianceY
        val angle = -Math.toDegrees(atan2(lean, 1.0)).toFloat()
        return if (abs(angle) < MIN_CORRECTABLE_DEGREES || abs(angle) > MAX_CORRECTABLE_DEGREES) {
            0f
        } else {
            angle
        }
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
