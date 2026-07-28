package com.abdullah.visionbridge.data.ocr

import java.util.Locale
import kotlin.math.abs

/**
 * Filters Gemini's Latin tokens against ML Kit evidence extracted from the exact same frame.
 * Arabic remains handled by Gemini's strict visual-confidence protocol because ML Kit's bundled
 * recognizer does not support Arabic script.
 */
class SameFrameOcrEvidenceFilter {
    private val latinToken = Regex("[A-Za-z0-9][A-Za-z0-9@._:/+\\-]*")
    private val repeatedUnknown = Regex("(?:\\[غير واضح])(?:\\s*\\[غير واضح])+")

    fun filter(geminiText: String, localEvidence: String): String {
        if (geminiText.isBlank() || localEvidence.isBlank()) return geminiText.trim()

        val localTokens = latinToken.findAll(localEvidence)
            .map { normalize(it.value) }
            .filter { it.isNotBlank() }
            .toSet()
        if (localTokens.isEmpty()) return geminiText.trim()

        val filtered = latinToken.replace(geminiText) { match ->
            val candidate = normalize(match.value)
            if (candidate.isBlank() || supported(candidate, localTokens)) {
                match.value
            } else {
                "[غير واضح]"
            }
        }
        return filtered
            .replace(repeatedUnknown, "[غير واضح]")
            .replace(Regex("\\s+([،,.!?؟؛;:])"), "\$1")
            .replace(Regex("[ \\t]+"), " ")
            .trim()
    }

    private fun supported(candidate: String, localTokens: Set<String>): Boolean {
        if (candidate in localTokens) return true
        if (candidate.length <= 2) return localTokens.any { candidate in it || it in candidate }

        return localTokens.any { evidence ->
            val lengthRatio = minOf(candidate.length, evidence.length).toDouble() /
                maxOf(candidate.length, evidence.length).coerceAtLeast(1)
            lengthRatio >= 0.60 && similarity(candidate, evidence) >= MIN_SIMILARITY
        }
    }

    private fun normalize(value: String): String =
        value.lowercase(Locale.ROOT).trim('.', ',', ':', ';', '-', '_', '/', '\\')

    private fun similarity(first: String, second: String): Double {
        if (first == second) return 1.0
        if (abs(first.length - second.length) > maxOf(first.length, second.length) * 0.45) return 0.0
        val distance = levenshtein(first, second)
        return 1.0 - distance.toDouble() / maxOf(first.length, second.length)
    }

    private fun levenshtein(first: String, second: String): Int {
        var previous = IntArray(second.length + 1) { it }
        for (i in first.indices) {
            val current = IntArray(second.length + 1)
            current[0] = i + 1
            for (j in second.indices) {
                current[j + 1] = minOf(
                    current[j] + 1,
                    previous[j + 1] + 1,
                    previous[j] + if (first[i] == second[j]) 0 else 1,
                )
            }
            previous = current
        }
        return previous[second.length]
    }

    private companion object {
        const val MIN_SIMILARITY = 0.76
    }
}
