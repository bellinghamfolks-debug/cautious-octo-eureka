package com.abdullah.visionbridge

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.abdullah.visionbridge.data.diagnostics.EvidenceStore
import com.abdullah.visionbridge.data.diagnostics.EvidenceVideoPlan
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * The device's own encoder turns evidence frames into a playable, indexed video.
 *
 * Unit tests cover the arithmetic; only a device can say whether an encoder starts, takes the
 * input layout it is given, and writes an MP4 that a decoder can open again.
 */
@RunWith(AndroidJUnit4::class)
class EvidenceVideoOnDeviceTest {
    @Test fun framesBecomeOnePlayableIndexedVideoPerStream() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "evidence-video-${UUID.randomUUID()}").apply { mkdirs() }
        val store = EvidenceStore(File(root, "evidence"))
        val bitmap = Bitmap.createBitmap(1199, 1600, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply { color = Color.BLACK; textSize = 96f }
        try {
            store.enabled = true
            store.currentSessionId = "session-a"
            val references = (0 until 24).mapNotNull { index ->
                canvas.drawColor(Color.WHITE)
                canvas.drawText("Page line $index", 40f, 200f + index * 40f, paint)
                SystemClock.sleep(40)
                store.capture(bitmap, "F%09d".format(index), "analysis_input_selected_input")
            }
            store.finishOpenSegments()
            assertTrue("only ${references.size} of 24 frames encoded", references.size >= 20)
            val segment = File(store.directory, EvidenceVideoPlan.referencedFile(references.first()))
            assertTrue(segment.isFile)
            assertTrue("segment is ${segment.length()} bytes", segment.length() in 1..2_000_000)

            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(segment.path)
                assertEquals(1, extractor.trackCount)
                val format = extractor.getTrackFormat(0)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                assertTrue(mime, mime == MediaFormat.MIMETYPE_VIDEO_HEVC || mime == MediaFormat.MIMETYPE_VIDEO_AVC)
                assertEquals(720, format.getInteger(MediaFormat.KEY_WIDTH))
                assertEquals(960, format.getInteger(MediaFormat.KEY_HEIGHT))
                extractor.selectTrack(0)
                var samples = 0
                while (extractor.sampleTime >= 0) {
                    samples++
                    extractor.advance()
                }
                assertTrue("only $samples samples", samples >= references.size / 2)
            } finally {
                extractor.release()
            }

            val index = File(store.directory, "session-a/${EvidenceStore.INDEX_FILE}").readLines().map(::JSONObject)
            assertEquals(references, index.map { it.getString("reference") })
            assertEquals(references.size, store.frameCount())
            assertEquals(references.size, EvidenceStore(store.directory).frameCount())
            val manifest = store.manifest()
            assertTrue(manifest["evidenceFormat"].toString().endsWith("mp4_segments"))
            assertTrue((manifest["evidenceBytes"] as Long) <= EvidenceVideoPlan.EVIDENCE_BYTE_CAP)
        } finally {
            bitmap.recycle()
            store.clear()
            root.deleteRecursively()
        }
    }
}
