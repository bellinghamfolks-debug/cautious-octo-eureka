package com.abdullah.visionbridge.capture.vision

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Estimates the projective transform between two views of the same plane, from point matches.
 *
 * A homography is the transform a flat subject undergoes when the camera moves anywhere at all —
 * closer, sideways, or to an angle. It is what separates "this page is now being read from the
 * side" from "this is a different page", which no amount of pixel differencing can do.
 *
 * Normalised Direct Linear Transform inside RANSAC: points are conditioned so the fit is not
 * dominated by their distance from the origin, four-point minimal models are drawn until one
 * explains most of the matches, and the winning model is refitted over all of its inliers.
 */
object Homography {

    data class Estimate(
        val warp: Warp,
        val inliers: Int,
        val total: Int,
        val meanInlierError: Double,
    ) {
        val inlierRatio: Double get() = if (total == 0) 0.0 else inliers.toDouble() / total
    }

    /**
     * @param matches correspondences whose `from` points live in the reference frame.
     * @param tolerance reprojection error, in pixels of the level the features were found on.
     */
    fun estimate(
        matches: List<Features.Match>,
        tolerance: Double = DEFAULT_TOLERANCE,
        iterations: Int = DEFAULT_ITERATIONS,
        seed: Long = 0x5DEECE66DL,
    ): Estimate? {
        if (matches.size < MINIMUM_MATCHES) return null

        val fromX = DoubleArray(matches.size) { matches[it].from.x.toDouble() }
        val fromY = DoubleArray(matches.size) { matches[it].from.y.toDouble() }
        val toX = DoubleArray(matches.size) { matches[it].to.x.toDouble() }
        val toY = DoubleArray(matches.size) { matches[it].to.y.toDouble() }

        var bestInliers = 0
        var bestModel: Warp? = null
        var bestError = Double.MAX_VALUE
        // A fixed generator, so the same frames always produce the same answer. A tracker that
        // disagrees with itself between runs cannot be debugged from a diagnostic bundle.
        var state = seed
        fun nextIndex(bound: Int): Int {
            state = (state * 0x5DEECE66DL + 0xB) and ((1L shl 48) - 1)
            return ((state ushr 17) % bound).toInt()
        }

        val sample = IntArray(4)
        // Adaptive: once a model explains most of the matches, the chance that more sampling finds
        // a better one is negligible, and the remaining iterations are pure cost.
        var needed = iterations
        var iteration = 0
        while (iteration < minOf(iterations, needed)) {
            iteration++
            // Four distinct correspondences define a homography exactly.
            var filled = 0
            var guard = 0
            while (filled < 4 && guard < 64) {
                guard++
                val candidate = nextIndex(matches.size)
                if ((0 until filled).none { sample[it] == candidate }) sample[filled++] = candidate
            }
            if (filled < 4) continue

            // The minimal case has a one-dimensional null space, so it is solved directly rather
            // than through the eigenvector routine the general fit uses. That routine ran 48 inverse
            // iterations of a 9x9 solve per sample, which at 400 samples was fourteen million
            // operations and made registration cost 13 ms a frame on its own.
            val model = fitFourPoints(
                DoubleArray(4) { fromX[sample[it]] },
                DoubleArray(4) { fromY[sample[it]] },
                DoubleArray(4) { toX[sample[it]] },
                DoubleArray(4) { toY[sample[it]] },
            ) ?: continue
            if (!model.isPlausible()) continue

            var inliers = 0
            var errorSum = 0.0
            for (index in matches.indices) {
                val mapped = model.apply(fromX[index], fromY[index]) ?: continue
                val error = sqrt(
                    (mapped[0] - toX[index]) * (mapped[0] - toX[index]) +
                        (mapped[1] - toY[index]) * (mapped[1] - toY[index]),
                )
                if (error <= tolerance) {
                    inliers++
                    errorSum += error
                }
            }
            if (inliers > bestInliers || (inliers == bestInliers && errorSum / maxOf(inliers, 1) < bestError)) {
                bestInliers = inliers
                bestModel = model
                bestError = errorSum / maxOf(inliers, 1)
                val ratio = inliers.toDouble() / matches.size
                if (ratio > 0.0 && ratio < 1.0) {
                    val chanceAllInliers = ratio * ratio * ratio * ratio
                    needed = kotlin.math.ceil(
                        kotlin.math.ln(1.0 - CONFIDENCE) / kotlin.math.ln(1.0 - chanceAllInliers),
                    ).toInt().coerceAtLeast(4)
                } else if (ratio >= 1.0) {
                    needed = iteration
                }
            }
        }

        val coarse = bestModel ?: return null
        if (bestInliers < MINIMUM_MATCHES) return null

        // Refit over every inlier of the winning model: the minimal fit was chosen for its
        // agreement, not its accuracy.
        val keepX = ArrayList<Double>(bestInliers)
        val keepY = ArrayList<Double>(bestInliers)
        val targetX = ArrayList<Double>(bestInliers)
        val targetY = ArrayList<Double>(bestInliers)
        for (index in matches.indices) {
            val mapped = coarse.apply(fromX[index], fromY[index]) ?: continue
            val error = sqrt(
                (mapped[0] - toX[index]) * (mapped[0] - toX[index]) +
                    (mapped[1] - toY[index]) * (mapped[1] - toY[index]),
            )
            if (error <= tolerance) {
                keepX.add(fromX[index])
                keepY.add(fromY[index])
                targetX.add(toX[index])
                targetY.add(toY[index])
            }
        }

        val refined = fit(
            keepX.toDoubleArray(),
            keepY.toDoubleArray(),
            targetX.toDoubleArray(),
            targetY.toDoubleArray(),
        )?.takeIf { it.isPlausible() } ?: coarse

        var errorSum = 0.0
        var counted = 0
        for (index in keepX.indices) {
            val mapped = refined.apply(keepX[index], keepY[index]) ?: continue
            errorSum += sqrt(
                (mapped[0] - targetX[index]) * (mapped[0] - targetX[index]) +
                    (mapped[1] - targetY[index]) * (mapped[1] - targetY[index]),
            )
            counted++
        }

        return Estimate(
            warp = refined,
            inliers = bestInliers,
            total = matches.size,
            meanInlierError = if (counted == 0) Double.MAX_VALUE else errorSum / counted,
        )
    }

    /**
     * The exact solution for four correspondences.
     *
     * With eight equations and nine unknowns the null space is one-dimensional, so fixing the last
     * coefficient at one turns the homogeneous system into an ordinary 8x8 solve. A configuration
     * where three of the four points are collinear makes it singular, and the solver says so rather
     * than returning a fold.
     */
    private fun fitFourPoints(
        fromX: DoubleArray,
        fromY: DoubleArray,
        toX: DoubleArray,
        toY: DoubleArray,
    ): Warp? {
        val a = Array(8) { DoubleArray(8) }
        val b = DoubleArray(8)
        for (index in 0 until 4) {
            val x = fromX[index]
            val y = fromY[index]
            val u = toX[index]
            val v = toY[index]
            val row = index * 2
            a[row][0] = x; a[row][1] = y; a[row][2] = 1.0
            a[row][3] = 0.0; a[row][4] = 0.0; a[row][5] = 0.0
            a[row][6] = -u * x; a[row][7] = -u * y
            b[row] = u
            a[row + 1][0] = 0.0; a[row + 1][1] = 0.0; a[row + 1][2] = 0.0
            a[row + 1][3] = x; a[row + 1][4] = y; a[row + 1][5] = 1.0
            a[row + 1][6] = -v * x; a[row + 1][7] = -v * y
            b[row + 1] = v
        }
        val h = LinearAlgebra.solve(a, b) ?: return null
        return Warp(doubleArrayOf(h[0], h[1], h[2], h[3], h[4], h[5], h[6], h[7], 1.0))
    }

    /**
     * Normalised DLT over any number of correspondences (four or more).
     *
     * Each correspondence contributes two rows to a homogeneous system; the solution is the null
     * vector of that system, found through the normal equations rather than a full decomposition.
     */
    fun fit(fromX: DoubleArray, fromY: DoubleArray, toX: DoubleArray, toY: DoubleArray): Warp? {
        val n = fromX.size
        if (n < 4 || fromY.size != n || toX.size != n || toY.size != n) return null

        val source = normalise(fromX, fromY) ?: return null
        val target = normalise(toX, toY) ?: return null

        // Aᵀ A, accumulated row by row so the 2n × 9 matrix is never materialised.
        val normal = Array(9) { DoubleArray(9) }
        val row = DoubleArray(9)
        for (index in 0 until n) {
            val x = source.x[index]
            val y = source.y[index]
            val u = target.x[index]
            val v = target.y[index]

            row[0] = -x; row[1] = -y; row[2] = -1.0
            row[3] = 0.0; row[4] = 0.0; row[5] = 0.0
            row[6] = u * x; row[7] = u * y; row[8] = u
            accumulate(normal, row)

            row[0] = 0.0; row[1] = 0.0; row[2] = 0.0
            row[3] = -x; row[4] = -y; row[5] = -1.0
            row[6] = v * x; row[7] = v * y; row[8] = v
            accumulate(normal, row)
        }

        val h = LinearAlgebra.smallestEigenvector(normal) ?: return null
        val normalised = Warp(h)
        // Undo the conditioning: H = T_target⁻¹ · Ĥ · T_source
        val inverseTarget = target.transform.inverse() ?: return null
        val result = inverseTarget * normalised * source.transform
        val scale = result.m[8]
        if (abs(scale) < 1e-12 || !scale.isFinite()) return null
        return Warp(DoubleArray(9) { result.m[it] / scale })
    }

    private fun accumulate(normal: Array<DoubleArray>, row: DoubleArray) {
        for (i in 0 until 9) {
            val value = row[i]
            if (value == 0.0) continue
            for (j in 0 until 9) normal[i][j] += value * row[j]
        }
    }

    private class Normalised(val x: DoubleArray, val y: DoubleArray, val transform: Warp)

    /** Centres the points on the origin and scales their mean distance to √2, as Hartley prescribes. */
    private fun normalise(x: DoubleArray, y: DoubleArray): Normalised? {
        val n = x.size
        var meanX = 0.0
        var meanY = 0.0
        for (index in 0 until n) {
            meanX += x[index]
            meanY += y[index]
        }
        meanX /= n
        meanY /= n

        var meanDistance = 0.0
        for (index in 0 until n) {
            meanDistance += sqrt(
                (x[index] - meanX) * (x[index] - meanX) + (y[index] - meanY) * (y[index] - meanY),
            )
        }
        meanDistance /= n
        if (meanDistance < 1e-9) return null
        val scale = sqrt(2.0) / meanDistance

        return Normalised(
            x = DoubleArray(n) { (x[it] - meanX) * scale },
            y = DoubleArray(n) { (y[it] - meanY) * scale },
            transform = Warp(
                doubleArrayOf(
                    scale, 0.0, -scale * meanX,
                    0.0, scale, -scale * meanY,
                    0.0, 0.0, 1.0,
                ),
            ),
        )
    }

    const val MINIMUM_MATCHES = 8
    private const val DEFAULT_TOLERANCE = 2.5
    private const val DEFAULT_ITERATIONS = 400

    /** Probability that at least one sample was free of outliers, used to stop early. */
    private const val CONFIDENCE = 0.999
}
