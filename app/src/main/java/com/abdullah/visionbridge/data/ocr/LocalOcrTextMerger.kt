package com.abdullah.visionbridge.data.ocr

/** Pure text helpers shared by on-device OCR and speech de-duplication. */
internal object LocalOcrTextMerger {
    private val cameraChrome = Regex(
        "^(?:LEICA|VIBR?|[0-9]+(?:\\.[0-9]+)?x?|lx)$",
        RegexOption.IGNORE_CASE,
    )

    fun merge(arabic: String, latin: String): String {
        val cleanArabic = clean(arabic)
        val cleanLatin = clean(latin)
        if (cleanArabic.isBlank()) return cleanLatin
        if (cleanLatin.isBlank()) return cleanArabic

        val arabicCount = cleanArabic.count(::isArabicLetter)
        if (arabicCount < MIN_ARABIC_LETTERS) {
            return listOf(cleanArabic, cleanLatin)
                .maxByOrNull(::evidenceScore)
                .orEmpty()
        }

        val arabicLines = usefulLines(cleanArabic).toMutableList()
        val normalizedArabic = arabicLines.mapTo(mutableSetOf(), ::normalizeLine)
        usefulLines(cleanLatin).forEach { line ->
            val normalized = normalizeLine(line)
            if (
                normalized.isNotBlank() &&
                normalized !in normalizedArabic &&
                normalizedArabic.none { existing -> existing.contains(normalized) || normalized.contains(existing) }
            ) {
                arabicLines += line
                normalizedArabic += normalized
            }
        }
        return arabicLines.joinToString("\n").trim()
    }

    /** Returns only lines that have not already been spoken for the same visual generation. */
    fun novel(previous: String, current: String): String {
        val old = usefulLines(previous).mapTo(mutableSetOf(), ::normalizeLine)
        return usefulLines(current)
            .filter { line ->
                val normalized = normalizeLine(line)
                normalized.isNotBlank() && old.none { existing ->
                    existing == normalized ||
                        (existing.length >= MIN_CONTAINMENT_CHARS && normalized.contains(existing)) ||
                        (normalized.length >= MIN_CONTAINMENT_CHARS && existing.contains(normalized))
                }
            }
            .joinToString("\n")
            .trim()
    }

    fun removeAlreadySpoken(candidate: String, spoken: String): String = novel(spoken, candidate)

    private fun clean(value: String): String = value
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .lineSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .filterNot(cameraChrome::matches)
        .joinToString("\n")
        .trim()

    private fun usefulLines(value: String): List<String> = clean(value)
        .lineSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .toList()

    private fun normalizeLine(value: String): String = value
        .lowercase()
        .replace(Regex("[\\p{P}\\p{S}\\s]+"), "")
        .replace('أ', 'ا')
        .replace('إ', 'ا')
        .replace('آ', 'ا')
        .replace('ى', 'ي')
        .replace('ة', 'ه')

    private fun evidenceScore(value: String): Int = usefulLines(value)
        .sumOf { line -> line.length + LINE_BONUS }

    private fun isArabicLetter(char: Char): Boolean =
        char in '\u0621'..'\u064A' || char in '\u066E'..'\u06D3'

    private const val MIN_ARABIC_LETTERS = 3
    private const val MIN_CONTAINMENT_CHARS = 7
    private const val LINE_BONUS = 8
}
