package com.abdullah.visionbridge.capture

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Detects meaningful visual changes and returns the measurements behind every accept/reject choice.
 *
 * Stable text capture deliberately does not require a hand-held camera to become pixel-perfectly
 * motionless. Once a genuinely different target remains present for a short settling window, the
 * next usable frame is accepted. A periodic refresh also retries a static screen, so one incomplete
 * OCR response can never permanently silence the rest of the text.
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
    private var candidateSince: Long = 0L
    private var lastAcceptedAt: Long = 0L

    @Synchronized
    fun evaluateStable(
        bitmap: Bitmap,
        minimumMeanDifference: Double,
        minimumChangedRatio: Double,
        stableForMs: Long,
        now: Long = System.currentTimeMillis(),
    ): Decision {
        val quality = evaluateUsability(bitmap)
        if (!quality.accepted) {
            return Decision(
                accepted = false,
                reason = "quality_${quality.reason}",
                meanAbsoluteDifference = null,
                changedPixelRatio = null,
            )
        }

        val signature = signature(bitmap)
        val accepted = acceptedSignature
        if (accepted == null) {
            accept(signature, now)
            return Decision(
                accepted = true,
                reason = "initial_usable_frame",
                meanAbsoluteDifference = null,
                changedPixelRatio = null,
            )
        }

        val change = difference(accepted, signature)
        if (now - lastAcceptedAt >= FORCE_STABLE_REFRESH_MS) {
            accept(signature, now)
            return Decision(
                accepted = true,
                reason = "periodic_static_text_refresh",
                meanAbsoluteDifference = change.meanAbsoluteDifference,
                changedPixelRatio = change.changedPixelRatio,
            )
        }

        // OR is intentional. Small text can alter only a narrow part of the screen while still being
        // a completely new target. Requiring both values discarded labels, settings rows, and cards.
        val meaningfulChange =
            change.meanAbsoluteDifference >= minimumMeanDifference ||
                change.changedPixelRatio >= minimumChangedRatio

        if (!meaningfulChange) {
            candidateSince = 0L
            return Decision(
                accepted = false,
                reason = "below_stable_change_thresholds",
                meanAbsoluteDifference = change.meanAbsoluteDifference,
                changedPixelRatio = change.changedPixelRatio,
            )
        }

        if (candidateSince == 0L) {
            candidateSince = now
            return Decision(
                accepted = false,
                reason = "new_target_settling_started",
                meanAbsoluteDifference = change.meanAbsoluteDifference,
                changedPixelRatio = change.changedPixelRatio,
                candidateStableElapsedMs = 0L,
            )
        }

        // Do not restart this timer for harmless hand tremor. The old implementation compared every
        // frame with the first candidate and therefore could wait forever while a readable bottle or
        // remote control remained in view.
        val stableElapsed = now - candidateSince
        val requiredSettlingMs = stableForMs.coerceAtMost(MAX_STABLE_SETTLING_MS)
        if (stableElapsed < requiredSettlingMs) {
            return Decision(
                accepted = false,
                reason = "waiting_for_target_settling",
                meanAbsoluteDifference = change.meanAbsoluteDifference,
                changedPixelRatio = change.changedPixelRatio,
                candidateStableElapsedMs = stableElapsed,
            )
        }

        accept(signature, now)
        return Decision(
            accepted = true,
            reason = "settled_changed_frame_accepted",
            meanAbsoluteDifference = change.meanAbsoluteDifference,
            changedPixelRatio = change.changedPixelRatio,
            candidateStableElapsedMs = stableElapsed,
        )
    }

    @Synchronized
    fun evaluateFast(
        bitmap: Bitmap,
        minimumMeanDifference: Double,
        minimumChangedRatio: Double,
    ): Decision {
        // Fast text and scene description must remain available in dark environments. The quality
        // gate is intentionally limited to stable OCR, where waiting for a better frame is safe.
        val signature = signature(bitmap)
        val accepted = acceptedSignature
        if (accepted == null) {
            accept(signature, System.currentTimeMillis())
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
        if (acceptedFast) accept(signature, System.currentTimeMillis())
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
        candidateSince = 0L
        lastAcceptedAt = 0L
    }

    private fun accept(signature: IntArray, now: Long) {
        acceptedSignature = signature
        candidateSince = 0L
        lastAcceptedAt = now
    }

    /**
     * Cheap central-content quality guard for stable OCR.
     *
     * A raw edge-count would reject a perfectly sharp perfume label simply because it contains only
     * a few words. Instead this computes the variance of a small Laplacian focus map. Sparse but crisp
     * letters and object boundaries produce strong second derivatives; a finger, cloth, or motion
     * smear remains smooth. Thresholds were calibrated against the exact Xiaomi diagnostic frames:
     * readable settings were around 3,000–4,500 while occluded frames were around 690–850.
     */
    private fun evaluateUsability(bitmap: Bitmap): FrameUsability {
        val largest = maxOf(bitmap.width, bitmap.height).coerceAtLeast(1)
        val ratio = QUALITY_LONG_EDGE.toFloat() / largest
        val width = (bitmap.width * ratio).roundToInt().coerceAtLeast(8)
        val height = (bitmap.height * ratio).roundToInt().coerceAtLeast(8)
        val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
        return try {
            val top = (height * CONTENT_TOP_FRACTION).roundToInt().coerceIn(0, height - 2)
            val bottom = (height * CONTENT_BOTTOM_FRACTION).roundToInt().coerceIn(top + 2, height)
            val contentHeight = bottom - top
            val histogram = IntArray(256)
            val luminance = IntArray(width * contentHeight)
            var dark = 0
            var bright = 0
            var edge = 0
            var samples = 0

            for (y in top until bottom) {
                var previous = -1
                val contentRow = (y - top) * width
                for (x in 0 until width) {
                    val color = scaled.getPixel(x, y)
                    val gray = (
                        Color.red(color) * 299 +
                            Color.green(color) * 587 +
                            Color.blue(color) * 114
                        ) / 1000
                    luminance[contentRow + x] = gray
                    histogram[gray]++
                    if (gray <= DARK_LUMA) dark++
                    if (gray >= BRIGHT_LUMA) bright++
                    if (previous >= 0 && abs(gray - previous) >= EDGE_DELTA) edge++
                    previous = gray
                    samples++
                }
            }

            if (samples == 0) return FrameUsability(false, "empty")
            val darkRatio = dark.toDouble() / samples
            val brightRatio = bright.toDouble() / samples
            val edgeRatio = edge.toDouble() / samples
            val range = percentile(histogram, samples, 0.95) - percentile(histogram, samples, 0.05)
            val focusVariance = laplacianVariance(luminance, width, contentHeight)

            when {
                darkRatio >= 0.965 && edgeRatio < 0.010 -> FrameUsability(false, "almost_black")
                brightRatio >= 0.70 && edgeRatio < 0.012 -> FrameUsability(false, "overexposed")
                range <= 14 && edgeRatio < 0.008 -> FrameUsability(false, "blank_low_contrast")
                brightRatio >= BRIGHT_OCCLUSION_RATIO && focusVariance < BRIGHT_OCCLUSION_MAX_FOCUS ->
                    FrameUsability(false, "bright_occlusion")
                focusVariance < MIN_FOCUS_VARIANCE -> FrameUsability(false, "blurred_or_occluded")
                else -> FrameUsability(true, "usable")
            }
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    private fun laplacianVariance(values: IntArray, width: Int, height: Int): Double {
        if (width < 3 || height < 3) return 0.0
        var count = 0L
        var sum = 0.0
        var squareSum = 0.0
        for (y in 1 until height - 1) {
            val row = y * width
            for (x in 1 until width - 1) {
                val index = row + x
                val laplacian =
                    values[index - width] + values[index + width] +
                        values[index - 1] + values[index + 1] -
                        4 * values[index]
                val value = laplacian.toDouble()
                sum += value
                squareSum += value * value
                count++
            }
        }
        if (count == 0L) return 0.0
        val mean = sum / count
        return (squareSum / count - mean * mean).coerceAtLeast(0.0)
    }

    private fun percentile(histogram: IntArray, total: Int, fraction: Double): Int {
        val target = (total * fraction).roundToInt().coerceAtLeast(1)
        var cumulative = 0
        for (value in histogram.indices) {
            cumulative += histogram[value]
            if (cumulative >= target) return value
        }
        return 255
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

    private data class FrameUsability(
        val accepted: Boolean,
        val reason: String,
    )

    private companion object {
        const val GRID = 24
        const val PIXEL_CHANGE_THRESHOLD = 18
        const val MAX_STABLE_SETTLING_MS = 650L
        const val FORCE_STABLE_REFRESH_MS = 5_000L

        const val QUALITY_LONG_EDGE = 192
        const val CONTENT_TOP_FRACTION = 0.13
        const val CONTENT_BOTTOM_FRACTION = 0.76
        const val DARK_LUMA = 12
        const val BRIGHT_LUMA = 248
        const val EDGE_DELTA = 24
        const val MIN_FOCUS_VARIANCE = 950.0
        const val BRIGHT_OCCLUSION_RATIO = 0.050
        const val BRIGHT_OCCLUSION_MAX_FOCUS = 2_000.0
    }
}
