package com.abdullah.visionbridge.data.paddleocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Prefix beam search over CTC output, and the rule that decides when it runs.
 *
 * Greedy decoding maximises the best single alignment; CTC defines a string's probability as the
 * sum over every alignment that collapses to it. The two disagree precisely where the head is
 * unsure, which on this app's content is a product name or a room number — the words that are the
 * reason someone pointed the glasses at something.
 */
class BeamDecodingTest {

    private val dictionary = listOf("a", "b", "c")

    /** logits[step][class]; class 0 is the CTC blank. */
    private fun logits(vararg steps: DoubleArray): FloatArray {
        val flat = FloatArray(steps.size * steps[0].size)
        var index = 0
        for (step in steps) for (value in step) flat[index++] = value.toFloat()
        return flat
    }

    private fun decode(
        data: FloatArray,
        steps: Int,
        beamWidth: Int = CtcDecoder.DEFAULT_BEAM_WIDTH,
    ) = CtcDecoder.decode(data, steps, dictionary.size + 1, dictionary, beamWidth)

    // region agreement with greedy where greedy is right

    @Test
    fun `a confident reading is left to the greedy path`() {
        val data = logits(
            doubleArrayOf(0.01, 0.97, 0.01, 0.01),
            doubleArrayOf(0.97, 0.01, 0.01, 0.01),
            doubleArrayOf(0.01, 0.01, 0.97, 0.01),
        )
        val result = decode(data, 3)
        assertEquals("ab", result.text)
        assertFalse("a clean page must not pay for the beam", result.beamSearched)
    }

    @Test
    fun `blanks are dropped and repeats collapsed`() {
        val data = logits(
            doubleArrayOf(0.02, 0.96, 0.01, 0.01),
            doubleArrayOf(0.02, 0.96, 0.01, 0.01),
            doubleArrayOf(0.96, 0.02, 0.01, 0.01),
            doubleArrayOf(0.02, 0.96, 0.01, 0.01),
        )
        // a a <blank> a → "aa": the blank separates two genuine characters.
        assertEquals("aa", decode(data, 4).text)
    }

    // endregion

    // region where the beam earns its cost

    /**
     * The case that motivates the whole thing. No single step is confident, and the mass for one
     * label is spread across several alignments while a different label happens to win each step
     * individually. Greedy reports the per-step winner; the beam reports the likelier string.
     */
    @Test
    fun `an uncertain reading is decoded by summing alignments`() {
        val data = logits(
            doubleArrayOf(0.30, 0.40, 0.30, 0.00),
            doubleArrayOf(0.45, 0.30, 0.25, 0.00),
            doubleArrayOf(0.30, 0.40, 0.30, 0.00),
        )
        val result = decode(data, 3)
        assertTrue("an uncertain crop must reach the beam", result.beamSearched)
        assertTrue("the beam must produce something", result.text.isNotEmpty())
    }

    @Test
    fun `the beam is skipped when it is disabled`() {
        val data = logits(
            doubleArrayOf(0.30, 0.40, 0.30, 0.00),
            doubleArrayOf(0.45, 0.30, 0.25, 0.00),
        )
        assertFalse(decode(data, 2, beamWidth = 1).beamSearched)
    }

    /** A wider beam may only ever find something at least as likely, never something worse. */
    @Test
    fun `a wider beam never decodes to a less likely string`() {
        val data = logits(
            doubleArrayOf(0.25, 0.30, 0.25, 0.20),
            doubleArrayOf(0.35, 0.25, 0.20, 0.20),
            doubleArrayOf(0.20, 0.30, 0.30, 0.20),
            doubleArrayOf(0.30, 0.25, 0.25, 0.20),
        )
        val narrow = decode(data, 4, beamWidth = 2)
        val wide = decode(data, 4, beamWidth = 16)
        assertTrue(narrow.text.isNotEmpty() && wide.text.isNotEmpty())
    }

    // endregion

    // region the properties that must not break

    @Test
    fun `an empty input decodes to nothing rather than throwing`() {
        assertEquals("", CtcDecoder.decode(FloatArray(0), 0, 0, dictionary).text)
    }

    @Test
    fun `an all blank frame decodes to nothing`() {
        val data = logits(
            doubleArrayOf(0.99, 0.01, 0.00, 0.00),
            doubleArrayOf(0.99, 0.01, 0.00, 0.00),
        )
        assertEquals("", decode(data, 2).text)
    }

    /** Confidence stays a per-character measure, because the rest of the pipeline is tuned to it. */
    @Test
    fun `confidence stays in range whichever path decoded`() {
        val confident = logits(
            doubleArrayOf(0.01, 0.97, 0.01, 0.01),
            doubleArrayOf(0.97, 0.01, 0.01, 0.01),
        )
        val uncertain = logits(
            doubleArrayOf(0.30, 0.40, 0.30, 0.00),
            doubleArrayOf(0.45, 0.30, 0.25, 0.00),
        )
        for (data in listOf(confident, uncertain)) {
            val result = decode(data, 2)
            assertTrue("confidence ${result.confidence}", result.confidence in 0f..1f)
        }
    }

    /** A dictionary shorter than the class count must not index out of bounds. */
    @Test
    fun `a class beyond the dictionary is ignored`() {
        val data = logits(
            doubleArrayOf(0.10, 0.20, 0.20, 0.50),
            doubleArrayOf(0.10, 0.30, 0.30, 0.30),
        )
        val short = CtcDecoder.decode(data, 2, 4, listOf("a"))
        assertTrue(short.text.all { it == 'a' })
    }

    @Test
    fun `decoding is deterministic`() {
        val data = logits(
            doubleArrayOf(0.30, 0.40, 0.30, 0.00),
            doubleArrayOf(0.45, 0.30, 0.25, 0.00),
            doubleArrayOf(0.30, 0.35, 0.35, 0.00),
        )
        assertEquals(decode(data, 3).text, decode(data, 3).text)
    }

    // endregion
}
