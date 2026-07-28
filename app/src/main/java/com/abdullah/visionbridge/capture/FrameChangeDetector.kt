package com.abdullah.visionbridge.capture

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs

/**
 * Detects meaningful visual changes with two selectable behaviours:
 * - stable mode waits until movement settles before analysis;
 * - fast-text mode accepts a changed frame immediately for moving captions and scrolling text.
 */
class FrameChangeDetector {
    private var acceptedSignature: IntArray? = null
    private var candidateSignature: IntArray? = null
    private var candidateSince: Long = 0L

    @Synchronized
    fun shouldProcessStable(
        bitmap: Bitmap,
        minimumMeanDifference: Double,
        minimumChangedRatio: Double,
        stableForMs: Long,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        val signature = signature(bitmap)
        val accepted = acceptedSignature
        if (accepted == null) {
            accept(signature)
            return true
        }

        val change = difference(accepted, signature)
        val meaningfulChange =
            change.meanAbsoluteDifference >= minimumMeanDifference &&
                change.changedPixelRatio >= minimumChangedRatio

        if (!meaningfulChange) {
            candidateSignature = null
            candidateSince = 0L
            return false
        }

        val candidate = candidateSignature
        if (candidate == null) {
            candidateSignature = signature
            candidateSince = now
            return false
        }

        val candidateDrift = difference(candidate, signature)
        val sceneIsStable =
            candidateDrift.meanAbsoluteDifference <= MAX_STABLE_MEAN_DIFFERENCE &&
                candidateDrift.changedPixelRatio <= MAX_STABLE_CHANGED_RATIO

        if (!sceneIsStable) {
            candidateSignature = signature
            candidateSince = now
            return false
        }

        if (now - candidateSince < stableForMs) return false
        accept(signature)
        return true
    }

    /**
     * Accepts a visually changed frame without waiting for stability. The threshold remains high
     * enough to reject sensor shimmer while allowing fast subtitles, tickers, and scrolling text.
     */
    @Synchronized
    fun shouldProcessFast(
        bitmap: Bitmap,
        minimumMeanDifference: Double,
        minimumChangedRatio: Double,
    ): Boolean {
        val signature = signature(bitmap)
        val accepted = acceptedSignature
        if (accepted == null) {
            accept(signature)
            return true
        }
        val change = difference(accepted, signature)
        val acceptedFast =
            change.meanAbsoluteDifference >= minimumMeanDifference ||
                change.changedPixelRatio >= minimumChangedRatio
        if (acceptedFast) accept(signature)
        return acceptedFast
    }

    @Synchronized
    fun reset() {
        acceptedSignature = null
        candidateSignature = null
        candidateSince = 0L
    }

    private fun accept(signature: IntArray) {
        acceptedSignature = signature
        candidateSignature = null
        candidateSince = 0L
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

    private fun difference(first: IntArray, second: IntArray): FrameDifference {
        var absoluteTotal = 0L
        var changedPixels = 0
        for (index in first.indices) {
            val delta = abs(first[index] - second[index])
            absoluteTotal += delta
            if (delta >= PIXEL_CHANGE_THRESHOLD) changedPixels++
        }
        return FrameDifference(
            meanAbsoluteDifference = absoluteTotal.toDouble() / first.size,
            changedPixelRatio = changedPixels.toDouble() / first.size,
        )
    }

    private data class FrameDifference(
        val meanAbsoluteDifference: Double,
        val changedPixelRatio: Double,
    )

    private companion object {
        const val GRID = 24
        const val PIXEL_CHANGE_THRESHOLD = 18
        const val MAX_STABLE_MEAN_DIFFERENCE = 3.5
        const val MAX_STABLE_CHANGED_RATIO = 0.04
    }
}
