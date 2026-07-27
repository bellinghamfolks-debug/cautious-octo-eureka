package com.abdullah.visionbridge.data.speech

import java.util.ArrayDeque
import kotlin.math.abs

/**
 * Prevents repeated speech even when OCR changes punctuation, spacing, Arabic diacritics,
 * digit shapes, or a small number of characters between frames.
 *
 * Comparing only the immediately previous sentence for a few seconds is insufficient for a
 * live camera feed. This implementation compares against a bounded history and combines
 * character similarity with token overlap.
 */
class SpeechDeduplicator(
    private val duplicateWindowMs: Long = 5 * 60_000L,
    private val urgentDuplicateWindowMs: Long = 20_000L,
    private val similarityThreshold: Double = 0.84,
    private val maxHistoryEntries: Int = 24,
) {
    private data class Entry(
        val normalized: String,
        val tokens: Set<String>,
        val spokenAt: Long,
        val urgent: Boolean,
    )

    private val history = ArrayDeque<Entry>()

    /** Returns the original text when it is novel enough to speak, otherwise null. */
    @Synchronized
    fun filter(text: String, urgent: Boolean, now: Long = System.currentTimeMillis()): String? {
        val normalized = SpeechTextTools.normalizeForComparison(text)
        if (normalized.isBlank()) return null
        val tokens = SpeechTextTools.tokensForComparison(normalized).toSet()

        removeExpired(now)
        val duplicate = history.any { previous ->
            // A newly urgent result may interrupt an earlier non-urgent reading once. Repeated
            // urgent warnings are still throttled so a static obstacle does not chatter.
            if (urgent && !previous.urgent) return@any false

            val allowedAge = if (urgent && previous.urgent) {
                urgentDuplicateWindowMs
            } else {
                duplicateWindowMs
            }
            now - previous.spokenAt < allowedAge && isDuplicate(previous, normalized, tokens)
        }
        if (duplicate) return null

        history.addLast(Entry(normalized, tokens, now, urgent))
        while (history.size > maxHistoryEntries) history.removeFirst()
        return text.trim()
    }

    @Synchronized
    fun shouldSpeak(text: String, urgent: Boolean, now: Long = System.currentTimeMillis()): Boolean =
        filter(text, urgent, now) != null

    @Synchronized
    fun reset() = history.clear()

    private fun removeExpired(now: Long) {
        val retention = maxOf(duplicateWindowMs, urgentDuplicateWindowMs)
        while (history.isNotEmpty() && now - history.first.spokenAt >= retention) {
            history.removeFirst()
        }
    }

    private fun isDuplicate(previous: Entry, current: String, currentTokens: Set<String>): Boolean {
        if (previous.normalized == current) return true

        val shorterLength = minOf(previous.normalized.length, current.length).toDouble()
        val longerLength = maxOf(previous.normalized.length, current.length).toDouble()
        val lengthRatio = if (longerLength == 0.0) 1.0 else shorterLength / longerLength

        val intersection = previous.tokens.count { it in currentTokens }.toDouble()
        val union = (previous.tokens + currentTokens).size.toDouble().coerceAtLeast(1.0)
        val smallerTokenSet = minOf(previous.tokens.size, currentTokens.size).toDouble().coerceAtLeast(1.0)
        val jaccard = intersection / union
        val containment = intersection / smallerTokenSet

        val characterSimilarity = if (lengthRatio >= 0.60) {
            characterSimilarity(previous.normalized, current)
        } else {
            0.0
        }

        return characterSimilarity >= similarityThreshold ||
            (lengthRatio >= 0.68 && jaccard >= 0.72) ||
            (lengthRatio >= 0.72 && containment >= 0.90)
    }

    private fun characterSimilarity(firstText: String, secondText: String): Double {
        if (firstText == secondText) return 1.0
        val first = firstText.take(MAX_COMPARISON_CHARACTERS)
        val second = secondText.take(MAX_COMPARISON_CHARACTERS)
        if (first.isEmpty() || second.isEmpty()) return 0.0
        if (abs(first.length - second.length) > maxOf(first.length, second.length) * 0.45) return 0.0

        val distance = levenshtein(first, second)
        return 1.0 - distance.toDouble() / maxOf(first.length, second.length)
    }

    private fun levenshtein(first: String, second: String): Int {
        // Put the shorter string on the horizontal axis to reduce allocation.
        val a: String
        val b: String
        if (first.length < second.length) {
            a = second
            b = first
        } else {
            a = first
            b = second
        }

        var previous = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            val current = IntArray(b.length + 1)
            current[0] = i + 1
            for (j in b.indices) {
                current[j + 1] = minOf(
                    current[j] + 1,
                    previous[j + 1] + 1,
                    previous[j] + if (a[i] == b[j]) 0 else 1,
                )
            }
            previous = current
        }
        return previous[b.length]
    }

    private companion object {
        const val MAX_COMPARISON_CHARACTERS = 2_000
    }
}
