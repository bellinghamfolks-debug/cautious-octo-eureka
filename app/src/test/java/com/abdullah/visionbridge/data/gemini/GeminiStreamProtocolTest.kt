package com.abdullah.visionbridge.data.gemini

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiStreamProtocolTest {
    @Test
    fun `metadata split across SSE events is hidden from speech`() {
        val accumulator = GeminiStreamAccumulator()

        assertEquals("", accumulator.append("META|language=mi"))
        val body = accumulator.append("xed|urgent=false\nابدأ OpenAI الآن."))

        assertEquals("ابدأ OpenAI الآن.", body)
        assertEquals("mixed", accumulator.language)
        assertFalse(accumulator.urgent)
        assertEquals("ابدأ OpenAI الآن.", accumulator.fullText)
    }

    @Test
    fun `urgent metadata is parsed before first spoken block`() {
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
    fun `long clause may stream at a natural comma`() {
        val buffer = StreamingSpeechBuffer()
        val prefix = "أمامك ممر واضح يمتد إلى الأمام مع كرسي قريب على اليمين وطاولة صغيرة على اليسار"
        val output = buffer.append("$prefix، ثم يظهر الباب في نهاية الممر.", urgent = false)

        assertEquals(1, output.size)
        assertTrue(output.first().endsWith("،"))
        assertEquals(listOf("ثم يظهر الباب في نهاية الممر."), buffer.finish())
    }

    @Test
    fun `three short sentences are emitted together instead of word fragments`() {
        val buffer = StreamingSpeechBuffer()
        assertTrue(buffer.append("باب. كرسي.", urgent = false).isEmpty())
        val output = buffer.append(" نافذة.", urgent = false)

        assertEquals(listOf("باب. كرسي. نافذة."), output)
    }

    @Test
    fun `unfinished tail is spoken only when stream closes`() {
        val buffer = StreamingSpeechBuffer()
        assertTrue(buffer.append("النص الأخير بلا نقطة", urgent = false).isEmpty())
        assertEquals(listOf("النص الأخير بلا نقطة"), buffer.finish())
    }
}
