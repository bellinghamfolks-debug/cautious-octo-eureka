package com.abdullah.visionbridge.capture.vision

import com.abdullah.visionbridge.domain.model.AnalysisMode
import com.abdullah.visionbridge.domain.model.ViewportMode
import kotlin.math.abs

/** Layout evidence precedes calibration: device aspect ratio is not proof of an app layout. */
object ViewportResolver {
    data class Resolution(val rect: Viewport.Rect, val strategy: String)
    private val full = Viewport.Rect(0f, 0f, 1f, 1f)

    fun resolve(plane: ImagePlane, width: Int, height: Int, mode: ViewportMode,
                analysisMode: AnalysisMode): Resolution {
        require(width > 0 && height > 0)
        val measured = Viewport.detect(plane)
        val fixed = EsightViewportCalibration.rectFor(mode, analysisMode, width, height)
        if (fixed != null && measured != null && agrees(fixed, measured)) {
            return Resolution(fixed, "ESIGHT_REFERENCE_CONFIRMED")
        }
        // Rotation is accepted only when the actual image boundary agrees. Portrait applications
        // often reflow their controls, so rotating landscape fractions unconditionally loses text.
        if (mode != ViewportMode.AUTO && height > width && measured != null) {
            val r = EsightViewportCalibration.rect
            val clockwise = Viewport.Rect(1-r.bottom, r.left, 1-r.top, r.right)
            val counterclockwise = Viewport.Rect(r.top, 1-r.right, r.bottom, 1-r.left)
            val matched = listOf(clockwise, counterclockwise).firstOrNull { agrees(it, measured) }
            if (matched != null) return Resolution(matched, "ESIGHT_ROTATION_CONFIRMED")
        }
        if (width == 1220 && height == 2712) {
            // Explicit observed capture geometry; full width avoids clipping narrow dark packaging.
            val rect = measured?.let {
                if (analysisMode == AnalysisMode.TEXT_READING) it.copy(left=0f,right=1f) else it
            } ?: full
            return Resolution(rect, if (measured == null) "PORTRAIT_1220_UNRESOLVED_FULL_FRAME" else "PORTRAIT_1220_DYNAMIC")
        }
        return Resolution(measured ?: full, if (measured == null) "UNRESOLVED_FULL_FRAME" else "DYNAMIC_BOUNDARY")
    }

    private fun agrees(a: Viewport.Rect, b: Viewport.Rect): Boolean =
        abs(a.left-b.left) < .04f && abs(a.top-b.top) < .04f &&
            abs(a.right-b.right) < .04f && abs(a.bottom-b.bottom) < .04f
}
