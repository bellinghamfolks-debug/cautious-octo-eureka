package com.abdullah.visionbridge.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * The tracking rules, exercised against constructed scenes.
 *
 * The thresholds are the ones the service uses for text reading, so a case that passes here is a
 * statement about the shipped behaviour and not about a convenient set of numbers.
 */
class VisualTargetTrackerTest {

    private fun tracker() = VisualTargetTracker(
        minimumResidualMean = 19.0,
        minimumResidualRatio = 0.32,
    )

    // region scenes

    /**
     * A page of dark text on light paper, at a given offset.
     *
     * Words and the gaps between them matter: a solid bar would be unchanged by a sideways shift,
     * and would make this suite easier than the thing it is testing.
     */
    private fun page(offsetX: Int = 0, offsetY: Int = 0, seed: Int = 1): FrameSignature {
        val size = FrameSignature.GRID
        val values = IntArray(size * size) { 232 }
        val random = Random(seed)
        // Nine text lines, three cells apart, each broken into words with spaces between them.
        for (line in 0 until 9) {
            val row = 2 + line * 3
            var column = 2
            while (column < size - 2) {
                val word = 2 + random.nextInt(4)
                repeat(word) {
                    if (column < size - 2) {
                        // Drawn before the bounds check so a clipped cell does not desynchronise
                        // the ink of every cell after it, which would make two offsets of the same
                        // page different documents rather than the same one moved.
                        val ink = 24 + random.nextInt(30)
                        val x = column + offsetX
                        val y = row + offsetY
                        if (x in 0 until size && y in 0 until size) values[y * size + x] = ink
                        column++
                    }
                }
                column += 1 + random.nextInt(2)
            }
        }
        return FrameSignature(size, size, values)
    }

    /** A perfume bottle: a bright label band on a dark background. */
    private fun bottle(offsetX: Int = 0, offsetY: Int = 0): FrameSignature {
        val size = FrameSignature.GRID
        val values = IntArray(size * size) { 30 }
        for (y in 10 until 20) {
            for (x in 8 until 24) {
                val px = x + offsetX
                val py = y + offsetY
                if (px in 0 until size && py in 0 until size) {
                    values[py * size + px] = if (y in 13..16 && x in 10..21) 40 else 235
                }
            }
        }
        return FrameSignature(size, size, values)
    }

    /** Sensor noise, which every real frame carries. */
    private fun withNoise(signature: FrameSignature, amplitude: Int, seed: Int): FrameSignature {
        val random = Random(seed)
        val values = IntArray(signature.luminance.size) { index ->
            (signature.luminance[index] + random.nextInt(-amplitude, amplitude + 1)).coerceIn(0, 255)
        }
        return FrameSignature(signature.width, signature.height, values)
    }

    // endregion

    /**
     * The defect, stated as a measurement. Holding a page one cell to the left is not a new page,
     * but comparing the two index by index says most of the frame changed — which is exactly what
     * the old detector did, and why one motionless bottle produced 439 target changes in 467
     * seconds.
     */
    @Test
    fun `a page that moves is not a page that changed`() {
        val tracker = tracker()
        tracker.evaluate(page())

        val moved = tracker.evaluate(page(offsetX = 1, offsetY = 1))
        assertFalse(
            "A one-cell drift must not be a new target (aligned residual " +
                "${moved.alignedMeanAbsoluteDifference}, unaligned " +
                "${moved.unalignedMeanAbsoluteDifference})",
            moved.targetChanged,
        )
        assertEquals("tracked_through_motion", moved.reason)
        assertEquals("The estimate must name the direction the content moved", 1, moved.motionXCells)
        assertEquals(1, moved.motionYCells)

        // And the difference the old detector would have measured really was over the threshold,
        // so this test is describing the real failure and not an easy one.
        assertTrue(
            "Expected the unaligned comparison to exceed the old threshold",
            moved.unalignedMeanAbsoluteDifference!! >= 19.0 ||
                moved.unalignedChangedCellRatio!! >= 0.32,
        )
        assertTrue(
            "Alignment must remove most of that difference",
            moved.alignedMeanAbsoluteDifference!! < moved.unalignedMeanAbsoluteDifference!! / 2,
        )
    }

    @Test
    fun `a target is tracked through drift in every direction`() {
        for (dx in -4..4) {
            for (dy in -4..4) {
                val tracker = tracker()
                tracker.evaluate(page())
                val moved = tracker.evaluate(page(offsetX = dx, offsetY = dy))
                assertFalse(
                    "Drift of ($dx, $dy) was reported as a new target",
                    moved.targetChanged,
                )
            }
        }
    }

    @Test
    fun `a hand that keeps drifting never accumulates into a false change`() {
        val tracker = tracker()
        tracker.evaluate(page())
        var changes = 0
        // Thirty frames of a hand wandering back and forth, as a person holding a label does.
        for (frame in 1..30) {
            val dx = ((frame % 7) - 3)
            val dy = ((frame % 5) - 2)
            if (tracker.evaluate(withNoise(page(dx, dy), amplitude = 4, seed = frame)).targetChanged) {
                changes++
            }
        }
        assertEquals("A steady hand must produce no new targets at all", 0, changes)
    }

    @Test
    fun `sensor noise alone is never a new target`() {
        val tracker = tracker()
        tracker.evaluate(page())
        repeat(20) { frame ->
            val decision = tracker.evaluate(withNoise(page(), amplitude = 8, seed = 100 + frame))
            assertFalse("Noise frame $frame was reported as a new target", decision.targetChanged)
        }
    }

    /** The behaviour that must survive: a genuinely different subject is picked up. */
    @Test
    fun `a bottle replaced by a page is a new target`() {
        val tracker = tracker()
        val start = tracker.evaluate(bottle())

        val first = tracker.evaluate(page())
        assertFalse("One frame is not enough to abandon a reading", first.targetChanged)
        assertEquals("awaiting_target_consensus", first.reason)

        val confirmed = tracker.evaluate(page())
        assertTrue("Two agreeing frames confirm a new target", confirmed.targetChanged)
        assertEquals("new_target_confirmed", confirmed.reason)
        assertTrue("The track identity must advance", confirmed.trackId > start.trackId)
    }

    /**
     * A hand passing over the page, or one badly exposed frame, is a single frame of nonsense. It
     * used to throw away the reading in progress; now it is outvoted.
     */
    @Test
    fun `a single occluded frame does not abandon the reading`() {
        val tracker = tracker()
        val start = tracker.evaluate(page())

        val occluded = FrameSignature(
            FrameSignature.GRID,
            FrameSignature.GRID,
            IntArray(FrameSignature.GRID * FrameSignature.GRID) { 12 },
        )
        assertFalse(tracker.evaluate(occluded).targetChanged)

        val back = tracker.evaluate(page())
        assertFalse("The page returning is not a new target", back.targetChanged)
        assertEquals("The track identity must survive the occlusion", start.trackId, back.trackId)
    }

    @Test
    fun `two different frames in a row do not confirm each other`() {
        val tracker = tracker()
        tracker.evaluate(page())

        // A hand, then a wall: both differ from the page and from each other, so neither is a
        // target the user chose to look at.
        val hand = FrameSignature(
            FrameSignature.GRID,
            FrameSignature.GRID,
            IntArray(FrameSignature.GRID * FrameSignature.GRID) { 12 },
        )
        val wall = FrameSignature(
            FrameSignature.GRID,
            FrameSignature.GRID,
            IntArray(FrameSignature.GRID * FrameSignature.GRID) { 240 },
        )
        assertFalse(tracker.evaluate(hand).targetChanged)
        assertFalse(tracker.evaluate(wall).targetChanged)
    }

    @Test
    fun `a new target settles into a stable track`() {
        val tracker = tracker()
        tracker.evaluate(bottle())
        tracker.evaluate(page())
        val confirmed = tracker.evaluate(page())
        assertTrue(confirmed.targetChanged)

        // Once confirmed, the new page is tracked like any other: drift and noise change nothing.
        var changes = 0
        for (frame in 1..15) {
            val moved = page(offsetX = (frame % 5) - 2, offsetY = (frame % 3) - 1)
            if (tracker.evaluate(withNoise(moved, amplitude = 5, seed = frame)).targetChanged) {
                changes++
            }
        }
        assertEquals(0, changes)
        assertEquals(confirmed.trackId, tracker.currentTrackId())
    }

    @Test
    fun `the first frame opens a track`() {
        val tracker = tracker()
        val first = tracker.evaluate(page())
        assertTrue(first.targetChanged)
        assertEquals("initial_frame", first.reason)
        assertEquals(1L, first.trackId)
    }

    @Test
    fun `a reset starts a new track`() {
        val tracker = tracker()
        tracker.evaluate(page())
        val before = tracker.currentTrackId()
        tracker.reset()
        val after = tracker.evaluate(page())
        assertTrue(after.targetChanged)
        assertTrue(after.trackId > before)
    }

    /**
     * Motion beyond what the search can follow is a sweep, not a read, and must be treated as a
     * change so a stale reading is not spoken over something the user has left behind.
     */
    @Test
    fun `a sweep past the search range is a new target`() {
        val tracker = tracker()
        tracker.evaluate(page())
        tracker.evaluate(page(offsetX = 20))
        assertTrue(tracker.evaluate(page(offsetX = 20)).targetChanged)
    }

    @Test
    fun `overlap is measured only where the frames actually overlap`() {
        val reference = page()
        val shifted = page(offsetX = 3)
        val overlap = reference.compareShifted(shifted, 3, 0)
        assertEquals(FrameSignature.GRID * (FrameSignature.GRID - 3), overlap.cells)
        assertTrue(
            "Aligned overlap should be near identical, was ${overlap.meanAbsoluteDifference}",
            overlap.meanAbsoluteDifference < 1.0,
        )
    }

    @Test
    fun `a shift with no overlap reports nothing rather than dividing by zero`() {
        val overlap = page().compareShifted(page(), FrameSignature.GRID, 0)
        assertEquals(0, overlap.cells)
        assertEquals(0.0, overlap.meanAbsoluteDifference, 0.0)
    }

    /**
     * The measurement that justifies the whole redesign, kept as a test so it cannot silently
     * regress: on a one-cell drift the old index-aligned comparison sees a different page and the
     * aligned one sees the same page.
     */
    @Test
    fun `alignment is what separates motion from replacement`() {
        val still = page()
        val drifted = page(offsetX = 1, offsetY = 1)
        val replaced = bottle()

        val driftUnaligned = still.compareShifted(drifted, 0, 0)
        val driftAligned = still.compareShifted(drifted, 1, 1)
        val replacedAligned = still.compareShifted(replaced, 0, 0)

        assertTrue(
            "Unaligned drift must look like a change: ${driftUnaligned.meanAbsoluteDifference}",
            driftUnaligned.meanAbsoluteDifference >= 19.0 ||
                driftUnaligned.changedCellRatio >= 0.32,
        )
        assertTrue(
            "Aligned drift must look like the same page: ${driftAligned.meanAbsoluteDifference}",
            driftAligned.meanAbsoluteDifference < 19.0 && driftAligned.changedCellRatio < 0.32,
        )
        assertTrue(
            "A replaced subject must stay over the threshold even at its best alignment",
            replacedAligned.meanAbsoluteDifference >= 19.0 ||
                replacedAligned.changedCellRatio >= 0.32,
        )
        assertTrue(abs(driftAligned.meanAbsoluteDifference) < 1.0)
    }
}
