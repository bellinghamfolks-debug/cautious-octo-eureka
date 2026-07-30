package com.abdullah.visionbridge.data.localvlm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VlmOutputSanitizerTest {

    @Test
    fun `chat template tokens never reach speech`() {
        val cleaned = VlmOutputSanitizer.sanitize(
            "<|im_start|>assistant\nالرصيد الحالي 9.20 ريال<|im_end|>"
        )
        assertEquals("الرصيد الحالي 9.20 ريال", cleaned)
    }

    @Test
    fun `conversational openers are removed in both languages`() {
        assertEquals(
            "Current balance 9.20 SAR",
            VlmOutputSanitizer.sanitize("Sure! Here is the text in the image: Current balance 9.20 SAR"),
        )
        assertEquals(
            "الرصيد الحالي",
            VlmOutputSanitizer.sanitize("النص الظاهر في الصورة هو: الرصيد الحالي"),
        )
    }

    @Test
    fun `a real sentence that merely resembles filler is preserved`() {
        val page = "هذا هو المبلغ المستحق عليك خلال ثلاثة أيام."
        assertEquals(page, VlmOutputSanitizer.sanitize(page))
    }

    @Test
    fun `markdown fences around a transcription are stripped`() {
        assertEquals(
            "خدمة العملاء\nCustomer care",
            VlmOutputSanitizer.sanitize("```text\nخدمة العملاء\nCustomer care\n```"),
        )
    }

    @Test
    fun `bidi overrides are removed but Arabic joiners survive`() {
        val withOverrides = "‫الرصيد‬ ‏Customer‎ care⁩"
        val cleaned = VlmOutputSanitizer.sanitize(withOverrides)
        assertFalse(cleaned.any { it in '‪'..'‮' })
        assertFalse(cleaned.contains('‏'))
        assertFalse(cleaned.contains('‎'))
        assertTrue(cleaned.contains("الرصيد"))
        assertTrue(cleaned.contains("Customer care"))

        val withJoiner = "می‌خواهم"
        assertTrue(VlmOutputSanitizer.sanitize(withJoiner).contains('‌'))
    }

    @Test
    fun `replacement characters from split tokens are dropped`() {
        assertEquals("خدمة العملاء", VlmOutputSanitizer.sanitize("خدمة� العملاء"))
    }

    @Test
    fun `a looping line is emitted once`() {
        val looped = buildString {
            append("لديك دفعة مستحقة خلال 3 أيام.\n".repeat(8))
        }
        assertEquals("لديك دفعة مستحقة خلال 3 أيام.", VlmOutputSanitizer.sanitize(looped))
    }

    @Test
    fun `a repeating tail is cut back to a single copy`() {
        val block = "تأكد من سدادها أو وجود رصيد كافٍ في بطاقتك. "
        val cleaned = VlmOutputSanitizer.sanitize("عزيزي العميل. $block$block$block")
        assertEquals(1, countOccurrences(cleaned, "تأكد من سدادها"))
    }

    @Test
    fun `a genuinely repeated short line far apart is kept`() {
        val page = (1..10).joinToString("\n") { index ->
            if (index == 1 || index == 10) "١٤ يوليو" else "سطر رقم $index من الرسالة"
        }
        val cleaned = VlmOutputSanitizer.sanitize(page)
        assertEquals(2, countOccurrences(cleaned, "١٤ يوليو"))
    }

    @Test
    fun `stuttered characters are collapsed`() {
        assertEquals("مرحبااا", VlmOutputSanitizer.sanitize("مرحبااااااااااا"))
    }

    @Test
    fun `blank output stays blank rather than becoming noise`() {
        assertEquals("", VlmOutputSanitizer.sanitize("<|im_start|>assistant<|im_end|>"))
        assertEquals("", VlmOutputSanitizer.sanitize("   \n\n  "))
    }

    @Test
    fun `mixed Arabic and Latin order is preserved exactly`() {
        val mixed = "ابدأ OpenAI ثم اضغط Save الآن."
        assertEquals(mixed, VlmOutputSanitizer.sanitize(mixed))
    }

    private fun countOccurrences(haystack: String, needle: String): Int {
        var count = 0
        var index = haystack.indexOf(needle)
        while (index >= 0) {
            count++
            index = haystack.indexOf(needle, index + needle.length)
        }
        return count
    }
}

class VlmLoopGuardTest {
    @Test
    fun `a healthy page never trips the guard`() {
        val guard = VlmLoopGuard()
        val page = """
            الرصيد الحالي 9.20 ريال
            Subscribe to sawa packages and enjoy more services
            خدمة العملاء الجديدة متاحة الآن على مدار الساعة
            عزيزي العميل نأمل منكم المبادرة بسداد المبلغ المستحق
            لديك دفعة مستحقة خلال ثلاثة أيام فتأكد من وجود رصيد
        """.trimIndent()
        var accumulated = ""
        var tripped = false
        page.chunked(8).forEach { fragment ->
            accumulated += fragment
            if (guard.hasDegenerated(accumulated)) tripped = true
        }
        assertFalse(tripped)
    }

    @Test
    fun `a repeating clause stops generation`() {
        val guard = VlmLoopGuard()
        val block = "لديك دفعة مستحقة خلال ثلاثة أيام. "
        var accumulated = "عزيزي العميل، "
        var stoppedAfter = -1
        repeat(10) { index ->
            accumulated += block
            if (stoppedAfter < 0 && guard.hasDegenerated(accumulated)) stoppedAfter = index + 1
        }
        assertTrue("guard never fired", stoppedAfter in 1..4)
    }

    @Test
    fun `repetition is detected across differing whitespace`() {
        val guard = VlmLoopGuard()
        var accumulated = ""
        repeat(8) { index ->
            accumulated += if (index % 2 == 0) {
                "Current balance is 9.20 SAR today. "
            } else {
                "Current  balance   is 9.20 SAR today.\n"
            }
        }
        assertTrue(guard.hasDegenerated(accumulated))
    }
}
