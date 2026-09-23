package com.abdullah.visionbridge
import com.abdullah.visionbridge.domain.model.*
import org.junit.Assert.*
import org.junit.Test
class AnalysisReadinessTest {
    @Test fun cloudTextWithoutKeyIsBlocked() {
        assertNotNull(AnalysisReadiness.error(AppSettings(useLocalOcr=false), false))
    }
    @Test fun localTextWorksWithoutKey() {
        assertNull(AnalysisReadiness.error(AppSettings(mode=AnalysisMode.TEXT_READING,useLocalOcr=true), false))
    }
    @Test fun sceneStillRequiresKeyWhenLocalTextIsSelected() {
        assertNotNull(AnalysisReadiness.error(AppSettings(mode=AnalysisMode.SCENE_DESCRIPTION,useLocalOcr=true), false))
    }
    @Test fun validKeyAllowsCloudModes() {
        for(mode in AnalysisMode.entries) assertNull(AnalysisReadiness.error(AppSettings(mode=mode), true))
    }
}
