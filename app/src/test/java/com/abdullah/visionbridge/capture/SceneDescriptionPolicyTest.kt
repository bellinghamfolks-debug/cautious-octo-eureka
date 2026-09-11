package com.abdullah.visionbridge.capture

import com.abdullah.visionbridge.domain.model.SceneDescriptionStyle.*
import com.abdullah.visionbridge.domain.model.SceneDescriptionStyle
import org.junit.Assert.*
import org.junit.Test

class SceneDescriptionPolicyTest {
    @Test fun briefDescribesFirstFrameAndKeepsSilentForCompensatedMotion() {
        val p=SceneDescriptionPolicy()
        assertTrue(p.shouldProbe(0,BRIEF,0,null,null).accepted)
        assertTrue(p.output("باب مفتوح إلى اليمين.",95,true,false,BRIEF,"").accepted)
        assertFalse(p.shouldProbe(0,BRIEF,3000,.01,2.0).accepted)
        assertTrue(p.shouldProbe(1,BRIEF,3100,null,null).accepted)
    }
    @Test fun comprehensiveStartsWithTheImportantClauseAndThenAddsOnlyNewDetail() {
        val p=SceneDescriptionPolicy();p.shouldProbe(0,COMPREHENSIVE,0,null,null)
        val first="عائق في الممر أمامك."
        assertEquals(first,p.output(first,95,true,false,COMPREHENSIVE,"").delta)
        assertEquals("كرسي إلى اليسار.",p.output("$first كرسي إلى اليسار.",95,true,false,COMPREHENSIVE,first).delta)
        assertFalse(p.output("غرفة أخرى.",95,true,false,COMPREHENSIVE,first).accepted)
    }
    @Test fun switchingStyleIsAnIndependentFirstScene() {
        val p=SceneDescriptionPolicy();p.shouldProbe(0,BRIEF,0,null,null)
        p.output("باب مفتوح.",95,true,false,BRIEF,"")
        assertTrue(p.shouldProbe(0,COMPREHENSIVE,10,0.0,0.0).accepted)
        val detailed=(1..40).joinToString(" ") { "عنصر" }
        assertFalse(p.output(detailed,95,true,false,BRIEF,"").accepted)
        assertTrue(p.output(detailed,95,true,false,COMPREHENSIVE,"").accepted)
    }
    @Test fun consecutiveTargetChangesAreNeverHeldByAnOutstandingProbeWindow() {
        val p=SceneDescriptionPolicy()
        for(gen in 0L..8L) {
            assertTrue(p.shouldProbe(gen,BRIEF,gen*50,null,null).accepted)
            p.submitted(gen*50)
        }
    }
    @Test fun bothStylesRejectUnavailableOrInventedContentAndRetry() {
        for(style in SceneDescriptionStyle.entries) {
            val p=SceneDescriptionPolicy();p.shouldProbe(0,style,0,null,null)
            assertFalse(p.output("NO_TEXT",100,false,false,style,"").accepted)
            assertFalse(p.output("غرفة.",100,true,true,style,"").accepted)
            assertFalse(p.output("غرفة.",20,true,false,style,"").accepted)
            assertTrue(p.shouldProbe(0,style,1000,null,null).accepted)
        }
    }
    @Test fun duplicateDescriptionIsSuppressedButAChangedRelativePositionIsNot() {
        val p=SceneDescriptionPolicy();p.shouldProbe(0,BRIEF,0,null,null)
        p.output("الباب على اليمين.",95,true,false,BRIEF,"")
        assertFalse(p.output("الباب على اليمين!",95,true,false,BRIEF,"").accepted)
        assertTrue(p.output("الباب على اليسار.",95,true,false,BRIEF,"").accepted)
    }
    @Test fun blackOutageIsAnnouncedOnceUntilAnAvailableFrameReturns() {
        val p=VisualAvailability()
        assertTrue(p.missing());repeat(30) { assertFalse(p.missing()) }
        p.available();assertTrue(p.missing())
    }
}
