package com.abdullah.visionbridge.capture

import android.graphics.Bitmap
import com.abdullah.visionbridge.capture.vision.FramePyramid
import com.abdullah.visionbridge.capture.vision.ImagePlane
import com.abdullah.visionbridge.capture.vision.TrackedFrame

/**
 * Turns a captured frame into the pyramid the tracker registers against.
 *
 * The base resolution is the one real decision here. The signature this replaces was 24×24, which
 * on a 1080-wide capture made a single cell about 45 pixels — wider than a steady hand drifts
 * between frames, so the smallest motion it could represent was already larger than the tremor it
 * needed to forgive. At 160 a cell is about 7 pixels, corners survive, and a page of text still has
 * structure to register against, while the whole pyramid is 34,000 samples and costs a fraction of
 * a millisecond to compare.
 */
object BitmapFrames {

    fun trackedFrame(bitmap: Bitmap, baseSize: Int = BASE_SIZE, depth: Int = DEPTH): TrackedFrame =
        TrackedFrame(FramePyramid.of(planeOf(bitmap, baseSize), depth))

    /**
     * A small plane that keeps the frame's aspect ratio, for anything that reasons about *where*
     * things are rather than how they moved. The tracker's square plane is deliberately distorted;
     * viewport detection needs the real shape, because the thing it is looking for is a rectangle.
     */
    fun aspectPlane(bitmap: Bitmap, longEdge: Int = VIEWPORT_EDGE): ImagePlane {
        val source = maxOf(bitmap.width, bitmap.height).coerceAtLeast(1)
        val factor = longEdge.toDouble() / source
        val width = (bitmap.width * factor).toInt().coerceAtLeast(8)
        val height = (bitmap.height * factor).toInt().coerceAtLeast(8)
        val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
        return try {
            val pixels = IntArray(width * height)
            scaled.getPixels(pixels, 0, width, 0, 0, width, height)
            ImagePlane.fromArgb(width, height, pixels)
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    private fun planeOf(bitmap: Bitmap, baseSize: Int): ImagePlane {
        // Square, so a rotation of the subject cannot change the aspect the tracker reasons about.
        val scaled = Bitmap.createScaledBitmap(bitmap, baseSize, baseSize, true)
        return try {
            val pixels = IntArray(baseSize * baseSize)
            scaled.getPixels(pixels, 0, baseSize, 0, 0, baseSize, baseSize)
            ImagePlane.fromArgb(baseSize, baseSize, pixels)
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    const val BASE_SIZE = 128
    const val DEPTH = 4

    /** Wide enough to resolve a letterbox gutter, small enough to cost nothing per frame. */
    const val VIEWPORT_EDGE = 160
}
