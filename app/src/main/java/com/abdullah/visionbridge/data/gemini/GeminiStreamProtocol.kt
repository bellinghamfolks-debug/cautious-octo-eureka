package com.abdullah.visionbridge.data.gemini

/**
 * Parses Gemini's small streaming protocol. Scene descriptions require one META line. OCR can also
 * require a QUALITY line, allowing the app to reject uncertain or inferred transcription before
 * any body text is displayed or spoken. All evidence belongs to the same captured frame.
 */
class GeminiStreamAccumulator(
    private val requireQualityHeader: Boolean = false,
) {
    private val preamble = StringBuilder()
    private val fullTextBuilder = StringBuilder()
    private var headerResolved = false

    var language: String = "und"
        private set
    var urgent: Boolean = false
        private set
    var confidence: Int = if (requireQualityHeader) 0 else 100
        private set
    var legible: Boolean = !requireQualityHeader
        private set
    var inferred: Boolean = false
        private set

    val ocrAccepted: Boolean
        get() = !requireQualityHeader || (legible && !inferred && confidence >= MIN_OCR_CONFIDENCE)

    val fullText: String
        get() = fullTextBuilder.toString().trim()

    fun append(delta: String): String {
        if (delta.isEmpty()) return ""
        if (headerResolved) return appendBody(delta)

        preamble.append(delta)
        val headerEnd = if (requireQualityHeader) secondNewlineIndex(preamble) else preamble.indexOf("\n")
        if (headerEnd < 0 && preamble.length < MAX_HEADER_LENGTH) return ""

        if (headerEnd >= 0) {
            val headerText = preamble.substring(0, headerEnd).trimEnd('\r', '\n')
            val lines = headerText.lines().map { it.trimEnd('\r').trim() }
            val remainder = preamble.substring(headerEnd + 1)
            val metadataValid = lines.firstOrNull()?.startsWith(META_PREFIX) == true
            val qualityValid = !requireQualityHeader || (
                lines.size >= 2 && lines[1].startsWith(QUALITY_PREFIX) && parseQuality(lines[1])
                )

            if (metadataValid) parseMetadata(lines.first())
            headerResolved = true
            preamble.clear()

            if (!metadataValid || !qualityValid) {
                if (requireQualityHeader) {
                    legible = false
                    inferred = true
                    return ""
                }
                return appendBody(headerText + if (remainder.isNotEmpty()) "\n$remainder" else "")
            }
            return appendBody(remainder)
        }

        headerResolved = true
        val fallback = preamble.toString()
        preamble.clear()
        return if (requireQualityHeader) {
            legible = false
            inferred = true
            ""
        } else {
            appendBody(fallback)
        }
    }

    fun finish(): String {
        if (headerResolved || preamble.isEmpty()) return ""
        headerResolved = true
        val remainder = preamble.toString()
        preamble.clear()
        return if (requireQualityHeader) {
            legible = false
            inferred = true
            ""
        } else {
            appendBody(remainder)
        }
    }

    private fun appendBody(text: String): String {
        if (text.isEmpty() || !ocrAccepted) return ""
        fullTextBuilder.append(text)
        return text
    }

    private fun parseMetadata(line: String) {
        fields(line).forEach { (key, value) ->
            when (key) {
                "language" -> language = value.ifBlank { "und" }
                "urgent" -> urgent = value.toBooleanValue()
            }
        }
    }

    private fun parseQuality(line: String): Boolean {
        val parsed = fields(line)
        legible = parsed["legible"]?.toBooleanValue() == true
        inferred = parsed["inferred"]?.toBooleanValue() == true
        confidence = parsed["confidence"]?.toIntOrNull()?.coerceIn(0, 100) ?: 0
        return parsed.keys.containsAll(setOf("legible", "inferred", "confidence"))
    }

    private fun fields(line: String): Map<String, String> = buildMap {
        line.split('|').drop(1).forEach { field ->
            val separator = field.indexOf('=')
            if (separator > 0) {
                put(
                    field.substring(0, separator).trim().lowercase(),
                    field.substring(separator + 1).trim(),
                )
            }
        }
    }

    private fun String.toBooleanValue(): Boolean =
        equals("true", ignoreCase = true) || this == "1" || equals("yes", ignoreCase = true)

    private fun secondNewlineIndex(value: CharSequence): Int {
        val first = value.indexOf('\n')
        return if (first < 0) -1 else value.indexOf('\n', first + 1)
    }

    private companion object {
        const val META_PREFIX = "META|"
        const val QUALITY_PREFIX = "QUALITY|"
        const val MIN_OCR_CONFIDENCE = 82
        const val MAX_HEADER_LENGTH = 420
    }
}

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
        const val MIN_URGENT_SENTENCE_CHARS = 6
        const val MIN_SENTENCE_CHARS = 10
        const val MIN_CLAUSE_CHARS = 36
        const val MAX_SENTENCES_PER_BLOCK = 3
        const val MAX_BLOCK_CHARS = 160
    }
}
