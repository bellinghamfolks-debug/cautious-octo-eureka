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
}
