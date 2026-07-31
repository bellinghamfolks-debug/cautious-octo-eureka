package com.abdullah.visionbridge.data.paddleocr

import kotlin.math.abs
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
     * Boxes whose vertical centres are closer than this fraction of their height belong to the
     * same visual line. Generous enough for a line that rises slightly across a photographed page.
     */
    private const val SAME_LINE_HEIGHT_FRACTION = 0.6f

    fun groupIntoLines(boxes: List<TextBox>): List<List<TextBox>> {
        if (boxes.isEmpty()) return emptyList()
        val sorted = boxes.sortedBy { it.centerY }
        val lines = mutableListOf<MutableList<TextBox>>()

        for (box in sorted) {
            val line = lines.lastOrNull()
            val reference = line?.let { current ->
                current.sumOf { it.centerY.toDouble() }.toFloat() / current.size
            }
            val tolerance = box.height * SAME_LINE_HEIGHT_FRACTION
            if (line != null && reference != null && abs(box.centerY - reference) <= tolerance) {
                line += box
            } else {
                lines += mutableListOf(box)
            }
        }
        return lines
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
