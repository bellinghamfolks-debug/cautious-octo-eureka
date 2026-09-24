package com.abdullah.visionbridge

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.abdullah.visionbridge.data.gemini.FrameTurnTransport
import com.abdullah.visionbridge.domain.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FrameRequestPayloadOnDeviceTest {
    @Test fun imageAndInstructionRemainOneTurnWithExplicitThinking() {
        for(mode in AnalysisMode.entries) {
            val settings=AppSettings(mode=mode)
            val payload=FrameTurnTransport.payload("synthetic_fixture_only",settings)
            val contents=payload.getJSONArray("contents")
            assertEquals(1,contents.length())
            val parts=contents.getJSONObject(0).getJSONArray("parts")
            assertEquals(2,parts.length())
            assertEquals("synthetic_fixture_only",parts.getJSONObject(0).getJSONObject("inlineData").getString("data"))
            assertEquals(FrameTurnTransport.instruction(settings),parts.getJSONObject(1).getString("text"))
            val config=payload.getJSONObject("generationConfig")
            assertEquals(0,config.getInt("temperature"))
            assertEquals(if(mode==AnalysisMode.TEXT_READING) "minimal" else "low",
                config.getJSONObject("thinkingConfig").getString("thinkingLevel"))
            assertFalse("Media quality is not reduced without a benchmark",config.has("mediaResolution"))
        }
    }
}
