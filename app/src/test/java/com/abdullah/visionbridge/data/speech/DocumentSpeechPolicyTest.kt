package com.abdullah.visionbridge.data.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentSpeechPolicyTest {
    @Test
    fun `re-reading the same page adds nothing`() {
        val page = "الرصيد الحالي ٩٫٢٠ ريال\nCustomer care\nخدمة العملاء"
        assertEquals("", DocumentSpeechPolicy.newContent(alreadySpoken = page, current = page))
    }

    @Test
    fun `a slightly different recognition of the same page still adds nothing`() {
        val first = "الرصيد الحالي 9.20 ريال\nCustomer care\nخدمة العملاء الجديدة"
        val second = "الرصيد الحالى ٩٫٢٠ ريال.\nCustomer care\nخدمة العملاء الجديدة"
        assertEquals("", DocumentSpeechPolicy.newContent(alreadySpoken = first, current = second))
    }

    @Test
    fun `only the unheard tail of a truncated page is returned`() {
        val heard = "السطر الأول من الرسالة\nالسطر الثاني من الرسالة"
        val complete = "السطر الأول من الرسالة\nالسطر الثاني من الرسالة\nالسطر الثالث الجديد تماما"
        assertEquals(
            "السطر الثالث الجديد تماما",
            DocumentSpeechPolicy.newContent(alreadySpoken = heard, current = complete),
        )
    }

    @Test
    fun `a genuinely different page is returned in full`() {
        val heard = "إعدادات الكاميرا\nجودة الصورة"
        val other = "رصيد المحفظة\nآخر عملية شراء"
        assertEquals(other, DocumentSpeechPolicy.newContent(alreadySpoken = heard, current = other))
    }

    @Test
    fun `same document tolerates recognition noise but separates real pages`() {
        assertTrue(
            DocumentSpeechPolicy.sameDocument(
                "الرصيد الحالي 9.20 ريال Customer care خدمة العملاء",
                "الرصيد الحالى ٩٫٢٠ ريال Customer care خدمة العملاء",
            )
        )
        assertFalse(
            DocumentSpeechPolicy.sameDocument(
                "إعدادات الكاميرا جودة الصورة الوضع الليلي",
                "رصيد المحفظة آخر عملية شراء بطاقة فيزا",
            )
        )
    }

    @Test
    fun `separator only blocks are never speakable`() {
        assertFalse(DocumentSpeechPolicy.isSpeakable(".."))
        assertFalse(DocumentSpeechPolicy.isSpeakable("  ...  "))
        assertTrue(DocumentSpeechPolicy.isSpeakable("STC"))
    }

    @Test
    fun `readable lines drop separator noise and keep visual order`() {
        val lines = DocumentSpeechPolicy.readableLines("أول\n..\n \nثاني\n...\nثالث")
        assertEquals(listOf("أول", "ثاني", "ثالث"), lines)
    }

    // region interface glyphs mistaken for text

    /**
     * The exact sequence from a device bundle. `NIVEA` and `BLEU DE CHANEL` were recognised across
     * seven pages and spoken zero times, because the frame also contained eSight's own controls and
     * the recogniser rendered them as `0`, `O`, `D`, `V` and `A` — single characters that are
     * substrings of almost every real line, so the continuation rules matched them against
     * everything and reported each page as already heard.
     */
    @Test
    fun `a brand name beside interface glyphs is not suppressed`() {
        val first = "\u2467\nNIVEA 0"
        val heard = DocumentSpeechPolicy.newContent(alreadySpoken = "", current = first)
        assertTrue("the first sight of a page is always new, got: $heard", heard.contains("NIVEA"))
    }

    @Test
    fun `single character lines are never spoken`() {
        val lines = DocumentSpeechPolicy.readableLines("0\nBLEU\nDE\nCHANEL\nV")
        assertEquals(listOf("BLEU", "DE", "CHANEL"), lines)
    }

    /**
     * The mechanism itself: a one-character line must not count as "already heard" just because its
     * character occurs inside some longer line the user did hear.
     */
    @Test
    fun `a stray character does not match a real line by containment`() {
        val remaining = DocumentSpeechPolicy.newContent(
            alreadySpoken = "CHANEL",
            current = "BLEU DE",
        )
        assertEquals("BLEU DE", remaining)
    }

    /** The rule it must not break: a genuine repeat of a page is still recognised as heard. */
    @Test
    fun `a real repeat is still suppressed`() {
        val remaining = DocumentSpeechPolicy.newContent(
            alreadySpoken = "BLEU DE CHANEL\nEAU DE PARFUM",
            current = "BLEU DE CHANEL\nEAU DE PARFUM",
        )
        assertEquals("", remaining)
    }

    /** And a longer line still absorbs the shorter one it genuinely contains. */
    @Test
    fun `substantial containment still counts`() {
        val remaining = DocumentSpeechPolicy.newContent(
            alreadySpoken = "نمط الطيران متوقف",
            current = "نمط الطيران",
        )
        assertEquals("", remaining)
    }

    // endregion
}
