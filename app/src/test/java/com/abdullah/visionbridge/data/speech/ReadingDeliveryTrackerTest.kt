package com.abdullah.visionbridge.data.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The accounting that decides whether a page may be marked as read.
 *
 * Every case here comes from the 2026-08-02 device bundle, where 24 pages were accepted for speech
 * and 15 utterances reached `onDone`, and the difference was recorded as heard.
 */
class ReadingDeliveryTrackerTest {

    @Test
    fun `a reading is not settled until every block has reported`() {
        val tracker = ReadingDeliveryTracker()
        tracker.open(1L, alreadyHeard = "", blocks = listOf("one two", "three four"))

        assertNull(tracker.record(1L, 0, SpeechOutcome.COMPLETED))
        val delivery = tracker.record(1L, 1, SpeechOutcome.COMPLETED)
        assertEquals("one two\nthree four", delivery?.deliveredText)
        assertEquals("", delivery?.owedText)
        assertTrue(delivery?.complete == true)
    }

    @Test
    fun `an interrupted first block delivers nothing`() {
        val tracker = ReadingDeliveryTracker()
        tracker.open(1L, alreadyHeard = "", blocks = listOf("BLEU D", "CHANEL"))

        tracker.record(1L, 0, SpeechOutcome.INTERRUPTED)
        val delivery = tracker.record(1L, 1, SpeechOutcome.INTERRUPTED)!!
        assertEquals("", delivery.deliveredText)
        assertEquals("BLEU D\nCHANEL", delivery.owedText)
        assertFalse(delivery.complete)
    }

    /**
     * Speech is sequential. A block that reports COMPLETED after an interrupted one cannot have
     * been heard, whatever the engine says about it, so delivery is a prefix and never a set.
     */
    @Test
    fun `delivery is the completed prefix, not every completed block`() {
        val tracker = ReadingDeliveryTracker()
        tracker.open(1L, alreadyHeard = "", blocks = listOf("one", "two", "three"))

        tracker.record(1L, 0, SpeechOutcome.COMPLETED)
        tracker.record(1L, 1, SpeechOutcome.INTERRUPTED)
        val delivery = tracker.record(1L, 2, SpeechOutcome.COMPLETED)!!
        assertEquals("one", delivery.deliveredText)
        assertEquals("two\nthree", delivery.owedText)
    }

    @Test
    fun `every outcome other than completed leaves the block owed`() {
        val owed = listOf(
            SpeechOutcome.INTERRUPTED,
            SpeechOutcome.FAILED,
            SpeechOutcome.SUPERSEDED_BEFORE_START,
            SpeechOutcome.CANCELLED_BY_USER,
        )
        for (outcome in owed) {
            val tracker = ReadingDeliveryTracker()
            tracker.open(1L, alreadyHeard = "", blocks = listOf("only block"))
            val delivery = tracker.record(1L, 0, outcome)!!
            assertEquals("$outcome must leave the block owed", "only block", delivery.owedText)
            assertEquals("$outcome must deliver nothing", "", delivery.deliveredText)
        }
        val tracker = ReadingDeliveryTracker()
        tracker.open(1L, alreadyHeard = "", blocks = listOf("only block"))
        assertEquals("only block", tracker.record(1L, 0, SpeechOutcome.COMPLETED)!!.deliveredText)
    }

    /** The first report wins: an interrupt can race the engine's own terminal callback. */
    @Test
    fun `a later report cannot overwrite what the user experienced`() {
        val tracker = ReadingDeliveryTracker()
        tracker.open(1L, alreadyHeard = "", blocks = listOf("one", "two"))

        tracker.record(1L, 0, SpeechOutcome.INTERRUPTED)
        tracker.record(1L, 0, SpeechOutcome.COMPLETED)
        val delivery = tracker.record(1L, 1, SpeechOutcome.COMPLETED)!!
        assertEquals("", delivery.deliveredText)
    }

    @Test
    fun `what the user had already heard is carried through to the ledger`() {
        val tracker = ReadingDeliveryTracker()
        tracker.open(1L, alreadyHeard = "Gate 12", blocks = listOf("Seat 14C"))

        val delivery = tracker.record(1L, 0, SpeechOutcome.COMPLETED)!!
        assertEquals("Gate 12", delivery.alreadyHeard)
        assertEquals("Seat 14C", delivery.deliveredText)
    }

    @Test
    fun `abandoning a reading treats silent blocks as unheard`() {
        val tracker = ReadingDeliveryTracker()
        tracker.open(1L, alreadyHeard = "", blocks = listOf("one two", "three four"))

        tracker.record(1L, 0, SpeechOutcome.COMPLETED)
        val delivery = tracker.abandon(1L, SpeechOutcome.CANCELLED_BY_USER)!!
        assertEquals("one two", delivery.deliveredText)
        assertEquals("three four", delivery.owedText)
        assertEquals(SpeechOutcome.CANCELLED_BY_USER, delivery.outcomes[1])
    }

    @Test
    fun `outcomes for an unknown or settled reading are ignored`() {
        val tracker = ReadingDeliveryTracker()
        tracker.open(1L, alreadyHeard = "", blocks = listOf("one"))
        assertEquals("one", tracker.record(1L, 0, SpeechOutcome.COMPLETED)!!.deliveredText)

        assertNull(tracker.record(1L, 0, SpeechOutcome.COMPLETED))
        assertNull(tracker.record(99L, 0, SpeechOutcome.COMPLETED))
        assertNull(tracker.abandon(1L, SpeechOutcome.INTERRUPTED))
    }

    @Test
    fun `an out of range block index is ignored rather than settling the reading`() {
        val tracker = ReadingDeliveryTracker()
        tracker.open(1L, alreadyHeard = "", blocks = listOf("one"))
        assertNull(tracker.record(1L, 7, SpeechOutcome.COMPLETED))
        assertEquals("one", tracker.record(1L, 0, SpeechOutcome.COMPLETED)!!.deliveredText)
    }

    /**
     * End to end, exactly as the coordinator wires it: the ledger learns what was heard from the
     * tracker, and the unheard tail comes back on the next recognition of the same page.
     */
    @Test
    fun `an interrupted reading is offered again and completed on the second pass`() {
        val ledger = ReadingLedger()
        val tracker = ReadingDeliveryTracker()
        val page = "BLEU\nDE CHANEL\nPARFUM"

        val first = ledger.evaluate(page, now = 0L) as ReadingLedger.Decision.Speak
        val blocks = listOf("BLEU", "DE CHANEL", "PARFUM")
        tracker.open(1L, first.alreadyHeard, blocks)
        tracker.record(1L, 0, SpeechOutcome.COMPLETED)
        tracker.record(1L, 1, SpeechOutcome.INTERRUPTED)
        val cut = tracker.record(1L, 2, SpeechOutcome.INTERRUPTED)!!
        ledger.recordDelivered(cut.alreadyHeard, cut.deliveredText, now = 0L)

        val second = ledger.evaluate(page, now = 1_000L)
        assertTrue(second is ReadingLedger.Decision.Speak)
        second as ReadingLedger.Decision.Speak
        assertEquals("DE CHANEL\nPARFUM", second.text)

        tracker.open(2L, second.alreadyHeard, listOf("DE CHANEL", "PARFUM"))
        tracker.record(2L, 0, SpeechOutcome.COMPLETED)
        val rest = tracker.record(2L, 1, SpeechOutcome.COMPLETED)!!
        ledger.recordDelivered(rest.alreadyHeard, rest.deliveredText, now = 1_000L)

        assertTrue(ledger.evaluate(page, now = 2_000L) is ReadingLedger.Decision.Skip)
    }
}
