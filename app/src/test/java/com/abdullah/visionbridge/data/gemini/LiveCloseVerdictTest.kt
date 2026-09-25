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

    // region the 2026-09-26 session, where one stale handle broke everything

    /**
     * The first error of that session, and the one that explains the rest. The app asked for TEXT
     * alone, of gemini-3.8-live-extended-thinking. What came back named two modalities it never
     * combined and a model it never sent — the signature of a resumption handle from an older
     * session being merged into the new setup.
     */
    @Test
    fun `a merged modality combination is still a modality refusal`() {
        val verdict = LiveCloseVerdict.of(
            1007,
            "The requested combination of response modalities (AUDIO, TEXT) is not supported by " +
                "the model. models/gemini_api_beyond_live",
        )
        assertEquals(LiveCloseVerdict.MODALITY_REFUSED, verdict)
    }

    /** Named outright by the server, twice in that session. */
    @Test
    fun `a handle the server no longer holds is correctable, not a verdict on the model`() {
        val verdict = LiveCloseVerdict.of(1008, "BidiGenerateContent session history not found")
        assertEquals(LiveCloseVerdict.STALE_RESUMPTION, verdict)
        assertTrue(verdict.correctable)
        assertFalse("a stale handle says nothing about the model", verdict.provesIncapable)
    }

    /** ar-XA, added in build 51 to stop English descriptions, is refused by a whole family. */
    @Test
    fun `an unsupported language code is correctable by dropping the language`() {
        val verdict = LiveCloseVerdict.of(
            1007,
            "Unsupported language code 'ar-XA' for model models/gemini-2.5-flash-native-audio-latest",
        )
        assertEquals(LiveCloseVerdict.LANGUAGE_UNSUPPORTED, verdict)
        assertTrue(verdict.correctable)
        assertFalse(verdict.provesIncapable)
    }

    /**
     * gemini-3.8-live — the only model ever measured answering on this device — closed 1011 and
     * was struck off for it. A server fault is not a model's verdict on itself.
     */
    @Test
    fun `an internal server error never counts against the model`() {
        val verdict = LiveCloseVerdict.of(1011, "Internal error encountered.")
        assertEquals(LiveCloseVerdict.TRANSPORT_ERROR, verdict)
        assertFalse("a server fault must not rule a model out", verdict.provesIncapable)
    }

    // endregion

    /** Only a refusal is evidence about capability; everything else is about the connection. */
    @Test
    fun `exactly the two refusals prove a model incapable`() {
        assertEquals(
            setOf(LiveCloseVerdict.MODALITY_REFUSED, LiveCloseVerdict.INVALID_ARGUMENT),
            LiveCloseVerdict.entries.filter { it.provesIncapable }.toSet(),
        )
    }

    /** A verdict may be correctable or damning, never both. */
    @Test
    fun `no verdict is both correctable and proof of incapability`() {
        assertTrue(LiveCloseVerdict.entries.none { it.correctable && it.provesIncapable })
    }
}
