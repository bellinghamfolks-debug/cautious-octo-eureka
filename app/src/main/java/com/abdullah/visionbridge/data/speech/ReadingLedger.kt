package com.abdullah.visionbridge.data.speech

/**
 * Remembers which documents have already been spoken, and decides what a fresh recognition owes
 * the user.
 *
 * This replaces per-utterance text de-duplication for text reading. Deduplicating individual speech
 * blocks silenced the retries of a page that had never been read completely, which is what produced
 * fragmented, repeating audio: every attempt after the first had most of its blocks suppressed while
 * a few reworded ones survived. The ledger works one whole document at a time instead, so a page is
 * either read in full, extended by exactly the lines that are new, or skipped outright.
 */
class ReadingLedger(
    private val repeatWindowMs: Long = DEFAULT_REPEAT_WINDOW_MS,
    private val maxEntries: Int = 8,
) {
    sealed interface Decision {
        /**
         * Speak [text], which is the part of [document] the user has not heard. [continuation] marks
         * the tail of a page they have already partly heard, so speech appends instead of cutting in.
         */
        data class Speak(
            val text: String,
            val document: String,
            val continuation: Boolean,
        ) : Decision

        data class Skip(val reason: String) : Decision
    }

    private data class Entry(val spokenText: String, var lastSeenAtMs: Long)

    private val entries = ArrayDeque<Entry>()

    @Synchronized
    fun evaluate(text: String, now: Long = System.currentTimeMillis()): Decision {
        val candidate = DocumentSpeechPolicy.readableLines(text).joinToString("\n")
        if (candidate.isBlank()) return Decision.Skip("no_readable_text")

        removeExpired(now)

        // Pick the entry that has already covered the most of this recognition, and treat it as a
        // re-read when enough of it is familiar. Coverage is the deciding measure rather than
        // document identity: on a real device the same page came back with different line splits
        // and slightly different extents each time, every variant failed a symmetric identity test,
        // and each one was therefore read out again in full while the user had not moved at all.
        val best = entries
            .map { entry -> entry to DocumentSpeechPolicy.coverageOf(entry.spokenText, candidate) }
            .maxByOrNull { (_, coverage) -> coverage }

        val matched = best?.takeIf { (entry, coverage) ->
            coverage >= RE_READ_COVERAGE ||
                DocumentSpeechPolicy.sameDocument(entry.spokenText, candidate) ||
                DocumentSpeechPolicy.covers(container = candidate, contained = entry.spokenText)
        } ?: return Decision.Speak(candidate, document = candidate, continuation = false)

        val (match, coverage) = matched
        match.lastSeenAtMs = now
        val addition = DocumentSpeechPolicy.newContent(
            alreadySpoken = match.spokenText,
            current = candidate,
        )
        return when {
            addition.isBlank() -> Decision.Skip("already_read_completely")

            // A few characters appearing between two reads of an otherwise familiar page is
            // recognition noise: a changed clock digit, a notification badge, a line break that
            // moved. Speaking it alone is the stray fragment users hear between full readings.
            //
            // The coverage condition matters as much as the length. A short addition to a page the
            // user has almost entirely heard is noise; the same short addition to a page that is
            // half new is the point of reading it, and must not be swallowed.
            addition.length < ALWAYS_NOISE_CHARACTERS ||
                (addition.length < MIN_CONTINUATION_CHARACTERS && coverage >= NOISE_FLOOR_COVERAGE) ->
                Decision.Skip("continuation_below_noise_floor")

            else -> Decision.Speak(addition, document = candidate, continuation = true)
        }
    }

    /**
     * Records the whole page the user has now heard, replacing any earlier partial record of it.
     *
     * Pass the full recognized document rather than the spoken delta: once the tail has been read
     * aloud the user has heard the entire page, and storing only the tail would make the first half
     * look unheard on the next pass and read it a second time.
     *
     * Only call this once speech has actually been accepted, so a rejected or dropped reading never
     * masks the page on the next attempt.
     */
    @Synchronized
    fun recordSpoken(document: String, now: Long = System.currentTimeMillis()) {
        val spoken = DocumentSpeechPolicy.readableLines(document).joinToString("\n")
        if (spoken.isBlank()) return

        entries.removeAll { entry ->
            DocumentSpeechPolicy.sameDocument(entry.spokenText, spoken) ||
                DocumentSpeechPolicy.covers(container = spoken, contained = entry.spokenText)
        }
        entries.addLast(Entry(spokenText = spoken, lastSeenAtMs = now))
        while (entries.size > maxEntries) entries.removeFirst()
    }

    @Synchronized
    fun reset() = entries.clear()

    private fun removeExpired(now: Long) {
        entries.removeAll { now - it.lastSeenAtMs >= repeatWindowMs }
    }

    private companion object {
        /**
         * Long enough that holding a gaze on one page never re-reads it, short enough that coming
         * back to the same page later in a session reads it again on purpose.
         */
        const val DEFAULT_REPEAT_WINDOW_MS = 120_000L

        /** Shortest addition worth interrupting a page for. Below this it is recognition noise. */
        const val MIN_CONTINUATION_CHARACTERS = 24

        /**
         * How much of a recognition must already be familiar for it to count as the same page.
         *
         * At 0.5 a page whose lines were re-split, or which came back a little shorter or longer,
         * is still recognized as the page the user is looking at, so only the genuinely new lines
         * are read. Below half, the content really has changed and deserves a full reading.
         */
        const val RE_READ_COVERAGE = 0.5

        /** A short addition only counts as noise on a page this thoroughly heard already. */
        const val NOISE_FLOOR_COVERAGE = 0.8

        /**
         * An addition this short is never worth interrupting for, whatever the coverage. A word or
         * two appearing between two reads of a label is a recognition difference, not new content:
         * a device log shows "BLEU / CHANEL" becoming "BLEU / DE / CHANEL" on the same bottle.
         */
        const val ALWAYS_NOISE_CHARACTERS = 12
    }
}
