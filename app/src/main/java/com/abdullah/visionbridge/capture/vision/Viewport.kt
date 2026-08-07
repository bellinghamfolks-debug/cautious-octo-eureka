package com.abdullah.visionbridge.capture.vision

/**
 * Finds the part of a mirrored screen that is actually the live camera image.
 *
 * Screen capture mirrors the whole display, and the display is not the view. eSight's "Share your
 * view" puts the camera image in the middle of its own app: letterbox bars above and below, a
 * column of large yellow controls down the right, a narrow icon rail on the left. VisionBridge was
 * analysing all of it, and a device bundle showed exactly what that costs:
 *
 * - The scene description described the app. Verbatim, from the field: *"يوجد أزرار تحكم صفراء على
 *   أقصى يمين الصورة"*. The model was not wrong; the buttons were in the picture it was given.
 * - The reader read the buttons. The most frequently recognised "lines" of a whole session were
 *   `0` (34 times), `D` (25), `O` (19), `V` (7), `⑧` (6) — control glyphs, not text.
 * - Worst, the adaptive resolution controller measured its text height from those buttons. They are
 *   80 to 150 pixels tall, so it solved for a working resolution of 640 on a 2712-pixel capture and
 *   held it there for 38 frames out of 44, at which point the actual label was too small to detect.
 *   A large, clear word went unread *because* something much larger was in the frame beside it.
 *
 * ## What separates the image from the app
 *
 * Not brightness. A dark subject — a navy perfume bottle — is darker than parts of the interface,
 * and cropping by brightness threw away the left half of exactly that frame in testing.
 *
 * What separates them is that the app's background is *inert*: uniformly black and perfectly flat.
 * A photograph is never flat, however dark; sensor noise alone gives it texture. So a row or column
 * counts as dead only when it is both very dark **and** has almost no variation along its length,
 * and the live region is the longest unbroken run of rows and columns that are not dead.
 *
 * Taking the longest run rather than trimming from the edges is what handles the control column:
 * the buttons themselves are lively, but the gutter of pure black between them and the image is
 * not, so the run stops there and the controls are left outside.
 *
 * ## When it declines
 *
 * A phone screenshot is live edge to edge, so the longest run is the whole frame and no crop is
 * proposed. That is the important safety property: this can only ever remove inert margins, and on
 * anything that is not letterboxed it does nothing at all.
 *
 * Verified against real captures before it was written: three "Share your view" frames — a bright
 * blue tin, a dark navy bottle, a phone screen held up to the camera — plus two ordinary photos.
 * All three shared views dropped the control column; both photos were left alone.
 */
object Viewport {

    /** Fractions of the frame, so the answer survives a change of capture resolution. */
    data class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
        val area: Float get() = width * height

        fun fields(): Map<String, Any?> = mapOf(
            "viewportLeft" to left,
            "viewportTop" to top,
            "viewportRight" to right,
            "viewportBottom" to bottom,
            "viewportArea" to area,
        )
    }

    /**
     * The live region of [plane], or null when the whole frame is live and there is nothing to trim.
     *
     * Null is the common and correct answer for a phone screen; it means "use the frame as it is"
     * rather than "something went wrong".
     */
    fun detect(plane: ImagePlane): Rect? {
        if (plane.width < MIN_SIZE || plane.height < MIN_SIZE) return null

        val liveColumns = BooleanArray(plane.width) { x -> !columnIsInert(plane, x) }
        val liveRows = BooleanArray(plane.height) { y -> !rowIsInert(plane, y) }

        val columns = longestRun(liveColumns) ?: return null
        val rows = longestRun(liveRows) ?: return null
        if (columns.second - columns.first + 1 < plane.width * MIN_SPAN) return null
        if (rows.second - rows.first + 1 < plane.height * MIN_SPAN) return null

        val rect = Rect(
            left = columns.first.toFloat() / plane.width,
            top = rows.first.toFloat() / plane.height,
            right = (columns.second + 1).toFloat() / plane.width,
            bottom = (rows.second + 1).toFloat() / plane.height,
        )
        if (rect.area < MIN_AREA) return null
        // Almost the whole frame is not a viewport, it is a frame. Cropping it would cost a
        // re-scale and a re-plan for nothing.
        if ((1f - rect.width) + (1f - rect.height) < MINIMUM_WORTHWHILE_TRIM) return null
        return rect
    }

    /**
     * Dark *and* flat. Either alone is not enough: a night photograph is dark but textured, and a
     * plain white margin is flat but bright, and neither is a letterbox bar.
     */
    private fun columnIsInert(plane: ImagePlane, x: Int): Boolean {
        var sum = 0.0
        var variation = 0.0
        for (y in 0 until plane.height) {
            val value = plane.luma(x, y)
            sum += value
            if (y > 0) variation += kotlin.math.abs(value - plane.luma(x, y - 1))
        }
        val mean = sum / plane.height
        val activity = variation / maxOf(1, plane.height - 1)
        return mean <= INERT_LUMA && activity <= INERT_ACTIVITY
    }

    private fun rowIsInert(plane: ImagePlane, y: Int): Boolean {
        var sum = 0.0
        var variation = 0.0
        for (x in 0 until plane.width) {
            val value = plane.luma(x, y)
            sum += value
            if (x > 0) variation += kotlin.math.abs(value - plane.luma(x - 1, y))
        }
        val mean = sum / plane.width
        val activity = variation / maxOf(1, plane.width - 1)
        return mean <= INERT_LUMA && activity <= INERT_ACTIVITY
    }

    /** Start and end indices of the longest unbroken true run, or null when there is none. */
    private fun longestRun(flags: BooleanArray): Pair<Int, Int>? {
        var bestStart = -1
        var bestLength = 0
        var start = -1
        for (index in flags.indices) {
            if (flags[index]) {
                if (start < 0) start = index
                val length = index - start + 1
                if (length > bestLength) {
                    bestLength = length
                    bestStart = start
                }
            } else {
                start = -1
            }
        }
        if (bestLength == 0) return null
        return bestStart to (bestStart + bestLength - 1)
    }

    private const val MIN_SIZE = 24

    /** Mean luminance at or below which a strip carries nothing, on a 0..255 plane. */
    private const val INERT_LUMA = 12.0

    /** Mean absolute step between neighbouring pixels along the strip. Sensor noise exceeds this. */
    private const val INERT_ACTIVITY = 2.0

    private const val MIN_SPAN = 0.20f
    private const val MIN_AREA = 0.15f

    /** Under this much total trimming the crop is not worth the rescale it would cost. */
    private const val MINIMUM_WORTHWHILE_TRIM = 0.05f
}
