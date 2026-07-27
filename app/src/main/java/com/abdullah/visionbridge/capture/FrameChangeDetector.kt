package com.abdullah.visionbridge.capture

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs

/**
 * Tiny perceptual signature used to avoid paying for and speaking duplicate frames.
 * It intentionally samples only 16x16 pixels, keeping CPU and battery cost small.
 */
class FrameChangeDetector {
    private var previous: IntArray? = null
    private var lastAcceptedAt: Long = 0L

    @Synchronized
    fun shouldProcess(
        bitmap: Bitmap,
        minimumDifference: Double,
        forceAfterMs: Long,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        val signature = signature(bitmap)
        val old = previous
        val force = now - lastAcceptedAt >= forceAfterMs
        val difference = if (old == null) Double.MAX_VALUE else {
            signature.indices.sumOf { abs(signature[it] - old[it]).toDouble() } / signature.size
        }
        val accepted = force || difference >= minimumDifference
        if (accepted) {
            previous = signature
            lastAcceptedAt = now
        }
        return accepted
    }

    @Synchronized
    fun reset() {
        previous = null
        lastAcceptedAt = 0L
    }

    private fun signature(bitmap: Bitmap): IntArray {
        val scaled = Bitmap.createScaledBitmap(bitmap, GRID, GRID, true)
        return try {
            IntArray(GRID * GRID).also { values ->
                var index = 0
                for (y in 0 until GRID) {
                    for (x in 0 until GRID) {
                        val color = scaled.getPixel(x, y)
                        values[index++] = (
                            Color.red(color) * 299 +
                                Color.green(color) * 587 +
                                Color.blue(color) * 114
                            ) / 1000
                    }
                }
            }
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    private companion object { const val GRID = 16 }
}
