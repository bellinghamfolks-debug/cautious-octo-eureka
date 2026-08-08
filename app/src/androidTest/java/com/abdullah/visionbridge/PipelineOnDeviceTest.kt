package com.abdullah.visionbridge

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.abdullah.visionbridge.capture.BitmapFrames
import com.abdullah.visionbridge.capture.vision.Viewport
import com.abdullah.visionbridge.data.paddleocr.BundledOcrModels
import com.abdullah.visionbridge.data.paddleocr.PaddleOcrEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The first automated tests that run on Android rather than on the JVM.
 *
 * Every one of the 286 unit tests is pure, which is what makes them fast and what makes them
 * blind: nothing in this project had ever executed against a real `Bitmap`, a real ONNX Runtime
 * session, or a real ARM device until a user installed it. Three defects in recent releases were of
 * exactly that shape — a resource file `aapt` rejected, a Kotlin construct the JVM harness accepted,
 * a model asset that has to actually load — and none of them could have been caught by a pure test.
 *
 * These are deliberately few and deliberately fast. They assert the things a pure test cannot even
 * ask: that the packaged models load on the runtime that ships with the APK, that the image path
 * works on a real Bitmap, and that the pieces meet.
 */
@RunWith(AndroidJUnit4::class)
class PipelineOnDeviceTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** A page of dark bars on white: not text, but the right shape for the geometry to work on. */
    private fun page(width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val ink = Paint().apply { color = Color.rgb(20, 20, 24) }
        var y = height / 8
        while (y < height - height / 8) {
            canvas.drawRect(width * 0.1f, y.toFloat(), width * 0.9f, (y + height / 40f), ink)
            y += height / 16
        }
        return bitmap
    }

    /**
     * The share view as captured: the page inside a letterbox with a control column beside it.
     */
    private fun shareView(width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLACK)
        val content = page((width * 0.7f).toInt(), (height * 0.75f).toInt())
        canvas.drawBitmap(content, width * 0.03f, height * 0.12f, null)
        content.recycle()
        val control = Paint().apply { color = Color.rgb(250, 220, 40) }
        var y = height * 0.15f
        while (y < height * 0.85f) {
            canvas.drawRect(width * 0.88f, y, width * 0.96f, y + height * 0.08f, control)
            y += height * 0.16f
        }
        return bitmap
    }

    /**
     * The models are the largest thing in the APK and the only part that can fail for reasons no
     * desktop test can reproduce: a corrupt asset, a runtime that will not initialise on this ABI,
     * a model whose dictionary did not survive packaging.
     */
    @Test
    fun theBundledModelsLoadOnThisDevice() {
        if (!BundledOcrModels.allPresent(context)) return
        val engine = PaddleOcrEngine(context)
        try {
            val loaded = runBlocking { engine.ensureLoaded() }
            assertTrue("PP-OCR failed to load: ${loaded.exceptionOrNull()}", loaded.isSuccess)
            assertTrue(engine.isLoaded)
        } finally {
            runBlocking { engine.release("instrumentation_test") }
        }
    }

    /** A real read over a real Bitmap, end to end through ONNX Runtime on this ABI. */
    @Test
    fun aPageIsReadWithoutCrashing() {
        if (!BundledOcrModels.allPresent(context)) return
        val engine = PaddleOcrEngine(context)
        val bitmap = page(720, 1280)
        try {
            assertTrue(runBlocking { engine.ensureLoaded() }.isSuccess)
            val result = runBlocking { engine.read(bitmap) }
            // What it reads is not the point — these are bars, not letters. That the pipeline
            // completes on device, with the packaged models, is.
            assertNotNull(result)
            assertTrue(result.confidence in 0f..1f)
        } finally {
            bitmap.recycle()
            runBlocking { engine.release("instrumentation_test") }
        }
    }

    /**
     * The geometry that decides what the whole pipeline looks at, exercised through a real Bitmap
     * rather than a synthetic float array.
     */
    @Test
    fun theViewportIsFoundInARealShareViewBitmap() {
        val bitmap = shareView(1356, 610)
        try {
            val rect = Viewport.detect(BitmapFrames.aspectPlane(bitmap))
            assertNotNull("no viewport found in a letterboxed capture", rect)
            assertTrue("the control column must be excluded: ${rect!!.right}", rect.right <= 0.86f)
            assertTrue("the page must survive: ${rect.area}", rect.area >= 0.35f)
        } finally {
            bitmap.recycle()
        }
    }

    /** An ordinary screenshot is not letterboxed, so it must be left exactly as it is. */
    @Test
    fun aFullScreenBitmapIsNotCropped() {
        val bitmap = page(720, 1600)
        try {
            assertEquals(null, Viewport.detect(BitmapFrames.aspectPlane(bitmap)))
        } finally {
            bitmap.recycle()
        }
    }
}
