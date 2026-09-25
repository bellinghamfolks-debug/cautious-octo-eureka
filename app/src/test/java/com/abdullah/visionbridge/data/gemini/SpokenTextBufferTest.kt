package com.abdullah.visionbridge.data.gemini

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the reading path hands to the speech engine.
 *
 * Two things have to hold at once. Nothing may be lost — every character the model wrote is
 * eventually spoken, in order — and nothing may be released mid-word, because the bilingual engine
 * chooses Arabic or English per utterance and a fragment cut inside a word gets the wrong voice.
 */
class SpokenTextBufferTest {

    private val buffer = SpokenTextBuffer()

    /** Feeds [text] in [size]-character pieces, the way the socket actually delivers it. */
    private fun stream(text: String, size: Int = 3): List<String> =
        text.chunked(size).map { buffer.take(it) }.filter { it.isNotEmpty() } +
            listOf(buffer.flush()).filter { it.isNotEmpty() }

    // region nothing is lost

    @Test
    fun `everything written is eventually spoken, in order`() {
        val page = "مستشفى الملك فهد.\nقسم الأشعة، الدور الثاني.\nSection B, Room 214."
        assertEquals(
            page.filterNot { it.isWhitespace() },
            stream(page).joinToString("").filterNot { it.isWhitespace() },
        )
    }

    @Test
    fun `a final clause with no full stop is still spoken`() {
        assertTrue(buffer.take("زيت زيتون بكر ممتاز").isEmpty())
        assertEquals("زيت زيتون بكر ممتاز", buffer.flush())
    }

    @Test
    fun `flushing twice does not repeat the text`() {
        buffer.take("حليب طازج")
        assertEquals("حليب طازج", buffer.flush())
        assertEquals("", buffer.flush())
    }

    @Test
    fun `reset drops the held text rather than carrying it into the next reading`() {
        buffer.take("النصف الأول من قراءة أُلغيت")
        buffer.reset()
        assertEquals("", buffer.flush())
    }

    // endregion

    // region and nothing is released mid-word

    @Test
    fun `a clause is held until its boundary arrives`() {
        assertEquals("", buffer.take("الدور"))
        assertEquals("", buffer.take(" الثا"))
        assertEquals("الدور الثاني.", buffer.take("ني."))
    }

    /** Each released piece must be a whole clause, so the next one never starts inside a word. */
    @Test
    fun `mixed-script prose is released clause by clause`() {
        assertEquals(
            listOf("Aisle 4.", "Olive oil,", "extra virgin 500 ml;", "best before 2027."),
            stream("Aisle 4. Olive oil, extra virgin 500 ml; best before 2027."),
        )
    }

    /** A sign with no punctuation at all must still start being spoken. */
    @Test
    fun `an unpunctuated run is released at a word end once it is long enough`() {
        val long = List(40) { "كلمة" }.joinToString(" ")
        val released = buffer.take(long)
        assertTrue("nothing was released from ${long.length} characters", released.isNotEmpty())
        assertTrue("released mid-word: '$released'", released.endsWith("كلمة"))
    }

    /**
     * A single token longer than the threshold has no word end to break at. It is released whole,
     * because holding it forever is the one outcome worse than a long utterance.
     */
    @Test
    fun `one unbroken token longer than the threshold is released rather than held`() {
        val token = "x".repeat(SpokenTextBuffer.SPEAK_WITHOUT_BOUNDARY_AFTER + 20)
        assertEquals(token, buffer.take(token))
    }

    // endregion
}
