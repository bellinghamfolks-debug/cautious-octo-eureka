package com.abdullah.visionbridge.data.gemini

/**
 * Gemini streams plain text through SSE. The prompt requires one metadata line followed by the
 * visual result. This parser hides the metadata line and exposes only ordered speakable text.
 */
class GeminiStreamAccumulator {
    private val preamble = StringBuilder()
    private val fullTextBuilder = StringBuilder()
    private var headerResolved = false

    var language: String = "und"
        private set
    var urgent: Boolean = false
        private set

    val fullText: String
        get() = fullTextBuilder.toString().trim()

    /** Returns newly available body text after removing the protocol header. */
    fun append(delta: String): String {
        if (delta.isEmpty()) return ""
        if (headerResolved) {
            fullTextBuilder.append(delta)
            return delta
        }

        preamble.append(delta)
        val newlineIndex = preamble.indexOf("\n")
        if (newlineIndex < 0 && preamble.length < MAX_HEADER_LENGTH) return ""

        if (newlineIndex >= 0) {
            val firstLine = preamble.substring(0, newlineIndex).trimEnd('\r').trim()
            val remainder = preamble.substring(newlineIndex + 1)
            if (firstLine.startsWith(META_PREFIX)) {
                parseMetadata(firstLine)
                headerResolved = true
                preamble.clear()
                fullTextBuilder.append(remainder)
                return remainder
            }
        }

        // Robust fallback: if the model omits or damages the header, do not lose user-visible text.
        headerResolved = true
        val fallback = preamble.toString()
        preamble.clear()
        fullTextBuilder.append(fallback)
        return fallback
    }

    /** Returns any text that was still waiting for a header when the stream ended. */
    fun finish(): String {
        if (headerResolved || preamble.isEmpty()) return ""
        headerResolved = true
        val remainder = preamble.toString()
        preamble.clear()
        fullTextBuilder.append(remainder)
        return remainder
    }

    private fun parseMetadata(line: String) {
        line.split('|').drop(1).forEach { field ->
            val separator = field.indexOf('=')
            if (separator <= 0) return@forEach
            val key = field.substring(0, separator).trim().lowercase()
            val value = field.substring(separator + 1).trim()
            when (key) {
                "language" -> language = value.ifBlank { "und" }
                "urgent" -> urgent = value.equals("true", ignoreCase = true) || value == "1"
            }
        }
    }

    private companion object {
        const val META_PREFIX = "META|"
        const val MAX_HEADER_LENGTH = 220
    }
}

/**
 * Converts arbitrary SSE deltas into natural speech blocks. Words are never emitted alone merely
 * because the network divided them into separate events.
 */
class StreamingSpeechBuffer {
    private val pending = StringBuilder()

    fun append(delta: String, urgent: Boolean): List<String> {
        if (delta.isNotEmpty()) pending.append(delta)
        return drain(force = false, urgent = urgent)
    }

    fun finish(): List<String> = drain(force = true, urgent = false)

    private fun drain(force: Boolean, urgent: Boolean): List<String> {
        val output = mutableListOf<String>()
        while (pending.isNotEmpty()) {
            val boundary = findNaturalBoundary(urgent)
            if (boundary <= 0) {
                if (force) emit(pending.length, output)
                break
            }
            emit(boundary, output)
        }
        return output
    }

    private fun findNaturalBoundary(urgent: Boolean): Int {
        var completeSentences = 0
        for (index in pending.indices) {
            val character = pending[index]
            val visibleLength = pending.substring(0, index + 1).trim().length
            if (character in STRONG_BOUNDARIES) {
                completeSentences++
                val minimum = if (urgent) MIN_URGENT_SENTENCE_CHARS else MIN_SENTENCE_CHARS
                if (visibleLength >= minimum || completeSentences >= MAX_SENTENCES_PER_BLOCK) {
                    return index + 1
                }
            }
            if (character in CLAUSE_BOUNDARIES && visibleLength >= MIN_CLAUSE_CHARS) {
                return index + 1
            }
        }

        if (pending.length >= MAX_BLOCK_CHARS) {
            val preferred = pending.lastIndexOf(' ', startIndex = MAX_BLOCK_CHARS)
            // A long URL, identifier, or OCR token without spaces must remain intact. It is safer
            // to wait for stream completion than to pronounce half a word.
            return if (preferred >= MIN_SENTENCE_CHARS) preferred + 1 else -1
        }
        return -1
    }

    private fun emit(boundary: Int, output: MutableList<String>) {
        val value = pending.substring(0, boundary).trim()
        pending.delete(0, boundary)
        while (pending.isNotEmpty() && pending.first().isWhitespace()) pending.deleteCharAt(0)
        if (value.isNotEmpty()) output += value
    }

    private companion object {
        val STRONG_BOUNDARIES = setOf('.', '!', '?', '؟', '\n')
        val CLAUSE_BOUNDARIES = setOf('،', ',', '؛', ';', ':')
        const val MIN_URGENT_SENTENCE_CHARS = 8
        const val MIN_SENTENCE_CHARS = 28
        const val MIN_CLAUSE_CHARS = 60
        const val MAX_SENTENCES_PER_BLOCK = 3
        const val MAX_BLOCK_CHARS = 220
    }
}
