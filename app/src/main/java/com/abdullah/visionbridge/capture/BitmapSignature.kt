package com.abdullah.visionbridge.capture

import android.graphics.Bitmap
import android.graphics.Color

/**
 * Reduces a captured frame to the small luminance grid the target tracker works on.
 *
 * Kept apart from [VisualTargetTracker] so the tracking rules stay free of Android types and can be
 * exercised against constructed scenes on a plain JVM.
 */
object BitmapSignature {

    fun of(bitmap: Bitmap, grid: Int = FrameSignature.GRID): FrameSignature {
        val scaled = Bitmap.createScaledBitmap(bitmap, grid, grid, true)
        return try {
            val values = IntArray(grid * grid)
            var index = 0
            for (y in 0 until grid) {
                for (x in 0 until grid) {
                    val color = scaled.getPixel(x, y)
                    values[index++] = (
                        Color.red(color) * 299 +
                            Color.green(color) * 587 +
                            Color.blue(color) * 114
                        ) / 1000
                }
            }
            FrameSignature(grid, grid, values)
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }
}
