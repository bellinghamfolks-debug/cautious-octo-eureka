package com.abdullah.visionbridge.data.diagnostics

import org.junit.Assert.*
import org.junit.Test

class FrameIntegrityVerdictTest {
    @Test fun responseLostBeforeOpticalVerificationIsNotAnAllClear() {
        val result=FrameIntegrityVerdict.analyse(listOf(event("FRAME_REQUEST_SENT"),event("FIRST_CHUNK"),
            event("CLOUD_ANALYSIS_BUDGET_EXCEEDED")))
        assertEquals("RESPONSE_TIMED_OUT_BEFORE_VERIFICATION",result.single().code)
    }
    @Test fun lateGroundingCompletionDoesNotHideAlreadyTimedOutResponse() {
        val result=FrameIntegrityVerdict.analyse(listOf(event("FRAME_REQUEST_SENT"),event("FIRST_CHUNK"),
            event("CLOUD_ANALYSIS_BUDGET_EXCEEDED"),event("LOCAL_GROUNDING_COMPLETED")))
        assertEquals("RESPONSE_TIMED_OUT_BEFORE_VERIFICATION",result.single().code)
    }
    @Test fun sceneResponseAndCompletedGroundingAreNotClassifiedAsWaitingForOcr() {
        val scene=listOf("FRAME_REQUEST_SENT","FIRST_CHUNK","CLOUD_ANALYSIS_BUDGET_EXCEEDED")
            .map { event(it,extra=mapOf("mode" to "SCENE_DESCRIPTION")) }
        assertTrue(FrameIntegrityVerdict.analyse(scene).isEmpty())
        val verified=listOf(event("FRAME_REQUEST_SENT"),event("FIRST_CHUNK"),
            event("LOCAL_GROUNDING_COMPLETED"),event("CLOUD_ANALYSIS_BUDGET_EXCEEDED"))
        assertTrue(FrameIntegrityVerdict.analyse(verified).isEmpty())
    }
    @Test fun unrelatedSessionOrTurnCannotSupplyAResponseToTimeoutFinding() {
        val events=listOf(event("FRAME_REQUEST_SENT"),event("FIRST_CHUNK","other"),
            event("FIRST_CHUNK",extra=mapOf("sessionId" to "other-session")),
            event("CLOUD_ANALYSIS_BUDGET_EXCEEDED"))
        assertTrue(FrameIntegrityVerdict.analyse(events).isEmpty())
    }
    private fun event(type:String,id:String="a",gen:Long=0,extra:Map<String,Any?> = emptyMap())=
        SessionVerdict.Event(type,mapOf("turnId" to id,"traceId" to id,"frameId" to id,
            "visualGeneration" to gen,"mode" to "TEXT_READING","model" to "test",
            "transportSessionId" to "session-$id","imageHash" to "a".repeat(64),"promptVersion" to "test",
            "acceptedContentHash" to "c".repeat(64))+extra)
    @Test fun matchingFrameIdentityDoesNotPermitUnacceptedSpeechOrDisplay() {
        val result=FrameIntegrityVerdict.analyse(listOf(event("TURN_ACTIVATED"),event("FRAME_REQUEST_SENT"),
            event("RUNTIME_RESULT"),event("TEXT_DISPLAYED",extra=mapOf("acceptedContentHash" to "d".repeat(64))),
            event("TTS_UTTERANCE_STARTED",extra=mapOf("acceptedContentHash" to "e".repeat(64)))))
        assertEquals("count=2",result.single { it.code=="UNACCEPTED_CONTENT_OUTPUT" }.measurement)
    }
    @Test fun earlierAcceptedStreamingRevisionCanFinishWithinItsActiveTurn() {
        assertTrue(FrameIntegrityVerdict.analyse(listOf(event("TURN_ACTIVATED"),event("FRAME_REQUEST_SENT"),
            event("RUNTIME_RESULT"),event("RUNTIME_RESULT",extra=mapOf("acceptedContentHash" to "d".repeat(64))),
            event("TTS_UTTERANCE_STARTED"),event("TEXT_DISPLAYED",extra=mapOf("acceptedContentHash" to "d".repeat(64))))).isEmpty())
    }
    @Test fun futureAcceptanceCannotRetroactivelyJustifyOutput() {
        val result=FrameIntegrityVerdict.analyse(listOf(event("TURN_ACTIVATED"),event("FRAME_REQUEST_SENT"),
            event("TEXT_DISPLAYED"),event("RUNTIME_RESULT")))
        assertTrue(result.any { it.code=="UNACCEPTED_CONTENT_OUTPUT" })
    }
    @Test fun missingOrMalformedContentHashesAreExplicitlyUnprovable() {
        for(hash in listOf(null,"", "z".repeat(64))) {
            val result=FrameIntegrityVerdict.analyse(listOf(event("FRAME_REQUEST_SENT"),
                event("RUNTIME_RESULT",extra=mapOf("acceptedContentHash" to hash))))
            assertTrue(result.any { it.code=="UNPROVABLE_ACCEPTED_CONTENT" })
        }
    }
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
