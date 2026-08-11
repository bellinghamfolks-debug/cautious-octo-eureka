package com.abdullah.visionbridge.data.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The whole hybrid policy as a table of cases.
 *
 * Every one of these is a moment someone actually has with the glasses on: a bottle held up, a
 * bottle held up for a second look, a doorway, a hand that moved away mid-answer. The feature is
 * only worth having if each of them produces the shortest true answer, so each of them is asserted.
 */
class HybridReadingPlanTest {

    private val label = "JAMAL'S PALACE\nEAU DE PARFUM"
    private val scene = "زجاجة عطر داكنة في يدك"

    private fun plan(
        text: String = label,
        description: String = scene,
        textAlreadyRead: Boolean = false,
        subjectAlreadyDescribed: Boolean = false,
        subjectUnchanged: Boolean = true,
    ) = HybridReadingPlan.plan(
        text = text,
        description = description,
        textAlreadyRead = textAlreadyRead,
        subjectAlreadyDescribed = subjectAlreadyDescribed,
        subjectUnchanged = subjectUnchanged,
    )

    // region the ordinary case

    @Test
    fun `a label with a description reads the label first`() {
        val result = plan()
        assertEquals(2, result.utterances.size)
        assertEquals(label, result.utterances[0].text)
        assertFalse("the label must never come second", result.utterances[0].isDescription)
        assertTrue(result.utterances[1].isDescription)
        assertEquals("text_then_description", result.reason)
    }

    /** The description is a tail. It may never be used as a preamble. */
    @Test
    fun `the description is never spoken before the text`() {
        for (unchanged in listOf(true, false)) {
            val result = plan(subjectUnchanged = unchanged)
            val first = result.utterances.firstOrNull() ?: continue
            assertFalse("description led with subjectUnchanged=$unchanged", first.isDescription)
        }
    }

    // endregion

    // region the user has moved on

    /**
     * The transcription is still the correct answer to what they asked; the description is about a
     * subject that is no longer in front of them, so it is dropped rather than spoken late.
     */
    @Test
    fun `a tail whose subject has gone is dropped and the reading is not`() {
        val result = plan(subjectUnchanged = false)
        assertEquals(1, result.utterances.size)
        assertEquals(label, result.utterances.single().text)
        assertEquals("text_only_description_expired", result.reason)
    }

    // endregion

    // region nothing repeated

    /** Holding the same bottle steady must produce one answer, not one per frame. */
    @Test
    fun `a page already read and a subject already described say nothing`() {
        val result = plan(textAlreadyRead = true, subjectAlreadyDescribed = true)
        assertFalse(result.speaks)
        assertEquals("nothing_new_to_say", result.reason)
    }

    /** New text on a subject that has already been described: read the text, skip the tail. */
    @Test
    fun `a described subject with new text reads only the text`() {
        val result = plan(subjectAlreadyDescribed = true)
        assertEquals(1, result.utterances.size)
        assertFalse(result.utterances.single().isDescription)
        assertEquals("text_only_description_already_said", result.reason)
    }

    /** The same page again, but the surroundings have not been described yet. */
    @Test
    fun `a re-read page still offers a description that was never said`() {
        val result = plan(textAlreadyRead = true)
        assertEquals(1, result.utterances.size)
        assertTrue(result.utterances.single().isDescription)
        assertEquals("text_already_read_description_new", result.reason)
    }

    // endregion

    // region no text in the frame

    /** A doorway. There is nothing to read, so the description is the answer, not a tail. */
    @Test
    fun `a frame with no text speaks the description alone`() {
        val result = plan(text = "")
        assertEquals(1, result.utterances.size)
        assertTrue(result.utterances.single().isDescription)
        assertEquals("description_only", result.reason)
    }

    /**
     * With no text the description is an ordinary scene description, and a scene description is
     * still worth hearing when the view has moved — that staleness is the coordinator's call.
     */
    @Test
    fun `a description without text survives a changed subject`() {
        assertTrue(plan(text = "", subjectUnchanged = false).speaks)
    }

    @Test
    fun `a described subject with no text says nothing`() {
        assertFalse(plan(text = "", subjectAlreadyDescribed = true).speaks)
    }

    // endregion

    // region degenerate input

    @Test
    fun `an empty answer says nothing`() {
        val result = plan(text = "", description = "")
        assertFalse(result.speaks)
        assertEquals("nothing_recognised", result.reason)
    }

    @Test
    fun `whitespace is not an answer`() {
        assertFalse(plan(text = "   ", description = "\n\t ").speaks)
    }

    @Test
    fun `a reading with no description offered is still delivered`() {
        val result = plan(description = "")
        assertEquals(1, result.utterances.size)
        assertEquals(label, result.utterances.single().text)
        assertEquals("text_only_no_description_offered", result.reason)
    }

    /** The text is handed on exactly as recognised; trimming is the only change permitted. */
    @Test
    fun `the transcription is not rewritten`() {
        val awkward = "  Line one\nLine two  "
        assertEquals(awkward.trim(), plan(text = awkward).utterances.first().text)
    }

    // endregion
}
