package com.abdullah.visionbridge.data.network

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sizing the upload to the link the request is bound to.
 *
 * Taken from a field session in which eight of twelve cloud requests died at 20.3 seconds — the
 * read timeout, to a tenth of a second, every time. The bound cellular network reported
 * `linkDownstreamBandwidthKbps = 14` while the Wi-Fi beside it reported 30,000. Nothing was broken;
 * the picture was simply far too large for the pipe it was being pushed through, and no timeout
 * value would have changed that.
 */
class UploadBudgetTest {

    @Test
    fun `a fast link imposes no budget of its own`() {
        assertNull(UploadBudget.bytesFor(30_000, 24_000))
        assertNull(UploadBudget.bytesFor(UploadBudget.COMFORTABLE_KBPS, 24_000))
    }

    @Test
    fun `an unknown link imposes no budget`() {
        assertNull(UploadBudget.bytesFor(null, 24_000))
        assertNull(UploadBudget.bytesFor(0, 24_000))
    }

    /** The measurement that started this: 14 kbps must not be handed a hundred-kilobyte image. */
    @Test
    fun `the fourteen kilobit link gets a tiny budget`() {
        val budget = UploadBudget.bytesFor(14, 24_000)
        assertNotNull(budget)
        assertTrue("expected well under 40 KB, got $budget", budget!! < 40_000)
    }

    @Test
    fun `a slower link gets a smaller budget`() {
        val slow = UploadBudget.bytesFor(200, 24_000)!!
        val slower = UploadBudget.bytesFor(80, 24_000)!!
        assertTrue("$slower should be below $slow", slower < slow)
    }

    @Test
    fun `a longer deadline allows more bytes`() {
        val short = UploadBudget.bytesFor(300, 12_000)!!
        val long = UploadBudget.bytesFor(300, 24_000)!!
        assertTrue(long > short)
    }

    /**
     * A budget must never collapse to something that cannot carry a picture at all. Below the floor
     * the honest answer is [UploadBudget.isUnusable], not a one-kilobyte thumbnail.
     */
    @Test
    fun `the budget never goes below a usable floor`() {
        assertTrue(UploadBudget.bytesFor(45, 24_000)!! >= 12_000)
    }

    @Test
    fun `a hopeless link is named as hopeless`() {
        assertTrue(UploadBudget.isUnusable(14))
        assertTrue(UploadBudget.isUnusable(39))
    }

    @Test
    fun `a usable or unknown link is not named as hopeless`() {
        assertTrue(!UploadBudget.isUnusable(UploadBudget.UNUSABLE_KBPS))
        assertTrue(!UploadBudget.isUnusable(8_105))
        assertTrue(!UploadBudget.isUnusable(null))
        assertTrue(!UploadBudget.isUnusable(0))
    }

    @Test
    fun `a smaller budget asks for a smaller image`() {
        val small = UploadBudget.longEdgeFor(15_000)
        val large = UploadBudget.longEdgeFor(200_000)
        assertTrue("$small should be below $large", small < large)
        assertTrue("a long edge must stay usable, got $small", small >= 320)
        assertTrue("and must never exceed the caps, got $large", large <= 1_600)
    }

    @Test
    fun `the long edge is monotone in the budget`() {
        var previous = 0
        for (bytes in listOf(12_000, 20_000, 40_000, 80_000, 160_000, 400_000)) {
            val edge = UploadBudget.longEdgeFor(bytes)
            assertTrue("$bytes gave $edge after $previous", edge >= previous)
            previous = edge
        }
    }
}
