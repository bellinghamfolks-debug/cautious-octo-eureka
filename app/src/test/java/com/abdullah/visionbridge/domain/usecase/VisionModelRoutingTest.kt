package com.abdullah.visionbridge.domain.usecase

import com.abdullah.visionbridge.domain.model.AnalysisMode
import com.abdullah.visionbridge.domain.model.AppSettings
import com.abdullah.visionbridge.domain.model.CaptureProfile
import com.abdullah.visionbridge.domain.model.SceneDescriptionStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionModelRoutingTest {
    @Test
    fun `Gemini 3_8 is the default smart vision model`() {
        assertEquals("gemini-3.8-flash", AppSettings.DEFAULT_MODEL)
        assertTrue("gemini-3.8-flash" in AppSettings.SUPPORTED_MODELS)
    }

    @Test
    fun `fast OCR always stays on Flash Lite for minimum latency`() {
        listOf(false, true).forEach { trustGateEnabled ->
            assertEquals(
                LOW_LATENCY_VISION_MODEL,
                selectVisionModel(
                    requestedModel = "gemini-3.8-flash",
                    mode = AnalysisMode.TEXT_READING,
                    captureProfile = CaptureProfile.FAST_TEXT,
                    sceneDescriptionStyle = SceneDescriptionStyle.COMPREHENSIVE,
                    trustGateEnabled = trustGateEnabled,
                ),
            )
        }
    }

    @Test
    fun `stable OCR uses Gemini 3_8`() {
        assertEquals(
            "gemini-3.8-flash",
            selectVisionModel(
                requestedModel = "gemini-3.8-flash",
                mode = AnalysisMode.TEXT_READING,
                captureProfile = CaptureProfile.STABLE,
                sceneDescriptionStyle = SceneDescriptionStyle.COMPREHENSIVE,
                trustGateEnabled = false,
            ),
        )
    }

    @Test
    fun `brief scenes stay on Flash Lite while comprehensive scenes use Gemini 3_8`() {
        val brief = selectVisionModel(
            requestedModel = "gemini-3.8-flash",
            mode = AnalysisMode.SCENE_DESCRIPTION,
            captureProfile = CaptureProfile.FAST_TEXT,
            sceneDescriptionStyle = SceneDescriptionStyle.BRIEF,
            trustGateEnabled = false,
        )
        val comprehensive = selectVisionModel(
            requestedModel = "gemini-3.8-flash",
            mode = AnalysisMode.SCENE_DESCRIPTION,
            captureProfile = CaptureProfile.FAST_TEXT,
            sceneDescriptionStyle = SceneDescriptionStyle.COMPREHENSIVE,
            trustGateEnabled = false,
        )
        assertEquals(LOW_LATENCY_VISION_MODEL, brief)
        assertEquals("gemini-3.8-flash", comprehensive)
    }
}
