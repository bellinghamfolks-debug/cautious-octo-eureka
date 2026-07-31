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
