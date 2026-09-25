package com.abdullah.visionbridge.data.gemini

import com.abdullah.visionbridge.domain.model.AnalysisMode

/**
 * What Gemini Live is asked to answer with. It is not the same question for the two modes.
 *
 * A Live session answers in exactly one modality, fixed at setup. Asking for AUDIO gets native
 * speech, and the only text that comes back is `outputAudioTranscription` — a transcript of what
 * the voice said, produced from the audio rather than from the image.
 *
 * For a scene description that is the right trade: the sentence is the model's own, so a transcript
 * of it loses nothing, and native audio is what makes the description feel live.
 *
 * For reading it is the wrong one, and measurably so. A transcription of speech cannot preserve
 * what a reading is for: digits come back as the words that were spoken, punctuation and line
 * structure are gone, and once `speechConfig.languageCode` is ar-XA — which build 51 set, correctly,
 * so descriptions stop arriving in English — every Latin word on the page is voiced by an Arabic
 * speaker and returns transliterated. The instruction said "preserve Arabic, English and numbers
 * exactly" the whole time and could not be honoured by that channel at any setting. The field
 * session on 2026-09-25 shows the size of it: twenty-one frames sent, seven producing any text at
 * all, and those seven averaging fifty-five characters.
 *
 * So reading asks for TEXT and speaks it through the app's own bilingual engine, which exists for
 * exactly the Arabic/English mixing a page has and which the user's speech-rate setting reaches.
 * The text published and displayed is then the text that was read.
 */
enum class LiveResponseMode(val modality: String) {
    /** The model speaks; the text is a transcript of that speech. */
    NATIVE_AUDIO("AUDIO"),

    /** The model writes; the app speaks it. */
    EXACT_TEXT("TEXT"),
    ;

    /** Whether the answer arrives as PCM for [com.abdullah.visionbridge.data.speech.LivePcmAudioPlayer]. */
    val speaksItself: Boolean get() = this == NATIVE_AUDIO

    /** Whether the text that arrives is the model's literal output rather than a speech transcript. */
    val carriesLiteralText: Boolean get() = this == EXACT_TEXT

    companion object {
        fun of(mode: AnalysisMode): LiveResponseMode = when (mode) {
            AnalysisMode.TEXT_READING -> EXACT_TEXT
            AnalysisMode.SCENE_DESCRIPTION -> NATIVE_AUDIO
        }
    }
}
