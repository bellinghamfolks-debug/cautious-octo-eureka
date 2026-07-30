package com.abdullah.visionbridge.data.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalOcrTextMergerTest {
    @Test
    fun `Arabic text remains primary while unique Latin labels are retained`() {
        val result = LocalOcrTextMerger.merge(
            arabic = "إعدادات الكاميرا\nجودة الصورة\nالوضع الليلي",
            latin = "LEICA\nRAW\n1x",
        )

        assertTrue(result.startsWith("إعدادات الكاميرا"))
        assertTrue(result.contains("RAW"))
        assertFalse(result.contains("LEICA"))
        assertFalse(result.contains("1x"))
    }

    @Test
    fun `novel returns only lines not already spoken`() {
        val result = LocalOcrTextMerger.novel(
            previous = "إعدادات الكاميرا\nجودة الصورة",
            current = "إعدادات الكاميرا\nجودة الصورة\nالوضع الليلي",
        )

        assertEquals("الوضع الليلي", result)
    }

    @Test
    fun `camera chrome alone is discarded`() {
        assertEquals("", LocalOcrTextMerger.merge("", "LEICA\n1x\nVIBR"))
    }
}
