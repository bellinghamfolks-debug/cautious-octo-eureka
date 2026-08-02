package com.abdullah.visionbridge.capture.vision

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Measures how different two frames still are once one has been warped onto the other.
 *
 * The old decision was a mean absolute difference between raw pixels, which answers a question
 * nobody asked: whether the numbers moved. Structural similarity asks whether the *content* moved,
 * by comparing local luminance, contrast and correlation in small windows. Two photographs of the
 * same label under different lighting score as the same content; a different label at identical
 * brightness scores as different content. Neither of those is true of a pixel difference.
 *
 * A colour term is carried beside it, because two labels can share a layout and differ only in
 * hue, and the previous grayscale signature was blind to exactly that.
 */
object StructuralResidual {

    /**
     * [dissimilarity] is `(1 − SSIM) / 2` over the aligned overlap, in 0..1: zero is identical
     * content, one is unrelated. [coverage] is the fraction of the reference that the warp kept
     * inside the current frame — a low coverage means the answer rests on very little.
     */
    data class Result(
        val dissimilarity: Double,
        val chromaDifference: Double,
        val coverage: Double,
        val windows: Int,
    ) {
        /** True when there was enough overlap for the measurement to mean anything. */
        val usable: Boolean get() = windows >= MIN_WINDOWS && coverage >= MIN_COVERAGE
    }

    /**
     * Compares [reference] against [current] warped by [warp], which maps reference coordinates
     * into the current frame.
     */
    fun measure(reference: ImagePlane, current: ImagePlane, warp: Warp): Result {
        var ssimSum = 0.0
        var weightSum = 0.0
        var windows = 0
        var chromaSum = 0.0
        var chromaCount = 0.0
        var inside = 0
        var considered = 0

        var windowY = 0
        while (windowY + WINDOW <= reference.height) {
            var windowX = 0
            while (windowX + WINDOW <= reference.width) {
                var sumA = 0.0
                var sumB = 0.0
                var sumAA = 0.0
                var sumBB = 0.0
                var sumAB = 0.0
                var count = 0
                var chromaLocal = 0.0

                for (y in windowY until windowY + WINDOW) {
                    for (x in windowX until windowX + WINDOW) {
                        considered++
                        val mapped = warp.apply(x.toDouble(), y.toDouble())
                        if (mapped == null || !current.contains(mapped[0], mapped[1])) continue
                        inside++
                        val a = reference.luma(x, y).toDouble()
                        val b = current.sampleLuma(mapped[0], mapped[1]).toDouble()
                        sumA += a
                        sumB += b
                        sumAA += a * a
                        sumBB += b * b
                        sumAB += a * b
                        count++

                        val referenceIndex = y * reference.width + x
                        chromaLocal += abs(
                            reference.chromaBlue[referenceIndex] -
                                current.sampleChromaBlue(mapped[0], mapped[1]),
                        ) + abs(
                            reference.chromaRed[referenceIndex] -
                                current.sampleChromaRed(mapped[0], mapped[1]),
                        )
                    }
                }

                if (count >= WINDOW * WINDOW / 2) {
                    val n = count.toDouble()
                    val meanA = sumA / n
                    val meanB = sumB / n
                    val varianceA = (sumAA / n - meanA * meanA).coerceAtLeast(0.0)
                    val varianceB = (sumBB / n - meanB * meanB).coerceAtLeast(0.0)
                    val covariance = sumAB / n - meanA * meanB

                    val luminance = (2 * meanA * meanB + C1) / (meanA * meanA + meanB * meanB + C1)
                    // Contrast and structure together, which is the standard SSIM term. Using
                    // correlation alone was tried and is wrong in a way that matters here: with no
                    // variance on one side it evaluates to one through its own stabilising
                    // constant, so a blank wall scored as the same subject as a page of text.
                    // Contrast is what notices that one of the two has no structure at all.
                    val structure = (2 * covariance + C2) / (varianceA + varianceB + C2)
                    val ssim = (luminance * structure).coerceIn(-1.0, 1.0)

                    // Weighted by how much structure the window actually holds. A window of blank
                    // paper agrees perfectly with any other window of blank paper, and an unweighted
                    // mean lets those drown out the windows that carry the text: two entirely
                    // different pages scored 0.18 against a threshold of 0.30 purely because most
                    // of both pages was white. Contrast is where the information is, so contrast is
                    // what the average is taken over.
                    val weight = sqrt(maxOf(varianceA, varianceB))
                    if (weight >= FLAT_WINDOW) {
                        ssimSum += ssim * weight
                        weightSum += weight
                        windows++
                        chromaSum += (chromaLocal / n) * weight
                        chromaCount += weight
                    }
                }
                windowX += STEP
            }
            windowY += STEP
        }

        // No structural window at all means there was nothing to compare - a blank wall, or a warp
        // that pushed every textured region out of frame. Reported as maximally dissimilar, and the
        // caller checks `usable` before believing it.
        val meanSsim = if (weightSum <= 0.0) -1.0 else ssimSum / weightSum
        return Result(
            dissimilarity = ((1.0 - meanSsim) / 2.0).coerceIn(0.0, 1.0),
            chromaDifference = if (chromaCount <= 0.0) 0.0 else chromaSum / chromaCount,
            coverage = if (considered == 0) 0.0 else inside.toDouble() / considered,
            windows = windows,
        )
    }

    /** Root-mean-square luminance error, kept for diagnostics beside the structural measure. */
    fun luminanceError(reference: ImagePlane, current: ImagePlane, warp: Warp): Double {
        var sum = 0.0
        var count = 0
        for (y in 0 until reference.height) {
            for (x in 0 until reference.width) {
                val mapped = warp.apply(x.toDouble(), y.toDouble()) ?: continue
                if (!current.contains(mapped[0], mapped[1])) continue
                val delta = reference.luma(x, y) - current.sampleLuma(mapped[0], mapped[1])
                sum += delta.toDouble() * delta.toDouble()
                count++
            }
        }
        return if (count == 0) Double.MAX_VALUE else sqrt(sum / count)
    }

    private const val WINDOW = 8
    private const val STEP = 4

    // The standard stabilising constants for an 8-bit dynamic range.
    private const val C1 = 6.5025
    private const val C2 = 58.5225

    /** Below this standard deviation a window is blank paper and agrees with anything. */
    private const val FLAT_WINDOW = 4.0

    private const val MIN_WINDOWS = 12
    private const val MIN_COVERAGE = 0.35
}
