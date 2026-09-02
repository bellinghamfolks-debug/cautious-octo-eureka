package com.abdullah.visionbridge.domain.usecase

import com.abdullah.visionbridge.domain.model.AnalysisMode
import com.abdullah.visionbridge.domain.model.CaptureProfile
import com.abdullah.visionbridge.domain.model.SceneDescriptionStyle
import org.junit.Assert.assertEquals
import org.junit.Test

class AnalyzeFrameUseCaseLatencyTest {
    @Test
    fun `fast untrusted text uses flash lite`() {
        assertEquals(
            LOW_LATENCY_VISION_MODEL,
            selectVisionModel(
                requestedModel = "gemini-3.6-flash",
                mode = AnalysisMode.TEXT_READING,
                captureProfile = CaptureProfile.FAST_TEXT,
                sceneDescriptionStyle = SceneDescriptionStyle.COMPREHENSIVE,
                trustGateEnabled = false,
            ),
        )
    }

    @Test
    fun `trusted stable text keeps requested quality model`() {
        assertEquals(
            "gemini-3.6-flash",
            selectVisionModel(
                requestedModel = "gemini-3.6-flash",
                mode = AnalysisMode.TEXT_READING,
                captureProfile = CaptureProfile.STABLE,
                sceneDescriptionStyle = SceneDescriptionStyle.COMPREHENSIVE,
                trustGateEnabled = true,
            ),
        )
    }

    @Test
    fun `brief scene uses flash lite but comprehensive keeps requested model`() {
        assertEquals(
            LOW_LATENCY_VISION_MODEL,
            selectVisionModel(
                requestedModel = "gemini-3.6-flash",
                mode = AnalysisMode.SCENE_DESCRIPTION,
                captureProfile = CaptureProfile.STABLE,
                sceneDescriptionStyle = SceneDescriptionStyle.BRIEF,
                trustGateEnabled = false,
            ),
        )
        assertEquals(
            "gemini-3.6-flash",
            selectVisionModel(
                requestedModel = "gemini-3.6-flash",
                mode = AnalysisMode.SCENE_DESCRIPTION,
                captureProfile = CaptureProfile.STABLE,
                sceneDescriptionStyle = SceneDescriptionStyle.COMPREHENSIVE,
                trustGateEnabled = false,
            ),
        )
    }
}
