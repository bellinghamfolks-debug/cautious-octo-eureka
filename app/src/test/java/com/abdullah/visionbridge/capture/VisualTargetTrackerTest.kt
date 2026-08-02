package com.abdullah.visionbridge.capture

import com.abdullah.visionbridge.capture.vision.VisionScenes
import com.abdullah.visionbridge.capture.vision.Warp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tracking decisions, at the thresholds the service ships.
 *
 * The case that matters most is the one the device bundle recorded: a user holding a single perfume
 * bottle for 467 seconds, and 439 declarations that the subject had changed.
 */
class VisualTargetTrackerTest {

    private fun tracker() = VisualTargetTracker(
        maximumDissimilarity = 0.26,
        maximumChromaDifference = 26.0,
    )

    private val centre = VisionScenes.SIZE / 2.0

    private fun page(warp: Warp) = VisionScenes.frame(VisionScenes.transform(VisionScenes.page(), warp))

    // region motion is not replacement

    @Test
    fun `a page that drifts is not a page that changed`() {
        val tracker = tracker()
        tracker.evaluate(VisionScenes.frame(VisionScenes.page()))

        val moved = tracker.evaluate(page(Warp.translation(4.0, 3.0)))
        assertFalse(
            "drift reported as a new target: aligned ${moved.dissimilarity}, " +
                "unaligned ${moved.unalignedDissimilarity}",
            moved.targetChanged,
        )
        assertEquals("tracked_through_motion", moved.reason)
        // The measurement without motion compensation is the one the old build decided on, and it
        // must be visibly worse — otherwise this test is not describing the real failure.
        assertTrue(
            "alignment must explain most of the difference",
            moved.dissimilarity!! < moved.unalignedDissimilarity!! / 2,
        )
    }

    @Test
    fun `a page that rotates is tracked through the rotation`() {
        for (degrees in listOf(4.0, 8.0, 12.0, -10.0)) {
            val tracker = tracker()
            tracker.evaluate(VisionScenes.frame(VisionScenes.page()))
            val decision = tracker.evaluate(page(Warp.similarity(centre, centre, degrees, 1.0, 0.0, 0.0)))
            assertFalse(
                "$degrees degrees reported as a new target (${decision.dissimilarity})",
                decision.targetChanged,
            )
        }
    }

    @Test
    fun `an object brought closer or moved away is tracked through the zoom`() {
        for (scale in listOf(0.88, 0.94, 1.08, 1.18)) {
            val tracker = tracker()
            tracker.evaluate(VisionScenes.frame(VisionScenes.page()))
            val decision = tracker.evaluate(page(Warp.similarity(centre, centre, 0.0, scale, 0.0, 0.0)))
            assertFalse(
                "scale $scale reported as a new target (${decision.dissimilarity})",
                decision.targetChanged,
            )
        }
    }

    @Test
    fun `rotation, zoom and drift at once are still one target`() {
        val tracker = tracker()
        tracker.evaluate(VisionScenes.frame(VisionScenes.page()))
        val decision = tracker.evaluate(page(Warp.similarity(centre, centre, 9.0, 1.1, 5.0, -4.0)))
        assertFalse("combined motion reported as a new target", decision.targetChanged)
        assertTrue(decision.rotationDegrees > 4.0)
        assertTrue(decision.scale > 1.03)
    }

    /** The device case: a hand holding a label, wandering, for thirty frames. */
    @Test
    fun `a steady hand produces no new targets at all`() {
        val tracker = tracker()
        tracker.evaluate(VisionScenes.frame(VisionScenes.page()))
        var changes = 0
        for (frame in 1..30) {
            val warp = Warp.similarity(
                centre,
                centre,
                ((frame % 7) - 3) * 1.2,
                1.0 + ((frame % 5) - 2) * 0.015,
                ((frame % 9) - 4).toDouble(),
                ((frame % 6) - 3).toDouble(),
            )
            val plane = VisionScenes.withNoise(
                VisionScenes.transform(VisionScenes.page(), warp),
                amplitude = 4f,
                seed = frame,
            )
            if (tracker.evaluate(VisionScenes.frame(plane)).targetChanged) changes++
        }
        assertEquals("a hand holding one label must be one target", 0, changes)
    }

    @Test
    fun `sensor noise alone is never a new target`() {
        val tracker = tracker()
        tracker.evaluate(VisionScenes.frame(VisionScenes.page()))
        repeat(20) { frame ->
            val noisy = VisionScenes.withNoise(VisionScenes.page(), amplitude = 9f, seed = 100 + frame)
            assertFalse("noise frame $frame", tracker.evaluate(VisionScenes.frame(noisy)).targetChanged)
        }
    }

    @Test
    fun `a change of lighting is not a change of subject`() {
        val tracker = tracker()
        tracker.evaluate(VisionScenes.frame(VisionScenes.page()))
        val relit = VisionScenes.relit(VisionScenes.page(), gain = 0.7f, offset = 60f)
        assertFalse(tracker.evaluate(VisionScenes.frame(relit)).targetChanged)
    }

    // endregion

    // region replacement is still detected

    @Test
    fun `a bottle replaced by a page is a new target`() {
        val tracker = tracker()
        val start = tracker.evaluate(VisionScenes.frame(VisionScenes.bottle()))

        val first = tracker.evaluate(VisionScenes.frame(VisionScenes.page()))
        assertFalse("one frame must not end a reading", first.targetChanged)
        assertEquals("awaiting_target_consensus", first.reason)

        val confirmed = tracker.evaluate(VisionScenes.frame(VisionScenes.page()))
        assertTrue("two agreeing frames must confirm", confirmed.targetChanged)
        assertEquals("new_target_confirmed", confirmed.reason)
        assertTrue(confirmed.trackId > start.trackId)
    }

    @Test
    fun `a page replaced by a different page is a new target`() {
        val tracker = tracker()
        tracker.evaluate(VisionScenes.frame(VisionScenes.page(seed = 7)))
        tracker.evaluate(VisionScenes.frame(VisionScenes.page(seed = 41)))
        assertTrue(tracker.evaluate(VisionScenes.frame(VisionScenes.page(seed = 41))).targetChanged)
    }

    /** The freedom the richer model adds must not make it blind to a real change. */
    @Test
    fun `many different scenes are each recognised as a change`() {
        var detected = 0
        val seeds = listOf(11, 23, 37, 53, 71, 89, 101, 127)
        for (seed in seeds) {
            val tracker = tracker()
            tracker.evaluate(VisionScenes.frame(VisionScenes.page(seed = 5)))
            tracker.evaluate(VisionScenes.frame(VisionScenes.page(seed = seed)))
            if (tracker.evaluate(VisionScenes.frame(VisionScenes.page(seed = seed))).targetChanged) {
                detected++
            }
        }
        assertEquals("every different page must be detected", seeds.size, detected)
    }

    // endregion

    // region consensus

    @Test
    fun `a single occluded frame does not abandon the reading`() {
        val tracker = tracker()
        val start = tracker.evaluate(VisionScenes.frame(VisionScenes.page()))

        assertFalse(tracker.evaluate(VisionScenes.frame(VisionScenes.flat(14))).targetChanged)

        val back = tracker.evaluate(VisionScenes.frame(VisionScenes.page()))
        assertFalse("the page returning is not a new target", back.targetChanged)
        assertEquals("the track must survive the occlusion", start.trackId, back.trackId)
    }

    @Test
    fun `two frames that differ from each other do not confirm each other`() {
        val tracker = tracker()
        tracker.evaluate(VisionScenes.frame(VisionScenes.page()))
        assertFalse(tracker.evaluate(VisionScenes.frame(VisionScenes.flat(14))).targetChanged)
        assertFalse(tracker.evaluate(VisionScenes.frame(VisionScenes.flat(238))).targetChanged)
    }

    @Test
    fun `a confirmed target settles into a stable track`() {
        val tracker = tracker()
        tracker.evaluate(VisionScenes.frame(VisionScenes.bottle()))
        tracker.evaluate(VisionScenes.frame(VisionScenes.page()))
        val confirmed = tracker.evaluate(VisionScenes.frame(VisionScenes.page()))
        assertTrue(confirmed.targetChanged)

        var changes = 0
        for (frame in 1..12) {
            val warp = Warp.translation(((frame % 5) - 2).toDouble(), ((frame % 3) - 1).toDouble())
            if (tracker.evaluate(page(warp)).targetChanged) changes++
        }
        assertEquals(0, changes)
        assertEquals(confirmed.trackId, tracker.currentTrackId())
    }

    // endregion

    @Test
    fun `the first frame opens a track`() {
        val first = tracker().evaluate(VisionScenes.frame(VisionScenes.page()))
        assertTrue(first.targetChanged)
        assertEquals("initial_frame", first.reason)
        assertEquals(1L, first.trackId)
    }

    @Test
    fun `a reset starts a new track`() {
        val tracker = tracker()
        tracker.evaluate(VisionScenes.frame(VisionScenes.page()))
        val before = tracker.currentTrackId()
        tracker.reset()
        val after = tracker.evaluate(VisionScenes.frame(VisionScenes.page()))
        assertTrue(after.targetChanged)
        assertTrue(after.trackId > before)
    }

    /** Tracking must cost a small fraction of the recognition pass it protects. */
    @Test
    fun `tracking a frame is fast enough to run on every frame`() {
        val tracker = tracker()
        tracker.evaluate(VisionScenes.frame(VisionScenes.page()))
        // Warm the JIT before measuring.
        repeat(5) { tracker.evaluate(page(Warp.translation(1.0, 1.0))) }

        val started = System.nanoTime()
        repeat(20) { frame ->
            tracker.evaluate(page(Warp.translation((frame % 4).toDouble(), (frame % 3).toDouble())))
        }
        val perFrameMs = (System.nanoTime() - started) / 20.0 / 1_000_000.0
        assertTrue(
            "tracking took %.1f ms per frame".format(perFrameMs),
            perFrameMs < 120.0,
        )
    }
}
