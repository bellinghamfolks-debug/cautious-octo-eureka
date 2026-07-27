package com.abdullah.visionbridge.data.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechTextToolsTest {
    @Test
    fun `dominant language detects Arabic`() {
        assertEquals(SpeechLanguage.ARABIC, SpeechTextTools.dominantLanguage("مرحبا بك في التطبيق"))
    }

    @Test
    fun `mixed text creates Arabic and English segments`() {
        val segments = SpeechTextTools.segment("مرحبا Abdullah welcome")
        assertTrue(segments.any { it.language == SpeechLanguage.ARABIC })
        assertTrue(segments.any { it.language == SpeechLanguage.ENGLISH })
    }

    @Test
    fun `comparison normalization ignores OCR formatting noise`() {
        val first = SpeechTextTools.normalizeForComparison("إِنَّ الرَّقْمَ ١٢٣، واضحٌ!")
        val second = SpeechTextTools.normalizeForComparison("ان الرقم 123 واضح")
        assertEquals(second, first)
    }

    @Test
    fun `deduplicator suppresses punctuation and diacritic variants`() {
        val deduplicator = SpeechDeduplicator(duplicateWindowMs = 10_000)
        assertTrue(deduplicator.shouldSpeak("الباب أمامك رقم ١٢.", urgent = false, now = 1_000))
        assertFalse(deduplicator.shouldSpeak("الباب امامك رقم 12", urgent = false, now = 2_000))
    }

    @Test
    fun `deduplicator compares against history not only last sentence`() {
        val deduplicator = SpeechDeduplicator(duplicateWindowMs = 20_000)
        assertTrue(deduplicator.shouldSpeak("الباب أمامك", urgent = false, now = 1_000))
        assertTrue(deduplicator.shouldSpeak("يوجد كرسي إلى اليمين", urgent = false, now = 2_000))
        assertFalse(deduplicator.shouldSpeak("الباب امامك", urgent = false, now = 3_000))
    }

    @Test
    fun `urgent warning interrupts once then is throttled`() {
        val deduplicator = SpeechDeduplicator(
            duplicateWindowMs = 60_000,
            urgentDuplicateWindowMs = 20_000,
        )
        assertTrue(deduplicator.shouldSpeak("عائق أمامك", urgent = false, now = 1_000))
        assertTrue(deduplicator.shouldSpeak("عائق أمامك", urgent = true, now = 2_000))
        assertFalse(deduplicator.shouldSpeak("عائق أمامك", urgent = true, now = 3_000))
        assertTrue(deduplicator.shouldSpeak("عائق أمامك", urgent = true, now = 23_000))
    }

    @Test
    fun `cloud delta keeps Arabic without repeating local English`() {
        val delta = SpeechTextTools.cloudDeltaAgainstLocal(
            cloudText = "Open settings الإعدادات ثم Save حفظ",
            localText = "Open settings Save",
        )
        assertFalse(delta.contains("Open settings"))
        assertFalse(delta.contains("Save"))
        assertTrue(delta.contains("الإعدادات"))
        assertTrue(delta.contains("حفظ"))
    }
}
