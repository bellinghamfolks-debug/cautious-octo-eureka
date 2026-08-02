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
         *
         * [alreadyHeard] is the ledger entry this recognition continues, and it must be handed back
         * to [recordDelivered] so the page is recorded as a whole rather than as a loose tail.
         */
        data class Speak(
            val text: String,
            val document: String,
            val continuation: Boolean,
            val alreadyHeard: String,
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
        } ?: return Decision.Speak(
            candidate,
            document = candidate,
            continuation = false,
            alreadyHeard = "",
        )

        val (match, _) = matched
        match.lastSeenAtMs = now
        val addition = DocumentSpeechPolicy.newContent(
            alreadySpoken = match.spokenText,
            current = candidate,
        )
        return when {
            addition.isBlank() -> Decision.Skip("already_read_completely")

            // Recognition jitter between two reads of the same page is loose glyphs: a stray "O",
            // a "D" from a bottle's shoulder, an "=" where a line ended. It is never a word. This
            // used to be judged by character count, and the count was wrong in the way that matters
            // most — it threw away "PARFUM", "DE", a room number and a platform number, which are
            // short precisely because they are the whole reason someone pointed the glasses at
            // something. A device bundle shows the addition "BLEU / DE / CHANEL / PARFUM" being
            // discarded as noise 42 times in one session. Asking whether the addition contains a
            // word keeps the jitter out and lets the product name through.
            !carriesAWord(addition) -> Decision.Skip("continuation_is_recognition_jitter")

            else -> Decision.Speak(
                addition,
                document = candidate,
                continuation = true,
                alreadyHeard = match.spokenText,
            )
        }
    }

    /**
     * Records the part of a page the user has now genuinely heard.
     *
     * [alreadyHeard] is the entry this reading continued, taken from [Decision.Speak]; passing it
     * back means the ledger stores the whole page rather than a loose tail, which would otherwise
     * make the first half look unread on the next pass and be spoken twice.
     *
     * [deliveredText] must contain only what reached the user's ears. Call this from a speech
     * completion callback, never from the code that queues speech: the two are seconds apart, and
     * on a device where the target changes every 343 ms they are usually different by most of a
     * page. Whatever is left out stays owed and will be offered again on the next recognition.
     */
    @Synchronized
    fun recordDelivered(
        alreadyHeard: String,
        deliveredText: String,
        now: Long = System.currentTimeMillis(),
    ) {
        val delivered = DocumentSpeechPolicy.readableLines(deliveredText).joinToString("\n")
        if (delivered.isBlank()) return
        val heard = DocumentSpeechPolicy.readableLines(alreadyHeard).joinToString("\n")
        val spoken = if (heard.isBlank()) delivered else "$heard\n$delivered"

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

    /** True when [text] contains at least one run of letters or digits long enough to be a word. */
    private fun carriesAWord(text: String): Boolean =
        NON_WORD.split(text).any { token -> token.length >= MIN_WORD_CHARACTERS }

    private companion object {
        private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")

        /**
         * Two characters. "DE" and "12" are words; a lone "O" or "=" left over from a bottle's
         * shoulder is not. A page that really is one character long is not a continuation at all,
         * so it is spoken through the fresh-page path and never reaches this rule.
         */
        const val MIN_WORD_CHARACTERS = 2

        /**
         * Long enough that holding a gaze on one page never re-reads it, short enough that coming
         * back to the same page later in a session reads it again on purpose.
         */
        const val DEFAULT_REPEAT_WINDOW_MS = 120_000L

        /**
         * How much of a recognition must already be familiar for it to count as the same page.
         *
         * At 0.5 a page whose lines were re-split, or which came back a little shorter or longer,
         * is still recognized as the page the user is looking at, so only the genuinely new lines
         * are read. Below half, the content really has changed and deserves a full reading.
         */
        const val RE_READ_COVERAGE = 0.5
    }
}
