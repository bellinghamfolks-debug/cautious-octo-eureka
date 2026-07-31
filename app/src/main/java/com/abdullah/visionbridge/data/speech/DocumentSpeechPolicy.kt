package com.abdullah.visionbridge.data.speech

/**
 * Line-level continuation rules for spoken documents.
 *
 * A page of text is re-recognized many times while the user holds their gaze on it, and every
 * recognition differs slightly in punctuation, spacing and the occasional misread character. These
 * helpers answer the only two questions the reading pipeline actually needs: is this the same page
 * we are already reading, and which lines of it has the user not heard yet.
 *
 * Everything here is pure so the reading contract can be unit tested without Android or a network.
 */
object DocumentSpeechPolicy {
    /** Two documents count as the same page when each one's lines are mostly present in the other. */
    const val SAME_DOCUMENT_CONTAINMENT = 0.80

    /** A single line counts as already spoken at a lower bar, because line breaks move between reads. */
    private const val SAME_LINE_SIMILARITY = 0.82
    private const val MIN_LINE_TOKENS_FOR_FUZZY_MATCH = 2

    /**
     * Returns the lines of [current] that are not already covered by [alreadySpoken], preserving the
     * original visual reading order and the original characters.
     */
    fun newContent(alreadySpoken: String, current: String): String {
        val currentLines = readableLines(current)
        if (currentLines.isEmpty()) return ""
        val spokenLines = readableLines(alreadySpoken).map(::LineKey)
        if (spokenLines.isEmpty()) return currentLines.joinToString("\n")

        return currentLines
            .filter { line -> spokenLines.none { spoken -> spoken.matches(LineKey(line)) } }
            .joinToString("\n")
            .trim()
    }

    /**
     * True when [first] and [second] are two recognitions of the same page rather than two pages.
     * Containment is measured in both directions so a partial re-read still matches its own full text.
     */
    fun sameDocument(first: String, second: String): Boolean {
        val firstTokens = SpeechTextTools.tokensForComparison(first).toSet()
        val secondTokens = SpeechTextTools.tokensForComparison(second).toSet()
        if (firstTokens.isEmpty() || secondTokens.isEmpty()) return firstTokens == secondTokens

        val shared = firstTokens.count { it in secondTokens }.toDouble()
        val forward = shared / firstTokens.size
        val backward = shared / secondTokens.size
        return minOf(forward, backward) >= SAME_DOCUMENT_CONTAINMENT
    }

    /**
     * True when every readable line of [contained] also appears in [container].
     *
     * This is the truncated-response case seen from the other side: the model stopped early, the
     * user heard the first half of a page, and the retry returns the whole thing. Symmetric
     * similarity rejects that pairing because the two texts are very different in length, so the
     * retry would be read from the top again. One-directional coverage recognizes it as the same
     * page continuing.
     */
    fun covers(container: String, contained: String): Boolean {
        if (contained.isBlank()) return true
        return newContent(alreadySpoken = container, current = contained).isBlank()
    }

    /**
     * Fraction of [current]'s readable lines the user has already heard in [alreadySpoken].
     *
     * This is the measure that actually decides whether something is a re-read, and it replaced
     * document identity for that purpose. Identity asks "are these the same page", which is the
     * wrong question and a brittle one: a device log showed a page recognized once as three lines
     * and again as four, and again with slightly different line breaks. Each variant failed the
     * symmetric containment test, so each was treated as a brand new page and read out in full even
     * though the user had not moved. Coverage asks the question that matters — how much of this is
     * new — and answers it the same way regardless of how the lines were split.
     */
    fun coverageOf(alreadySpoken: String, current: String): Double {
        val currentLines = readableLines(current)
        if (currentLines.isEmpty()) return 1.0
        val remaining = readableLines(newContent(alreadySpoken = alreadySpoken, current = current))
        return 1.0 - (remaining.size.toDouble() / currentLines.size)
    }

    /** Speech-worthy lines: trimmed, non-blank, and containing at least one letter or digit. */
    fun readableLines(value: String): List<String> = value
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .lineSequence()
        .map(String::trim)
        .filter { line -> line.any(Char::isLetterOrDigit) }
        .toList()

    /** True when a block carries something worth speaking rather than stray separators. */
    fun isSpeakable(value: String): Boolean = value.any(Char::isLetterOrDigit)

    private class LineKey(line: String) {
        val normalized: String = SpeechTextTools.normalizeForComparison(line)
        val tokens: Set<String> = normalized.split(' ').filterTo(mutableSetOf()) { it.isNotBlank() }

        fun matches(other: LineKey): Boolean {
            if (normalized.isEmpty() || other.normalized.isEmpty()) return false
            if (normalized == other.normalized) return true
            if (normalized.contains(other.normalized) || other.normalized.contains(normalized)) return true
            if (tokens.size < MIN_LINE_TOKENS_FOR_FUZZY_MATCH ||
                other.tokens.size < MIN_LINE_TOKENS_FOR_FUZZY_MATCH
            ) {
                return false
            }
            val shared = tokens.count { it in other.tokens }.toDouble()
            val containment = shared / minOf(tokens.size, other.tokens.size)
            return containment >= SAME_LINE_SIMILARITY
        }
    }
}
