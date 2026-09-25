package com.abdullah.visionbridge.data.gemini

/**
 * Turns a stream of model text fragments into pieces worth speaking.
 *
 * Gemini Live emits text in whatever chunks the decoder produces — often a few characters, and
 * frequently splitting a word. Handing each one straight to the speech engine costs twice: the
 * bilingual segmenter decides Arabic or English per utterance, so a fragment cut mid-word can be
 * voiced in the wrong language, and every fragment is a separate utterance with its own gap, which
 * is the choppiness the whole live path was built to remove.
 *
 * So fragments are held until a clause has arrived, and released up to and including the last
 * boundary — which is also what makes the spoken text a growing prefix of the published text, never
 * a rewrite of it. A line with no punctuation at all cannot be held forever, so past
 * [SPEAK_WITHOUT_BOUNDARY_AFTER] characters the buffer releases at the last space instead; a single
 * unbroken run longer than that is released whole rather than split inside a word.
 *
 * Pure and stateful, with no Android or network dependency, so the rule is testable on its own.
 */
class SpokenTextBuffer {
    private val pending = StringBuilder()

    /** Adds [delta] and returns whatever is now complete enough to speak, possibly empty. */
    fun take(delta: String): String {
        if (delta.isEmpty()) return ""
        pending.append(delta)
        val boundary = pending.indexOfLast { it in BOUNDARIES }
        if (boundary >= 0) return release(boundary + 1)
        if (pending.length < SPEAK_WITHOUT_BOUNDARY_AFTER) return ""
        val space = pending.indexOfLast { it.isWhitespace() }
        return if (space > 0) release(space + 1) else release(pending.length)
    }

    /** Everything still held, for the end of a turn. */
    fun flush(): String = release(pending.length)

    fun reset() = pending.setLength(0)

    private fun release(endExclusive: Int): String {
        if (endExclusive <= 0) return ""
        val text = pending.substring(0, endExclusive)
        pending.delete(0, endExclusive)
        return text.trim()
    }

    companion object {
        /** Sentence and clause ends in both scripts, plus the line breaks a page is laid out in. */
        private val BOUNDARIES = charArrayOf(
            '\n', '.', '!', '?', ':', ';', ',',
            '،', // Arabic comma
            '؛', // Arabic semicolon
            '؟', // Arabic question mark
            '۔', // Arabic full stop
        ).toSet()

        /**
         * Long enough that ordinary punctuated prose is never cut mid-clause, short enough that a
         * label written without any punctuation still starts being spoken about a line in.
         */
        internal const val SPEAK_WITHOUT_BOUNDARY_AFTER = 140
    }
}
