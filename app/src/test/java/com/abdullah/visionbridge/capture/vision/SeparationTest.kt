package com.abdullah.visionbridge.capture.vision

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The measurement the thresholds rest on, kept as a test so it cannot drift unnoticed.
 *
 * A threshold is only meaningful if there is a gap to put it in. This runs the whole registration
 * stack over many views of one page and over many genuinely different subjects, and asserts that
 * the two populations stay separated — and that the shipped thresholds lie between them.
 */
class SeparationTest {

    private val centre = VisionScenes.SIZE / 2.0
    private val base = VisionScenes.page()
    private val baseFrame = VisionScenes.frame(base)

    /** The best alignment either estimator can find, and what it leaves behind. */
    private fun residual(other: ImagePlane): Double {
        val frame = VisionScenes.frame(other)
        var best = 1.0
        LucasKanade.align(baseFrame.pyramid, frame.pyramid, finestLevel = baseFrame.analysisLevel)
            ?.takeIf { it.warp.isPlausible() }
            ?.let { best = StructuralResidual.measure(baseFrame.plane, frame.plane, it.warp).dissimilarity }
        Homography.estimate(Features.match(baseFrame.features, frame.features))
            ?.takeIf { it.warp.isPlausible() }
            ?.let {
                best = minOf(
                    best,
                    StructuralResidual.measure(baseFrame.plane, frame.plane, it.warp).dissimilarity,
                )
            }
        return best
    }

    private fun sameSubject(): List<Double> {
        val views = ArrayList<Double>()
        for (shift in listOf(1.0, 2.0, 4.0, 6.0, 9.0)) {
            views += residual(VisionScenes.transform(base, Warp.translation(shift, -shift / 2)))
        }
        for (degrees in listOf(2.0, 5.0, 8.0, 12.0, -7.0, -11.0)) {
            views += residual(VisionScenes.transform(base, Warp.similarity(centre, centre, degrees, 1.0, 0.0, 0.0)))
        }
        for (scale in listOf(0.85, 0.9, 0.95, 1.05, 1.12, 1.2)) {
            views += residual(VisionScenes.transform(base, Warp.similarity(centre, centre, 0.0, scale, 0.0, 0.0)))
        }
        for (index in 0 until 6) {
            val warp = Warp.similarity(
                centre,
                centre,
                (index - 3) * 3.0,
                1.0 + (index - 3) * 0.03,
                (index - 3) * 2.0,
                (3 - index) * 1.5,
            )
            views += residual(VisionScenes.transform(base, warp))
        }
        for (gain in listOf(0.7f, 0.85f, 1.15f)) {
            views += residual(VisionScenes.relit(base, gain, (1f - gain) * 140f))
        }
        for (amplitude in listOf(4f, 8f, 12f)) {
            views += residual(VisionScenes.withNoise(base, amplitude, 5))
        }
        return views
    }

    private fun differentSubject(): List<Double> {
        val others = ArrayList<Double>()
        for (seed in listOf(11, 23, 37, 53, 71, 89, 101, 127, 149, 163)) {
            others += residual(VisionScenes.page(seed = seed))
        }
        others += residual(VisionScenes.bottle())
        others += residual(VisionScenes.flat(30))
        others += residual(VisionScenes.flat(230))
        return others
    }

    @Test
    fun `the same subject and a different one do not overlap`() {
        val same = sameSubject()
        val different = differentSubject()
        val worstSame = same.max()
        val bestDifferent = different.min()
        assertTrue(
            "same-subject views reached $worstSame and different subjects fell to $bestDifferent, " +
                "so there is no gap left to put a threshold in",
            bestDifferent > worstSame,
        )
    }

    /** Both shipped thresholds must lie strictly inside the gap. */
    @Test
    fun `the shipped thresholds lie inside the gap`() {
        val worstSame = sameSubject().max()
        val bestDifferent = differentSubject().min()
        for (threshold in listOf(TEXT_THRESHOLD, SCENE_THRESHOLD)) {
            assertTrue(
                "threshold $threshold is not above the worst same-subject score $worstSame",
                threshold > worstSame,
            )
            assertTrue(
                "threshold $threshold is not below the best different-subject score $bestDifferent",
                threshold < bestDifferent,
            )
        }
    }

    private companion object {
        /** Mirrors MediaProjectionService; a change there without one here fails this test. */
        const val TEXT_THRESHOLD = 0.26
        const val SCENE_THRESHOLD = 0.24
    }
}
