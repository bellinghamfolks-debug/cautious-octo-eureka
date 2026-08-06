package com.abdullah.visionbridge.data.paddleocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The account of what the detector found and this stage threw away.
 *
 * This exists because of one unanswerable bundle: a large, clear English word was not read, and the
 * timeline recorded only that no box covered it. "Never detected" and "detected then discarded"
 * need opposite repairs, and four silent `continue` statements were deciding between them. These
 * cases check that each of those four is now counted, and — the part that actually settles the
 * question — that the largest discarded region is described well enough to recognise.
 */
class DetectionCensusTest {

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

    private fun extract(
        probability: FloatArray,
        width: Int,
        height: Int,
    ) = DbPostProcessor.extract(
        probability = probability,
        mapWidth = width,
        mapHeight = height,
        scaleX = 1f,
        scaleY = 1f,
        sourceWidth = width,
        sourceHeight = height,
    )

    @Test
    fun `an accepted region is counted as found and accepted`() {
        val census = extract(
            mapWith(40, 20, listOf(intArrayOf(2, 2, 20, 10))),
            40,
            20,
        ).census
        assertEquals(1, census.regionsFound)
        assertEquals(1, census.accepted)
        assertEquals(0, census.rejectedLowScore)
        assertNull("nothing was rejected, so nothing is described", census.largestRejected)
    }

    /**
     * The case the whole census was built for: a big region, above the binary threshold, discarded
     * for its mean probability. Before this it left no trace at all.
     */
    @Test
    fun `a large faint region is counted and described rather than vanishing`() {
        val detection = extract(
            mapWith(100, 60, listOf(intArrayOf(10, 10, 90, 40)), value = 0.35f),
            100,
            60,
        )
        assertTrue(detection.boxes.isEmpty())
        val census = detection.census
        assertEquals(1, census.regionsFound)
        assertEquals(0, census.accepted)
        assertEquals(1, census.rejectedLowScore)

        val rejected = census.largestRejected
        assertNotNull(rejected)
        assertEquals("mean_probability_below_threshold", rejected!!.reason)
        assertEquals(0.8f, rejected.widthFraction, 0.02f)
        assertEquals(0.5f, rejected.heightFraction, 0.02f)
        assertEquals(0.5f, rejected.centreXFraction, 0.02f)
        assertEquals(0.35f, rejected.score, 0.01f)
    }

    @Test
    fun `a speck below the minimum side is counted separately`() {
        val census = extract(mapWith(40, 20, listOf(intArrayOf(5, 5, 7, 7))), 40, 20).census
        assertEquals(1, census.rejectedTooSmall)
        assertEquals("below_minimum_side", census.largestRejected?.reason)
    }

    /** A scrollbar. Counted under its own reason so it is never mistaken for lost text. */
    @Test
    fun `a tall thin bar is counted under its own reason`() {
        val census = extract(mapWith(60, 100, listOf(intArrayOf(10, 5, 14, 95))), 60, 100).census
        assertEquals(1, census.rejectedTooTall)
        assertEquals("taller_than_wide_limit", census.largestRejected?.reason)
    }

    /** Among several discards it is the biggest that gets described, not the first or the last. */
    @Test
    fun `the largest discard wins the description`() {
        val census = extract(
            mapWith(
                200,
                100,
                listOf(
                    intArrayOf(2, 2, 6, 6),
                    intArrayOf(20, 20, 180, 80),
                    intArrayOf(2, 90, 8, 96),
                ),
                value = 0.35f,
            ),
            200,
            100,
        ).census
        assertEquals(3, census.regionsFound)
        val rejected = census.largestRejected
        assertNotNull(rejected)
        assertEquals(0.8f, rejected!!.widthFraction, 0.02f)
        assertEquals(0.6f, rejected.heightFraction, 0.02f)
    }

    /**
     * The heights are what the resolution controller solves on, so a bundle that shows every
     * accepted line at nine pixels tall explains a poor read without anyone opening an image.
     */
    @Test
    fun `accepted heights are reported as a spread`() {
        val census = extract(
            mapWith(
                200,
                120,
                listOf(
                    intArrayOf(10, 10, 190, 16),
                    intArrayOf(10, 30, 190, 40),
                    intArrayOf(10, 60, 190, 90),
                ),
            ),
            200,
            120,
        ).census
        assertEquals(3, census.accepted)
        assertTrue(census.acceptedHeightP10 <= census.acceptedHeightMedian)
        assertTrue(census.acceptedHeightMedian <= census.acceptedHeightP90)
        // The middle band is ten map rows, grown by the unclip ratio on both sides.
        assertTrue(
            "median ${census.acceptedHeightMedian} should reflect the middle band",
            census.acceptedHeightMedian in 10..22,
        )
    }

    @Test
    fun `an empty map reports an empty census rather than throwing`() {
        val census = extract(FloatArray(40 * 20), 40, 20).census
        assertEquals(0, census.regionsFound)
        assertEquals(0, census.accepted)
        assertNull(census.largestRejected)
    }

    /** Every field is carried into the event, or the census is a measurement nobody ever sees. */
    @Test
    fun `the census flattens into diagnostic fields`() {
        val census = extract(
            mapWith(100, 60, listOf(intArrayOf(10, 10, 90, 40)), value = 0.35f),
            100,
            60,
        ).census
        val fields = census.fields()
        assertEquals(1, fields["regionsFound"])
        assertEquals(1, fields["rejectedLowScore"])
        assertEquals("mean_probability_below_threshold", fields["largestRejectedReason"])
        assertNotNull(fields["largestRejectedCentreY"])
        assertEquals(DbPostProcessor.BOX_SCORE_THRESHOLD, fields["boxScoreThreshold"])
    }

    /** The old entry point keeps its shape, because callers that only want boxes still exist. */
    @Test
    fun `extractBoxes still returns just the boxes`() {
        val boxes = DbPostProcessor.extractBoxes(
            mapWith(40, 20, listOf(intArrayOf(2, 2, 20, 10))),
            40,
            20,
            1f,
            1f,
            40,
            20,
        )
        assertEquals(1, boxes.size)
    }
}
