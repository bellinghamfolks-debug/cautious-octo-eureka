package com.abdullah.visionbridge.capture.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Finding the live camera image inside a mirrored screen.
 *
 * Every case here is a shape taken from a real "Share your view" capture, because the defect it
 * fixes was invisible in every abstraction above the pixels: the app was analysing eSight's own
 * controls, describing them aloud, reading their glyphs as text, and — worst — measuring its
 * working resolution from how tall the buttons were.
 *
 * The two properties that matter are opposite in direction, so both are tested hard: it must remove
 * the interface, and it must never remove any part of the picture, however dark that picture is.
 */
class ViewportTest {

    private val random = Random(20260807)

    private class Canvas(val width: Int, val height: Int) {
        val luma = FloatArray(width * height)
        fun fill(x0: Int, y0: Int, x1: Int, y1: Int, value: (Int, Int) -> Float) {
            for (y in y0 until y1) for (x in x0 until x1) {
                if (x in 0 until width && y in 0 until height) luma[y * width + x] = value(x, y)
            }
        }
        fun plane(): ImagePlane {
            val flat = FloatArray(width * height) { 128f }
            return ImagePlane(width, height, luma, flat, flat)
        }
    }

    /** Photographic texture: never perfectly flat, whatever its brightness. */
    private fun texture(base: Float) = { _: Int, _: Int -> base + random.nextFloat() * 18f - 9f }

    /**
     * The share view as captured: black letterbox top and bottom, a control column on the right
     * separated by a black gutter, a narrow icon rail on the left.
     */
    private fun shareView(base: Float): Canvas {
        val canvas = Canvas(160, 72)
        // Left icon rail.
        canvas.fill(0, 0, 8, 72) { _, y -> if (y % 9 < 4) 150f else 6f }
        // The camera image.
        canvas.fill(8, 8, 122, 62, texture(base))
        // Black gutter is left at zero, then the controls.
        canvas.fill(130, 6, 150, 66) { x, y -> if ((x / 6 + y / 8) % 2 == 0) 200f else 4f }
        return canvas
    }

    @Test
    fun `the control column is left outside the viewport`() {
        val rect = Viewport.detect(shareView(base = 140f).plane())
        assertNotNull("expected a viewport on a letterboxed share view", rect)
        assertTrue("right edge ${rect!!.right} should stop before the controls", rect.right <= 0.80f)
        assertTrue("top edge ${rect.top} should skip the letterbox", rect.top >= 0.05f)
        assertTrue("bottom edge ${rect.bottom} should skip the letterbox", rect.bottom <= 0.95f)
    }

    /**
     * The case that broke the first version of this. A navy bottle photographed in a dim room is
     * darker than parts of the interface beside it; cropping by brightness cut the label away.
     */
    @Test
    fun `a very dark photograph is kept whole`() {
        val rect = Viewport.detect(shareView(base = 26f).plane())
        assertNotNull("a dark view still has a viewport", rect)
        assertTrue("left edge ${rect!!.left} must not eat into the image", rect.left <= 0.10f)
        assertTrue("right edge ${rect.right} must keep the image", rect.right >= 0.70f)
        assertTrue("the dark image must survive", rect.area >= 0.40f)
    }

    /** A phone screenshot is live to every edge, so the honest answer is to change nothing. */
    @Test
    fun `a full screen is left alone`() {
        val canvas = Canvas(120, 240)
        canvas.fill(0, 0, 120, 240, texture(120f))
        assertNull(Viewport.detect(canvas.plane()))
    }

    /** A frame that is all letterbox has no viewport to find, and must not invent one. */
    @Test
    fun `an entirely black frame yields nothing`() {
        val canvas = Canvas(120, 80)
        assertNull(Viewport.detect(canvas.plane()))
    }

    /** A tiny lit patch is not a viewport; cropping to it would throw the session away. */
    @Test
    fun `a small bright patch is rejected`() {
        val canvas = Canvas(160, 80)
        canvas.fill(70, 34, 90, 46, texture(200f))
        assertNull(Viewport.detect(canvas.plane()))
    }

    /** Fractions, so the answer is the same whatever resolution the capture happens to be. */
    @Test
    fun `the answer is scale free`() {
        val small = Viewport.detect(shareView(base = 140f).plane())
        val large = Canvas(320, 144).also { canvas ->
            canvas.fill(0, 0, 16, 144) { _, y -> if (y % 18 < 8) 150f else 6f }
            canvas.fill(16, 16, 244, 124, texture(140f))
            canvas.fill(260, 12, 300, 132) { x, y -> if ((x / 12 + y / 16) % 2 == 0) 200f else 4f }
        }
        val big = Viewport.detect(large.plane())
        assertNotNull(small); assertNotNull(big)
        assertEquals(small!!.right, big!!.right, 0.06f)
        assertEquals(small.bottom, big.bottom, 0.06f)
    }

    /** Too small to reason about is answered honestly rather than guessed at. */
    @Test
    fun `a tiny plane is declined`() {
        val canvas = Canvas(12, 12)
        canvas.fill(0, 0, 12, 12, texture(120f))
        assertNull(Viewport.detect(canvas.plane()))
    }

    /**
     * A letterbox with no side controls — a video played full width — still has its bars removed,
     * because those bars are what drags a text-height measurement off course.
     */
    @Test
    fun `horizontal bars alone are trimmed`() {
        val canvas = Canvas(160, 90)
        canvas.fill(0, 20, 160, 70, texture(130f))
        val rect = Viewport.detect(canvas.plane())
        assertNotNull(rect)
        assertTrue(rect!!.top >= 0.15f)
        assertTrue(rect.bottom <= 0.85f)
        assertEquals(1f, rect.width, 0.02f)
    }

    /** The rectangle is reported into the bundle, or the crop is a decision no one can audit. */
    @Test
    fun `the rectangle is carried into diagnostics`() {
        val rect = Viewport.detect(shareView(base = 140f).plane())!!
        val fields = rect.fields()
        assertEquals(rect.right, fields["viewportRight"])
        assertNotNull(fields["viewportArea"])
    }
}
