package com.abdullah.visionbridge.capture.vision

import com.abdullah.visionbridge.domain.model.AnalysisMode
import com.abdullah.visionbridge.domain.model.ViewportMode
import kotlin.math.roundToInt

/**
 * Calibrated eSight Share Your View viewport.
 *
 * Reference capture supplied from the real headset app: 1356 x 610 pixels. The live camera image
 * occupies x=68..1034 and y=76..533 inclusive. The fractions below deliberately come from those
 * measured pixel boundaries rather than from brightness/content heuristics.
 */
object EsightViewportCalibration {
    const val REFERENCE_WIDTH = 1356
    const val REFERENCE_HEIGHT = 610
    const val REFERENCE_LEFT_PX = 68
    const val REFERENCE_TOP_PX = 76
    const val REFERENCE_RIGHT_EXCLUSIVE_PX = 1035
    const val REFERENCE_BOTTOM_EXCLUSIVE_PX = 534

    val rect = Viewport.Rect(
        left = REFERENCE_LEFT_PX.toFloat() / REFERENCE_WIDTH,
        top = REFERENCE_TOP_PX.toFloat() / REFERENCE_HEIGHT,
        right = REFERENCE_RIGHT_EXCLUSIVE_PX.toFloat() / REFERENCE_WIDTH,
        bottom = REFERENCE_BOTTOM_EXCLUSIVE_PX.toFloat() / REFERENCE_HEIGHT,
    )

    /**
     * Fixed eSight geometry is used only when the capture is landscape and plausibly phone-shaped.
     * A portrait/odd-aspect capture falls back to automatic detection instead of applying a bad crop.
     */
    fun rectFor(
        mode: ViewportMode,
        analysisMode: AnalysisMode,
        width: Int,
        height: Int,
    ): Viewport.Rect? {
        val wantsFixed = mode == ViewportMode.ESIGHT_FIXED ||
            (mode == ViewportMode.ESIGHT_TEXT_SAFE && analysisMode == AnalysisMode.TEXT_READING)
        if (!wantsFixed || width <= 0 || height <= 0) return null
        val aspect = width.toFloat() / height.toFloat()
        if (aspect !in MIN_LANDSCAPE_ASPECT..MAX_LANDSCAPE_ASPECT) return null
        return rect
    }

    fun pixelFields(width: Int, height: Int): Map<String, Any?> {
        val left = (rect.left * width).roundToInt().coerceIn(0, width)
        val top = (rect.top * height).roundToInt().coerceIn(0, height)
        val right = (rect.right * width).roundToInt().coerceIn(left, width)
        val bottom = (rect.bottom * height).roundToInt().coerceIn(top, height)
        return mapOf(
            "referenceWidth" to REFERENCE_WIDTH,
            "referenceHeight" to REFERENCE_HEIGHT,
            "appliedLeftPx" to left,
            "appliedTopPx" to top,
            "appliedRightExclusivePx" to right,
            "appliedBottomExclusivePx" to bottom,
            "appliedWidthPx" to (right - left),
            "appliedHeightPx" to (bottom - top),
        )
    }

    private const val MIN_LANDSCAPE_ASPECT = 1.70f
    private const val MAX_LANDSCAPE_ASPECT = 2.60f
}
