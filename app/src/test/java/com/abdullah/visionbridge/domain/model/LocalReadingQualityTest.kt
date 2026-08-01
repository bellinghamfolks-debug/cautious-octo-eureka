package com.abdullah.visionbridge.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalReadingQualityTest {

    /** The phone this was measured on captures 1220x2712. */
    private val captureWidth = 1220
    private val captureHeight = 2712

    private fun detectorSees(quality: LocalReadingQuality): Pair<Int, Int> {
        val ratio = quality.detectionLongEdge.toFloat() / maxOf(captureWidth, captureHeight)
        return Math.round(captureWidth * ratio) to Math.round(captureHeight * ratio)
    }

    /** Height in the detector of text that is [onScreen] pixels tall on the phone. */
    private fun detectedHeight(quality: LocalReadingQuality, onScreen: Int): Float =
        onScreen * (quality.detectionLongEdge.toFloat() / maxOf(captureWidth, captureHeight))

    @Test
    fun `higher quality always gives the detector more to look at`() {
        val edges = LocalReadingQuality.entries.map { it.detectionLongEdge }
        assertEquals(edges.sorted(), edges)
        val widths = LocalReadingQuality.entries.map { it.recognitionMaxWidth }
        assertEquals(widths.sorted(), widths)
    }

    /**
     * The point of the setting. 18-pixel text arrives 6 pixels tall at FAST, which no recognizer
     * can read, and 12.7 pixels at MAXIMUM, which is a different proposition entirely.
     */
    @Test
    fun `small text survives to the detector only at higher quality`() {
        assertTrue(detectedHeight(LocalReadingQuality.FAST, 18) < 7f)
        assertTrue(detectedHeight(LocalReadingQuality.MAXIMUM, 18) > 12f)
    }

    @Test
    fun `fast stays exactly as it was so choosing it changes nothing`() {
        assertEquals(960, LocalReadingQuality.FAST.detectionLongEdge)
        assertEquals(640, LocalReadingQuality.FAST.recognitionMaxWidth)
        assertEquals(432 to 960, detectorSees(LocalReadingQuality.FAST))
    }

    /**
     * A full-width line was being squeezed horizontally to fit a 640-pixel cap, which distorts
     * connected Arabic letters. At the higher caps a realistic line is no longer squashed at all.
     */
    @Test
    fun `a wide line stops being squashed as quality rises`() {
        fun squash(quality: LocalReadingQuality, boxWidth: Int, boxHeight: Int): Float {
            val natural = (boxWidth.toFloat() / boxHeight) * 48f
            return maxOf(1f, natural / quality.recognitionMaxWidth)
        }
        assertTrue(squash(LocalReadingQuality.FAST, 1150, 30) > 2.8f)
        assertEquals(1f, squash(LocalReadingQuality.MAXIMUM, 1150, 30), 0.001f)
        assertEquals(1f, squash(LocalReadingQuality.BALANCED, 900, 40), 0.001f)
    }

    @Test
    fun `an unknown stored value falls back to balanced rather than to the slowest`() {
        assertEquals(LocalReadingQuality.BALANCED, LocalReadingQuality.fromStored(null))
        assertEquals(LocalReadingQuality.BALANCED, LocalReadingQuality.fromStored("NONSENSE"))
        assertEquals(LocalReadingQuality.MAXIMUM, LocalReadingQuality.fromStored("MAXIMUM"))
    }
}
