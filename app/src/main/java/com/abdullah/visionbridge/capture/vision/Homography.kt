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
 * Normalised Direct Linear Transform inside a **locally optimised, quality-ordered RANSAC** with
 * marginalised scoring. Three departures from the 1981 original, each of them standard practice
 * since roughly 2003–2020 and each earning its place here:
 *
 * - **Quality-ordered sampling (PROSAC, Chum & Matas 2005).** Matches arrive sorted by descriptor
 *   distance, and a good match is far more likely to be an inlier than a poor one. Drawing early
 *   samples from a growing prefix of the best matches finds a correct model in a handful of
 *   iterations where uniform sampling needs hundreds. It degrades gracefully to uniform sampling if
 *   the ordering turns out to carry no information.
 * - **Local optimisation (LO-RANSAC, Chum, Matas & Kittler 2003).** A minimal four-point model is
 *   chosen for *agreement*, not accuracy, and its inlier set is therefore an underestimate. When a
 *   sample beats the incumbent, it is immediately refitted over its own inliers and rescored, twice.
 *   The classic result is that this brings the iteration count down by roughly an order of
 *   magnitude and the final accuracy up.
 * - **Marginalised (σ-consensus) scoring, after MAGSAC/MAGSAC++ (Barath et al. 2019/2020).** A hard
 *   inlier count makes the answer hinge on one threshold that nobody can choose correctly for every
 *   frame. Scoring instead by a smooth quality that falls off with residual removes the cliff: a
 *   match just outside the threshold still contributes, just less.
 *
 * Full VSAC or MAGSAC++ would add SPRT verification and a genuine σ-marginalisation. Neither is
 * warranted at the match counts here (tens, not thousands); what is implemented is the part of that
 * literature that actually changes the answer at this scale.
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
     * A model's standing: how many matches it explains inside the threshold, how well it explains
     * everything nearby, and how tightly. [quality] leads, because it is the measure that does not
     * hinge on one threshold being right.
     */
    private data class Score(val inliers: Int, val quality: Double, val meanError: Double) {
        fun betterThan(other: Score): Boolean = when {
            quality > other.quality + 1e-9 -> true
            quality < other.quality - 1e-9 -> false
            inliers != other.inliers -> inliers > other.inliers
            else -> meanError < other.meanError
        }
    }

    /**
     * Size of the pool the [iteration]-th sample may draw from, under PROSAC's growth schedule.
     *
     * Starts at the best handful of matches and reaches the whole set by the time the ordering has
     * been given a fair chance to pay off. Linear growth rather than the paper's exact schedule:
     * at these match counts the difference is unmeasurable and the arithmetic is obvious.
     */
    private fun prosacPool(iteration: Int, total: Int): Int {
        if (total <= PROSAC_START) return total
        val grown = PROSAC_START + (total - PROSAC_START) * iteration / PROSAC_GROWTH_ITERATIONS
        return grown.coerceIn(PROSAC_START, total)
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

        // Best matches first, so the ordered sampling below has something to exploit. A stable sort
        // keeps the answer reproducible when distances tie.
        val ordered = matches.sortedBy { it.distance }
        val fromX = DoubleArray(ordered.size) { ordered[it].from.x.toDouble() }
        val fromY = DoubleArray(ordered.size) { ordered[it].from.y.toDouble() }
        val toX = DoubleArray(ordered.size) { ordered[it].to.x.toDouble() }
        val toY = DoubleArray(ordered.size) { ordered[it].to.y.toDouble() }

        /** Smooth quality of one model: Σ over matches of a residual-decayed weight. */
        fun score(model: Warp): Score {
            var inliers = 0
            var errorSum = 0.0
            var quality = 0.0
            for (index in fromX.indices) {
                val mapped = model.apply(fromX[index], fromY[index]) ?: continue
                val dx = mapped[0] - toX[index]
                val dy = mapped[1] - toY[index]
                val error = sqrt(dx * dx + dy * dy)
                if (error <= tolerance) {
                    inliers++
                    errorSum += error
                }
                // Falls to zero at the marginalisation radius rather than at the threshold, so a
                // match sitting just outside still says something about the model.
                if (error < tolerance * MARGINAL_RADIUS) {
                    val normalised = error / (tolerance * MARGINAL_RADIUS)
                    quality += 1.0 - normalised * normalised
                }
            }
            return Score(inliers, quality, errorSum / maxOf(inliers, 1))
        }

        /** Refits over the inliers of [model] and keeps the result only if it scores better. */
        fun locallyOptimise(model: Warp, from: Score): Pair<Warp, Score> {
            var bestLocal = model
            var bestScore = from
            repeat(LOCAL_OPTIMISATION_STEPS) {
                val keepX = ArrayList<Double>()
                val keepY = ArrayList<Double>()
                val targetX = ArrayList<Double>()
                val targetY = ArrayList<Double>()
                for (index in fromX.indices) {
                    val mapped = bestLocal.apply(fromX[index], fromY[index]) ?: continue
                    val dx = mapped[0] - toX[index]
                    val dy = mapped[1] - toY[index]
                    // A widened band on the first pass: the minimal model is biased, so its own
                    // inlier set is systematically too small to refit from.
                    if (sqrt(dx * dx + dy * dy) <= tolerance * LOCAL_OPTIMISATION_BAND) {
                        keepX += fromX[index]; keepY += fromY[index]
                        targetX += toX[index]; targetY += toY[index]
                    }
                }
                if (keepX.size < 4) return@repeat
                val refitted = fit(
                    keepX.toDoubleArray(), keepY.toDoubleArray(),
                    targetX.toDoubleArray(), targetY.toDoubleArray(),
                ) ?: return@repeat
                if (!refitted.isPlausible()) return@repeat
                val refittedScore = score(refitted)
                if (refittedScore.betterThan(bestScore)) {
                    bestLocal = refitted
                    bestScore = refittedScore
                }
            }
            return bestLocal to bestScore
        }

        var bestInliers = 0
        var bestModel: Warp? = null
        var bestError = Double.MAX_VALUE
        var bestQuality = -1.0
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
            // Quality-ordered sampling. The pool starts at the best few matches and grows towards
            // the whole set, so the early iterations — the ones that usually decide the answer —
            // draw from correspondences that are far more likely to be inliers. Once the pool has
            // grown to cover everything this is exactly uniform RANSAC, which is the fallback when
            // the descriptor ordering carries no information.
            val pool = prosacPool(iteration, ordered.size)
            var filled = 0
            var guard = 0
            while (filled < 4 && guard < 64) {
                guard++
                val candidate = nextIndex(pool)
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

            val raw = score(model)
            // Only a sample that already looks promising earns a local optimisation; refitting
            // every sample would cost more than the sampling it saves.
            val (candidateModel, candidateScore) =
                if (raw.quality > bestQuality) locallyOptimise(model, raw) else model to raw

            if (candidateScore.betterThan(Score(bestInliers, bestQuality, bestError))) {
                bestInliers = candidateScore.inliers
                bestModel = candidateModel
                bestError = candidateScore.meanError
                bestQuality = candidateScore.quality
                val ratio = candidateScore.inliers.toDouble() / ordered.size
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
        for (index in fromX.indices) {
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
     * Each correspondence contributes two rows to a homogeneous system, and the solution is that
     * system's null vector — taken now from a one-sided Jacobi SVD of the design matrix itself.
     *
     * The earlier version accumulated `AᵀA` and ran inverse iteration on it. That is cheaper and it
     * is also the classic mistake: `AᵀA` squares the condition number, which undoes much of what
     * Hartley conditioning was applied to achieve two lines earlier. For the point sets this
     * pipeline sees — correspondences clustered on one label, or nearly collinear along a line of
     * text — that difference is not academic.
     */
    fun fit(fromX: DoubleArray, fromY: DoubleArray, toX: DoubleArray, toY: DoubleArray): Warp? {
        val n = fromX.size
        if (n < 4 || fromY.size != n || toX.size != n || toY.size != n) return null

        val source = normalise(fromX, fromY) ?: return null
        val target = normalise(toX, toY) ?: return null

        // The 2n × 9 design matrix, handed to the decomposition intact.
        val design = Array(2 * n) { DoubleArray(9) }
        for (index in 0 until n) {
            val x = source.x[index]
            val y = source.y[index]
            val u = target.x[index]
            val v = target.y[index]

            val first = design[2 * index]
            first[0] = -x; first[1] = -y; first[2] = -1.0
            first[6] = u * x; first[7] = u * y; first[8] = u

            val second = design[2 * index + 1]
            second[3] = -x; second[4] = -y; second[5] = -1.0
            second[6] = v * x; second[7] = v * y; second[8] = v
        }

        val h = LinearAlgebra.smallestSingularVector(design) ?: return null
        val normalised = Warp(h)
        // Undo the conditioning: H = T_target⁻¹ · Ĥ · T_source
        val inverseTarget = target.transform.inverse() ?: return null
        val result = inverseTarget * normalised * source.transform
        val scale = result.m[8]
        if (abs(scale) < 1e-12 || !scale.isFinite()) return null
        return Warp(DoubleArray(9) { result.m[it] / scale })
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

    /** Residual beyond which a match tells the score nothing at all, as a multiple of tolerance. */
    private const val MARGINAL_RADIUS = 2.5

    /** How many refit-and-rescore passes a promising sample earns. */
    private const val LOCAL_OPTIMISATION_STEPS = 2

    /** The first refit uses a widened band, because a minimal model's own inlier set is too small. */
    private const val LOCAL_OPTIMISATION_BAND = 2.0

    /** Smallest ordered pool a sample may be drawn from. Four points define the model. */
    private const val PROSAC_START = 8

    /** By this many iterations the pool has grown to the whole match set. */
    private const val PROSAC_GROWTH_ITERATIONS = 64

    const val MINIMUM_MATCHES = 8
    private const val DEFAULT_TOLERANCE = 2.5
    private const val DEFAULT_ITERATIONS = 400

    /** Probability that at least one sample was free of outliers, used to stop early. */
    private const val CONFIDENCE = 0.999
}
