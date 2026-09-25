package com.abdullah.visionbridge.data.gemini

/**
 * Which Live model to try, for which kind of answer, in which order.
 *
 * The account's real catalogue, read off the device on 2026-09-25, is nine models:
 *
 * ```
 * gemini-3.5-transcribe-live                     gemini-3.8-live
 * gemini-2.5-flash-native-audio-latest           gemini-3.8-live-extended-thinking
 * gemini-2.5-flash-native-audio-preview-09-2025  gemini-robotics-er-2-streaming-preview
 * gemini-2.5-flash-native-audio-preview-12-2025  gemini-3.5-live-translate-preview
 * gemini-3.1-flash-live-preview
 * ```
 *
 * Two lessons are written into the ordering below, both paid for on the device.
 *
 * The first is that "not native audio" is not the same as "can read a page". The earlier rule had
 * only that one axis, so it put `gemini-3.5-transcribe-live` first — a name that sounds ideal for
 * an OCR app and is in fact a speech-transcription model. It accepted the setup, took a video
 * frame, and produced nothing; the first-token deadline caught it in three seconds. A translation
 * model and a robotics streaming model sit in the same catalogue with the same trap.
 *
 * So models are ranked by what they are for, not only by what they are not: a general Live model
 * first, then anything unclassified, then the special-purpose ones, then native audio. Within each
 * band the API's own order is kept.
 *
 * The second is that nothing here is more than a guess about a name, so the ordering only decides
 * what is tried first. What decides the answer is the device: the transport gives each candidate a
 * first-token deadline and remembers the verdict.
 */
object LiveModelDirectory {

    /** The model this app has always spoken with, and the floor if discovery returns nothing. */
    const val NATIVE_AUDIO_MODEL = "gemini-3.8-live"

    /** Generates speech directly. Right for audio; answers a text turn with nothing. */
    private val NATIVE_AUDIO_MARKERS = listOf("native-audio", "native_audio", "nativeaudio", "tts")

    /**
     * Built for one job that is not reading a document off a video frame.
     *
     * `transcribe` is speech to text, `translate` rewrites rather than transcribes — the one thing
     * a reading must never do — and `robotics`/`er` streams for embodied control.
     */
    private val SPECIAL_PURPOSE_MARKERS =
        listOf("transcribe", "translate", "robotics", "-er-", "embedding", "image", "veo", "imagen")

    /** Marks a general-purpose Live model, which is what a page should be read by. */
    private val GENERAL_LIVE_MARKERS = listOf("flash-live", "live-preview", "flash-preview")

    /**
     * Models this account's own device has already proved cannot answer in text.
     *
     * Ranking is a guess about a name; this is a measurement, and it outranks the guess. Every
     * entry was refused or answered with silence on 2026-09-25, and re-testing them costs the user
     * seconds of a live session to re-learn something already known:
     *
     * - `gemini-3.1-flash-live-preview` — the server said it outright, close 1007: "The requested
     *   combination of response modalities (TEXT) is not supported by the model."
     * - `gemini-3.5-transcribe-live` — accepted the setup, took seventeen frames, said nothing.
     *   It transcribes speech, not pages.
     * - `gemini-robotics-er-2-streaming-preview` — completed a turn carrying no text at all.
     * - `gemini-3.5-live-translate-preview` — close 1007, "Request contains an invalid argument."
     *
     * `gemini-3.8-live-extended-thinking` is deliberately absent. It failed too, but only by
     * asking to be told a thinking level — a request, not a refusal — and it has never actually
     * been tried with one. It is the single untested candidate left, which is why the attempt is
     * now one socket rather than a tour of the catalogue.
     *
     * The list is about text only. `gemini-3.8-live` answers audio on this device and is the path
     * everything falls back to.
     */
    private val MEASURED_UNABLE_TO_WRITE = setOf(
        "gemini-3.1-flash-live-preview",
        "gemini-3.5-transcribe-live",
        "gemini-robotics-er-2-streaming-preview",
        "gemini-3.5-live-translate-preview",
    )

    /**
     * Orders [available] for [responseMode], best first. Nothing is dropped, only ranked, so a
     * catalogue full of wrong-shaped models still yields something to try — the deadline is what
     * bounds the cost of trying it.
     */
    fun ordered(available: List<String>, responseMode: LiveResponseMode): List<String> {
        val candidates = available.map(::shortName).filter { it.isNotBlank() }.distinct()
            .ifEmpty { listOf(NATIVE_AUDIO_MODEL) }
        // Measurement beats ranking. A model already shown not to write is not a low-ranked
        // candidate, it is not a candidate — and probing it again spends seconds of a live session
        // re-learning it, which is what made reading feel broken rather than slow. A model built
        // to speak is excluded on the same grounds: that it answers a text turn with nothing is
        // the whole lesson of build 53, and it does not need proving once per session.
        //
        // An empty text list is a valid answer, not a failure. It means this catalogue has nothing
        // left to try, and the caller degrades to audio on Live — which streams, and works.
        return when (responseMode) {
            LiveResponseMode.EXACT_TEXT ->
                candidates.filterNot { it in MEASURED_UNABLE_TO_WRITE || looksNativeAudio(it) }
            LiveResponseMode.NATIVE_AUDIO -> candidates
        }.sortedBy { rank(it, responseMode) }
    }

    /** Whether the device has already shown [model] cannot answer in text. */
    fun measuredUnableToWrite(model: String): Boolean = shortName(model) in MEASURED_UNABLE_TO_WRITE

    /**
     * Lower sorts earlier. [List.sortedBy] is stable, so models sharing a rank keep the order the
     * API gave them.
     */
    private fun rank(model: String, responseMode: LiveResponseMode): Int {
        val speaks = looksNativeAudio(model)
        val special = looksSpecialPurpose(model)
        val general = looksGeneralLive(model)
        return when (responseMode) {
            // Audio wants the model built to speak. The one this app has always used comes first
            // because it is the only one measured working in the field, not because of its name.
            LiveResponseMode.NATIVE_AUDIO -> when {
                shortName(model) == NATIVE_AUDIO_MODEL -> -1
                speaks -> 0
                general && !special -> 1
                !special -> 2
                else -> 3
            }
            // Text wants a general model; a speaking one is last because it cannot answer at all.
            LiveResponseMode.EXACT_TEXT -> when {
                speaks -> 4
                general && !special -> 0
                !special -> 1
                else -> 2
            }
        }
    }

    fun looksNativeAudio(model: String): Boolean {
        val name = shortName(model).lowercase()
        return NATIVE_AUDIO_MARKERS.any { name.contains(it) } || name == NATIVE_AUDIO_MODEL
    }

    fun looksSpecialPurpose(model: String): Boolean {
        val name = shortName(model).lowercase()
        return SPECIAL_PURPOSE_MARKERS.any { name.contains(it) }
    }

    fun looksGeneralLive(model: String): Boolean {
        val name = shortName(model).lowercase()
        return GENERAL_LIVE_MARKERS.any { name.contains(it) }
    }

    /** Strips the `models/` prefix the API returns, so one spelling is stored and compared. */
    fun shortName(model: String): String = model.substringAfterLast('/').trim()
}
