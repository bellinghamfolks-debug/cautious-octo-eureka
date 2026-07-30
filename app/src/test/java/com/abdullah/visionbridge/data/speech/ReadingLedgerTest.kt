package com.abdullah.visionbridge.data.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingLedgerTest {
    private val page = """
        الرصيد الحالي 9.20 ريال
        Subscribe to sawa packages
        خدمة العملاء الجديدة
        عزيزي العميل نأمل منكم المبادرة بالسداد
    """.trimIndent()

    @Test
    fun `a new page is spoken in full`() {
        val ledger = ReadingLedger()
        val decision = ledger.evaluate(page, now = 0L)
        assertTrue(decision is ReadingLedger.Decision.Speak)
        decision as ReadingLedger.Decision.Speak
        assertFalse(decision.continuation)
        assertTrue(decision.text.contains("الرصيد الحالي"))
        assertTrue(decision.text.contains("عزيزي العميل"))
    }

    /**
     * The exact defect from the diagnostics: one motionless screen was analyzed twenty five times in
     * a hundred and forty five seconds. Every pass after the first must now contribute nothing.
     */
    @Test
    fun `a static page analyzed repeatedly is read once`() {
        val ledger = ReadingLedger()
        val first = ledger.evaluate(page, now = 0L)
        assertTrue(first is ReadingLedger.Decision.Speak)
        ledger.recordSpoken(page, now = 0L)

        var spokenAgain = 0
        repeat(24) { attempt ->
            val now = (attempt + 1) * 5_000L
            when (val decision = ledger.evaluate(page, now = now)) {
                is ReadingLedger.Decision.Speak -> {
                    spokenAgain++
                    ledger.recordSpoken(decision.document, now)
                }

                is ReadingLedger.Decision.Skip ->
                    assertEquals("already_read_completely", decision.reason)
            }
        }
        assertEquals(0, spokenAgain)
    }

    @Test
    fun `a truncated first read is completed by its retry without repeating`() {
        val ledger = ReadingLedger()
        val truncated = "الرصيد الحالي 9.20 ريال\nSubscribe to sawa packages"
        ledger.recordSpoken(truncated, now = 0L)

        val decision = ledger.evaluate(page, now = 6_000L)
        assertTrue(decision is ReadingLedger.Decision.Speak)
        decision as ReadingLedger.Decision.Speak
        assertTrue(decision.continuation)
        assertFalse(decision.text.contains("الرصيد الحالي"))
        assertTrue(decision.text.contains("خدمة العملاء"))
        assertTrue(decision.text.contains("عزيزي العميل"))

        // Once the tail has been heard the page is complete and stays silent.
        ledger.recordSpoken(decision.document, now = 6_000L)
        assertTrue(ledger.evaluate(page, now = 12_000L) is ReadingLedger.Decision.Skip)
    }

    @Test
    fun `a different page interrupts and is read in full`() {
        val ledger = ReadingLedger()
        ledger.recordSpoken(page, now = 0L)
        val other = "إعدادات الكاميرا\nجودة الصورة\nالوضع الليلي"

        val decision = ledger.evaluate(other, now = 1_000L)
        assertTrue(decision is ReadingLedger.Decision.Speak)
        decision as ReadingLedger.Decision.Speak
        assertFalse(decision.continuation)
        assertTrue(decision.text.contains("الوضع الليلي"))
    }

    @Test
    fun `returning to a page much later reads it again on purpose`() {
        val ledger = ReadingLedger(repeatWindowMs = 10_000L)
        ledger.recordSpoken(page, now = 0L)
        assertTrue(ledger.evaluate(page, now = 60_000L) is ReadingLedger.Decision.Speak)
    }

    @Test
    fun `a frame with no readable text is skipped`() {
        val ledger = ReadingLedger()
        val decision = ledger.evaluate("  ...\n..  ", now = 0L)
        assertTrue(decision is ReadingLedger.Decision.Skip)
        assertEquals("no_readable_text", (decision as ReadingLedger.Decision.Skip).reason)
    }
}
