package com.abdullah.visionbridge.data.gemini

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which Live model is tried first, and for which kind of answer.
 *
 * The list in [CATALOGUE] is not invented: it is what the account actually offers, read off the
 * device on 2026-09-25 and recorded in the bundle as LIVE_MODELS_DISCOVERED. Ordering it correctly
 * is the whole job of this object, and getting it wrong has cost two builds:
 *
 * - Build 53 asked a native-audio model for text. It accepted the setup and answered nothing.
 * - Build 55 ranked on "not native audio" alone, so it put `gemini-3.5-transcribe-live` first — a
 *   name that reads like the ideal choice for an OCR app and is a speech-transcription model. It
 *   took a video frame and produced nothing, and the first-token deadline caught it in 3.0 s.
 *
 * So the ordering is tested against the real catalogue rather than against tidy invented names.
 */
class LiveModelDirectoryTest {

    /** Exactly what the device reported, in the order the API returned it. */
    private val CATALOGUE = listOf(
        "gemini-3.5-transcribe-live",
        "gemini-2.5-flash-native-audio-latest",
        "gemini-2.5-flash-native-audio-preview-09-2025",
        "gemini-2.5-flash-native-audio-preview-12-2025",
        "gemini-3.1-flash-live-preview",
        "gemini-3.8-live",
        "gemini-3.8-live-extended-thinking",
        "gemini-robotics-er-2-streaming-preview",
        "gemini-3.5-live-translate-preview",
    )

    private fun forText() = LiveModelDirectory.ordered(CATALOGUE, LiveResponseMode.EXACT_TEXT)
    private fun forAudio() = LiveModelDirectory.ordered(CATALOGUE, LiveResponseMode.NATIVE_AUDIO)

    // region reading

    @Test
    fun `text tries the general live model first`() {
        assertEquals("gemini-3.1-flash-live-preview", forText().first())
    }

    /** The regression from build 55, named. */
    @Test
    fun `text does not try a speech-transcription model before a general one`() {
        val order = forText()
        assertTrue(
            "transcribe-live was tried before the general model: $order",
            order.indexOf("gemini-3.1-flash-live-preview") <
                order.indexOf("gemini-3.5-transcribe-live"),
        )
    }

    @Test
    fun `text puts every model that can only speak last`() {
        val order = forText()
        val lastGeneral = order.indexOfLast { !LiveModelDirectory.looksNativeAudio(it) }
        val firstSpeaking = order.indexOfFirst { LiveModelDirectory.looksNativeAudio(it) }
        assertTrue("a speaking model outranked a writing one: $order", lastGeneral < firstSpeaking)
    }

    /** Translating is the one thing a reading must never do; robotics streams for control. */
    @Test
    fun `text ranks the single-purpose models below the general ones`() {
        val order = forText()
        for (special in listOf(
            "gemini-3.5-transcribe-live",
            "gemini-3.5-live-translate-preview",
            "gemini-robotics-er-2-streaming-preview",
        )) {
            assertTrue(
                "$special outranked gemini-3.8-live-extended-thinking: $order",
                order.indexOf("gemini-3.8-live-extended-thinking") < order.indexOf(special),
            )
        }
    }

    // endregion

    // region describing

    /** The only model measured working for audio in the field keeps its place at the front. */
    @Test
    fun `audio tries the model this app has always spoken with first`() {
        assertEquals(LiveModelDirectory.NATIVE_AUDIO_MODEL, forAudio().first())
    }

    @Test
    fun `audio prefers speaking models over the rest`() {
        val order = forAudio()
        val lastSpeaking = order.indexOfLast { LiveModelDirectory.looksNativeAudio(it) }
        val firstSilent = order.indexOfFirst { !LiveModelDirectory.looksNativeAudio(it) }
        assertTrue("a non-speaking model outranked a speaking one: $order", lastSpeaking < firstSilent)
    }

    // endregion

    // region what is never done

    /** Ranking, never discarding: a catalogue of wrong shapes must still yield something to try. */
    @Test
    fun `every model stays a candidate in both modes`() {
        for (mode in LiveResponseMode.entries) {
            assertEquals(CATALOGUE.toSet(), LiveModelDirectory.ordered(CATALOGUE, mode).toSet())
        }
    }

    @Test
    fun `order within a rank is the order the API gave`() {
        val peers = listOf("gemini-9-flash-live", "gemini-8-flash-live", "gemini-7-flash-live")
        assertEquals(peers, LiveModelDirectory.ordered(peers, LiveResponseMode.EXACT_TEXT))
    }

    @Test
    fun `an empty listing still yields something to try`() {
        assertEquals(
            listOf(LiveModelDirectory.NATIVE_AUDIO_MODEL),
            LiveModelDirectory.ordered(emptyList(), LiveResponseMode.EXACT_TEXT),
        )
    }

    @Test
    fun `blanks and duplicates and prefixes collapse to one candidate`() {
        assertEquals(
            listOf("gemini-3.1-flash-live-preview"),
            LiveModelDirectory.ordered(
                listOf(
                    "gemini-3.1-flash-live-preview",
                    "models/gemini-3.1-flash-live-preview",
                    "",
                    " ",
                ),
                LiveResponseMode.EXACT_TEXT,
            ),
        )
    }

    // endregion

    // region recognising the shapes

    @Test
    fun `the speaking models are recognised`() {
        for (name in CATALOGUE.filter { it.contains("native-audio") }) {
            assertTrue(name, LiveModelDirectory.looksNativeAudio(name))
        }
        assertTrue(LiveModelDirectory.looksNativeAudio("models/gemini-3.8-live"))
    }

    @Test
    fun `a general live model is not mistaken for a speaking one`() {
        assertFalse(LiveModelDirectory.looksNativeAudio("models/gemini-3.1-flash-live-preview"))
        assertTrue(LiveModelDirectory.looksGeneralLive("gemini-3.1-flash-live-preview"))
        assertFalse(LiveModelDirectory.looksSpecialPurpose("gemini-3.1-flash-live-preview"))
    }

    @Test
    fun `the single-purpose models are recognised`() {
        for (name in listOf(
            "gemini-3.5-transcribe-live",
            "gemini-3.5-live-translate-preview",
            "gemini-robotics-er-2-streaming-preview",
        )) {
            assertTrue(name, LiveModelDirectory.looksSpecialPurpose(name))
        }
    }

    @Test
    fun `the API prefix is stripped so one spelling is compared`() {
        assertEquals("gemini-3.8-live", LiveModelDirectory.shortName("models/gemini-3.8-live"))
        assertEquals("gemini-3.8-live", LiveModelDirectory.shortName("gemini-3.8-live"))
    }

    // endregion
}
