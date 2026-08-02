package com.abdullah.visionbridge.capture

import kotlin.math.abs

/**
 * A frame reduced to a small luminance grid, which is all the tracker needs and small enough to
 * search exhaustively for motion.
 *
 * Deliberately free of Android types so the tracking rules can be exercised against constructed
 * scenes — a page that shifts, a page that is replaced — without a device.
 */
class FrameSignature(
    val width: Int,
    val height: Int,
    val luminance: IntArray,
) {
    init {
        require(width > 0 && height > 0) { "signature must have a positive size" }
        require(luminance.size == width * height) {
            "expected ${width * height} samples, got ${luminance.size}"
        }
    }

    operator fun get(x: Int, y: Int): Int = luminance[y * width + x]

    /**
     * Compares this signature with [other] after shifting [other] by ([shiftX], [shiftY]) cells,
     * measuring only where the two actually overlap.
     *
     * Measuring the overlap is the point. A page that moved half a cell to the left is the same
     * page; comparing it index by index, as the previous detector did, reports that most of the
     * frame changed and throws the reading away.
     */
    fun compareShifted(other: FrameSignature, shiftX: Int, shiftY: Int): Overlap {
        val startX = maxOf(0, -shiftX)
        val endX = minOf(width, other.width - shiftX)
        val startY = maxOf(0, -shiftY)
        val endY = minOf(height, other.height - shiftY)
        if (startX >= endX || startY >= endY) return Overlap(0, 0.0, 0.0)

        var total = 0L
        var changed = 0
        var count = 0
        for (y in startY until endY) {
            for (x in startX until endX) {
                val delta = abs(this[x, y] - other[x + shiftX, y + shiftY])
                total += delta
                if (delta >= CELL_CHANGE_THRESHOLD) changed++
                count++
            }
        }
        return Overlap(
            cells = count,
            meanAbsoluteDifference = total.toDouble() / count,
            changedCellRatio = changed.toDouble() / count,
        )
    }

    data class Overlap(
        val cells: Int,
        val meanAbsoluteDifference: Double,
        val changedCellRatio: Double,
    )

    companion object {
        /** Luminance steps below this are camera noise rather than a changed cell. */
        const val CELL_CHANGE_THRESHOLD = 18

        /**
         * 32 cells on a side. The previous 24 made one cell about 45 px on a 1080-wide capture,
         * which is less than a steady hand drifts between frames, so the smallest correctable
         * motion was already larger than the tremor it needed to correct for.
         */
        const val GRID = 32
    }
}
