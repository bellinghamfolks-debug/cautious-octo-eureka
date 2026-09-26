package com.abdullah.visionbridge.data.gemini

import com.abdullah.visionbridge.data.gemini.LiveTurnPolicy.AfterSilence
import com.abdullah.visionbridge.data.gemini.LiveTurnPolicy.FALLBACK_MODEL
import com.abdullah.visionbridge.data.gemini.LiveTurnPolicy.PRIMARY_MODEL
import com.abdullah.visionbridge.domain.model.AnalysisMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 2026-09-26 16:33 session, replayed against the rules that replaced the ones it exposed.
 *
 * Times are seconds into that session, written as milliseconds. Each test names the moment it
 * replays so a failure here can be read against the bundle it came from.
 */
class LiveTurnPolicyTest {

    // region 8.56 s → 9.19 s: the best model struck off after it had answered

    @Test
    fun `a function call with no lines is an answer`() {
        // 8.56 LIVE_TOOL_TEXT_REPORTED chars=0 lines=0 on gemini-3.8-live, no audio, no transcript.
        assertTrue(LiveTurnPolicy.answered(audio = false, transcript = false, toolCall = true))
    }

    @Test
    fun `nothing on any channel is not an answer`() {
        assertFalse(LiveTurnPolicy.answered(audio = false, transcript = false, toolCall = false))
    }

    @Test
    fun `a silent turn reopens the socket and leaves the model where it is`() {
        // 17:28, 40.07 → 58.72: three quiet turns, then a reconnect, then the page 82 ms later.
        assertEquals(AfterSilence.RECONNECT_SAME_MODEL, LiveTurnPolicy.afterSilentTurn(1))
        // Silence is not a connection failure, so it never reaches nextModel as one.
        assertEquals(PRIMARY_MODEL, LiveTurnPolicy.nextModel(PRIMARY_MODEL, 0, 0L, 9_190L))
    }

    @Test
    fun `no turn is left waiting before the first reconnect`() {
        assertEquals(AfterSilence.FREE_LANE, LiveTurnPolicy.afterSilentTurn(0))
    }

    @Test
    fun `the deadline is longer than the slowest answer the session saw`() {
        // 3.8-live's tool call came 0.6 s before the old three-second deadline fired.
        assertTrue(LiveTurnPolicy.FIRST_ANSWER_DEADLINE_MS > 3_000L)
    }

    // endregion

    // region 13 s → 61 s: touring four models that never answer here

    @Test
    fun `only the two models measured answering are ever used`() {
        assertEquals(listOf("gemini-3.8-live", "gemini-3.1-flash-live-preview"), LiveTurnPolicy.PINNED_MODELS)
        listOf(
            "gemini-2.5-flash-native-audio-latest",
            "gemini-2.5-flash-native-audio-preview-09-2025",
            "gemini-2.5-flash-native-audio-preview-12-2025",
            "gemini-3.5-transcribe-live",
            "gemini-3.8-live-extended-thinking",
        ).forEach { model ->
            assertFalse(model in LiveTurnPolicy.PINNED_MODELS)
            assertEquals(PRIMARY_MODEL, LiveTurnPolicy.nextModel(model, 0, 0L, 0L))
        }
    }

    @Test
    fun `the fallback is used only after repeated connection failures`() {
        assertEquals(PRIMARY_MODEL, LiveTurnPolicy.nextModel(PRIMARY_MODEL, 2, 0L, 10_000L))
        assertEquals(
            FALLBACK_MODEL,
            LiveTurnPolicy.nextModel(PRIMARY_MODEL, LiveTurnPolicy.CONNECT_FAILURES_BEFORE_SWITCH, 0L, 10_000L),
        )
    }

    @Test
    fun `the fallback is a detour and the primary is tried again after a minute`() {
        assertEquals(FALLBACK_MODEL, LiveTurnPolicy.nextModel(FALLBACK_MODEL, 0, 67_000L, 100_000L))
        assertEquals(
            PRIMARY_MODEL,
            LiveTurnPolicy.nextModel(FALLBACK_MODEL, 0, 67_000L, 67_000L + LiveTurnPolicy.RETURN_TO_PRIMARY_AFTER_MS),
        )
    }

    @Test
    fun `a failing fallback hands back to the primary rather than to nothing`() {
        assertEquals(
            PRIMARY_MODEL,
            LiveTurnPolicy.nextModel(FALLBACK_MODEL, LiveTurnPolicy.CONNECT_FAILURES_BEFORE_SWITCH, 0L, 1_000L),
        )
    }

    // endregion

    // region 79.59 s → 79.64 s: a 23-character summary silencing a 747-character page

    @Test
    fun `the model's voice is never played while reading`() {
        assertFalse(LiveTurnPolicy.playsModelAudio(AnalysisMode.TEXT_READING))
    }

    @Test
    fun `describing keeps the model's own live voice`() {
        assertTrue(LiveTurnPolicy.playsModelAudio(AnalysisMode.SCENE_DESCRIPTION))
        assertFalse(LiveTurnPolicy.declaresReadingTool(AnalysisMode.SCENE_DESCRIPTION))
    }

    @Test
    fun `reading declares the tool that carries the page`() {
        assertTrue(LiveTurnPolicy.declaresReadingTool(AnalysisMode.TEXT_READING))
    }

    @Test
    fun `the transcript is spoken only when the tool never delivered`() {
        // 79.64: the tool delivered, so the 23-character transcript must not be spoken.
        assertFalse(LiveTurnPolicy.speaksTranscriptAtTurnEnd(AnalysisMode.TEXT_READING, true, true))
        // The model spoke and never called: the transcript is all there is, and silence is worse.
        assertTrue(LiveTurnPolicy.speaksTranscriptAtTurnEnd(AnalysisMode.TEXT_READING, false, true))
        assertFalse(LiveTurnPolicy.speaksTranscriptAtTurnEnd(AnalysisMode.TEXT_READING, false, false))
        // A description's transcript is already being heard in the model's voice.
        assertFalse(LiveTurnPolicy.speaksTranscriptAtTurnEnd(AnalysisMode.SCENE_DESCRIPTION, false, true))
    }

    // endregion

    // region 17:28 session: answers thrown away because the camera moved

    @Test
    fun `a reading that arrives after the view moved is still read`() {
        // 72.28 a small head movement; 73.21 the answer to the frame before it, marked stale.
        assertTrue(LiveTurnPolicy.keepsAnswerAfterViewMoved(AnalysisMode.TEXT_READING))
    }

    @Test
    fun `a description that arrives after the view moved is not`() {
        assertFalse(LiveTurnPolicy.keepsAnswerAfterViewMoved(AnalysisMode.SCENE_DESCRIPTION))
    }

    @Test
    fun `moving the camera does not cut a reading, only a different page does`() {
        // 66.04, 71.15, 103.40: the chemistry summary cut and restarted from its first line.
        assertFalse(LiveTurnPolicy.cutsSpeechOnTargetChange(AnalysisMode.TEXT_READING, true))
        assertTrue(LiveTurnPolicy.cutsSpeechOnTargetChange(AnalysisMode.SCENE_DESCRIPTION, true))
        assertFalse(LiveTurnPolicy.cutsSpeechOnTargetChange(AnalysisMode.SCENE_DESCRIPTION, false))
    }

    // endregion

    // region 5.63 / 5.91 / 6.18 s: three frames in half a second

    @Test
    fun `frames reserved during the handshake do not burst out after it`() {
        assertTrue(LiveTurnPolicy.frameMaySend(5_630L, null))
        assertFalse(LiveTurnPolicy.frameMaySend(5_910L, 5_630L))
        assertFalse(LiveTurnPolicy.frameMaySend(6_180L, 5_630L))
        assertTrue(LiveTurnPolicy.frameMaySend(6_630L, 5_630L))
    }

    @Test
    fun `only the newest waiting frame is sent`() {
        // Three frames queued behind the handshake took tickets 1, 2 and 3.
        assertFalse(LiveTurnPolicy.isNewest(1L, 3L))
        assertFalse(LiveTurnPolicy.isNewest(2L, 3L))
        assertTrue(LiveTurnPolicy.isNewest(3L, 3L))
    }

    // endregion

    // region 0.40 – 0.89 s: target changes before any socket existed

    @Test
    fun `target changes before the first frame are ignored`() {
        listOf(400L, 600L, 890L).forEach { _ ->
            assertFalse(LiveTurnPolicy.honoursTargetChange(firstFrameSent = false))
        }
        assertTrue(LiveTurnPolicy.honoursTargetChange(firstFrameSent = true))
    }

    // endregion

    // region looking again at the same view

    @Test
    fun `a page that was read is not sent again until the view changes`() {
        assertFalse(LiveTurnPolicy.resendsSameView(AnalysisMode.TEXT_READING, false, true, 60_000L))
    }

    @Test
    fun `a page that came back empty is looked at again once the glasses have had time to focus`() {
        assertFalse(LiveTurnPolicy.resendsSameView(AnalysisMode.TEXT_READING, false, false, 1_000L))
        assertTrue(
            LiveTurnPolicy.resendsSameView(
                AnalysisMode.TEXT_READING,
                false,
                false,
                LiveTurnPolicy.EMPTY_READING_RETRY_MS,
            ),
        )
    }

    @Test
    fun `a scene is sampled again as soon as its answer has finished, never during it`() {
        assertTrue(LiveTurnPolicy.resendsSameView(AnalysisMode.SCENE_DESCRIPTION, false, true, 1_000L))
        assertFalse(LiveTurnPolicy.resendsSameView(AnalysisMode.SCENE_DESCRIPTION, true, true, 60_000L))
        assertFalse(LiveTurnPolicy.resendsSameView(AnalysisMode.TEXT_READING, true, false, 60_000L))
    }

    // endregion
}
