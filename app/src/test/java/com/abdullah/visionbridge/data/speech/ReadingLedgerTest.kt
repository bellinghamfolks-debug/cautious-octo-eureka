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
        ledger.recordDelivered("", page, now = 0L)

        var spokenAgain = 0
        repeat(24) { attempt ->
            val now = (attempt + 1) * 5_000L
            when (val decision = ledger.evaluate(page, now = now)) {
                is ReadingLedger.Decision.Speak -> {
                    spokenAgain++
                    ledger.recordDelivered(decision.alreadyHeard, decision.text, now)
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
        ledger.recordDelivered("", truncated, now = 0L)

        val decision = ledger.evaluate(page, now = 6_000L)
        assertTrue(decision is ReadingLedger.Decision.Speak)
        decision as ReadingLedger.Decision.Speak
        assertTrue(decision.continuation)
        assertFalse(decision.text.contains("الرصيد الحالي"))
        assertTrue(decision.text.contains("خدمة العملاء"))
        assertTrue(decision.text.contains("عزيزي العميل"))

        // Once the tail has been heard the page is complete and stays silent.
        ledger.recordDelivered(decision.alreadyHeard, decision.text, now = 6_000L)
        assertTrue(ledger.evaluate(page, now = 12_000L) is ReadingLedger.Decision.Skip)
    }

    /**
     * From a device log: a perfume label came back as "BLEU / CHANEL", then as
     * "BLEU / DE / CHANEL". The page must be recognized as the same page — only the word the user
     * has not heard is spoken, and the label is never read from the top a second time.
     */
    @Test
    fun `a short label re-split by OCR speaks only the word that is new`() {
        val ledger = ReadingLedger()
        ledger.recordDelivered("", "BLEU\nCHANEL", now = 0L)

        val decision = ledger.evaluate("BLEU\nDE\nCHANEL", now = 3_000L)
        assertTrue(decision is ReadingLedger.Decision.Speak)
        decision as ReadingLedger.Decision.Speak
        assertTrue(decision.continuation)
        assertEquals("DE", decision.text)
        assertFalse(decision.text.contains("BLEU"))

        // And once it has been heard the label settles: further recognitions add nothing.
        ledger.recordDelivered(decision.alreadyHeard, decision.text, now = 3_000L)
        assertTrue(ledger.evaluate("BLEU\nDE\nCHANEL", now = 4_000L) is ReadingLedger.Decision.Skip)
        assertTrue(ledger.evaluate("BLEU\nCHANEL", now = 5_000L) is ReadingLedger.Decision.Skip)
    }

    /**
     * The user's own complaint, reproduced from frames F000000682 and F000002602 of the
     * 2026-08-02 bundle. The reader recognized "BLEU / CHANEL / PARFUM" at 0.87 confidence and the
     * ledger threw the product name away 42 times in one session because it was shorter than
     * twenty four characters. A product name is short precisely because it is the whole point.
     */
    @Test
    fun `a product name added to a label the user has heard is spoken`() {
        val ledger = ReadingLedger()
        ledger.recordDelivered("", "BLEU 0\nCHANEL\nD", now = 0L)

        val decision = ledger.evaluate("O\nBLEU 0\nCHANEL D\nPARFUM", now = 2_000L)
        assertTrue(decision is ReadingLedger.Decision.Speak)
        decision as ReadingLedger.Decision.Speak
        assertTrue(decision.continuation)
        assertTrue(decision.text.contains("PARFUM"))
    }

    /** A room number, a gate number and a platform number are all shorter than a sentence. */
    @Test
    fun `a room number added to a sign is spoken`() {
        val ledger = ReadingLedger()
        ledger.recordDelivered("", "King Abdulaziz Hospital\nRadiology", now = 0L)

        val decision = ledger.evaluate("King Abdulaziz Hospital\nRadiology\n204", now = 2_000L)
        assertTrue(decision is ReadingLedger.Decision.Speak)
        assertEquals("204", (decision as ReadingLedger.Decision.Speak).text)
    }

    /**
     * The rule that replaced the character count. Loose glyphs off a bottle's shoulder are not
     * words, and they were the actual jitter in the device log: "O", "D", "0", "=", "c", "X".
     */
    @Test
    fun `loose glyphs between two reads of a page are still suppressed`() {
        val ledger = ReadingLedger()
        ledger.recordDelivered("", "BLEU\nCHANEL\nPARFUM", now = 0L)

        val decision = ledger.evaluate("BLEU\nCHANEL\nPARFUM\nO\nD\n=", now = 2_000L)
        assertTrue(decision is ReadingLedger.Decision.Skip)
        assertEquals(
            "continuation_is_recognition_jitter",
            (decision as ReadingLedger.Decision.Skip).reason,
        )
    }

    /**
     * The core of the delivery defect. The coordinator used to record the whole page the moment it
     * finished queueing speech; the device bundle shows 29 utterances submitted and 15 completed,
     * with "BLEU D / CHANEL" cut off mid-word by a target change and then never offered again
     * because the ledger already held it as heard.
     */
    @Test
    fun `a page whose speech was cut off is still owed to the user`() {
        val ledger = ReadingLedger()
        val first = ledger.evaluate("BLEU D\nCHANEL", now = 0L)
        assertTrue(first is ReadingLedger.Decision.Speak)

        // Nothing reached onDone, so nothing is recorded.
        ledger.recordDelivered("", "", now = 0L)

        val retry = ledger.evaluate("BLEU D\nCHANEL", now = 1_000L)
        assertTrue(retry is ReadingLedger.Decision.Speak)
        assertTrue((retry as ReadingLedger.Decision.Speak).text.contains("CHANEL"))
    }

    /** Half a page heard, half a page owed: the next pass offers exactly the missing half. */
    @Test
    fun `only the part that was heard is recorded`() {
        val ledger = ReadingLedger()
        ledger.recordDelivered("", "Gate 12", now = 0L)

        val decision = ledger.evaluate("Gate 12\nBoarding 09:40\nSeat 14C", now = 1_000L)
        assertTrue(decision is ReadingLedger.Decision.Speak)
        decision as ReadingLedger.Decision.Speak
        assertEquals("Boarding 09:40\nSeat 14C", decision.text)
        assertEquals("Gate 12", decision.alreadyHeard)

        // Recording the tail stores the whole page, so the first line is not read a second time.
        ledger.recordDelivered(decision.alreadyHeard, decision.text, now = 1_000L)
        assertTrue(
            ledger.evaluate("Gate 12\nBoarding 09:40\nSeat 14C", now = 2_000L)
                is ReadingLedger.Decision.Skip,
        )
    }

    /**
     * Also from the log: the same notes screen returned twice with different extents and line
     * breaks, and both readings were spoken in full.
     */
    @Test
    fun `a page returning with different line splits speaks only what is new`() {
        val ledger = ReadingLedger()
        val first = """
            نص إنجليزي قراه بسرعة وبدون
            أخطاء
            فجاة جالس يخربط في ارقام
        """.trimIndent()
        ledger.recordDelivered("", first, now = 0L)

        val second = """
            نص إنجليزي قراه بسرعة وبدون
            أخطاء
            فجاة جالس يخربط في ارقام
            وهنا سطر جديد تماما لم يسمعه المستخدم من قبل
        """.trimIndent()
        val decision = ledger.evaluate(second, now = 4_000L)
        assertTrue(decision is ReadingLedger.Decision.Speak)
        decision as ReadingLedger.Decision.Speak
        assertTrue(decision.continuation)
        assertFalse(decision.text.contains("نص إنجليزي قراه"))
        assertTrue(decision.text.contains("سطر جديد تماما"))
    }

    @Test
    fun `half a page in common is still the same page`() {
        val ledger = ReadingLedger()
        ledger.recordDelivered("", "سطر واحد\nسطر اثنان\nسطر ثلاثة\nسطر اربعة", now = 0L)
        val coverage = com.abdullah.visionbridge.data.speech.DocumentSpeechPolicy.coverageOf(
            alreadySpoken = "سطر واحد\nسطر اثنان\nسطر ثلاثة\nسطر اربعة",
            current = "سطر ثلاثة\nسطر اربعة\nسطر خمسة\nسطر ستة",
        )
        assertEquals(0.5, coverage, 0.001)
        assertTrue(ledger.evaluate("سطر ثلاثة\nسطر اربعة\nسطر خمسة\nسطر ستة", now = 2_000L)
            is ReadingLedger.Decision.Speak)
    }

    @Test
    fun `a different page interrupts and is read in full`() {
        val ledger = ReadingLedger()
        ledger.recordDelivered("", page, now = 0L)
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
        ledger.recordDelivered("", page, now = 0L)
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
