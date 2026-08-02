package com.abdullah.visionbridge.data.paddleocr

import kotlin.math.max
import kotlin.math.min

/**
 * One detected text region, in source-image pixel coordinates.
 *
 * PP-OCR's detector emits arbitrary quadrilaterals because text is often photographed at an angle.
 * Only the axis-aligned bounds plus the average vertical position are needed downstream, so the
 * quad is reduced here rather than carried through the whole pipeline.
 */
data class TextBox(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val confidence: Float,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerY: Float get() = (top + bottom) / 2f
    val centerX: Float get() = (left + right) / 2f

    fun expanded(pixels: Int, maxWidth: Int, maxHeight: Int): TextBox = TextBox(
        left = max(0, left - pixels),
        top = max(0, top - pixels),
        right = min(maxWidth, right + pixels),
        bottom = min(maxHeight, bottom + pixels),
        confidence = confidence,
    )
}

/**
 * Groups detected boxes into visual lines and orders them for reading aloud.
 *
 * Reading order is the whole point of this stage. A screen reader user hears one linear stream, so
 * boxes have to come out top to bottom, and within a line in the direction the text is actually
 * written. Arabic runs right to left, and a mixed line puts an English word inside an Arabic
 * sentence without changing the sentence's direction, so the direction of a line is decided by
 * which script dominates it rather than per box.
 */
object TextLineOrdering {

    /**
     * How much of the shorter of a box and a line must overlap vertically for them to be the same
     * visual line. Generous enough for a line that rises slightly across a photographed page.
     */
    private const val MIN_LINE_OVERLAP = 0.5f

    /**
     * Groups boxes into visual lines by how much they overlap vertically.
     *
     * Comparing centre distances against a tolerance derived from the incoming box's own height was
     * wrong in a way that only showed up on real layouts: a tall box gets a large tolerance, so a
     * heading reached up and swallowed the smaller line above it, and any tall element absorbed
     * whatever it passed. Overlap is symmetric — a big box and a small one have to actually share
     * vertical space — and it handles a rising baseline for free.
     */
    fun groupIntoLines(boxes: List<TextBox>): List<List<TextBox>> {
        if (boxes.isEmpty()) return emptyList()
        val sorted = boxes.sortedBy { it.centerY }
        val lines = mutableListOf<MutableList<TextBox>>()

        for (box in sorted) {
            val line = lines.lastOrNull()
            if (line != null && overlapsLine(box, line)) {
                line += box
            } else {
                lines += mutableListOf(box)
            }
        }
        return lines
    }

    private fun overlapsLine(box: TextBox, line: List<TextBox>): Boolean {
        val lineTop = line.minOf { it.top }
        val lineBottom = line.maxOf { it.bottom }
        val overlap = min(box.bottom, lineBottom) - max(box.top, lineTop)
        val shorter = min(box.height, lineBottom - lineTop).coerceAtLeast(1)
        return overlap >= shorter * MIN_LINE_OVERLAP
    }

    /**
     * Gap between two boxes, as a fraction of their height, below which they are the same word or
     * phrase rather than two separate pieces of text.
     *
     * Ordinary inter-word gaps sit near 0.3 of the text height and gaps between columns are
     * several times it, so 0.8 merges words and letterspaced display type without pulling two
     * columns together.
     */
    private const val SAME_PHRASE_GAP_FRACTION = 0.8f

    /** Boxes must overlap vertically by at least this much of the shorter one to be merged. */
    private const val MERGE_VERTICAL_OVERLAP = 0.5f

    /**
     * Joins boxes that are really one piece of text before anything is recognized.
     *
     * The detector emits a region per connected blob, and on large letterspaced type — a shop sign,
     * a perfume label, a title — every letter is its own blob. Recognizing those one at a time is
     * the worst case for a CTC model: it has no neighbouring glyphs to condition on, so it returns
     * one low-confidence character that the acceptance floor then discards, and the whole word is
     * silently lost. A device bundle showed this plainly: 68% of discarded crops were a single
     * character, and the median accepted crop was two characters long.
     *
     * Merging first also costs less, because one wide crop replaces several narrow ones.
     */
    fun mergeAdjacent(line: List<TextBox>): List<TextBox> {
        if (line.size < 2) return line
        val sorted = line.sortedBy { it.left }
        val merged = mutableListOf(sorted.first())
        for (box in sorted.drop(1)) {
            val previous = merged.last()
            if (belongTogether(previous, box)) {
                merged[merged.lastIndex] = TextBox(
                    left = min(previous.left, box.left),
                    top = min(previous.top, box.top),
                    right = max(previous.right, box.right),
                    bottom = max(previous.bottom, box.bottom),
                    confidence = min(previous.confidence, box.confidence),
                )
            } else {
                merged += box
            }
        }
        return merged
    }

    private fun belongTogether(left: TextBox, right: TextBox): Boolean {
        val overlap = min(left.bottom, right.bottom) - max(left.top, right.top)
        val shorter = min(left.height, right.height).coerceAtLeast(1)
        if (overlap < shorter * MERGE_VERTICAL_OVERLAP) return false
        // Overlapping boxes have a negative gap, which is well inside the threshold.
        val gap = right.left - left.right
        return gap <= shorter * SAME_PHRASE_GAP_FRACTION
    }

    /**
     * Orders one line's boxes. [rightToLeft] comes from the recognized text of the line, not from
     * geometry, because geometry alone cannot tell an Arabic line from an English one.
     */
    fun orderWithinLine(line: List<TextBox>, rightToLeft: Boolean): List<TextBox> =
        orderWithinLineBy(line, rightToLeft) { it }

    /**
     * Orders anything carrying a box, so a box and the text read from it can be ordered together.
     * Reordering boxes and then looking their text up by position is how word order gets scrambled
     * the moment one box in a line fails to recognize.
     */
    fun <T> orderWithinLineBy(
        items: List<T>,
        rightToLeft: Boolean,
        box: (T) -> TextBox,
    ): List<T> = if (rightToLeft) {
        items.sortedByDescending { box(it).centerX }
    } else {
        items.sortedBy { box(it).centerX }
    }
}
