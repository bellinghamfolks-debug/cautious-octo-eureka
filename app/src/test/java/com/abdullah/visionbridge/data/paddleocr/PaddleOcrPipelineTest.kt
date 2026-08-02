package com.abdullah.visionbridge.data.paddleocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DbPostProcessorTest {

    /** Builds a probability map with rectangular hot regions, as the detector would produce. */
    private fun mapWith(
        width: Int,
        height: Int,
        regions: List<IntArray>,
        value: Float = 0.9f,
    ): FloatArray {
        val data = FloatArray(width * height)
        regions.forEach { (left, top, right, bottom) ->
            for (y in top until bottom) for (x in left until right) data[y * width + x] = value
        }
        return data
    }

    @Test
    fun `two separated regions become two boxes`() {
        val probability = mapWith(
            width = 40,
            height = 20,
            regions = listOf(intArrayOf(2, 2, 14, 8), intArrayOf(24, 2, 36, 8)),
        )
        val boxes = DbPostProcessor.extractBoxes(
            probability, 40, 20, scaleX = 1f, scaleY = 1f, sourceWidth = 40, sourceHeight = 20,
        )
        assertEquals(2, boxes.size)
    }

    @Test
    fun `touching regions are one box`() {
        val probability = mapWith(
            width = 40, height = 20, regions = listOf(intArrayOf(2, 2, 20, 8), intArrayOf(20, 2, 36, 8)),
        )
        val boxes = DbPostProcessor.extractBoxes(
            probability, 40, 20, scaleX = 1f, scaleY = 1f, sourceWidth = 40, sourceHeight = 20,
        )
        assertEquals(1, boxes.size)
    }

    @Test
    fun `a faint region is rejected as noise`() {
        val probability = mapWith(
            width = 40, height = 20, regions = listOf(intArrayOf(2, 2, 20, 10)), value = 0.35f,
        )
        val boxes = DbPostProcessor.extractBoxes(
            probability, 40, 20, scaleX = 1f, scaleY = 1f, sourceWidth = 40, sourceHeight = 20,
        )
        assertTrue(boxes.isEmpty())
    }

    @Test
    fun `boxes are mapped back into source pixels and stay in bounds`() {
        val probability = mapWith(width = 20, height = 10, regions = listOf(intArrayOf(0, 0, 20, 10)))
        val boxes = DbPostProcessor.extractBoxes(
            probability, 20, 10, scaleX = 4f, scaleY = 4f, sourceWidth = 80, sourceHeight = 40,
        )
        assertEquals(1, boxes.size)
        val box = boxes.first()
        assertTrue(box.left >= 0 && box.top >= 0)
        assertTrue(box.right <= 80 && box.bottom <= 40)
        assertEquals(80, box.right)
        assertEquals(40, box.bottom)
    }

    /** A full-page paragraph is exactly the case that overflowed a recursive flood fill. */
    @Test
    fun `a region covering the whole map does not overflow`() {
        val probability = FloatArray(300 * 300) { 0.9f }
        val boxes = DbPostProcessor.extractBoxes(
            probability, 300, 300, scaleX = 1f, scaleY = 1f, sourceWidth = 300, sourceHeight = 300,
        )
        assertEquals(1, boxes.size)
    }
}

class CtcDecoderTest {

    private val dictionary = listOf("a", "b", "c", "د", "ر", "س")

    /** logits[step][class]; class 0 is the CTC blank. */
    private fun logits(steps: List<IntArray>, classes: Int): FloatArray {
        val data = FloatArray(steps.size * classes)
        steps.forEachIndexed { step, active ->
            data[step * classes + active[0]] = active[1] / 100f
        }
        return data
    }

    @Test
    fun `repeats collapse and blanks disappear`() {
        // a, a, blank, a, b  ->  "aab" is wrong; CTC gives "aab" only across a blank: "a" "a" "b"
        val data = logits(
            listOf(
                intArrayOf(1, 90), intArrayOf(1, 90), intArrayOf(0, 90),
                intArrayOf(1, 90), intArrayOf(2, 90),
            ),
            classes = 7,
        )
        val result = CtcDecoder.decode(data, steps = 5, classes = 7, dictionary = dictionary)
        assertEquals("aab", result.text)
    }

    @Test
    fun `arabic classes decode to arabic characters`() {
        val data = logits(
            listOf(intArrayOf(4, 95), intArrayOf(0, 95), intArrayOf(5, 95), intArrayOf(6, 95)),
            classes = 7,
        )
        val result = CtcDecoder.decode(data, steps = 4, classes = 7, dictionary = dictionary)
        assertEquals("درس", result.text)
    }

    @Test
    fun `confidence ignores blank steps`() {
        val data = FloatArray(4 * 7)
        data[0 * 7 + 1] = 0.8f
        data[1 * 7 + 0] = 0.99f
        data[2 * 7 + 0] = 0.99f
        data[3 * 7 + 2] = 0.6f
        val result = CtcDecoder.decode(data, steps = 4, classes = 7, dictionary = dictionary)
        assertEquals("ab", result.text)
        assertEquals(0.7f, result.confidence, 0.001f)
    }

    @Test
    fun `an all blank output is empty with no confidence`() {
        val data = FloatArray(3 * 7) { if (it % 7 == 0) 0.99f else 0f }
        val result = CtcDecoder.decode(data, steps = 3, classes = 7, dictionary = dictionary)
        assertEquals("", result.text)
        assertEquals(0f, result.confidence, 0.001f)
    }
}

class BilingualLineSelectorTest {

    /**
     * The exact failure this replaces: a Latin-only recognizer shown Arabic returned confident
     * nonsense that was displayed and spoken. On-script output must win even when it scores lower.
     */
    @Test
    fun `arabic wins over confident latin gibberish on an arabic line`() {
        val chosen = BilingualLineSelector.select(
            arabic = LineReading("خدمة العملاء", 0.72f, OcrScript.ARABIC),
            latin = LineReading("Library phone not font", 0.88f, OcrScript.LATIN),
        )
        assertNotNull(chosen)
        assertEquals(OcrScript.ARABIC, chosen!!.script)
        assertEquals("خدمة العملاء", chosen.text)
    }

    /**
     * On a Latin line the multilingual Arabic head transcribes Latin too, just less accurately than
     * the English specialist. That is the case where the English head is allowed to win.
     */
    @Test
    fun `the english specialist wins when the arabic head also reads latin`() {
        val chosen = BilingualLineSelector.select(
            arabic = LineReading("Batteny Heaith", 0.60f, OcrScript.ARABIC),
            latin = LineReading("Battery Health", 0.86f, OcrScript.LATIN),
        )
        assertEquals("Battery Health", chosen?.text)
    }

    @Test
    fun `the arabic head keeps its latin transcription when the english head fails`() {
        val chosen = BilingualLineSelector.select(
            arabic = LineReading("Battery Health", 0.71f, OcrScript.ARABIC),
            latin = null,
        )
        assertEquals("Battery Health", chosen?.text)
    }

    /** The crop is settled without running the English head at all. */
    @Test
    fun `an arabic reading settles the line on its own`() {
        assertTrue(
            BilingualLineSelector.isDecisiveArabic(
                LineReading("خدمة العملاء", 0.72f, OcrScript.ARABIC)
            )
        )
        assertFalse(
            BilingualLineSelector.isDecisiveArabic(
                LineReading("Battery Health", 0.95f, OcrScript.ARABIC)
            )
        )
        assertFalse(
            BilingualLineSelector.isDecisiveArabic(LineReading("خدمة", 0.30f, OcrScript.ARABIC))
        )
    }

    /** With no letters at all neither script can claim the line, so confidence decides. */
    @Test
    fun `a digits only line falls back to confidence`() {
        val chosen = BilingualLineSelector.select(
            arabic = LineReading("1 234", 0.61f, OcrScript.ARABIC),
            latin = LineReading("1234", 0.83f, OcrScript.LATIN),
        )
        assertEquals("1234", chosen?.text)
    }

    @Test
    fun `both below the acceptance floor yields nothing`() {
        assertNull(
            BilingualLineSelector.select(
                arabic = LineReading("خ", 0.20f, OcrScript.ARABIC),
                latin = LineReading("l", 0.31f, OcrScript.LATIN),
            )
        )
    }

    @Test
    fun `direction follows the dominant script, not the presence of latin`() {
        assertTrue(BilingualLineSelector.isRightToLeft("ابدأ OpenAI ثم اضغط"))
        assertFalse(BilingualLineSelector.isRightToLeft("Press Save الآن"))
    }
}

class TextLineOrderingTest {

    private fun box(left: Int, top: Int, right: Int, bottom: Int) =
        TextBox(left, top, right, bottom, 0.9f)

    @Test
    fun `boxes on the same row group into one line`() {
        val lines = TextLineOrdering.groupIntoLines(
            listOf(box(0, 0, 40, 20), box(60, 2, 100, 22), box(0, 60, 40, 80)),
        )
        assertEquals(2, lines.size)
        assertEquals(2, lines[0].size)
        assertEquals(1, lines[1].size)
    }

    @Test
    fun `an arabic line reads right to left`() {
        val ordered = TextLineOrdering.orderWithinLine(
            listOf(box(0, 0, 40, 20), box(60, 0, 100, 20)),
            rightToLeft = true,
        )
        assertEquals(60, ordered.first().left)
    }

    @Test
    fun `an english line reads left to right`() {
        val ordered = TextLineOrdering.orderWithinLine(
            listOf(box(60, 0, 100, 20), box(0, 0, 40, 20)),
            rightToLeft = false,
        )
        assertEquals(0, ordered.first().left)
    }

    /**
     * The failure this guards: when the middle box of a line is unreadable, ordering boxes and
     * looking their text back up by position hands every later word to the wrong box.
     */
    @Test
    fun `text stays attached to its own box when a box in the line is unreadable`() {
        val read = listOf(
            box(0, 0, 40, 20) to "الرصيد",
            // the box at x=60 failed to recognize and is absent
            box(120, 0, 160, 20) to "الحالي",
        )
        val ordered = TextLineOrdering.orderWithinLineBy(read, rightToLeft = true) { it.first }
        assertEquals(listOf("الحالي", "الرصيد"), ordered.map { it.second })
    }
}

class PageAssemblerTest {

    @Test
    fun `each visual line becomes exactly one output line`() {
        val page = PageAssembler.assemble(
            listOf(
                listOf(
                    LineReading("الرصيد", 0.9f, OcrScript.ARABIC),
                    LineReading("الحالي", 0.9f, OcrScript.ARABIC),
                ),
                listOf(LineReading("Battery Health", 0.9f, OcrScript.LATIN)),
            )
        )
        assertEquals("الرصيد الحالي\nBattery Health", page)
    }

    @Test
    fun `empty lines are dropped rather than becoming blank speech`() {
        val page = PageAssembler.assemble(
            listOf(listOf(LineReading("  ", 0.9f, OcrScript.LATIN)), listOf(LineReading("نص", 0.9f, OcrScript.ARABIC))),
        )
        assertEquals("نص", page)
    }
}

/**
 * Every case here is a string the recognizer actually produced on a real phone, paired with what
 * the screen actually said.
 */
class BidiTextOrderTest {

    @Test
    fun `an arabic line comes back in logical order`() {
        assertEquals(
            "نقطة الاتصال المحمولة",
            BidiTextOrder.toLogicalOrder("ةلومحملا لاصتالا ةطقن"),
        )
        assertEquals("الإعدادات", BidiTextOrder.toLogicalOrder("تادادعإلا"))
        assertEquals("إضافة شبكة", BidiTextOrder.toLogicalOrder("ةكبش ةفاضإ"))
        assertEquals(
            "مزيد من خيارات الاتصال",
            BidiTextOrder.toLogicalOrder("لاصتالا تارايخ نم ديزم"),
        )
    }

    /** Reversing the whole string would turn Wi-Fi into iF-iW. */
    @Test
    fun `a latin run inside an arabic line keeps its own direction`() {
        assertEquals("شبكة Wi-Fi", BidiTextOrder.toLogicalOrder("Wi-Fi ةكبش"))
        assertEquals("مشغل Bluetooth", BidiTextOrder.toLogicalOrder("Bluetooth لغشم"))
    }

    @Test
    fun `digits inside an arabic line stay in reading order`() {
        assertEquals("الإصدار 2.0.0", BidiTextOrder.toLogicalOrder("2.0.0 رادصإلا"))
        assertEquals("جهاز 1", BidiTextOrder.toLogicalOrder("1 زاهج"))
    }

    /** A line with no right-to-left script was never reversed, so it must not be touched. */
    @Test
    fun `a latin only line is returned unchanged`() {
        assertEquals("Battery Health", BidiTextOrder.toLogicalOrder("Battery Health"))
        assertEquals("Xiaomi HyperAI", BidiTextOrder.toLogicalOrder("Xiaomi HyperAI"))
        assertEquals("", BidiTextOrder.toLogicalOrder(""))
    }

    @Test
    fun `brackets are mirrored so a span still opens before it closes`() {
        assertEquals("(شبكة)", BidiTextOrder.toLogicalOrder("(ةكبش)"))
    }
}

class RecognitionDictionaryTest {

    /**
     * The metadata ends with a newline. Keeping the empty entry it produces added a phantom class
     * before the space, so the model's space index landed on the phantom and every space decoded as
     * "" — a whole screen arrived as one run-on word while every other character was correct.
     */
    @Test
    fun `a trailing newline does not add a phantom class`() {
        val parsed = RecognitionDictionary.parse("a\nb\nc\n")
        assertEquals(listOf("a", "b", "c", " "), parsed)
    }

    @Test
    fun `the space class is the last entry so the model index reaches it`() {
        // Real proportions: the Arabic head reports 749 classes for 747 characters plus blank plus
        // space. Class index 748 must resolve to a space, which is dictionary[747] after the blank.
        val raw = (1..747).joinToString("\n") { "c$it" } + "\n"
        val parsed = RecognitionDictionary.parse(raw)
        assertEquals(748, parsed.size)
        assertEquals(" ", parsed[747])
    }

    @Test
    fun `windows line endings are stripped`() {
        assertEquals(listOf("a", "b", " "), RecognitionDictionary.parse("a\r\nb\r\n"))
    }

    @Test
    fun `a file without a trailing newline keeps every character`() {
        assertEquals(listOf("a", "b", " "), RecognitionDictionary.parse("a\nb"))
    }
}

class ArabicHeadShortcutTest {

    /** Pure Arabic: the English head adds nothing, so skipping it is free. */
    @Test
    fun `a purely arabic reading skips the english head`() {
        assertTrue(
            BilingualLineSelector.isPureConfidentArabic(
                LineReading("تجوال البيانات", 0.93f, OcrScript.ARABIC)
            )
        )
    }

    /**
     * The regression this guards: "تشغيل بطاقة SIM 1" is decisively Arabic by majority, so the old
     * shortcut suppressed the English specialist on exactly the lines whose Latin part matters.
     */
    @Test
    fun `an arabic line containing latin still consults the english head`() {
        val mixed = LineReading("تشغيل بطاقة SIM 1", 0.93f, OcrScript.ARABIC)
        assertTrue(BilingualLineSelector.isDecisiveArabic(mixed))
        assertFalse(BilingualLineSelector.isPureConfidentArabic(mixed))
    }

    @Test
    fun `a weak arabic reading never short circuits`() {
        assertFalse(
            BilingualLineSelector.isPureConfidentArabic(
                LineReading("تجوال", 0.31f, OcrScript.ARABIC)
            )
        )
    }

    @Test
    fun `arabic indic digits do not count as latin`() {
        assertTrue(
            BilingualLineSelector.isPureConfidentArabic(
                LineReading("غير معين ٩٦٦٥٣٨٤٨٣٥٣٣", 0.90f, OcrScript.ARABIC)
            )
        )
    }
}

/**
 * Built from a device bundle in which 68% of discarded crops were a single character and the
 * median accepted crop was two characters long — the signature of a word arriving one glyph at a
 * time.
 */
class BoxMergingTest {

    private fun box(left: Int, right: Int, top: Int = 0, bottom: Int = 40) =
        TextBox(left, top, right, bottom, 0.9f)

    /** "CHANEL" in wide display type: six blobs, one word. */
    @Test
    fun `letterspaced glyphs become one crop`() {
        val glyphs = listOf(
            box(0, 30), box(50, 80), box(100, 130),
            box(150, 180), box(200, 230), box(250, 280),
        )
        val merged = TextLineOrdering.mergeAdjacent(glyphs)
        assertEquals(1, merged.size)
        assertEquals(0, merged.first().left)
        assertEquals(280, merged.first().right)
    }

    /** A label and its value sit far apart and must stay apart. */
    @Test
    fun `a wide gap is left alone`() {
        val merged = TextLineOrdering.mergeAdjacent(listOf(box(0, 200), box(700, 900)))
        assertEquals(2, merged.size)
    }

    @Test
    fun `boxes on different rows are never merged`() {
        val merged = TextLineOrdering.mergeAdjacent(
            listOf(box(0, 30, top = 0, bottom = 40), box(35, 65, top = 200, bottom = 240)),
        )
        assertEquals(2, merged.size)
    }

    @Test
    fun `overlapping boxes merge`() {
        val merged = TextLineOrdering.mergeAdjacent(listOf(box(0, 100), box(80, 160)))
        assertEquals(1, merged.size)
        assertEquals(160, merged.first().right)
    }

    @Test
    fun `a single box and an empty line are returned unchanged`() {
        assertEquals(1, TextLineOrdering.mergeAdjacent(listOf(box(0, 30))).size)
        assertTrue(TextLineOrdering.mergeAdjacent(emptyList()).isEmpty())
    }

    /** Merging must not reorder or lose a line's extent. */
    @Test
    fun `merging preserves the full horizontal extent of a line`() {
        val boxes = listOf(box(0, 30), box(40, 70), box(600, 700))
        val merged = TextLineOrdering.mergeAdjacent(boxes)
        assertEquals(2, merged.size)
        assertEquals(0, merged.first().left)
        assertEquals(700, merged.last().right)
    }
}

/**
 * Cases found by simulating real layouts against the detection and grouping code rather than by
 * waiting for a device to hit them.
 */
class LayoutSimulationTest {

    private fun box(left: Int, top: Int, right: Int, bottom: Int) =
        TextBox(left, top, right, bottom, 0.9f)

    /** Renders a line as separate glyph blobs, which is what the detector actually emits. */
    private fun canvas(width: Int, height: Int, build: (put: (Int, Int, Int, Int) -> Unit) -> Unit): FloatArray {
        val data = FloatArray(width * height)
        build { l, t, r, b ->
            for (y in t.coerceAtLeast(0) until b.coerceAtMost(height))
                for (x in l.coerceAtLeast(0) until r.coerceAtMost(width)) data[y * width + x] = 0.9f
        }
        return data
    }

    /**
     * A tolerance taken from the incoming box's own height let a tall heading reach up and swallow
     * the smaller line above it, because a big box got a big tolerance.
     */
    @Test
    fun `a tall heading does not absorb the line above it`() {
        val lines = TextLineOrdering.groupIntoLines(
            listOf(box(0, 10, 100, 26), box(0, 20, 200, 60)),
        )
        assertEquals(2, lines.size)
    }

    @Test
    fun `boxes of very different sizes on the same row stay one line`() {
        val lines = TextLineOrdering.groupIntoLines(
            listOf(box(10, 20, 70, 60), box(110, 32, 150, 46)),
        )
        assertEquals(1, lines.size)
    }

    /** A scrollbar or window rule spans every row at once and drags unrelated rows together. */
    @Test
    fun `detection rejects a full height sliver`() {
        val data = canvas(400, 400) { put ->
            put(300, 0, 304, 400)
            put(0, 10, 60, 26)
            put(0, 100, 60, 116)
        }
        val boxes = DbPostProcessor.extractBoxes(data, 400, 400, 1f, 1f, 400, 400)
        assertEquals(2, boxes.size)
        assertEquals(2, TextLineOrdering.groupIntoLines(boxes).size)
    }

    /** A single Arabic alif is about five to one, so real glyphs stay well clear of the guard. */
    @Test
    fun `a tall narrow glyph is still accepted`() {
        val data = canvas(100, 60) { put -> put(20, 10, 24, 40) }
        assertEquals(1, DbPostProcessor.extractBoxes(data, 100, 60, 1f, 1f, 100, 60).size)
    }

    /** End to end: a letterspaced sign must come out as one crop, not six. */
    @Test
    fun `a letterspaced sign survives detection grouping and merging as one crop`() {
        val data = canvas(400, 100) { put ->
            var x = 20
            repeat(6) { put(x, 20, x + 22, 64); x += 42 }
        }
        val boxes = DbPostProcessor.extractBoxes(data, 400, 100, 1f, 1f, 400, 100)
        val crops = TextLineOrdering.groupIntoLines(boxes).map(TextLineOrdering::mergeAdjacent)
        assertEquals(1, crops.size)
        assertEquals(1, crops.first().size)
    }

    @Test
    fun `two columns on the same row are not merged`() {
        val data = canvas(600, 60) { put ->
            var x = 10
            repeat(6) { put(x, 20, x + 8, 36); x += 10 }
            x = 400
            repeat(5) { put(x, 20, x + 8, 36); x += 10 }
        }
        val boxes = DbPostProcessor.extractBoxes(data, 600, 60, 1f, 1f, 600, 60)
        val crops = TextLineOrdering.groupIntoLines(boxes).map(TextLineOrdering::mergeAdjacent)
        assertEquals(2, crops.first().size)
    }
}

class BidiRunTest {

    /** The sign belongs to the number. Trimming it off left "966+" on a real dialling code. */
    @Test
    fun `a leading sign stays with its number`() {
        assertEquals(
            "الرقم +966 3550",
            BidiTextOrder.toLogicalOrder("3550 +966 مقرلا"),
        )
    }

    @Test
    fun `a trailing symbol stays with its number`() {
        assertEquals("البطارية 80%", BidiTextOrder.toLogicalOrder("80% ةيراطبلا"))
    }
}
