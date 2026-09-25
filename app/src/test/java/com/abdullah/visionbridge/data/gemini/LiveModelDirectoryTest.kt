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

    /**
     * The probe is one socket now, not a tour. Every other model in this catalogue was refused or
     * silent on the device, and re-testing them is the session time that made reading feel broken.
     */
    @Test
    fun `text tries exactly the one candidate the device has not ruled out`() {
        assertEquals(listOf("gemini-3.8-live-extended-thinking"), forText())
    }

    @Test
    fun `text never offers a model built to speak`() {
        assertTrue(
            "a speaking model was offered for text: ${forText()}",
            forText().none { LiveModelDirectory.looksNativeAudio(it) },
        )
    }

    /** Translating is the one thing a reading must never do; robotics streams for control. */
    @Test
    fun `the models measured unable to write are not offered for text`() {
        for (measured in listOf(
            "gemini-3.5-transcribe-live",
            "gemini-3.5-live-translate-preview",
            "gemini-robotics-er-2-streaming-preview",
            "gemini-3.1-flash-live-preview",
        )) {
            assertTrue(measured, LiveModelDirectory.measuredUnableToWrite(measured))
            assertFalse("$measured was offered for text", forText().contains(measured))
        }
    }

    /**
     * It failed too, but by asking to be told a thinking level — a request, not a refusal — and it
     * has never been tried with one. Removing it would end the search for live exact text.
     */
    @Test
    fun `the model that only asked to be configured stays a candidate`() {
        assertFalse(
            LiveModelDirectory.measuredUnableToWrite("gemini-3.8-live-extended-thinking"),
        )
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

    /** Audio discards nothing: that path works, and its fallbacks must stay available. */
    @Test
    fun `every model stays a candidate for audio`() {
        assertEquals(
            CATALOGUE.toSet(),
            LiveModelDirectory.ordered(CATALOGUE, LiveResponseMode.NATIVE_AUDIO).toSet(),
        )
    }

    /**
     * An empty text list is an answer, not a failure: it means nothing here can write, and the
     * caller degrades to audio on Live, which streams. It must never mean "give up on Live".
     */
    @Test
    fun `a catalogue with nothing that can write yields no text candidate`() {
        assertEquals(
            emptyList<String>(),
            LiveModelDirectory.ordered(
                listOf("gemini-3.8-live", "gemini-3.5-transcribe-live"),
                LiveResponseMode.EXACT_TEXT,
            ),
        )
    }

    @Test
    fun `order within a rank is the order the API gave`() {
        val peers = listOf("gemini-9-flash-live", "gemini-8-flash-live", "gemini-7-flash-live")
        assertEquals(peers, LiveModelDirectory.ordered(peers, LiveResponseMode.EXACT_TEXT))
    }

    @Test
    fun `an empty listing still yields something to speak with`() {
        assertEquals(
            listOf(LiveModelDirectory.NATIVE_AUDIO_MODEL),
            LiveModelDirectory.ordered(emptyList(), LiveResponseMode.NATIVE_AUDIO),
        )
    }

    @Test
    fun `blanks and duplicates and prefixes collapse to one candidate`() {
        assertEquals(
            listOf("gemini-9-flash-live"),
            LiveModelDirectory.ordered(
                listOf("gemini-9-flash-live", "models/gemini-9-flash-live", "", " "),
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
