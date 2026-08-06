package com.abdullah.visionbridge.data.paddleocr

import com.abdullah.visionbridge.capture.vision.ImagePlane
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The controller that replaced the quality menu, and the probe that starts it off.
 *
 * The three presets a user used to choose between were three points on one curve. These cases check
 * that the curve is evaluated correctly at each of them, and — more importantly — everywhere
 * between and beyond.
 */
class AdaptiveReadingScaleTest {

    /** A phone capture: 1220x2712. */
    private val sourceLongEdge = 2712

    private fun controller() = AdaptiveReadingScale(sourceLongEdge)

    // region the curve

    /** A label held close needs almost nothing, and taking less is what makes it fast. */
    @Test
    fun `large near text drops the resolution`() {
        val decision = controller().next(measuredTextHeight = 200f)
        assertTrue(
            "expected a low resolution for 200px text, got ${decision.detectionLongEdge}",
            decision.detectionLongEdge <= 900,
        )
    }

    /** A page at arm's length lands where the old default sat, without anyone choosing it. */
    @Test
    fun `medium text lands near the old balanced preset`() {
        val decision = controller().next(measuredTextHeight = 40f)
        assertTrue(
            "expected roughly 1500, got ${decision.detectionLongEdge}",
            abs(decision.detectionLongEdge - 1490) < 400,
        )
    }

    /** A sign across a room needs everything, which is the case the menu existed for. */
    @Test
    fun `small distant text asks for the whole capture`() {
        val decision = controller().next(measuredTextHeight = 14f)
        assertEquals(
            AdaptiveReadingScale.DEFAULT_LADDER.last(),
            decision.detectionLongEdge,
        )
    }

    /** The relationship is monotone: smaller text never asks for less resolution. */
    @Test
    fun `resolution never decreases as text gets smaller`() {
        var previous = 0
        for (height in listOf(300f, 200f, 120f, 80f, 55f, 40f, 28f, 20f, 14f, 9f)) {
            val edge = controller().next(measuredTextHeight = height).detectionLongEdge
            assertTrue(
                "height $height gave $edge after $previous",
                edge >= previous,
            )
            previous = edge
        }
    }

    /** The controller solves the equation it claims to: E = S x target / h. */
    @Test
    fun `the ideal resolution follows the stated equation`() {
        val decision = controller().next(measuredTextHeight = 50f)
        val expected = (sourceLongEdge * AdaptiveReadingScale.TARGET_DETECTED_HEIGHT / 50f).toInt()
        assertEquals(expected, decision.idealLongEdge!!.toInt())
    }

    // endregion

    // region behaviour on a moving hand

    /** A zoom is anticipated from the tracker's measurement, not corrected a frame later. */
    @Test
    fun `a subject moving closer lowers the resolution in the same frame`() {
        val controller = controller()
        val far = controller.next(measuredTextHeight = 20f).detectionLongEdge
        // The tracker reports the subject is now 2.2x bigger; the text is too, so the resolution
        // drops in the same frame rather than after the next measurement catches up.
        val closer = controller.next(measuredTextHeight = 20f, subjectScale = 2.2)
        assertTrue("expected ${closer.detectionLongEdge} to be below $far", closer.detectionLongEdge < far)
        assertEquals("followed_subject_scale", closer.reason)
    }

    /** A small re-measurement must not move the resolution, or the runtime re-plans every frame. */
    @Test
    fun `a small change is inside the deadband and holds`() {
        val controller = controller()
        val first = controller.next(measuredTextHeight = 40f).detectionLongEdge
        val second = controller.next(measuredTextHeight = 42f)
        assertEquals(first, second.detectionLongEdge)
        assertEquals("held", second.reason)
    }

    /** A hand wobbling around one distance must not make the resolution oscillate. */
    @Test
    fun `a wobbling hand does not thrash the resolution`() {
        val controller = controller()
        controller.next(measuredTextHeight = 40f)
        val edges = (1..20).map { frame ->
            val jitter = 40f + ((frame % 5) - 2) * 1.5f
            controller.next(measuredTextHeight = jitter, subjectScale = 1.0 + ((frame % 3) - 1) * 0.01)
                .detectionLongEdge
        }
        assertEquals("the resolution must settle", 1, edges.toSet().size)
    }

    /** Every choice is a rung, so the runtime sees a handful of shapes rather than a continuum. */
    @Test
    fun `every resolution is on the ladder`() {
        val ladder = AdaptiveReadingScale.DEFAULT_LADDER.toSet()
        for (height in 5..300 step 3) {
            val edge = controller().next(measuredTextHeight = height.toFloat()).detectionLongEdge
            assertTrue("$edge is not a rung", edge in ladder)
        }
    }

    // endregion

    // region finding text at all

    /**
     * Nothing found and text too small to find look identical from outside, and only one of them is
     * fixed by looking harder — so both are tested rather than assumed.
     */
    @Test
    fun `an empty pass searches instead of sitting still`() {
        val controller = controller()
        val start = controller.currentLongEdge()
        val first = controller.next(measuredTextHeight = null)
        assertEquals("nothing_found_searching", first.reason)
        assertTrue(first.detectionLongEdge != start)

        val visited = mutableSetOf(start, first.detectionLongEdge)
        repeat(5) { visited.add(controller.next(measuredTextHeight = null).detectionLongEdge) }
        assertTrue(
            "the search must try both directions, visited $visited",
            visited.any { it < start } && visited.any { it > start },
        )
    }

    @Test
    fun `finding text again ends the search`() {
        val controller = controller()
        repeat(3) { controller.next(measuredTextHeight = null) }
        val found = controller.next(measuredTextHeight = 40f)
        assertEquals("matched_text_height", found.reason)
    }

    @Test
    fun `a reset returns to the middle of the ladder`() {
        val controller = controller()
        controller.next(measuredTextHeight = 8f)
        controller.reset()
        assertEquals(
            AdaptiveReadingScale.DEFAULT_LADDER[AdaptiveReadingScale.DEFAULT_LADDER.size / 2],
            controller.currentLongEdge(),
        )
    }

    /** A nonsense measurement must not drive the resolution off the end of the ladder. */
    @Test
    fun `an implausible height is clamped rather than believed`() {
        val decision = controller().next(measuredTextHeight = 0.001f)
        assertEquals(AdaptiveReadingScale.DEFAULT_LADDER.last(), decision.detectionLongEdge)
    }

    /** The crop width has to keep up, or a wide line is squashed on its way to the recogniser. */
    @Test
    fun `the crop width cap rises with the resolution`() {
        val near = controller().next(measuredTextHeight = 200f)
        val far = controller().next(measuredTextHeight = 12f)
        assertTrue(far.recognitionMaxWidth > near.recognitionMaxWidth)
        assertTrue(near.recognitionMaxWidth >= AdaptiveReadingScale.MIN_CROP_WIDTH)
    }

    // endregion

    // region the probe

    /** A page whose line height is known exactly, so the estimate can be checked against truth. */
    private fun page(lineHeight: Int, gap: Int, width: Int = 256, height: Int = 256): ImagePlane {
        val luma = FloatArray(width * height) { 240f }
        var y = 12
        while (y + lineHeight < height - 12) {
            for (row in y until y + lineHeight) {
                var x = 16
                while (x < width - 16) {
                    // Words with gaps, so the rows carry horizontal structure rather than a bar.
                    val word = 5 + (x / 7) % 9
                    for (i in 0 until word) {
                        if (x + i < width - 16) luma[row * width + x + i] = 30f
                    }
                    x += word + 3
                }
            }
            y += lineHeight + gap
        }
        val flat = FloatArray(width * height) { 128f }
        return ImagePlane(width, height, luma, flat, flat)
    }

    @Test
    fun `the probe measures a known line height`() {
        for (lineHeight in listOf(3, 5, 8, 12, 20)) {
            val estimate = TextScaleProbe.estimate(page(lineHeight, gap = lineHeight + 2))
            assertNotNull("no estimate for line height $lineHeight", estimate)
            assertEquals(
                "line height $lineHeight",
                lineHeight.toFloat(),
                estimate!!.textHeightPixels,
                1.5f,
            )
        }
    }

    @Test
    fun `the probe reports in source pixels when asked`() {
        val estimate = TextScaleProbe.estimate(page(6, gap = 8), sourceScale = 4f)
        assertNotNull(estimate)
        assertEquals(24f, estimate!!.textHeightPixels, 6f)
    }

    /** A wrong estimate is worse than none, because none falls back to a search that terminates. */
    @Test
    fun `the probe declines a blank frame`() {
        val flat = FloatArray(128 * 128) { 200f }
        val grey = FloatArray(128 * 128) { 128f }
        assertNull(TextScaleProbe.estimate(ImagePlane(128, 128, flat, grey, grey)))
    }

    @Test
    fun `the probe declines a frame that is ink everywhere`() {
        val noisy = FloatArray(128 * 128) { index -> if ((index / 3) % 2 == 0) 20f else 230f }
        val grey = FloatArray(128 * 128) { 128f }
        assertNull(TextScaleProbe.estimate(ImagePlane(128, 128, noisy, grey, grey)))
    }

    @Test
    fun `the probe declines a frame with too few lines to be a page`() {
        val luma = FloatArray(128 * 128) { 240f }
        for (row in 60 until 66) {
            for (x in 20 until 108) luma[row * 128 + x] = 30f
        }
        val grey = FloatArray(128 * 128) { 128f }
        assertNull(TextScaleProbe.estimate(ImagePlane(128, 128, luma, grey, grey)))
    }

    /** End to end: the probe's estimate drives the controller to a sensible starting resolution. */
    @Test
    fun `probe and controller together choose a resolution for an unseen page`() {
        // A 256-tall working plane taken from a 2712-long capture: each probe pixel is 10.6 source.
        val estimate = TextScaleProbe.estimate(page(4, gap = 6), sourceScale = 2712f / 256f)
        assertNotNull(estimate)
        val decision = controller().next(estimate!!.textHeightPixels)
        assertTrue(
            "small text should ask for a high resolution, got ${decision.detectionLongEdge}",
            decision.detectionLongEdge >= 1440,
        )
    }

    // endregion
}
