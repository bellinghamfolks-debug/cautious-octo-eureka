package com.abdullah.visionbridge.data.localvlm

/**
 * Turns raw local-model output into a string that is safe to display and speak.
 *
 * A quantized on-device VLM misbehaves in ways a hosted model does not, and every
 * one of them reaches a blind user as either silence or nonsense:
 *
 *  * it echoes chat-template control tokens and its own role headers,
 *  * it prefixes an answer with conversational filler that is not on the screen,
 *  * it degenerates into repeating a phrase or a line until the token budget runs out,
 *  * it emits explicit Unicode bidi overrides around mixed Arabic and Latin runs,
 *    which corrupt reading order for both TTS and TalkBack.
 *
 * Everything here is pure so the guarantees can be tested without a model.
 */
object VlmOutputSanitizer {

    private val CONTROL_TOKENS = listOf(
        "<|im_start|>", "<|im_end|>", "<|endoftext|>", "<|end|>",
        "<|assistant|>", "<|user|>", "<|system|>",
        "<s>", "</s>", "<pad>", "<unk>",
        "<__media__>", "<image>", "</image>", "<img>", "</img>",
    )

    /**
     * Conversational openers the model adds in either language. Removed only when
     * they begin the answer, so an identical phrase inside a real document survives.
     */
    private val LEADING_FILLER = listOf(
        // The trailing "هو" or colon is mandatory, so an ordinary sentence that
        // merely starts with the word "النص" is never truncated.
        Regex("""^\s*النص(?:\s+(?:الظاهر|المكتوب))?(?:\s+في\s+الصورة)?(?:\s+هو\s*:?|\s*:)\s*"""),
        Regex("""^\s*(?:إليك|هذا هو|فيما يلي)\s+(?:النص|الوصف)[^\n:]{0,40}:\s*""", RegexOption.IGNORE_CASE),
        Regex("""^\s*(?:here\s+is|here's|the\s+text\s+in\s+the\s+image\s+is|sure[,!]?)\b[^\n:]{0,40}:\s*""", RegexOption.IGNORE_CASE),
        Regex("""^\s*(?:this\s+image\s+shows|the\s+image\s+shows)\s*:?\s*""", RegexOption.IGNORE_CASE),
        Regex("""^\s*assistant\s*[:\n]\s*""", RegexOption.IGNORE_CASE),
    )

    /** Markdown fences the model wraps transcriptions in. */
    private val CODE_FENCE = Regex("""^\s*```[a-zA-Z]*\s*\n?|\n?\s*```\s*$""")

    /**
     * Explicit bidi formatting and zero-width noise.
     *
     * ZWNJ (U+200C) and ZWJ (U+200D) are deliberately kept: they carry meaning in
     * Arabic-script orthography. Directional marks, embeddings, overrides and
     * isolates are all removed, because the correct reading order for a screen
     * reader comes from the Unicode bidi algorithm applied to clean text, not
     * from overrides a language model guessed at.
     */
    private val BIDI_AND_ZERO_WIDTH =
        Regex("[\\u200B\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069\\uFEFF]")

    /** C0/C1 control characters other than newline and tab. */
    private val CONTROL_CHARACTERS =
        Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F-\\u009F]")

    private val TRAILING_SPACES = Regex("[ \\t]+(?=\\n)")
    private val EXCESS_BLANK_LINES = Regex("\\n{3,}")
    private val EXCESS_SPACES = Regex("[ \\t]{2,}")

    /**
     * Cleans [raw] into a UTF-8-safe, loop-free string ready for TTS.
     *
     * @param collapseLines collapse repeated lines. Correct for a transcription,
     *   where a genuine page rarely repeats a whole line back to back; left off
     *   for scene descriptions, which are short enough not to need it.
     */
    fun sanitize(raw: String, collapseLines: Boolean = true): String {
        if (raw.isBlank()) return ""

        var text = raw
        CONTROL_TOKENS.forEach { token -> text = text.replace(token, "\n") }
        text = text.replace(CODE_FENCE, "")
        text = text.replace(BIDI_AND_ZERO_WIDTH, "")
        text = text.replace(CONTROL_CHARACTERS, "")
        text = text.replace("\r\n", "\n").replace('\r', '\n')
        // Lone surrogates survive a truncated token boundary and render as U+FFFD.
        text = text.filterNot { it.isSurrogate() || it == '\uFFFD' }

        LEADING_FILLER.forEach { filler -> text = filler.replace(text, "") }

        text = collapseCharacterRuns(text)
        if (collapseLines) text = collapseRepeatedLines(text)
        text = dropRepeatingTail(text)

        return text
            .replace(TRAILING_SPACES, "")
            .replace(EXCESS_SPACES, " ")
            .replace(EXCESS_BLANK_LINES, "\n\n")
            .lines()
            .joinToString("\n") { it.trim() }
            .trim()
    }

    /** Collapses a single character stuttered past any plausible real usage. */
    private fun collapseCharacterRuns(text: String): String {
        val builder = StringBuilder(text.length)
        var run = 0
        var previous: Char? = null
        for (character in text) {
            run = if (character == previous) run + 1 else 0
            previous = character
            // Two consecutive newlines are a paragraph break and stay; no character
            // legitimately appears more than MAX_CONSECUTIVE_REPEATS times in a row.
            val allowed = if (character == '\n') 2 else MAX_CONSECUTIVE_REPEATS
            if (run < allowed) builder.append(character)
        }
        return builder.toString()
    }

    /**
     * Removes a line that repeats one already emitted nearby.
     *
     * Bounded lookback rather than a whole-document set: a real page legitimately
     * contains the same short line twice far apart (a repeated date, a column
     * header), while degenerate repetition is always adjacent.
     */
    private fun collapseRepeatedLines(text: String): String {
        val lines = text.lines()
        val output = ArrayList<String>(lines.size)
        val recent = ArrayDeque<String>()

        for (line in lines) {
            val key = line.trim().lowercase()
            if (key.isEmpty() || key.none(Char::isLetterOrDigit)) {
                output += line
                continue
            }
            if (recent.contains(key)) continue
            output += line
            recent.addLast(key)
            while (recent.size > REPEAT_LOOKBACK_LINES) recent.removeFirst()
        }
        return output.joinToString("\n")
    }

    /**
     * Cuts a tail that has degenerated into a repeating cycle.
     *
     * Generation is normally stopped mid-loop by [VlmLoopGuard], which leaves one
     * or two copies of the repeating block at the end. This removes them so the
     * user is not read the same clause twice at the close of a page.
     */
    private fun dropRepeatingTail(text: String): String {
        if (text.length < MIN_PERIOD_CHARS * 2) return text
        for (period in MIN_PERIOD_CHARS..minOf(MAX_PERIOD_CHARS, text.length / 2)) {
            val tail = text.takeLast(period * 2)
            val block = tail.take(period)
            if (!block.any(Char::isLetterOrDigit)) continue
            if (tail.regionMatches(period, block, 0, period)) {
                return dropRepeatingTail(text.dropLast(period))
            }
        }
        return text
    }

    private const val MAX_CONSECUTIVE_REPEATS = 3
    private const val REPEAT_LOOKBACK_LINES = 6
    internal const val MIN_PERIOD_CHARS = 12
    internal const val MAX_PERIOD_CHARS = 200
}

/**
 * Stops generation the moment the model starts cycling.
 *
 * A repetition penalty discourages repeating *tokens*; it does not stop a model
 * that re-emits an entire clause with slightly different spacing, which is the
 * form the fragment-looping bug actually takes. This watches the produced text
 * and halts as soon as a block repeats consecutively, which both saves several
 * seconds of wasted decoding and keeps the loop out of the transcript.
 */
class VlmLoopGuard(
    private val minPeriodChars: Int = VlmOutputSanitizer.MIN_PERIOD_CHARS,
    private val maxPeriodChars: Int = VlmOutputSanitizer.MAX_PERIOD_CHARS,
    private val requiredRepeats: Int = 3,
    private val checkEveryChars: Int = 24,
) {
    private var lastCheckedLength = 0

    /** Returns true when [text] has started to cycle and generation should stop. */
    fun hasDegenerated(text: String): Boolean {
        if (text.length - lastCheckedLength < checkEveryChars) return false
        lastCheckedLength = text.length

        val comparable = text.filterNot(Char::isWhitespace)
        for (period in minPeriodChars..maxPeriodChars) {
            val needed = period * requiredRepeats
            if (comparable.length < needed) break
            val tail = comparable.takeLast(needed)
            val block = tail.take(period)
            if (!block.any(Char::isLetterOrDigit)) continue
            var repeats = true
            for (repeat in 1 until requiredRepeats) {
                if (!tail.regionMatches(repeat * period, block, 0, period)) {
                    repeats = false
                    break
                }
            }
            if (repeats) return true
        }
        return false
    }

    fun reset() {
        lastCheckedLength = 0
    }
}
