package com.abdullah.visionbridge.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class EvidenceVideoPlanTest {

    /** The two shapes measured on the Xiaomi 14T: analysis inputs 1199×1600, timeline 719×1600. */
    @Test fun canvasKeepsTheShapeOnTheEncoderGrid() {
        assertEquals(EvidenceVideoPlan.Canvas(720, 960), EvidenceVideoPlan.canvasFor(1199, 1600))
        assertEquals(EvidenceVideoPlan.Canvas(432, 960), EvidenceVideoPlan.canvasFor(719, 1600))
        assertEquals(EvidenceVideoPlan.Canvas(960, 432), EvidenceVideoPlan.canvasFor(2400, 1080))
        // Already small: not enlarged, only snapped.
        assertEquals(EvidenceVideoPlan.Canvas(320, 480), EvidenceVideoPlan.canvasFor(320, 480))
        val canvas = EvidenceVideoPlan.canvasFor(64, 64)
        assertTrue(canvas.width % EvidenceVideoPlan.ALIGNMENT_PX == 0 && canvas.height % EvidenceVideoPlan.ALIGNMENT_PX == 0)
    }

    @Test fun aRotatedOrDifferentlyCroppedFrameStartsANewSegment() {
        val portrait = EvidenceVideoPlan.canvasFor(1199, 1600)
        assertTrue(EvidenceVideoPlan.fits(portrait, 1199, 1600))
        assertTrue(EvidenceVideoPlan.fits(portrait, 1150, 1600))
        assertFalse(EvidenceVideoPlan.fits(portrait, 1600, 1199))
        assertFalse(EvidenceVideoPlan.fits(portrait, 719, 1600))
    }

    @Test fun placementFitsCentresAndNeverStretches() {
        val canvas = EvidenceVideoPlan.Canvas(720, 960)
        // 1199×1600 is a hair narrower than 3:4, so it keeps its own shape: 719 wide, centred.
        assertEquals(EvidenceVideoPlan.Placement(0, 0, 719, 960), EvidenceVideoPlan.placement(canvas, 1199, 1600))
        assertEquals(EvidenceVideoPlan.Placement(0, 0, 720, 960), EvidenceVideoPlan.placement(canvas, 1200, 1600))
        val wide = EvidenceVideoPlan.placement(canvas, 1600, 800)
        assertEquals(720, wide.width)
        assertEquals(360, wide.height)
        assertEquals((960 - 360) / 2, wide.top)
    }

    @Test fun theOpeningBitrateSpreadsTheBudgetOverTenMinutes() {
        val total = EvidenceVideoPlan.totalBitrate(bytesUsed = 0, elapsedMs = 0)
        // (24 MB − reserve) over 600 s ≈ 312 kbit/s; the measured 960 px / 250 kbit/s setting
        // kept the screen readable, so the inputs stream starts at about that.
        assertTrue(total in 300_000..320_000)
        assertTrue(EvidenceVideoPlan.streamBitrate(EvidenceVideoPlan.Stream.INPUTS, total) in 240_000..256_000)
        assertEquals(total, EvidenceVideoPlan.streamBitrate(EvidenceVideoPlan.Stream.INPUTS, total) +
            EvidenceVideoPlan.streamBitrate(EvidenceVideoPlan.Stream.TIMELINE, total))
    }

    /**
     * Hardware encoders miss the rate they are given. Simulate ones that spend from half to three
     * times what they are asked for, the whole time, corrected every five seconds as the store
     * does: the budget must last the full ten minutes and never be exceeded.
     */
    @Test fun aMisbehavingEncoderStillCoversTheWindowInsideTheCap() {
        for (overshoot in listOf(0.5, 1.0, 2.0, 3.0)) {
            val correction = EvidenceVideoPlan.RateCorrection()
            var used = 0L
            var covered = 0
            var requestedBits = 0.0
            var actualBits = 0.0
            for (second in 0 until 600) {
                if (!EvidenceVideoPlan.hasRoom(used)) break
                covered = second + 1
                val request = correction.request(EvidenceVideoPlan.totalBitrate(used, second * 1_000L))
                val spent = request * overshoot
                used += (spent / 8).toLong()
                requestedBits += request
                actualBits += spent
                if (second % 5 == 4) {
                    correction.observe(requestedBits, actualBits)
                    requestedBits = 0.0
                    actualBits = 0.0
                }
            }
            assertTrue("overshoot $overshoot used $used", used <= EvidenceVideoPlan.EVIDENCE_BYTE_CAP)
            assertEquals("overshoot $overshoot ran out early", 600, covered)
            // And the budget is actually used, not hoarded.
            assertTrue("overshoot $overshoot used only $used", used >= EvidenceVideoPlan.EVIDENCE_BYTE_CAP * 0.6)
        }
    }

    /** Without correction the cap still holds; the window is what suffers. */
    @Test fun theCapHoldsEvenWithoutCorrection() {
        var used = 0L
        for (second in 0 until 600) {
            if (!EvidenceVideoPlan.hasRoom(used)) break
            used += (EvidenceVideoPlan.totalBitrate(used, second * 1_000L) * 3.0 / 8).toLong()
        }
        assertTrue(used <= EvidenceVideoPlan.EVIDENCE_BYTE_CAP)
    }

    @Test fun theBudgetStopsCaptureBeforeTheCap() {
        assertTrue(EvidenceVideoPlan.hasRoom(0))
        assertFalse(EvidenceVideoPlan.hasRoom(EvidenceVideoPlan.EVIDENCE_BYTE_CAP - EvidenceVideoPlan.RESERVE_BYTES))
        assertEquals(EvidenceVideoPlan.MIN_TOTAL_BITRATE, EvidenceVideoPlan.totalBitrate(EvidenceVideoPlan.EVIDENCE_BYTE_CAP, 0))
    }

    @Test fun referencesNameTheSegmentAndTheMoment() {
        val reference = EvidenceVideoPlan.reference("s1/inputs-0003.mp4", 12_345_678L)
        assertEquals("s1/inputs-0003.mp4#t=12.345", reference)
        assertEquals("s1/inputs-0003.mp4", EvidenceVideoPlan.referencedFile(reference))
        assertEquals("s1/F1.jpg", EvidenceVideoPlan.referencedFile("s1/F1.jpg"))
        assertEquals("s/t.mp4#t=0.007", EvidenceVideoPlan.reference("s/t.mp4", 7_000L))
    }

    @Test fun streamsFollowTheReason() {
        assertEquals(EvidenceVideoPlan.Stream.TIMELINE, EvidenceVideoPlan.Stream.forReason("timeline_1s"))
        assertEquals(EvidenceVideoPlan.Stream.INPUTS, EvidenceVideoPlan.Stream.forReason("analysis_input_live"))
        assertEquals(EvidenceVideoPlan.Stream.INPUTS, EvidenceVideoPlan.Stream.forReason("ocr_empty"))
    }

    @Test fun yuvPlanesAreWrittenThroughTheirStridesPlanarAndInterleaved() {
        val white = 0xFFFFFFFF.toInt()
        val red = 0xFFFF0000.toInt()
        val pixels = intArrayOf(white, red, white, red, white, red, white, red) // 4×2
        // Planar (I420) with row padding.
        val y = ByteBuffer.allocate(8 * 2)
        val u = ByteBuffer.allocate(4 * 1)
        val v = ByteBuffer.allocate(4 * 1)
        Yuv420Writer.write(pixels, 4, 2, Yuv420Writer.Plane(y, 8, 1), Yuv420Writer.Plane(u, 4, 1), Yuv420Writer.Plane(v, 4, 1))
        assertEquals(235, y.get(0).toInt() and 0xFF)
        assertEquals(Yuv420Writer.luma(255, 0, 0), y.get(1).toInt() and 0xFF)
        assertEquals(0, y.get(4).toInt()) // padding untouched
        assertEquals(Yuv420Writer.chromaRed(255, 127, 127), v.get(0).toInt() and 0xFF)
        // Interleaved (NV12): U and V share one buffer, two bytes apart.
        val uv = ByteBuffer.allocate(4)
        Yuv420Writer.write(
            pixels, 4, 2,
            Yuv420Writer.Plane(ByteBuffer.allocate(8), 4, 1),
            Yuv420Writer.Plane(uv, 4, 2),
            Yuv420Writer.Plane(uv.duplicate().apply { position(1) }.slice(), 4, 2),
        )
        assertEquals(Yuv420Writer.chromaBlue(255, 127, 127), uv.get(0).toInt() and 0xFF)
        assertEquals(Yuv420Writer.chromaRed(255, 127, 127), uv.get(1).toInt() and 0xFF)
        assertEquals(Yuv420Writer.chromaBlue(255, 127, 127), uv.get(2).toInt() and 0xFF)
    }

    @Test fun oddSizedPicturesConvertWithoutReadingPastTheEdge() {
        val pixels = IntArray(3 * 3) { 0xFF808080.toInt() }
        val y = ByteBuffer.allocate(9)
        val u = ByteBuffer.allocate(4)
        val v = ByteBuffer.allocate(4)
        Yuv420Writer.write(pixels, 3, 3, Yuv420Writer.Plane(y, 3, 1), Yuv420Writer.Plane(u, 2, 1), Yuv420Writer.Plane(v, 2, 1))
        assertEquals(128, u.get(3).toInt() and 0xFF)
    }

    @Test fun exportKeepsTheNewestMediaInsideTheCapAndNamesTheRest() {
        val items = listOf(
            EvidenceExportSelection.Item("old/F1.jpg", 10_000_000, 1),
            EvidenceExportSelection.Item("s/inputs-0001.mp4", 9_000_000, 10),
            EvidenceExportSelection.Item("s/inputs-0002.mp4", 9_000_000, 20),
            EvidenceExportSelection.Item("s/frames.jsonl", 50_000, 20),
        )
        val result = EvidenceExportSelection.select(items, 24_000_000)
        assertEquals(listOf("s/frames.jsonl", "s/inputs-0001.mp4", "s/inputs-0002.mp4"), result.included.map { it.path })
        assertEquals(listOf("old/F1.jpg"), result.omitted.map { it.path })
        assertTrue(result.included.sumOf { it.bytes } <= 24_000_000)
    }

    @Test fun auditAcceptsFramesInsideVideosAndExplainedAbsences() {
        val files = listOf("s/inputs-0001.mp4", "s/timeline-0001.mp4")
        val references = listOf("s/inputs-0001.mp4#t=0.100", "s/inputs-0001.mp4#t=0.400", "s/timeline-0001.mp4#t=1.000")
        assertTrue(EvidenceArchiveAudit.check(files, references).valid)
        val lost = references + "s/inputs-0002.mp4#t=0.200"
        assertFalse(EvidenceArchiveAudit.check(files, lost).valid)
        val explained = EvidenceArchiveAudit.check(files, lost, accountedFor = listOf("s/inputs-0002.mp4"))
        assertTrue(explained.valid)
        assertEquals(listOf("s/inputs-0002.mp4#t=0.200"), explained.accounted)
        assertFalse(EvidenceArchiveAudit.check(files, references + references.first()).valid)
    }
}
