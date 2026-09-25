package com.abdullah.visionbridge.capture.vision

import com.abdullah.visionbridge.domain.model.AnalysisMode
import com.abdullah.visionbridge.domain.model.ViewportMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether the calibrated eSight window is used, and — the part that costs time when it is missing —
 * why not.
 *
 * A field bundle on 2026-09-25 was reported as "the glasses window was ignored". The rectangle was
 * indeed not the eSight one, and nothing in the export said whether the geometry had been rejected,
 * never requested, or simply had not matched. Answering that took reading the captured frames to
 * discover the session had never gone through the headset at all: it was the phone's own camera
 * app, held upright, which the landscape reference fractions cannot describe. That answer belongs
 * in one field of the bundle, not in an afternoon of image forensics.
 */
class EsightViewportCalibrationTest {

    /** The reference capture the fractions were measured from. */
    private val referenceWidth = EsightViewportCalibration.REFERENCE_WIDTH
    private val referenceHeight = EsightViewportCalibration.REFERENCE_HEIGHT

    private fun decide(
        mode: ViewportMode = ViewportMode.ESIGHT_TEXT_SAFE,
        analysisMode: AnalysisMode = AnalysisMode.TEXT_READING,
        width: Int = referenceWidth,
        height: Int = referenceHeight,
    ) = EsightViewportCalibration.decide(mode, analysisMode, width, height)

    // region the window is used when it applies

    @Test
    fun `the reference capture gets the calibrated window`() {
        val decision = decide()
        assertNotNull(decision.rect)
        assertEquals("reference_geometry", decision.reason)
        assertEquals(true, decision.fields["esightCalibrationApplied"])
    }

    /** The fractions must still describe the pixels they were measured from. */
    @Test
    fun `the calibrated window is the measured pixel boundary`() {
        val rect = EsightViewportCalibration.rect
        assertEquals(
            EsightViewportCalibration.REFERENCE_LEFT_PX.toFloat() / referenceWidth, rect.left, 1e-6f,
        )
        assertEquals(
            EsightViewportCalibration.REFERENCE_RIGHT_EXCLUSIVE_PX.toFloat() / referenceWidth,
            rect.right,
            1e-6f,
        )
        // The controls down the right-hand side of Share Your View sit outside the camera image.
        assertTrue("the control column must be excluded: ${rect.right}", rect.right < 0.80f)
    }

    /** A larger capture of the same layout is the same window, because the fractions scale. */
    @Test
    fun `a differently sized landscape capture still gets the window`() {
        assertNotNull(decide(width = 2712, height = 1220).rect)
    }

    // endregion

    // region and it says why when it does not

    /**
     * The case from the field. The phone was upright and the capture was 1220x2712, so the
     * landscape fractions would have cropped the wrong part of the screen entirely.
     */
    @Test
    fun `an upright capture is refused and says the aspect that refused it`() {
        val decision = decide(width = 1220, height = 2712)
        assertNull(decision.rect)
        assertTrue(
            "the reason must carry the aspect: ${decision.reason}",
            decision.reason.startsWith("capture_not_landscape_aspect_"),
        )
        assertEquals(false, decision.fields["esightCalibrationApplied"])
    }

    @Test
    fun `describing a scene does not use the text-safe window, and says so`() {
        val decision = decide(analysisMode = AnalysisMode.SCENE_DESCRIPTION)
        assertNull(decision.rect)
        assertTrue(decision.reason.startsWith("not_requested_for_"))
    }

    @Test
    fun `automatic mode does not use the fixed window, and says so`() {
        val decision = decide(mode = ViewportMode.AUTO)
        assertNull(decision.rect)
        assertTrue(decision.reason.startsWith("not_requested_for_"))
    }

    /** Fixed mode is fixed for both modes, not only for reading. */
    @Test
    fun `fixed mode applies to describing too`() {
        assertNotNull(
            decide(mode = ViewportMode.ESIGHT_FIXED, analysisMode = AnalysisMode.SCENE_DESCRIPTION)
                .rect,
        )
    }

    @Test
    fun `a frame with no size is refused rather than divided by zero`() {
        assertEquals("no_frame_size", decide(width = 0, height = 0).reason)
    }

    // endregion

    /** Every outcome has to be explainable; a blank reason is the failure this class prevents. */
    @Test
    fun `every decision carries a reason`() {
        for (mode in ViewportMode.entries) {
            for (analysisMode in AnalysisMode.entries) {
                for ((w, h) in listOf(1356 to 610, 1220 to 2712, 2712 to 1220, 0 to 0)) {
                    val decision = EsightViewportCalibration.decide(mode, analysisMode, w, h)
                    assertTrue(
                        "$mode/$analysisMode ${w}x$h had no reason",
                        decision.reason.isNotBlank(),
                    )
                }
            }
        }
    }
}
