package com.abdullah.visionbridge.data.gemini

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading the server's refusal correctly.
 *
 * Every string here was sent by Gemini to this app on 2026-09-25 and copied out of the bundle.
 * They are not paraphrased, because the whole point is that one of them is a request rather than a
 * refusal, and the difference is in the words.
 */
class LiveCloseVerdictTest {

    private val modalityRefused =
        "The requested combination of response modalities (TEXT) is not supported by the model. " +
            "models/gemini-3.1-flash-live-preview"
    private val needsThinking = "Thinking level must be specified for this model."
    private val invalidArgument = "Request contains an invalid argument."

    @Test
    fun `a model refusing the modality is refusing permanently`() {
        val verdict = LiveCloseVerdict.of(1007, modalityRefused)
        assertEquals(LiveCloseVerdict.MODALITY_REFUSED, verdict)
        assertFalse("a modality refusal must not be retried", verdict.correctable)
    }

    /**
     * The one that matters. gemini-3.8-live-extended-thinking is the sibling of the model this app
     * already speaks with, and build 56 discarded it for asking to be configured.
     */
    @Test
    fun `a model asking for a thinking level is asking, not refusing`() {
        val verdict = LiveCloseVerdict.of(1007, needsThinking)
        assertEquals(LiveCloseVerdict.NEEDS_THINKING_LEVEL, verdict)
        assertTrue("the model asked to be called differently", verdict.correctable)
    }

    @Test
    fun `an unexplained rejection is not retried`() {
        val verdict = LiveCloseVerdict.of(1007, invalidArgument)
        assertEquals(LiveCloseVerdict.INVALID_ARGUMENT, verdict)
        assertFalse(verdict.correctable)
    }

    @Test
    fun `a plain close carries no verdict of its own`() {
        assertEquals(LiveCloseVerdict.UNEXPLAINED, LiveCloseVerdict.of(1000, ""))
        assertFalse(LiveCloseVerdict.of(1000, "").correctable)
    }

    /** The wording varies between the long and short forms; the stem is what is matched. */
    @Test
    fun `both spellings of a modality refusal are recognised`() {
        for (reason in listOf(
            modalityRefused,
            "Response modality AUDIO is not supported",
            "the requested response modalities are invalid",
        )) {
            assertEquals(reason, LiveCloseVerdict.MODALITY_REFUSED, LiveCloseVerdict.of(1007, reason))
        }
    }

    /** Case cannot decide a verdict, whatever the server capitalises. */
    @Test
    fun `the reason is read without regard to case`() {
        assertEquals(
            LiveCloseVerdict.NEEDS_THINKING_LEVEL,
            LiveCloseVerdict.of(1007, "THINKING LEVEL MUST BE SPECIFIED FOR THIS MODEL."),
        )
    }

    /** Exactly one verdict may ever be acted on by retrying, or a refusal becomes a loop. */
    @Test
    fun `only one verdict is correctable`() {
        assertEquals(1, LiveCloseVerdict.entries.count { it.correctable })
    }
}
