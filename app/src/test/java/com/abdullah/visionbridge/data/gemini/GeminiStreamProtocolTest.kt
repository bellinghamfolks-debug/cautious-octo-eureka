package com.abdullah.visionbridge.data.gemini

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiStreamProtocolTest {
    @Test
    fun `metadata split across SSE events is hidden from scene speech`() {
        val accumulator = GeminiStreamAccumulator()
        assertEquals("", accumulator.append("META|language=mi"))
        val body = accumulator.append("xed|urgent=false\nابدأ OpenAI الآن.")
        assertEquals("ابدأ OpenAI الآن.", body)
        assertEquals("mixed", accumulator.language)
        assertFalse(accumulator.urgent)
        assertEquals("ابدأ OpenAI الآن.", accumulator.fullText)
    }

    @Test
    fun `cancelled stream never exposes an unfinished metadata line`() {
        val accumulator = GeminiStreamAccumulator(requireQualityHeader = false)
        assertEquals("", accumulator.append("META|language="))
        assertEquals("", accumulator.finish())
        assertEquals("", accumulator.fullText)
    }

    @Test
    fun `fast OCR protocol exposes text after one metadata line`() {
        val accumulator = GeminiStreamAccumulator(requireQualityHeader = false)
        val body = accumulator.append("META|language=mixed|urgent=false\nمرحبا OpenAI.")
        assertEquals("مرحبا OpenAI.", body)
        assertTrue(accumulator.ocrAccepted)
    }

    @Test
    fun `trusted OCR waits for both protocol lines before exposing text`() {
        val accumulator = GeminiStreamAccumulator(requireQualityHeader = true)
        assertEquals("", accumulator.append("META|language=mixed|urgent=false\nQUAL"))
        val body = accumulator.append("ITY|legible=true|confidence=94|inferred=false\nمرحبا OpenAI.")
        assertTrue(accumulator.ocrAccepted)
        assertEquals(94, accumulator.confidence)
        assertEquals("مرحبا OpenAI.", body)
        assertEquals("مرحبا OpenAI.", accumulator.fullText)
    }

    @Test
    fun `low confidence OCR body is never exposed`() {
        val accumulator = GeminiStreamAccumulator(requireQualityHeader = true)
        val body = accumulator.append(
            "META|language=ar|urgent=false\n" +
                "QUALITY|legible=true|confidence=61|inferred=false\n" +
                "نص متوقع من السياق."
        )
        assertFalse(accumulator.ocrAccepted)
        assertEquals("", body)
        assertEquals("", accumulator.fullText)
    }

    @Test
    fun `self reported inference rejects otherwise confident OCR`() {
        val accumulator = GeminiStreamAccumulator(requireQualityHeader = true)
        val body = accumulator.append(
            "META|language=en|urgent=false\n" +
                "QUALITY|legible=true|confidence=98|inferred=true\n" +
                "Guessed brand name"
        )
        assertFalse(accumulator.ocrAccepted)
        assertEquals("", body)
    }

    @Test
    fun `urgent metadata is parsed before first scene block`() {
        val accumulator = GeminiStreamAccumulator()
        val body = accumulator.append("META|language=ar|urgent=true\nدرج أمامك.")
        assertTrue(accumulator.urgent)
        assertEquals("ar", accumulator.language)
        assertEquals("درج أمامك.", body)
    }

    @Test
    fun `network word fragments are held until a complete sentence`() {
        val buffer = StreamingSpeechBuffer()
        assertTrue(buffer.append("يوجد نص عربي وفي الوسط Open", urgent = false).isEmpty())
        assertTrue(buffer.append("AI ثم يعود النص العربي", urgent = false).isEmpty())
        val output = buffer.append(" في الترتيب الصحيح تماماً.", urgent = false)
        assertEquals(
            listOf("يوجد نص عربي وفي الوسط OpenAI ثم يعود النص العربي في الترتيب الصحيح تماماً."),
            output,
        )
    }

    @Test
    fun `long clause and completed sentence stream without waiting for close`() {
        val buffer = StreamingSpeechBuffer()
        val prefix = "أمامك ممر واضح يمتد إلى الأمام مع كرسي قريب على اليمين وطاولة صغيرة على اليسار"
        val output = buffer.append("$prefix، ثم يظهر الباب في نهاية الممر.", urgent = false)
        assertEquals(
            listOf(
                "$prefix،",
                "ثم يظهر الباب في نهاية الممر.",
            ),
            output,
        )
        assertTrue(buffer.finish().isEmpty())
    }

    @Test
    fun `two completed short sentences can start immediately`() {
        val buffer = StreamingSpeechBuffer()
        assertEquals(listOf("باب. كرسي."), buffer.append("باب. كرسي.", urgent = false))
        assertTrue(buffer.append(" نافذة.", urgent = false).isEmpty())
        assertEquals(listOf("نافذة."), buffer.finish())
    }

    @Test
    fun `unfinished tail is spoken only when stream closes`() {
        val buffer = StreamingSpeechBuffer()
        assertTrue(buffer.append("النص الأخير بلا نقطة", urgent = false).isEmpty())
        assertEquals(listOf("النص الأخير بلا نقطة"), buffer.finish())
    }

    @Test
    fun `a document is never cut at a comma`() {
        val buffer = StreamingSpeechBuffer(StreamingSpeechBuffer.Profile.DOCUMENT)
        val line = "أمامك ممر واضح يمتد إلى الأمام مع كرسي قريب على اليمين وطاولة صغيرة على اليسار"
        val output = buffer.append("$line، ثم يظهر الباب في نهاية الممر.", urgent = false) + buffer.finish()
        assertEquals(listOf("$line، ثم يظهر الباب في نهاية الممر."), output)
    }

    @Test
    fun `separator only fragments never reach speech`() {
        val buffer = StreamingSpeechBuffer(StreamingSpeechBuffer.Profile.DOCUMENT)
        val output = buffer.append("..\n...\n. .\n", urgent = false) + buffer.finish()
        assertTrue(output.isEmpty())
    }

    /**
     * The dense screen from the diagnostics was split into twenty eight blocks, most of which were
     * then lost to a bounded queue. Document phrasing keeps it to a handful of whole blocks.
     */
    @Test
    fun `a dense page becomes a few whole blocks instead of dozens of fragments`() {
        val buffer = StreamingSpeechBuffer(StreamingSpeechBuffer.Profile.DOCUMENT)
        val page = """
            Current balance: 9.20 SAR.
            Subscribe to sawa packages and enjoy more.
            الخميس Customer care ... خدمة العملاء الجديدة ...
            عذراً عبدالله، الأربعاء STC مرحبا.
            نوّد التنويه بوجود مبلغ مستحق على حسابك.
            عزيزي عميل تابي نود إفادتكم بعدم السداد.
            لديك دفعة مستحقة خلال 3 أيام، تأكد من سدادها.
        """.trimIndent()
        val output = buffer.append(page, urgent = false) + buffer.finish()

        assertTrue("expected few blocks but got ${output.size}", output.size <= 6)
        assertTrue(output.all { block -> block.any(Char::isLetterOrDigit) })
        val rejoined = output.joinToString(" ")
        assertTrue(rejoined.contains("Current balance"))
        assertTrue(rejoined.contains("لديك دفعة مستحقة"))
    }
}
