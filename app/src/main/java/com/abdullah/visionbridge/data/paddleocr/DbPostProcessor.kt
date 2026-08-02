package com.abdullah.visionbridge.data.paddleocr

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Turns PP-OCR's differentiable-binarization probability map into text boxes.
 *
 * The detector outputs one float per pixel: the probability that the pixel is inside a text
 * region. Everything after that is ordinary connected-component work, which is why it lives here
 * in pure Kotlin instead of pulling OpenCV into the build for two operations.
 *
 * Two details matter for accuracy and are easy to get wrong:
 *
 *  * regions are grown outward before cropping, because DB deliberately shrinks its masks and a
 *    tight crop cuts the ascenders and descenders the recognizer needs;
 *  * a region's score is the mean probability inside it, not its peak, so a few bright pixels of
 *    noise cannot pass as a line of text.
 */
object DbPostProcessor {

    /** Pixels above this probability are considered text. */
    const val BINARY_THRESHOLD = 0.3f

    /** Regions whose mean probability falls below this are discarded as noise. */
    const val BOX_SCORE_THRESHOLD = 0.5f

    /** Regions thinner or shorter than this many pixels in the map cannot be a text line. */
    private const val MIN_REGION_SIDE = 3

    /** How far to grow each region, as a fraction of its shorter side. */
    private const val UNCLIP_RATIO = 0.4f

    /** Above this height-to-width ratio a region is a rule or a scrollbar, not a line of text. */
    private const val MAX_HEIGHT_TO_WIDTH_RATIO = 12

    /**
     * @param probability row-major probability map of size [mapWidth] x [mapHeight].
     * @param scaleX maps map coordinates back to source-image pixels.
     */
    fun extractBoxes(
        probability: FloatArray,
        mapWidth: Int,
        mapHeight: Int,
        scaleX: Float,
        scaleY: Float,
        sourceWidth: Int,
        sourceHeight: Int,
    ): List<TextBox> {
        if (mapWidth <= 0 || mapHeight <= 0) return emptyList()
        require(probability.size >= mapWidth * mapHeight) {
            "probability map smaller than its declared size"
        }

        val visited = BooleanArray(mapWidth * mapHeight)
        val boxes = mutableListOf<TextBox>()
        val stack = ArrayDeque<Int>()

        for (start in 0 until mapWidth * mapHeight) {
            if (visited[start] || probability[start] < BINARY_THRESHOLD) continue

            // Iterative flood fill: a full-screen paragraph can be tens of thousands of pixels and
            // recursion would overflow the stack on exactly the dense pages this app exists for.
            var minX = Int.MAX_VALUE
            var minY = Int.MAX_VALUE
            var maxX = Int.MIN_VALUE
            var maxY = Int.MIN_VALUE
            var sum = 0.0
            var count = 0

            stack.addLast(start)
            visited[start] = true
            while (stack.isNotEmpty()) {
                val index = stack.removeLast()
                val x = index % mapWidth
                val y = index / mapWidth
                minX = min(minX, x); maxX = max(maxX, x)
                minY = min(minY, y); maxY = max(maxY, y)
                sum += probability[index]
                count++

                if (x > 0) push(stack, visited, probability, index - 1)
                if (x < mapWidth - 1) push(stack, visited, probability, index + 1)
                if (y > 0) push(stack, visited, probability, index - mapWidth)
                if (y < mapHeight - 1) push(stack, visited, probability, index + mapWidth)
            }

            val regionWidth = maxX - minX + 1
            val regionHeight = maxY - minY + 1
            if (regionWidth < MIN_REGION_SIDE || regionHeight < MIN_REGION_SIDE) continue
            // A scrollbar, a table rule or a window divider is a text-like blob to a detector, and
            // because it spans the whole screen it lands on every row at once and drags unrelated
            // rows together. No glyph is this much taller than it is wide — a bare Arabic alif is
            // about five to one — so the bar is well clear of anything real.
            if (regionHeight > regionWidth * MAX_HEIGHT_TO_WIDTH_RATIO) continue

            val score = (sum / count).toFloat()
            if (score < BOX_SCORE_THRESHOLD) continue

            val grow = (min(regionWidth, regionHeight) * UNCLIP_RATIO).roundToInt()
            val left = ((minX - grow) * scaleX).roundToInt().coerceIn(0, sourceWidth)
            val top = ((minY - grow) * scaleY).roundToInt().coerceIn(0, sourceHeight)
            val right = ((maxX + 1 + grow) * scaleX).roundToInt().coerceIn(0, sourceWidth)
            val bottom = ((maxY + 1 + grow) * scaleY).roundToInt().coerceIn(0, sourceHeight)
            if (right - left < 2 || bottom - top < 2) continue

            boxes += TextBox(left, top, right, bottom, score)
        }
        return boxes
    }

    private fun push(
        stack: ArrayDeque<Int>,
        visited: BooleanArray,
        probability: FloatArray,
        index: Int,
    ) {
        if (!visited[index] && probability[index] >= BINARY_THRESHOLD) {
            visited[index] = true
            stack.addLast(index)
        }
    }
}
