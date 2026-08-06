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
     * A region the detector found and this stage threw away, described well enough to argue with.
     *
     * Coordinates are fractions of the map rather than pixels, so a bundle read months later does
     * not need to know what resolution the frame was detected at to know where on the screen the
     * thing was.
     */
    data class RejectedRegion(
        val reason: String,
        val widthFraction: Float,
        val heightFraction: Float,
        val centreXFraction: Float,
        val centreYFraction: Float,
        val score: Float,
    )

    /**
     * What became of every region above the binary threshold.
     *
     * This exists because of a question a diagnostic bundle could not answer: a large, clear English
     * word was not read, and the timeline showed only that no box covered it. "The detector never
     * saw it" and "the detector saw it and this stage discarded it" need opposite repairs, and
     * nothing distinguished them, so the fix was guesswork. Four `continue` statements were silently
     * deciding the outcome; now each one is counted, and the biggest thing any of them dropped is
     * described.
     */
    data class Census(
        val regionsFound: Int,
        val accepted: Int,
        val rejectedTooSmall: Int,
        val rejectedTooTall: Int,
        val rejectedLowScore: Int,
        val rejectedDegenerate: Int,
        /** The largest region discarded, by map area. A big one is the case worth explaining. */
        val largestRejected: RejectedRegion?,
        /** Accepted heights in source pixels, which is what the resolution controller solves on. */
        val acceptedHeightP10: Int,
        val acceptedHeightMedian: Int,
        val acceptedHeightP90: Int,
    ) {
        fun fields(): Map<String, Any?> = mapOf(
            "regionsFound" to regionsFound,
            "accepted" to accepted,
            "rejectedTooSmall" to rejectedTooSmall,
            "rejectedTooTall" to rejectedTooTall,
            "rejectedLowScore" to rejectedLowScore,
            "rejectedDegenerate" to rejectedDegenerate,
            "largestRejectedReason" to largestRejected?.reason,
            "largestRejectedWidthFraction" to largestRejected?.widthFraction,
            "largestRejectedHeightFraction" to largestRejected?.heightFraction,
            "largestRejectedCentreX" to largestRejected?.centreXFraction,
            "largestRejectedCentreY" to largestRejected?.centreYFraction,
            "largestRejectedScore" to largestRejected?.score,
            "acceptedHeightP10" to acceptedHeightP10,
            "acceptedHeightMedian" to acceptedHeightMedian,
            "acceptedHeightP90" to acceptedHeightP90,
            "binaryThreshold" to BINARY_THRESHOLD,
            "boxScoreThreshold" to BOX_SCORE_THRESHOLD,
        )

        companion object {
            val EMPTY = Census(0, 0, 0, 0, 0, 0, null, 0, 0, 0)
        }
    }

    data class Detection(val boxes: List<TextBox>, val census: Census)

    /** The boxes alone, for callers that do not report on what was discarded. */
    fun extractBoxes(
        probability: FloatArray,
        mapWidth: Int,
        mapHeight: Int,
        scaleX: Float,
        scaleY: Float,
        sourceWidth: Int,
        sourceHeight: Int,
    ): List<TextBox> =
        extract(probability, mapWidth, mapHeight, scaleX, scaleY, sourceWidth, sourceHeight).boxes

    /**
     * @param probability row-major probability map of size [mapWidth] x [mapHeight].
     * @param scaleX maps map coordinates back to source-image pixels.
     */
    fun extract(
        probability: FloatArray,
        mapWidth: Int,
        mapHeight: Int,
        scaleX: Float,
        scaleY: Float,
        sourceWidth: Int,
        sourceHeight: Int,
    ): Detection {
        if (mapWidth <= 0 || mapHeight <= 0) return Detection(emptyList(), Census.EMPTY)
        require(probability.size >= mapWidth * mapHeight) {
            "probability map smaller than its declared size"
        }

        val visited = BooleanArray(mapWidth * mapHeight)
        val boxes = mutableListOf<TextBox>()
        val stack = ArrayDeque<Int>()

        var regionsFound = 0
        var tooSmall = 0
        var tooTall = 0
        var lowScore = 0
        var degenerate = 0
        var largestRejected: RejectedRegion? = null
        var largestRejectedArea = 0

        fun reject(
            reason: String,
            minX: Int,
            minY: Int,
            width: Int,
            height: Int,
            score: Float,
        ) {
            val area = width * height
            if (area <= largestRejectedArea) return
            largestRejectedArea = area
            largestRejected = RejectedRegion(
                reason = reason,
                widthFraction = width.toFloat() / mapWidth,
                heightFraction = height.toFloat() / mapHeight,
                centreXFraction = (minX + width / 2f) / mapWidth,
                centreYFraction = (minY + height / 2f) / mapHeight,
                score = score,
            )
        }

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
            regionsFound++
            val score = (sum / count).toFloat()
            if (regionWidth < MIN_REGION_SIDE || regionHeight < MIN_REGION_SIDE) {
                tooSmall++
                reject("below_minimum_side", minX, minY, regionWidth, regionHeight, score)
                continue
            }
            // A scrollbar, a table rule or a window divider is a text-like blob to a detector, and
            // because it spans the whole screen it lands on every row at once and drags unrelated
            // rows together. No glyph is this much taller than it is wide — a bare Arabic alif is
            // about five to one — so the bar is well clear of anything real.
            if (regionHeight > regionWidth * MAX_HEIGHT_TO_WIDTH_RATIO) {
                tooTall++
                reject("taller_than_wide_limit", minX, minY, regionWidth, regionHeight, score)
                continue
            }

            if (score < BOX_SCORE_THRESHOLD) {
                lowScore++
                reject("mean_probability_below_threshold", minX, minY, regionWidth, regionHeight, score)
                continue
            }

            val grow = (min(regionWidth, regionHeight) * UNCLIP_RATIO).roundToInt()
            val left = ((minX - grow) * scaleX).roundToInt().coerceIn(0, sourceWidth)
            val top = ((minY - grow) * scaleY).roundToInt().coerceIn(0, sourceHeight)
            val right = ((maxX + 1 + grow) * scaleX).roundToInt().coerceIn(0, sourceWidth)
            val bottom = ((maxY + 1 + grow) * scaleY).roundToInt().coerceIn(0, sourceHeight)
            if (right - left < 2 || bottom - top < 2) {
                degenerate++
                reject("empty_after_scaling", minX, minY, regionWidth, regionHeight, score)
                continue
            }

            boxes += TextBox(left, top, right, bottom, score)
        }

        val heights = boxes.map { it.bottom - it.top }.sorted()
        return Detection(
            boxes = boxes,
            census = Census(
                regionsFound = regionsFound,
                accepted = boxes.size,
                rejectedTooSmall = tooSmall,
                rejectedTooTall = tooTall,
                rejectedLowScore = lowScore,
                rejectedDegenerate = degenerate,
                largestRejected = largestRejected,
                acceptedHeightP10 = heights.percentile(0.10),
                acceptedHeightMedian = heights.percentile(0.50),
                acceptedHeightP90 = heights.percentile(0.90),
            ),
        )
    }

    private fun List<Int>.percentile(fraction: Double): Int {
        if (isEmpty()) return 0
        val index = ((size - 1) * fraction).roundToInt().coerceIn(0, size - 1)
        return this[index]
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
