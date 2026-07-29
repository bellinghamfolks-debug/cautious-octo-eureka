package com.abdullah.visionbridge.capture

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs

/**
 * Detects meaningful visual changes and returns the measurements behind every accept/reject choice.
 * Boolean wrappers remain for compatibility; diagnostics use the detailed decision methods.
 */
class FrameChangeDetector {
    data class Decision(
        val accepted: Boolean,
        val reason: String,
        val meanAbsoluteDifference: Double?,
        val changedPixelRatio: Double?,
        val candidateMeanAbsoluteDifference: Double? = null,
        val candidateChangedPixelRatio: Double? = null,
        val candidateStableElapsedMs: Long? = null,
    )

    private var acceptedSignature: IntArray? = null
    private var candidateSignature: IntArray? = null
    private var candidateSince: Long = 0L

    @Synchronized
    fun evaluateStable(
        bitmap: Bitmap,
        minimumMeanDifference: Double,
        minimumChangedRatio: Double,
        stableForMs: Long,
        now: Long = System.currentTimeMillis(),
    ): Decision {
        val signature = signature(bitmap)
        val accepted = acceptedSignature
        if (accepted == null) {
            accept(signature)
            return Decision(
                accepted = true,
                reason = "initial_frame",
                meanAbsoluteDifference = null,
                changedPixelRatio = null,
            )
        }

        val change = difference(accepted, signature)
        val meaningfulChange =
            change.meanAbsoluteDifference >= minimumMeanDifference &&
                change.changedPixelRatio >= minimumChangedRatio

        if (!meaningfulChange) {
            candidateSignature = null
            candidateSince = 0L
            return Decision(
                accepted = false,
                reason = "below_stable_change_thresholds",
                meanAbsoluteDifference = change.meanAbsoluteDifference,
                changedPixelRatio = change.changedPixelRatio,
            )
        }

        val candidate = candidateSignature
        if (candidate == null) {
            candidateSignature = signature
            candidateSince = now
            return Decision(
                accepted = false,
                reason = "stability_candidate_started",
                meanAbsoluteDifference = change.meanAbsoluteDifference,
                changedPixelRatio = change.changedPixelRatio,
                candidateStableElapsedMs = 0L,
            )
        }

        val candidateDrift = difference(candidate, signature)
        val sceneIsStable =
            candidateDrift.meanAbsoluteDifference <= MAX_STABLE_MEAN_DIFFERENCE &&
                candidateDrift.changedPixelRatio <= MAX_STABLE_CHANGED_RATIO

        if (!sceneIsStable) {
            candidateSignature = signature
            candidateSince = now
            return Decision(
                accepted = false,
                reason = "stability_candidate_moved",
                meanAbsoluteDifference = change.meanAbsoluteDifference,
                changedPixelRatio = change.changedPixelRatio,
                candidateMeanAbsoluteDifference = candidateDrift.meanAbsoluteDifference,
                candidateChangedPixelRatio = candidateDrift.changedPixelRatio,
                candidateStableElapsedMs = 0L,
            )
        }

        val stableElapsed = now - candidateSince
        if (stableElapsed < stableForMs) {
            return Decision(
                accepted = false,
                reason = "waiting_for_stability_duration",
                meanAbsoluteDifference = change.meanAbsoluteDifference,
                changedPixelRatio = change.changedPixelRatio,
                candidateMeanAbsoluteDifference = candidateDrift.meanAbsoluteDifference,
                candidateChangedPixelRatio = candidateDrift.changedPixelRatio,
                candidateStableElapsedMs = stableElapsed,
            )
        }

        accept(signature)
        return Decision(
            accepted = true,
            reason = "stable_changed_frame_accepted",
            meanAbsoluteDifference = change.meanAbsoluteDifference,
            changedPixelRatio = change.changedPixelRatio,
            candidateMeanAbsoluteDifference = candidateDrift.meanAbsoluteDifference,
            candidateChangedPixelRatio = candidateDrift.changedPixelRatio,
            candidateStableElapsedMs = stableElapsed,
        )
    }

    @Synchronized
    fun evaluateFast(
        bitmap: Bitmap,
        minimumMeanDifference: Double,
        minimumChangedRatio: Double,
    ): Decision {
        val signature = signature(bitmap)
        val accepted = acceptedSignature
        if (accepted == null) {
            accept(signature)
            return Decision(
                accepted = true,
                reason = "initial_frame",
                meanAbsoluteDifference = null,
                changedPixelRatio = null,
            )
        }
        val change = difference(accepted, signature)
        val acceptedFast =
            change.meanAbsoluteDifference >= minimumMeanDifference ||
                change.changedPixelRatio >= minimumChangedRatio
        if (acceptedFast) accept(signature)
        return Decision(
            accepted = acceptedFast,
            reason = if (acceptedFast) "fast_change_threshold_met" else "below_fast_change_thresholds",
            meanAbsoluteDifference = change.meanAbsoluteDifference,
            changedPixelRatio = change.changedPixelRatio,
        )
    }

    fun shouldProcessStable(
        bitmap: Bitmap,
        minimumMeanDifference: Double,
        minimumChangedRatio: Double,
        stableForMs: Long,
        now: Long = System.currentTimeMillis(),
    ): Boolean = evaluateStable(
        bitmap = bitmap,
        minimumMeanDifference = minimumMeanDifference,
        minimumChangedRatio = minimumChangedRatio,
        stableForMs = stableForMs,
        now = now,
    ).accepted

    fun shouldProcessFast(
        bitmap: Bitmap,
        minimumMeanDifference: Double,
        minimumChangedRatio: Double,
    ): Boolean = evaluateFast(
        bitmap = bitmap,
        minimumMeanDifference = minimumMeanDifference,
        minimumChangedRatio = minimumChangedRatio,
    ).accepted

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
