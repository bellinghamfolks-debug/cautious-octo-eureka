package com.abdullah.visionbridge.data.gemini

/**
 * Parses Gemini's small streaming protocol. Scene descriptions require one META line. OCR can also
 * require a QUALITY line, allowing the app to reject uncertain or inferred transcription before
 * any body text is displayed or spoken. All evidence belongs to the same captured frame.
 */
class GeminiStreamAccumulator(
    private val requireQualityHeader: Boolean = false,
    /**
     * Accept a `SCENE|` section after the transcription, and hold it back from the streamed body.
     *
     * This is what makes reading-with-a-description one request instead of two. The transcription
     * still streams to speech the instant it arrives, because that is what someone pointing at a
     * label is waiting for; the description accumulates silently behind the marker and is offered
     * separately, so the caller can speak it as a tail, or drop it if the user has already moved
     * on. Mixing the two into one stream is what would make the feature a jumble.
     */
    private val acceptSceneTail: Boolean = false,
) {
    private val preamble = StringBuilder()
    private val fullTextBuilder = StringBuilder()
    private val sceneTailBuilder = StringBuilder()
    private var headerResolved = false

    /**
     * A partial marker held back until enough characters arrive to tell `SCENE|` from ordinary
     * text that merely begins with `S`. Without this the marker would be spoken one letter at a
     * time as it streamed in.
     */
    private val markerCandidate = StringBuilder()
    private var inSceneTail = false

    /** The description that followed the transcription, empty when there was none. */
    val sceneTail: String
        get() = sceneTailBuilder.toString().trim()

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
                // A malformed or truncated protocol line is internal control data, never user text.
                if (headerText.trimStart().startsWith(META_PREFIX)) return ""
                return appendBody(headerText + if (remainder.isNotEmpty()) "\n$remainder" else "")
            }
            return appendBody(remainder)
        }

        headerResolved = true
        val fallback = preamble.toString()
        preamble.clear()
        if (fallback.trimStart().startsWith(META_PREFIX) || fallback.trimStart().startsWith(QUALITY_PREFIX)) {
            if (requireQualityHeader) {
                legible = false
                inferred = true
            }
            return ""
        }
        return if (requireQualityHeader) {
            legible = false
            inferred = true
            ""
        } else {
            appendBody(fallback)
        }
    }

    fun finish(): String {
        if (headerResolved || preamble.isEmpty()) return releaseMarkerCandidate()
        headerResolved = true
        val remainder = preamble.toString()
        preamble.clear()

        // A cancelled SSE stream frequently ends halfway through "META|language=...". The previous
        // fallback exposed that protocol fragment on screen and through TTS. Control lines are now
        // discarded even when the request closes before its first newline.
        val trimmed = remainder.trimStart()
        if (trimmed.startsWith(META_PREFIX) || trimmed.startsWith(QUALITY_PREFIX)) {
            if (requireQualityHeader) {
                legible = false
                inferred = true
            }
            return ""
        }

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
        if (!acceptSceneTail) {
            fullTextBuilder.append(text)
            return text
        }
        return appendSectioned(text)
    }

    /**
     * Routes each character to the transcription or to the held-back description, splitting on the
     * `SCENE|` marker.
     *
     * The marker can be delivered across any number of SSE deltas — `SC`, then `ENE|` — so a
     * prefix of it is buffered rather than emitted. If the buffered text turns out not to be the
     * marker after all it is released unchanged, because a page that genuinely begins a line with
     * "SCENE" is a page, not a protocol.
     */
    private fun appendSectioned(text: String): String {
        val emitted = StringBuilder()
        for (character in text) {
            if (inSceneTail) {
                sceneTailBuilder.append(character)
                continue
            }

            // The line break that ends the transcription belongs to the marker, not to the page, so
            // it is held with it rather than emitted first and trimmed afterwards. Emitting it and
            // then removing it from the accumulated text would break the one invariant the caller
            // relies on: that the deltas it was handed add up to `fullText`.
            if (markerCandidate.isEmpty() && !couldStartMarker(character)) {
                fullTextBuilder.append(character)
                emitted.append(character)
                continue
            }

            markerCandidate.append(character)
            when {
                markerCandidate.toString().trimStart('\n', '\r') == SCENE_PREFIX -> {
                    inSceneTail = true
                    markerCandidate.clear()
                }
                isMarkerPrefix(markerCandidate) -> Unit // still could be the marker
                else -> emitted.append(releaseMarkerCandidate())
            }
        }
        return emitted.toString()
    }

    /** A line break may precede the marker, so both it and the marker's first letter open one. */
    private fun couldStartMarker(character: Char): Boolean =
        character == '\n' || character == '\r' || character == SCENE_PREFIX[0]

    /** Newlines, then as much of `SCENE|` as has arrived, and nothing else. */
    private fun isMarkerPrefix(candidate: CharSequence): Boolean {
        val rest = candidate.toString().trimStart('\n', '\r')
        return SCENE_PREFIX.startsWith(rest)
    }

    /** Whatever was still being held as a possible marker when the stream ended was ordinary text. */
    private fun releaseMarkerCandidate(): String {
        if (markerCandidate.isEmpty()) return ""
        val held = markerCandidate.toString()
        markerCandidate.clear()
        fullTextBuilder.append(held)
        return held
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

        /** Starts the description that follows a transcription in reading-with-description. */
        const val SCENE_PREFIX = "SCENE|"
        const val MIN_OCR_CONFIDENCE = 82
        const val MAX_HEADER_LENGTH = 420
    }
}

/**
 * Splits a streamed answer into speakable blocks.
 *
 * The block size is deliberately different for the two things this app says out loud. A scene
 * description is a warning that has to reach the user quickly, so it is cut at the first natural
 * pause. A page of text is a document, and cutting it at every comma turned a single screen into
 * dozens of tiny utterances that stuttered, queued badly and read as fragments. Documents are
 * therefore cut at sentence and line boundaries only, into far larger blocks.
 */
class StreamingSpeechBuffer(private val profile: Profile = Profile.RESPONSIVE) {
    enum class Profile {
        /** Scene description: shortest useful block wins, latency matters most. */
        RESPONSIVE,

        /** Text reading: whole sentences and lines, completeness and phrasing matter most. */
        DOCUMENT,
    }

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
        val minimumSentence = when {
            profile == Profile.DOCUMENT -> MIN_DOCUMENT_SENTENCE_CHARS
            urgent -> MIN_URGENT_SENTENCE_CHARS
            else -> MIN_SENTENCE_CHARS
        }
        val maximumBlock = if (profile == Profile.DOCUMENT) MAX_DOCUMENT_BLOCK_CHARS else MAX_BLOCK_CHARS
        val maximumSentences =
            if (profile == Profile.DOCUMENT) MAX_DOCUMENT_SENTENCES_PER_BLOCK else MAX_SENTENCES_PER_BLOCK

        var completeSentences = 0
        for (index in pending.indices) {
            val character = pending[index]
            val visibleLength = pending.substring(0, index + 1).trim().length
            if (character in STRONG_BOUNDARIES) {
                completeSentences++
                if (visibleLength >= minimumSentence || completeSentences >= maximumSentences) {
                    return index + 1
                }
            }
            // Clause splitting keeps a spoken warning responsive, but it is what shredded documents
            // into fragments, so a document is never cut at a comma.
            if (profile != Profile.DOCUMENT &&
                character in CLAUSE_BOUNDARIES &&
                visibleLength >= MIN_CLAUSE_CHARS
            ) {
                return index + 1
            }
        }

        if (pending.length >= maximumBlock) {
            val preferred = pending.lastIndexOf(' ', startIndex = maximumBlock)
            return if (preferred >= minimumSentence) preferred + 1 else -1
        }
        return -1
    }

    private fun emit(boundary: Int, output: MutableList<String>) {
        val value = pending.substring(0, boundary).trim()
        pending.delete(0, boundary)
        while (pending.isNotEmpty() && pending.first().isWhitespace()) pending.deleteCharAt(0)
        // A block of nothing but separators reached TTS as an audible stumble between sentences.
        if (value.any(Char::isLetterOrDigit)) output += value
    }

    private companion object {
        val STRONG_BOUNDARIES = setOf('.', '!', '?', '؟', '\n')
        val CLAUSE_BOUNDARIES = setOf('،', ',', '؛', ';', ':')
        const val MIN_URGENT_SENTENCE_CHARS = 6
        const val MIN_SENTENCE_CHARS = 10
        const val MIN_CLAUSE_CHARS = 36
        const val MAX_SENTENCES_PER_BLOCK = 3
        const val MAX_BLOCK_CHARS = 160

        const val MIN_DOCUMENT_SENTENCE_CHARS = 90
        const val MAX_DOCUMENT_SENTENCES_PER_BLOCK = 6
        const val MAX_DOCUMENT_BLOCK_CHARS = 320
    }
}
