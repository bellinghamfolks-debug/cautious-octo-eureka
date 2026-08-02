package com.abdullah.visionbridge.capture.vision

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** A corner with the orientation and binary description used to recognise it in another frame. */
class Feature(
    val x: Float,
    val y: Float,
    val score: Float,
    val angle: Float,
    val descriptor: LongArray,
) {
    /** Hamming distance between two 256-bit descriptors. */
    fun distanceTo(other: Feature): Int {
        var total = 0
        for (index in descriptor.indices) {
            total += java.lang.Long.bitCount(descriptor[index] xor other.descriptor[index])
        }
        return total
    }
}

/**
 * FAST-9 corner detection with a Harris ranking, plus an oriented BRIEF descriptor.
 *
 * This is the half of the tracker that does not need the two frames to look alike at all. Lucas-
 * Kanade refines a warp it can already almost see; feature matching finds correspondences from
 * nothing, which is what recovers a lock after a large movement, a big rotation, or a moment of
 * occlusion. Descriptors are steered by each corner's own intensity centroid, so a label read at
 * an angle still matches the same label read upright.
 */
object Features {

    fun detect(
        plane: ImagePlane,
        maximumFeatures: Int = DEFAULT_MAX_FEATURES,
        threshold: Float = DEFAULT_THRESHOLD,
    ): List<Feature> {
        // BRIEF compares single samples, so it reads sensor noise as signal unless the patch is
        // smoothed first. Skipping this is why only 7 of a possible 200 matches survived a 15-degree
        // rotation - below the 8 a homography needs. Detection still runs on the sharp plane,
        // because a corner detector wants the edges left alone.
        val smoothed = boxSmoothed(plane)
        val candidates = ArrayList<IntArray>()
        val scores = ArrayList<Float>()

        for (y in MARGIN until plane.height - MARGIN) {
            for (x in MARGIN until plane.width - MARGIN) {
                if (!isCorner(plane, x, y, threshold)) continue
                candidates.add(intArrayOf(x, y))
                scores.add(harris(plane, x, y))
            }
        }
        if (candidates.isEmpty()) return emptyList()

        // Non-maximum suppression on a coarse grid keeps the set spread over the frame instead of
        // clustered on one high-contrast edge, which is what makes a homography well conditioned.
        val order = candidates.indices.sortedByDescending { scores[it] }
        val claimed = HashSet<Long>()
        val kept = ArrayList<Feature>(maximumFeatures)
        for (index in order) {
            if (kept.size >= maximumFeatures) break
            val (x, y) = candidates[index]
            val cell = (x / SUPPRESSION_CELL).toLong() * 100_000L + (y / SUPPRESSION_CELL).toLong()
            if (!claimed.add(cell)) continue
            val angle = orientation(plane, x, y)
            kept.add(
                Feature(
                    x = x.toFloat(),
                    y = y.toFloat(),
                    score = scores[index],
                    angle = angle,
                    descriptor = describe(smoothed, plane.width, plane.height, x, y, angle),
                ),
            )
        }
        return kept
    }

    /** FAST-9: nine contiguous pixels of the radius-3 circle all brighter, or all darker. */
    private fun isCorner(plane: ImagePlane, x: Int, y: Int, threshold: Float): Boolean {
        val centre = plane.luma(x, y)
        val bright = centre + threshold
        val dark = centre - threshold

        // Cheap rejection on the four compass points before walking the whole ring.
        var brighterCompass = 0
        var darkerCompass = 0
        for (index in intArrayOf(0, 4, 8, 12)) {
            val value = plane.luma(x + CIRCLE_X[index], y + CIRCLE_Y[index])
            if (value > bright) brighterCompass++
            if (value < dark) darkerCompass++
        }
        if (brighterCompass < 3 && darkerCompass < 3) return false

        var brightRun = 0
        var darkRun = 0
        var bestBright = 0
        var bestDark = 0
        // Twice around, so a run that wraps the start of the ring is still counted.
        for (step in 0 until CIRCLE_X.size * 2) {
            val index = step % CIRCLE_X.size
            val value = plane.luma(x + CIRCLE_X[index], y + CIRCLE_Y[index])
            brightRun = if (value > bright) brightRun + 1 else 0
            darkRun = if (value < dark) darkRun + 1 else 0
            if (brightRun > bestBright) bestBright = brightRun
            if (darkRun > bestDark) bestDark = darkRun
            if (bestBright >= CONTIGUOUS || bestDark >= CONTIGUOUS) return true
        }
        return false
    }

    /** Shi-Tomasi-style score: the smaller eigenvalue of the local structure tensor. */
    private fun harris(plane: ImagePlane, x: Int, y: Int): Float {
        var xx = 0f
        var yy = 0f
        var xy = 0f
        for (dy in -HARRIS_WINDOW..HARRIS_WINDOW) {
            for (dx in -HARRIS_WINDOW..HARRIS_WINDOW) {
                val gx = plane.gradientX(x + dx, y + dy)
                val gy = plane.gradientY(x + dx, y + dy)
                xx += gx * gx
                yy += gy * gy
                xy += gx * gy
            }
        }
        val trace = xx + yy
        val determinant = xx * yy - xy * xy
        val discriminant = trace * trace / 4f - determinant
        val root = if (discriminant > 0f) kotlin.math.sqrt(discriminant) else 0f
        return trace / 2f - root
    }

    /** Intensity-centroid orientation, which is what lets the descriptor be rotation invariant. */
    private fun orientation(plane: ImagePlane, x: Int, y: Int): Float {
        var momentX = 0f
        var momentY = 0f
        for (dy in -PATCH..PATCH) {
            for (dx in -PATCH..PATCH) {
                val px = (x + dx).coerceIn(0, plane.width - 1)
                val py = (y + dy).coerceIn(0, plane.height - 1)
                val value = plane.luma(px, py)
                momentX += dx * value
                momentY += dy * value
            }
        }
        return atan2(momentY, momentX)
    }

    /** A 5x5 box blur of luminance, which is the smoothing BRIEF assumes it is sampling from. */
    private fun boxSmoothed(plane: ImagePlane): FloatArray {
        val width = plane.width
        val height = plane.height
        val horizontal = FloatArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var sum = 0f
                for (dx in -2..2) sum += plane.luma((x + dx).coerceIn(0, width - 1), y)
                horizontal[y * width + x] = sum / 5f
            }
        }
        val result = FloatArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var sum = 0f
                for (dy in -2..2) sum += horizontal[(y + dy).coerceIn(0, height - 1) * width + x]
                result[y * width + x] = sum / 5f
            }
        }
        return result
    }

    /** 256-bit oriented BRIEF: the sampling pattern is rotated by the corner's own angle. */
    private fun describe(
        smoothed: FloatArray,
        width: Int,
        height: Int,
        x: Int,
        y: Int,
        angle: Float,
    ): LongArray {
        val cosine = cos(angle)
        val sine = sin(angle)
        val descriptor = LongArray(4)
        for (bit in 0 until 256) {
            val ax = PATTERN[bit * 4]
            val ay = PATTERN[bit * 4 + 1]
            val bx = PATTERN[bit * 4 + 2]
            val by = PATTERN[bit * 4 + 3]
            val rax = (cosine * ax - sine * ay).roundToInt()
            val ray = (sine * ax + cosine * ay).roundToInt()
            val rbx = (cosine * bx - sine * by).roundToInt()
            val rby = (sine * bx + cosine * by).roundToInt()
            val first = smoothed[
                (y + ray).coerceIn(0, height - 1) * width + (x + rax).coerceIn(0, width - 1),
            ]
            val second = smoothed[
                (y + rby).coerceIn(0, height - 1) * width + (x + rbx).coerceIn(0, width - 1),
            ]
            if (first < second) {
                descriptor[bit ushr 6] = descriptor[bit ushr 6] or (1L shl (bit and 63))
            }
        }
        return descriptor
    }

    /**
     * Brute-force matching with Lowe's ratio test and a cross-check.
     *
     * Both filters exist to keep bad correspondences out of the homography fit rather than to be
     * corrected by it: RANSAC survives outliers, but it survives far fewer of them than a
     * repetitive page of text will otherwise produce.
     */
    fun match(from: List<Feature>, to: List<Feature>): List<Match> {
        if (from.isEmpty() || to.isEmpty()) return emptyList()
        val forward = IntArray(from.size) { -1 }
        val forwardDistance = IntArray(from.size) { Int.MAX_VALUE }

        for (i in from.indices) {
            var best = Int.MAX_VALUE
            var second = Int.MAX_VALUE
            var bestIndex = -1
            for (j in to.indices) {
                val distance = from[i].distanceTo(to[j])
                if (distance < best) {
                    second = best
                    best = distance
                    bestIndex = j
                } else if (distance < second) {
                    second = distance
                }
            }
            if (bestIndex >= 0 && best <= MAX_DISTANCE && best < RATIO * second) {
                forward[i] = bestIndex
                forwardDistance[i] = best
            }
        }

        // Cross-check: a match survives only if each side would choose the other.
        val backward = IntArray(to.size) { -1 }
        val backwardDistance = IntArray(to.size) { Int.MAX_VALUE }
        for (i in from.indices) {
            val j = forward[i]
            if (j < 0) continue
            if (forwardDistance[i] < backwardDistance[j]) {
                backwardDistance[j] = forwardDistance[i]
                backward[j] = i
            }
        }

        val matches = ArrayList<Match>()
        for (i in from.indices) {
            val j = forward[i]
            if (j >= 0 && backward[j] == i) matches.add(Match(from[i], to[j], forwardDistance[i]))
        }
        return matches
    }

    data class Match(val from: Feature, val to: Feature, val distance: Int)

    private const val CONTIGUOUS = 9

    /** Wide enough that a rotated descriptor patch still lies inside the plane. */
    private const val MARGIN = 13
    private const val PATCH = 7

    /** Sampling radius of the descriptor. Too small and every corner of a text page looks alike. */
    private const val DESCRIPTOR_RADIUS = 9
    private const val HARRIS_WINDOW = 2
    private const val SUPPRESSION_CELL = 6
    private const val DEFAULT_MAX_FEATURES = 220
    private const val DEFAULT_THRESHOLD = 12f
    private const val RATIO = 0.82f
    private const val MAX_DISTANCE = 96

    private val CIRCLE_X = intArrayOf(0, 1, 2, 3, 3, 3, 2, 1, 0, -1, -2, -3, -3, -3, -2, -1)
    private val CIRCLE_Y = intArrayOf(-3, -3, -2, -1, 0, 1, 2, 3, 3, 3, 2, 1, 0, -1, -2, -3)

    /**
     * The BRIEF sampling pattern: 256 point pairs drawn once from a fixed seed.
     *
     * Generated deterministically rather than stored as a literal table, so the pattern is
     * identical on every device and in every test without carrying a thousand numbers in source.
     */
    private val PATTERN: IntArray = buildPattern()

    private fun buildPattern(): IntArray {
        val values = IntArray(256 * 4)
        // A 64-bit linear congruential generator, drawn once per coordinate so consecutive samples
        // are independent. The earlier version reused overlapping slices of one xorshift word,
        // which correlated the two ends of every pair and cost the descriptor most of its power.
        var state = 0x2545F4914F6CDD1DUL.toLong()
        fun uniform(bound: Int): Int {
            state = state * 6364136223846793005L + 1442695040888963407L
            val bits = (state ushr 33).toInt() and Int.MAX_VALUE
            return bits % bound
        }
        // Two uniforms summed give a triangular spread, which concentrates pairs near the centre of
        // the patch where a corner actually carries information.
        fun coordinate(): Int {
            val span = DESCRIPTOR_RADIUS
            val a = uniform(span * 2 + 1) - span
            val b = uniform(span * 2 + 1) - span
            return ((a + b) / 2).coerceIn(-span, span)
        }

        for (bit in 0 until 256) {
            val base = bit * 4
            var ax: Int
            var ay: Int
            var bx: Int
            var by: Int
            var guard = 0
            do {
                ax = coordinate(); ay = coordinate(); bx = coordinate(); by = coordinate()
                guard++
                // A pair of identical points is a constant bit and carries nothing.
            } while (ax == bx && ay == by && guard < 16)
            values[base] = ax
            values[base + 1] = ay
            values[base + 2] = bx
            values[base + 3] = by
        }
        return values
    }
}
