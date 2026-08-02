package com.abdullah.visionbridge.capture.vision

import kotlin.math.abs

/**
 * Pyramidal Lucas-Kanade alignment for a six-parameter affine warp.
 *
 * This is the piece that answers "did the camera move?" instead of "did the pixels change?". It
 * takes the reference frame as a template, and iteratively refines a warp that makes the current
 * frame agree with it, by solving the linearised least-squares problem
 *
 * ```
 *   Δp = H⁻¹ · Σ [∇I · ∂W/∂p]ᵀ · (T(x) − I(W(x;p)))
 * ```
 *
 * at each pyramid level, coarse to fine. The coarse levels give it a capture range far wider than
 * its own linearisation would allow; the fine levels give it sub-pixel precision. A hand that
 * drifts, a head that turns a few degrees, a bottle brought closer — all of them are a warp, and a
 * warp is something to compensate for rather than a reason to stop reading aloud.
 *
 * Affine rather than pure translation because rotation and scale are what a person holding an
 * object actually does, and translation-only compensation leaves both looking like a new subject.
 */
object LucasKanade {

    /**
     * [warp] maps reference coordinates to current-frame coordinates. [residual] is the mean
     * absolute luminance error over the pixels that were usable, and [coverage] the fraction of the
     * template that stayed inside the current frame.
     */
    data class Alignment(
        val warp: Warp,
        val residual: Double,
        val coverage: Double,
        val iterations: Int,
        val converged: Boolean,
    )

    /**
     * Aligns [current] onto [reference], starting from [initial].
     *
     * Returns null when there is not enough texture to estimate anything, which is the honest
     * answer for a blank wall and is treated by the caller as "no evidence of tracking" rather than
     * as a successful lock.
     */
    fun align(
        reference: FramePyramid,
        current: FramePyramid,
        initial: Warp = Warp.identity(),
        maxIterationsPerLevel: Int = DEFAULT_ITERATIONS,
        finestLevel: Int = 0,
    ): Alignment? {
        val depth = minOf(reference.depth, current.depth)
        if (depth == 0) return null

        // Start at the coarsest level, scaling the incoming guess - which is in [finestLevel]
        // coordinates - down to it.
        var warp = initial.scaledBy(1.0 / (1 shl (depth - 1 - finestLevel)))
        var lastResidual = Double.MAX_VALUE
        var lastCoverage = 0.0
        var totalIterations = 0
        var converged = false

        for (level in depth - 1 downTo finestLevel) {
            val template = reference[level]
            val image = current[level]
            val plain = alignLevel(template, image, warp, maxIterationsPerLevel)
            val result = if (level == depth - 1 && needsSeeding(plain)) {
                // Multi-start, at the coarsest level and only when the plain descent struggled. The
                // objective is not convex: on a page of text a 1.25x zoom has a local minimum at
                // identity, because the central lines still roughly overlap, and Gauss-Newton from
                // rest sat in it and reported a scale of 1.007. Seeding a spread of hypotheses
                // costs half a millisecond at 32x32 and removes the failure — but it is not run
                // when the plain descent already succeeded, so a seed can never hijack a case that
                // was being handled correctly.
                bestOfSeeds(template, image, warp, maxIterationsPerLevel, plain)
            } else {
                plain
            } ?: return null
            warp = result.warp
            lastResidual = result.residual
            lastCoverage = result.coverage
            totalIterations += result.iterations
            converged = result.converged
            if (level > finestLevel) warp = warp.scaledBy(2.0)
        }

        if (!warp.isPlausible()) return null
        // Reported in the coordinates of [finestLevel], which is the level the caller measures on.
        return Alignment(warp, lastResidual, lastCoverage, totalIterations, converged)
    }

    /**
     * Runs the coarsest level from several starting hypotheses and keeps the best fit.
     *
     * The seeds cover the motions a single Gauss-Newton descent cannot reach from rest: a large
     * zoom in either direction, and a large rotation in either direction. Everything smaller is
     * found from the identity seed anyway.
     */
    /** True when a descent ended somewhere it should not have: unconverged, or still far off. */
    private fun needsSeeding(plain: Alignment?): Boolean =
        plain == null || !plain.converged || plain.residual > SEEDING_RESIDUAL

    private fun bestOfSeeds(
        template: ImagePlane,
        image: ImagePlane,
        start: Warp,
        maxIterations: Int,
        plain: Alignment?,
    ): Alignment? {
        val centreX = template.width / 2.0
        val centreY = template.height / 2.0
        val seeds = ArrayList<Warp>(SEED_SCALES.size + SEED_DEGREES.size + 1)
        seeds.add(start)
        for (scale in SEED_SCALES) {
            seeds.add(start * Warp.similarity(centreX, centreY, 0.0, scale, 0.0, 0.0))
        }
        for (degrees in SEED_DEGREES) {
            seeds.add(start * Warp.similarity(centreX, centreY, degrees, 1.0, 0.0, 0.0))
        }

        var best: Alignment? = plain?.takeIf { it.warp.isPlausible() }
        for (seed in seeds) {
            val candidate = alignLevel(template, image, seed, maxIterations) ?: continue
            if (!candidate.warp.isPlausible()) continue
            // Coverage is part of the comparison: a fit that explains a little of the frame very
            // well is not better than one that explains most of it slightly less well.
            if (best == null || candidate.score() < best!!.score()) best = candidate
        }
        return best
    }

    private fun Alignment.score(): Double = residual + COVERAGE_WEIGHT * (1.0 - coverage)

    /**
     * One pyramid level, by the inverse-compositional algorithm of Baker and Matthews.
     *
     * The straightforward formulation recomputes the image gradient, the six steepest-descent
     * images and the whole 6×6 Hessian at every pixel of every iteration — twenty-one multiplies
     * per pixel just to rebuild a matrix that barely changes. Inverse composition takes those from
     * the *template* instead, where they are constant, so they are computed once per level and the
     * iteration is left with a warp, a subtraction and six dot products. Same fixed point, a third
     * of the work: alignment fell from 17.1 ms to a few milliseconds per frame.
     *
     * The price is that the update composes rather than adds: `W ← W ∘ W(Δp)⁻¹`.
     */
    private fun alignLevel(
        template: ImagePlane,
        image: ImagePlane,
        start: Warp,
        maxIterations: Int,
    ): Alignment? {
        val stride = if (template.width > STRIDE_ABOVE) 2 else 1

        // --- Precomputation over the template, done once. ---
        val capacity = ((template.width - 2 * BORDER) / stride + 1) *
            ((template.height - 2 * BORDER) / stride + 1)
        val sampleX = IntArray(capacity)
        val sampleY = IntArray(capacity)
        val steepest = Array(6) { DoubleArray(capacity) }
        val templateValue = DoubleArray(capacity)
        var samples = 0

        var y = BORDER
        while (y < template.height - BORDER) {
            var x = BORDER
            while (x < template.width - BORDER) {
                val tx = template.gradientX(x, y).toDouble()
                val ty = template.gradientY(x, y).toDouble()
                sampleX[samples] = x
                sampleY[samples] = y
                templateValue[samples] = template.luma(x, y).toDouble()
                // ∂W/∂p for the affine parameterisation [1+p0, p2, p4; p1, 1+p3, p5].
                steepest[0][samples] = tx * x
                steepest[1][samples] = ty * x
                steepest[2][samples] = tx * y
                steepest[3][samples] = ty * y
                steepest[4][samples] = tx
                steepest[5][samples] = ty
                samples++
                x += stride
            }
            y += stride
        }
        if (samples < MIN_PIXELS) return null

        // The Hessian over every template pixel, which is what the fast path uses.
        val fullHessian = hessianOver(steepest, samples, null) ?: return null
        val valid = BooleanArray(samples)

        // --- Iteration. ---
        var warp = start
        var coverage = 0.0
        var iterations = 0
        var converged = false
        val gradient = DoubleArray(6)

        // The best warp seen, not the last one. Gauss-Newton on real image data can overshoot and
        // oscillate, and a level that ends worse than it began used to hand that result down to
        // every finer level. Keeping the best makes each level a refinement or a no-op, never a
        // regression.
        var bestWarp = start
        var bestResidual = Double.MAX_VALUE

        for (pass in 0 until maxIterations) {
            iterations++
            gradient.fill(0.0)
            var errorSum = 0.0
            var used = 0
            val m = warp.m

            for (index in 0 until samples) {
                val px = sampleX[index]
                val py = sampleY[index]
                val warpedX = m[0] * px + m[1] * py + m[2]
                val warpedY = m[3] * px + m[4] * py + m[5]
                if (!image.contains(warpedX, warpedY)) {
                    valid[index] = false
                    continue
                }
                valid[index] = true
                // Image minus template: inverse composition swaps the roles, and with them the sign.
                val error = image.sampleLuma(warpedX, warpedY) - templateValue[index]
                for (i in 0 until 6) gradient[i] += steepest[i][index] * error
                errorSum += abs(error)
                used++
            }

            if (used < MIN_PIXELS) return if (bestResidual < Double.MAX_VALUE) {
                Alignment(bestWarp, bestResidual, coverage, iterations, converged)
            } else {
                null
            }
            coverage = used.toDouble() / samples
            // This error belongs to the warp that produced it, before the step below changes it.
            val residual = errorSum / used
            if (residual < bestResidual) {
                bestResidual = residual
                bestWarp = warp
            }

            // The Hessian has to describe the same pixels the error was summed over. When a zoom
            // or a large rotation pushes part of the template outside the frame, reusing the full
            // one biases every step: a 1.25x zoom, which leaves 64% of the template in view, stalled
            // at 1.007 with the mismatch. Rebuilt only when coverage is short, so the common case -
            // a steady hand, everything in frame - still pays nothing for it.
            val hessian = if (used == samples) {
                fullHessian
            } else {
                hessianOver(steepest, samples, valid) ?: return finish(bestWarp, bestResidual, coverage, iterations, converged)
            }
            val step = LinearAlgebra.solve(hessian, gradient) ?: return null
            if (step.any { !it.isFinite() }) return null

            // W ← W ∘ W(Δp)⁻¹
            val delta = Warp(
                doubleArrayOf(
                    1.0 + step[0], step[2], step[4],
                    step[1], 1.0 + step[3], step[5],
                    0.0, 0.0, 1.0,
                ),
            )
            val inverseDelta = delta.inverse() ?: return null
            warp = warp * inverseDelta
            if (!warp.m.all { it.isFinite() }) return null

            // How far the step actually moves the image, measured at its corner. The previous test
            // compared the raw scale coefficient against a fixed bound, which on a 32-wide level
            // declared convergence at a scale increment of 0.0006 per pass — so a 25% zoom stopped
            // at 1.007 and the leftover scale read as a different subject.
            val halfWidth = template.width / 2.0
            val halfHeight = template.height / 2.0
            val movement = maxOf(
                abs(step[0]) * halfWidth + abs(step[2]) * halfHeight + abs(step[4]),
                abs(step[1]) * halfWidth + abs(step[3]) * halfHeight + abs(step[5]),
            )
            if (movement < CONVERGED) {
                converged = true
                break
            }
        }

        return Alignment(bestWarp, bestResidual, coverage, iterations, converged)
    }

    /**
     * Enough for a quarter-scale change to converge. At twelve, a 1.25x zoom stopped halfway at
     * 1.12 and the leftover scale read as a different subject.
     */
    /**
     * Accumulates the 6x6 Gauss-Newton Hessian over the template's steepest-descent images,
     * restricted to [valid] when given. Damped, and null when the patch has no texture to fit.
     */
    private fun hessianOver(
        steepest: Array<DoubleArray>,
        samples: Int,
        valid: BooleanArray?,
    ): Array<DoubleArray>? {
        val hessian = Array(6) { DoubleArray(6) }
        for (index in 0 until samples) {
            if (valid != null && !valid[index]) continue
            for (i in 0 until 6) {
                val value = steepest[i][index]
                if (value == 0.0) continue
                for (j in i until 6) hessian[i][j] += value * steepest[j][index]
            }
        }
        var trace = 0.0
        for (i in 0 until 6) trace += hessian[i][i]
        if (trace < MIN_TEXTURE) return null
        for (i in 0 until 6) for (j in 0 until i) hessian[i][j] = hessian[j][i]
        val damping = trace / 6.0 * DAMPING
        for (i in 0 until 6) hessian[i][i] += damping
        return hessian
    }

    private fun finish(
        warp: Warp,
        residual: Double,
        coverage: Double,
        iterations: Int,
        converged: Boolean,
    ): Alignment? = if (residual < Double.MAX_VALUE) {
        Alignment(warp, residual, coverage, iterations, converged)
    } else {
        null
    }

    /**
     * Zoom hypotheses, spread closely enough that the truth is always near one of them. Two seeds
     * far apart left a gap: a true scale of 1.12 was captured by a 1.28 seed and refined no further.
     */
    private val SEED_SCALES = doubleArrayOf(0.78, 0.88, 1.12, 1.28)

    /** Rotation hypotheses, in degrees. */
    private val SEED_DEGREES = doubleArrayOf(-22.0, -11.0, 11.0, 22.0)

    /** How much a lost tenth of the frame is worth in units of mean luminance error. */
    private const val COVERAGE_WEIGHT = 12.0

    /** A descent that ends above this mean error has probably found the wrong minimum. */
    private const val SEEDING_RESIDUAL = 6.0

    private const val DEFAULT_ITERATIONS = 40
    private const val BORDER = 1
    private const val MIN_PIXELS = 40
    private const val MIN_TEXTURE = 1e-3
    private const val DAMPING = 1e-4
    private const val CONVERGED = 0.03
    private const val STRIDE_ABOVE = 96
}
