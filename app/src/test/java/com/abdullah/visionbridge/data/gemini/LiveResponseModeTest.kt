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

    /**
     * Reading no longer asks for TEXT, because nothing on this account will serve it: every
     * candidate answered 1007 "The requested combination of response modalities (AUDIO, TEXT) is
     * not supported by the model." The probe cost the first eight seconds of every session, and the
     * page's exact characters now come back through the reading tool on the audio session instead.
     */
    @Test
    fun `reading asks for audio, because text is refused and the tool carries the characters`() {
        val mode = LiveResponseMode.of(AnalysisMode.TEXT_READING)
        assertEquals("AUDIO", mode.modality)
        assertTrue(mode.speaksItself)
    }

    @Test
    fun `describing asks for audio, because native speech is what makes it live`() {
        val mode = LiveResponseMode.of(AnalysisMode.SCENE_DESCRIPTION)
        assertEquals("AUDIO", mode.modality)
        assertTrue(mode.speaksItself)
        assertFalse(mode.carriesLiteralText)
    }

    /** No mode may ask for a modality this account's models refuse. */
    @Test
    fun `no mode asks for text`() {
        assertTrue(AnalysisMode.entries.none { LiveResponseMode.of(it).carriesLiteralText })
    }

    /**
     * The type still describes both channels even though only one is requested: the setup code that
     * serves TEXT documents the protocol, and costs nothing while unreachable.
     */
    @Test
    fun `the text channel is still described, just never asked for`() {
        assertEquals("TEXT", LiveResponseMode.EXACT_TEXT.modality)
        assertTrue(LiveResponseMode.EXACT_TEXT.carriesLiteralText)
        assertFalse(LiveResponseMode.EXACT_TEXT.speaksItself)
    }
}
