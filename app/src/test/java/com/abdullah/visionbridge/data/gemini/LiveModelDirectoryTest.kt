package com.abdullah.visionbridge.data.gemini

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which Live model is tried first, and for which kind of answer.
 *
 * Build 53 asked a native-audio model for text. It accepted the setup and then produced nothing —
 * three frames sent, no content, no turnComplete, no error, a socket read timing out at 30.6 s, and
 * every reconnect closing 1011. This ordering is half of the fix; the transport's first-token
 * deadline is the other half, and it is what catches a model this ordering guesses wrong about.
 */
class LiveModelDirectoryTest {

    private val nativeAudio = "gemini-3.8-live"
    private val cascade = "gemini-live-3.8-flash"
    private val explicit = "gemini-4.0-flash-native-audio-preview"

    // region the ordering

    @Test
    fun `text prefers a model that is not generating speech directly`() {
        assertEquals(
            listOf(cascade, nativeAudio),
            LiveModelDirectory.ordered(listOf(nativeAudio, cascade), LiveResponseMode.EXACT_TEXT),
        )
    }

    @Test
    fun `audio prefers the model that speaks`() {
        assertEquals(
            listOf(nativeAudio, cascade),
            LiveModelDirectory.ordered(listOf(cascade, nativeAudio), LiveResponseMode.NATIVE_AUDIO),
        )
    }

    /**
     * A wrong-shaped model is ranked last, never dropped. A deployment that offers only native
     * audio should still try rather than refuse outright — the deadline bounds what that costs.
     */
    @Test
    fun `nothing is discarded, only ranked`() {
        val available = listOf(nativeAudio, cascade, explicit)
        for (mode in LiveResponseMode.entries) {
            assertEquals(
                available.toSet(),
                LiveModelDirectory.ordered(available, mode).toSet(),
            )
        }
    }

    /** The API's own preference survives inside each group. */
    @Test
    fun `order within a group is the order the API gave`() {
        val cascades = listOf("live-a", "live-b", "live-c")
        assertEquals(
            cascades,
            LiveModelDirectory.ordered(cascades, LiveResponseMode.EXACT_TEXT),
        )
    }

    @Test
    fun `an empty listing still yields something to try`() {
        assertEquals(
            listOf(LiveModelDirectory.NATIVE_AUDIO_MODEL),
            LiveModelDirectory.ordered(emptyList(), LiveResponseMode.EXACT_TEXT),
        )
    }

    @Test
    fun `blanks and duplicates do not become candidates`() {
        assertEquals(
            listOf(cascade),
            LiveModelDirectory.ordered(
                listOf(cascade, "", cascade, "   ".trim()),
                LiveResponseMode.EXACT_TEXT,
            ),
        )
    }

    // endregion

    // region recognising the shape

    @Test
    fun `a model named for native audio is recognised however it is spelled`() {
        for (name in listOf(explicit, "models/x-native_audio-1", "SOMETHING-NativeAudio")) {
            assertTrue(name, LiveModelDirectory.looksNativeAudio(name))
        }
    }

    @Test
    fun `the model this app has always spoken with counts as one`() {
        assertTrue(LiveModelDirectory.looksNativeAudio("models/$nativeAudio"))
    }

    @Test
    fun `a cascade model is not mistaken for one`() {
        assertFalse(LiveModelDirectory.looksNativeAudio("models/$cascade"))
    }

    @Test
    fun `the API prefix is stripped so one spelling is compared`() {
        assertEquals(nativeAudio, LiveModelDirectory.shortName("models/$nativeAudio"))
        assertEquals(nativeAudio, LiveModelDirectory.shortName(nativeAudio))
    }

    // endregion
}
