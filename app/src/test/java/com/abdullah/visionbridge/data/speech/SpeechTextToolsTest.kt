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
    fun `deduplicator suppresses repeated sentence in window`() {
        val deduplicator = SpeechDeduplicator(duplicateWindowMs = 10_000)
        assertTrue(deduplicator.shouldSpeak("باب أمامك", urgent = false, now = 1_000))
        assertFalse(deduplicator.shouldSpeak("باب أمامك", urgent = false, now = 2_000))
    }

    @Test
    fun `urgent result bypasses duplicate suppression`() {
        val deduplicator = SpeechDeduplicator(duplicateWindowMs = 10_000)
        assertTrue(deduplicator.shouldSpeak("عائق أمامك", urgent = false, now = 1_000))
        assertTrue(deduplicator.shouldSpeak("عائق أمامك", urgent = true, now = 2_000))
    }
}
