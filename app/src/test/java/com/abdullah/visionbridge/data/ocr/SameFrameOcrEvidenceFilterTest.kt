package com.abdullah.visionbridge.data.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SameFrameOcrEvidenceFilterTest {
    private val filter = SameFrameOcrEvidenceFilter()

    @Test
    fun `unsupported English brand is hidden while Arabic order remains`() {
        val result = filter.filter(
            geminiText = "ابدأ OpenAI ثم اضغط Save الآن.",
            localEvidence = "OpenAl Save",
        )

        assertTrue(result.startsWith("ابدأ"))
        assertTrue(result.contains("OpenAI"))
        assertTrue(result.contains("Save"))
        assertTrue(result.endsWith("الآن."))
    }

    @Test
    fun `invented Latin token becomes unclear`() {
        val result = filter.filter(
            geminiText = "السعر Premium 49 SAR",
            localEvidence = "49 SAR",
        )

        assertEquals("السعر [غير واضح] 49 SAR", result)
    }

    @Test
    fun `blank local evidence does not erase small Arabic or English text`() {
        assertEquals(
            "مرحبا OpenAI",
            filter.filter("مرحبا OpenAI", ""),
        )
    }
}
