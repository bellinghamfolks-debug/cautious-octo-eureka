package com.abdullah.visionbridge.data.gemini

import com.abdullah.visionbridge.domain.model.AnalysisMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which channel each mode's answer comes back on.
 *
 * The rule this fixes cost the user the thing reading is for. A Live session set to AUDIO returns
 * text only as `outputAudioTranscription`, which is produced from the synthesised speech rather
 * than from the image, so a reading came back as a transcript of a voice — and after build 51 set
 * `speechConfig.languageCode` to ar-XA, an Arabic voice, which returns every Latin word on the page
 * transliterated. The prompt said "preserve Arabic, English and numbers exactly" throughout and
 * that channel could not honour it at any setting.
 */
class LiveResponseModeTest {

    @Test
    fun `reading asks for text, because a reading must be the page's own characters`() {
        val mode = LiveResponseMode.of(AnalysisMode.TEXT_READING)
        assertEquals("TEXT", mode.modality)
        assertTrue(mode.carriesLiteralText)
        assertFalse("a reading session must not be answered by a voice", mode.speaksItself)
    }

    @Test
    fun `describing asks for audio, because native speech is what makes it live`() {
        val mode = LiveResponseMode.of(AnalysisMode.SCENE_DESCRIPTION)
        assertEquals("AUDIO", mode.modality)
        assertTrue(mode.speaksItself)
        assertFalse(mode.carriesLiteralText)
    }

    /**
     * Live fixes the modality in the setup handshake, so the two modes cannot share one socket.
     * If these ever collapsed to the same value, one of the two would be silently wrong.
     */
    @Test
    fun `the two modes never share a modality`() {
        assertEquals(
            AnalysisMode.entries.size,
            AnalysisMode.entries.map { LiveResponseMode.of(it).modality }.toSet().size,
        )
    }

    @Test
    fun `exactly one mode speaks for itself`() {
        assertEquals(1, AnalysisMode.entries.count { LiveResponseMode.of(it).speaksItself })
    }
}
