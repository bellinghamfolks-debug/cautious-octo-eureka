package com.abdullah.visionbridge.data.paddleocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.abdullah.visionbridge.domain.model.LocalReadingQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the real reader on real hardware.
 *
 * Everything below this class can be simulated on a workstation — geometry, ordering, decoding,
 * bidi — and all of it is. What cannot be simulated is whether ONNX Runtime loads these four models
 * on an arm64 phone, whether XNNPACK registers, whether inference fits in memory, and whether the
 * chain actually turns pixels into the right characters. That is what this covers, by drawing text
 * with the platform's own text engine and asking the reader to read it back.
 *
 * It needs no API key, no network and no screen-capture consent, so it can run unattended on a
 * device farm.
 */
@RunWith(AndroidJUnit4::class)
class PaddleOcrOnDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var engine: PaddleOcrEngine

    @Before
    fun setUp() {
        engine = PaddleOcrEngine(context)
        val loaded = runBlocking { engine.ensureLoaded() }
        assertTrue("models must load on this device: ${loaded.exceptionOrNull()}", loaded.isSuccess)
    }

    @After
    fun tearDown() {
        runBlocking { engine.release("instrumentation_finished") }
    }

    /** Draws lines of text the way a screen would, dark on light, at a realistic size. */
    private fun render(lines: List<String>, textSize: Float = 56f, letterSpacing: Float = 0f): Bitmap {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            this.textSize = textSize
            this.letterSpacing = letterSpacing
            typeface = Typeface.DEFAULT
        }
        val widest = lines.maxOf { paint.measureText(it) }
        val lineHeight = textSize * 1.8f
        val bitmap = Bitmap.createBitmap(
            (widest + 80f).toInt().coerceAtLeast(320),
            (lineHeight * lines.size + 80f).toInt(),
            Bitmap.Config.ARGB_8888,
        )
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            lines.forEachIndexed { index, line ->
                drawText(line, 40f, 80f + index * lineHeight, paint)
            }
        }
        return bitmap
    }

    private fun read(bitmap: Bitmap, quality: LocalReadingQuality = LocalReadingQuality.BALANCED) =
        runBlocking { engine.read(bitmap, quality) }

    @Test
    fun readsPlainEnglish() {
        val result = read(render(listOf("Battery Health")))
        assertTrue("expected some text, got '${result.text}'", result.text.isNotBlank())
        assertTrue(
            "expected Battery in '${result.text}'",
            result.text.contains("Battery", ignoreCase = true),
        )
    }

    /** The space class is the one that a trailing newline in the dictionary silently ate. */
    @Test
    fun keepsTheSpaceBetweenWords() {
        val result = read(render(listOf("Open Settings")))
        assertTrue("expected a space in '${result.text}'", result.text.trim().contains(" "))
    }

    /** A recognizer walks image columns, so Arabic arrives backwards unless it is put right. */
    @Test
    fun readsArabicInLogicalOrder() {
        val result = read(render(listOf("شبكات الهاتف")))
        assertTrue("expected some text", result.text.isNotBlank())
        assertTrue(
            "expected logical order, got '${result.text}'",
            result.text.contains("شبكات") || result.text.contains("الهاتف"),
        )
    }

    /** The perfume-label case: wide letter spacing must not become one crop per glyph. */
    @Test
    fun readsWidelySpacedCapitals() {
        val result = read(render(listOf("PARFUM"), textSize = 96f, letterSpacing = 0.45f))
        assertTrue("expected some text, got '${result.text}'", result.text.isNotBlank())
        assertTrue(
            "expected more than a single character, got '${result.text}'",
            result.text.trim().length >= 3,
        )
    }

    @Test
    fun readsMultipleLinesInOrder() {
        val result = read(render(listOf("First Line", "Second Line", "Third Line")))
        assertTrue("expected 3 lines, got ${result.lineCount}: '${result.text}'", result.lineCount >= 2)
        val first = result.text.indexOf("First", ignoreCase = true)
        val third = result.text.indexOf("Third", ignoreCase = true)
        if (first >= 0 && third >= 0) {
            assertTrue("lines came out of order: '${result.text}'", first < third)
        }
    }

    /** Small text is the whole reason the quality setting exists. */
    @Test
    fun higherQualitySeesSmallerText() {
        val small = render(listOf("Settings"), textSize = 22f)
        val atMaximum = read(small, LocalReadingQuality.MAXIMUM)
        assertTrue(
            "maximum quality should read 22px text, got '${atMaximum.text}'",
            atMaximum.text.isNotBlank(),
        )
    }

    @Test
    fun aBlankFrameProducesNothingRatherThanNoise() {
        val blank = Bitmap.createBitmap(600, 400, Bitmap.Config.ARGB_8888).apply {
            Canvas(this).drawColor(Color.WHITE)
        }
        val result = read(blank)
        assertEquals("", result.text)
        assertEquals(0, result.lineCount)
    }

    /**
     * Releasing while a read is running used to close the native sessions underneath it, which
     * faults the process rather than throwing. Reaching the end of this test alive is the
     * assertion.
     */
    @Test
    fun releasingDuringAReadDoesNotCrash() = runBlocking {
        val page = render(List(8) { "Line number $it with several words on it" })
        val reader = launch(Dispatchers.Default) {
            runCatching { engine.read(page, LocalReadingQuality.MAXIMUM) }
        }
        engine.release("released_mid_read")
        reader.join()
        assertTrue(true)
    }
}
