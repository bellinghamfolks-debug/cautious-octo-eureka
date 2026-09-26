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
        /**
         * Always audio, and the reading's exact characters come back through the tool instead.
         *
         * Asking for TEXT is no longer a thing this account can do. Every candidate refused it, and
         * the refusal is the same every time: 1007 "The requested combination of response
         * modalities (AUDIO, TEXT) is not supported by the model." Google removed TEXT from the
         * general-purpose Live families, and the one model that still accepts it transcribes speech
         * rather than reading pixels.
         *
         * Attempting it anyway was not free. In the 2026-09-26 03:18 session the probe burned the
         * first eight seconds of the session — connect, refusal, strike-off, degrade — before a
         * single frame could be answered, and it did that at the start of every session. The tool
         * call it was competing with had already worked: three readings came back through
         * `report_visible_text`, character for character.
         *
         * [EXACT_TEXT] stays in the type, and the setup code that serves it stays with it, because
         * it documents the protocol and costs nothing while unreachable. It is simply not asked for.
         */
        fun of(mode: AnalysisMode): LiveResponseMode = NATIVE_AUDIO
    }
}
