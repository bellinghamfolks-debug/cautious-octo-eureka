package com.abdullah.visionbridge.data.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a live reading does with a page that is reported again while it is still being spoken.
 *
 * In the 2026-09-26 16:33 session the same 747-character page came back 21 s after the first
 * report, well before thirty-five lines could have been read aloud. The ledger learns a page only
 * when its last block finishes, so the page in flight has to count as heard in the meantime.
 */
class LiveReadingSpeakerTest {

    private val page = (1..35).joinToString("\n") { "Line $it of the ingredients panel" }

    @Test
    fun `nothing in flight leaves the decision to the ledger`() {
        assertNull(LiveReadingSpeaker.continuesInFlight(null, null, page))
        assertNull(LiveReadingSpeaker.continuesInFlight("", null, page))
    }

    @Test
    fun `the same page reported again while it is being read is skipped, not restarted`() {
        val decision = LiveReadingSpeaker.continuesInFlight(page, "", page)
        assertEquals(ReadingLedger.Decision.Skip("already_being_read"), decision)
    }

    @Test
    fun `the same page split into different lines is still the same page`() {
        val resplit = page.lines().chunked(2).joinToString("\n") { it.joinToString(" ") }
        val decision = LiveReadingSpeaker.continuesInFlight(page, "", resplit)
        assertTrue(decision is ReadingLedger.Decision.Skip)
    }

    @Test
    fun `new lines on the page in flight are queued behind it, and only they are`() {
        val longer = page + "\nBest before 2027-03-14\nMade in Spain"
        val decision = LiveReadingSpeaker.continuesInFlight(page, "", longer)
        decision as ReadingLedger.Decision.Speak
        assertTrue(decision.continuation)
        assertEquals("Best before 2027-03-14\nMade in Spain", decision.text)
        assertEquals(page, decision.alreadyHeard)
    }

    @Test
    fun `a different page is not the page in flight`() {
        val other = "صابون زيت الزيتون\nOlive oil soap\n100 g"
        assertNull(LiveReadingSpeaker.continuesInFlight(page, "", other))
    }

    @Test
    fun `a page is spoken in whole-line blocks and nothing is lost between them`() {
        val blocks = LiveReadingSpeaker.blocksOf(page)
        assertTrue("35 lines should not be one utterance", blocks.size > 1)
        assertEquals(
            DocumentSpeechPolicy.readableLines(page),
            blocks.flatMap(DocumentSpeechPolicy::readableLines),
        )
        assertFalse(blocks.any { it.isBlank() })
    }
}
