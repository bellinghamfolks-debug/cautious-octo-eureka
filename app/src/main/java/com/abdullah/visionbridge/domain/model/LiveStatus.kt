package com.abdullah.visionbridge.domain.model

/**
 * What the transport is actually doing right now, as the screen can say it out loud.
 *
 * Every field here answers a question the user has had to ask by exporting a diagnostic bundle and
 * waiting for someone to read it. Whether the answer came over the live socket or fell back to a
 * per-frame request changes how fast it arrives; whether the exact characters came back changes
 * whether a digit can be trusted. Both were invisible in the app while being the two things that
 * decide what the user hears.
 */
data class LiveStatus(
    /** The model that answered, as the service names it. */
    val model: String = "",

    /** True while the live socket is carrying frames; false once a frame fell back to a request. */
    val streaming: Boolean = false,

    /**
     * True when the page's characters arrived through the reading tool rather than as a transcript
     * of speech. This is the difference between a digit and the word for that digit.
     */
    val exactText: Boolean = false,

    /** Milliseconds from sending the frame to the first word or sound of the answer. */
    val firstAnswerMs: Double? = null,
) {
    /** A short Arabic phrase for the status line and for TalkBack. */
    val summary: String
        get() = buildString {
            append(if (streaming) "بث مباشر" else "طلب لكل لقطة")
            if (exactText) append("، نص حرفي") else if (streaming) append("، نص من تفريغ الصوت")
            firstAnswerMs?.let { append("، أول إجابة ${it.toInt()} مللي ثانية") }
        }
}
