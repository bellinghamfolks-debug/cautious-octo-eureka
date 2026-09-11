package com.abdullah.visionbridge.data.speech

import com.abdullah.visionbridge.capture.TurnGate
import com.abdullah.visionbridge.domain.model.AnalysisMode
import com.abdullah.visionbridge.domain.model.AnalysisTurn
import org.junit.Assert.*
import org.junit.Test

class VisualSpeechTimelineTest {
    private fun turn(id:String="one",generation:Long=0) = AnalysisTurn(id,id,id,generation,
        AnalysisMode.TEXT_READING,0,0,"test","test","session-$id",1,"a".repeat(64))

    @Test fun aFirstUtteranceCannotStartAfterOneSecond() {
        val window=VisualSpeechTimeline().window(turn(),"READ_TEXT",1_000_000)
        assertFalse(window.expired(1_000_999_999))
        assertTrue(window.expired(1_001_000_000))
    }

    @Test fun deliveredContinuationKeepsOriginalQueueAgeButGetsItsOwnStartWindow() {
        val policy=VisualSpeechTimeline();val turn=turn()
        policy.delivered(turn,8_000_000_000)
        val window=policy.window(turn,"SCENE_DESCRIPTION",1_000_000)
        assertEquals(1_000_000L,window.queuedAtNanos)
        assertEquals(8_000_000_000,window.eligibleAtNanos)
        assertFalse(window.expired(8_100_000_000))
        assertTrue(window.expired(9_000_000_000))
    }

    @Test fun sceneTailDoesNotBorrowTheReadingContinuationWindow() {
        val policy=VisualSpeechTimeline();val turn=turn()
        policy.delivered(turn,8_000_000_000)
        assertTrue(policy.window(turn,"SCENE_TAIL",1_000_000).expired(8_000_000_000))
    }

    @Test fun replacementCannotInheritOldSpeechEligibilityOrPublishItsLateStart() {
        val gate=TurnGate();val policy=VisualSpeechTimeline();val old=turn()
        assertTrue(gate.activate(old.copy(submittedAtNanos=null,imageHash=null)))
        assertTrue(gate.bindSubmission(old));policy.delivered(old,8_000_000_000)
        gate.invalidate { policy.invalidate() }
        val new=turn("two",gate.generation())
        assertTrue(gate.activate(new.copy(submittedAtNanos=null,imageHash=null)))
        assertTrue(gate.bindSubmission(new))
        var starts=0
        assertFalse(gate.commit(old) { starts++ })
        assertEquals(0,starts)
        assertTrue(policy.window(new,"READ_TEXT",1_000_000).expired(8_000_000_000))
    }
}
