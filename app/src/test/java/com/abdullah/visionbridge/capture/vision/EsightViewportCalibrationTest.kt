package com.abdullah.visionbridge.capture.vision

import com.abdullah.visionbridge.domain.model.AnalysisMode
import com.abdullah.visionbridge.domain.model.ViewportMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.roundToInt

class EsightViewportCalibrationTest {
    @Test
    fun `real 1356 by 610 reference maps to measured camera rectangle`() {
        val rect = EsightViewportCalibration.rectFor(
            mode = ViewportMode.ESIGHT_FIXED,
            analysisMode = AnalysisMode.TEXT_READING,
            width = 1356,
            height = 610,
        )
        assertNotNull(rect)
        rect!!
        assertEquals(68, (rect.left * 1356).roundToInt())
        assertEquals(76, (rect.top * 610).roundToInt())
        assertEquals(1035, (rect.right * 1356).roundToInt())
        assertEquals(534, (rect.bottom * 610).roundToInt())
    }

    @Test
    fun `text safe fixes eSight crop only for text reading`() {
        assertNotNull(
            EsightViewportCalibration.rectFor(
                mode = ViewportMode.ESIGHT_TEXT_SAFE,
                analysisMode = AnalysisMode.TEXT_READING,
                width = 1356,
                height = 610,
            )
        )
        assertNull(
            EsightViewportCalibration.rectFor(
                mode = ViewportMode.ESIGHT_TEXT_SAFE,
                analysisMode = AnalysisMode.SCENE_DESCRIPTION,
                width = 1356,
                height = 610,
            )
        )
    }

    @Test
    fun `portrait capture never receives landscape eSight calibration`() {
        assertNull(
            EsightViewportCalibration.rectFor(
                mode = ViewportMode.ESIGHT_FIXED,
                analysisMode = AnalysisMode.TEXT_READING,
                width = 610,
                height = 1356,
            )
        )
    }
}
