package com.abdullah.visionbridge.data.diagnostics

import org.junit.Assert.*
import org.junit.Test

class FrameIntegrityVerdictTest {
    private fun event(type:String,id:String="a",gen:Long=0,extra:Map<String,Any?> = emptyMap())=
        SessionVerdict.Event(type,mapOf("turnId" to id,"traceId" to id,"frameId" to id,
            "visualGeneration" to gen,"mode" to "TEXT_READING","model" to "test",
            "transportSessionId" to "session-$id","imageHash" to "a".repeat(64),"promptVersion" to "test")+extra)
    @Test fun lateOldOutputAndWrongImageAreDistinctFailures() {
        val result=FrameIntegrityVerdict.analyse(listOf(event("TURN_ACTIVATED"),event("FRAME_REQUEST_SENT"),
            event("TURN_ACTIVATED","b",1),event("FRAME_REQUEST_SENT","b",1),event("TTS_UTTERANCE_STARTED"),
            event("RUNTIME_RESULT","b",1,mapOf("imageHash" to "b".repeat(64)))))
        assertTrue(result.any { it.code=="STALE_VISUAL_OUTPUT" })
        assertTrue(result.any { it.code=="FRAME_RESULT_IDENTITY_MISMATCH" })
    }
    @Test fun protectedStaleDropsAreNotReportedAsLeakedOutput() {
        assertTrue(FrameIntegrityVerdict.analyse(listOf(event("TURN_ACTIVATED"),event("FRAME_REQUEST_SENT"),
            event("RESULT_DROPPED","old"),event("RUNTIME_RESULT"))).isEmpty())
    }
    @Test fun missingLegacyIdentityIsUnprovableNotClaimedMismatched() {
        val r=FrameIntegrityVerdict.analyse(listOf(SessionVerdict.Event("TEXT_DISPLAYED",mapOf("frameId" to "legacy"))))
        assertEquals(listOf("UNPROVABLE_FRAME_RESULT_IDENTITY"),r.map { it.code })
    }
    @Test fun oldSuppressionViewportAndLongSpeechQueueCannotProduceAnAllClear() {
        val events=MutableList(207) { event("FRAME_SELECTED_FOR_ANALYSIS") }
        repeat(19) { events+=event("LIVE_FRAME_SENT") }
        repeat(207) { events+=event("ESIGHT_VIEWPORT_FALLBACK_AUTO") }
        repeat(139) { events+=event("LIVE_LOCAL_SPEECH_BACKPRESSURE") }
        events+=SessionVerdict.Event("TTS_UTTERANCE_STARTED",mapOf("queueWaitMs" to 19000))
        val codes=FrameIntegrityVerdict.analyse(events).map { it.code }
        assertTrue(codes.containsAll(listOf("EXCESSIVE_FRAME_SUPPRESSION","PERSISTENT_VIEWPORT_FALLBACK","EXCESSIVE_TTS_QUEUE_AGE")))
    }
    @Test fun documentedOpticalDuplicatesAreNotSpeechBackpressure() {
        val events=MutableList(30) { event("FRAME_SELECTED_FOR_ANALYSIS") }
        repeat(29) { events+=event("FRAME_SKIPPED",extra=mapOf("reason" to "optically_verified_duplicate")) }
        events+=event("FRAME_REQUEST_SENT")
        assertFalse(FrameIntegrityVerdict.analyse(events).any { it.code=="EXCESSIVE_FRAME_SUPPRESSION" })
    }
    @Test fun contradictoryNoTextIsVisibleEvenThoughTheGateRejectedIt() {
        val result=FrameIntegrityVerdict.analyse(listOf(event("TEXT_GROUNDING_DECISION",extra=mapOf(
            "reason" to "no_text_conflicts_with_optical_evidence","accepted" to false))))
        assertEquals("NO_TEXT_WITH_OPTICAL_EVIDENCE",result.single().code)
    }
}
