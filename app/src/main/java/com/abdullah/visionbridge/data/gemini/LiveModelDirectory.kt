package com.abdullah.visionbridge.data.gemini

/**
 * Which Live model can answer which way.
 *
 * Gemini's Live models come in two shapes and only one of them can carry a reading. A
 * *native-audio* model generates speech directly and answers in audio; it accepts
 * `responseModalities: ["TEXT"]` in the setup payload and then produces nothing at all. A
 * *half-cascade* model runs a text model behind a speech front end, so it can answer in either.
 *
 * Build 53 shipped TEXT against `gemini-3.8-live`, which is native-audio, and the 2026-09-25 18:24
 * bundle shows exactly the shape of that mistake: setup accepted at 1.0 s, a resumption handle at
 * 1.05 s, three frames and three client turns sent, and not one token back — no content, no
 * turnComplete, no error — until the socket read timed out at 30.6 s and every reconnect closed
 * 1011 "Internal error encountered."
 *
 * The lesson is not "pick a different name". It is that the right name cannot be known from here:
 * model ids change, and a wrong guess costs the user a broken build to discover. So the transport
 * asks the API which models serve `bidiGenerateContent`, orders them by which shape they look like,
 * and tries them — with a deadline, so a model that cannot answer is found in seconds rather than
 * in a session. [ordered] is that ordering, and it is pure so the rule can be tested without a
 * network.
 */
object LiveModelDirectory {

    /** The model this app has always used for spoken description; kept first for audio. */
    const val NATIVE_AUDIO_MODEL = "gemini-3.8-live"

    /**
     * Names that mark a model as generating speech directly. Such a model is right for audio and
     * cannot answer in text, so it sorts last when text is what is needed.
     */
    private val NATIVE_AUDIO_MARKERS = listOf("native-audio", "native_audio", "nativeaudio")

    /**
     * Orders [available] for [responseMode], best first.
     *
     * For audio, a native-audio model is preferred and anything else is a usable second choice.
     * For text the order is reversed, because a native-audio model is not a second choice at all —
     * it is the failure this object exists to avoid. It is still kept at the end rather than
     * dropped, so that a deployment offering nothing else degrades to something rather than to an
     * empty list; the first-token deadline is what stops that costing more than one turn.
     *
     * Ordering is stable within each group, so the API's own preference order is preserved.
     */
    fun ordered(available: List<String>, responseMode: LiveResponseMode): List<String> {
        val candidates = available.filter { it.isNotBlank() }.distinct()
            .ifEmpty { listOf(NATIVE_AUDIO_MODEL) }
        val nativeAudio = candidates.filter(::looksNativeAudio)
        val cascade = candidates.filterNot(::looksNativeAudio)
        return when (responseMode) {
            LiveResponseMode.NATIVE_AUDIO -> nativeAudio + cascade
            LiveResponseMode.EXACT_TEXT -> cascade + nativeAudio
        }
    }

    /** True when the name marks a model that generates speech directly. */
    fun looksNativeAudio(model: String): Boolean {
        val name = model.substringAfterLast('/').lowercase()
        return NATIVE_AUDIO_MARKERS.any { name.contains(it) } || name == NATIVE_AUDIO_MODEL
    }

    /** Strips the `models/` prefix the API returns, so one spelling is stored and compared. */
    fun shortName(model: String): String = model.substringAfterLast('/')
}
