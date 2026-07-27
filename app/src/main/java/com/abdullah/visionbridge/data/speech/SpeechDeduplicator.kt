package com.abdullah.visionbridge.data.speech

class SpeechDeduplicator(
    private val duplicateWindowMs: Long = 8_000L,
    private val similarityThreshold: Double = 0.92,
) {
    private var lastText: String = ""
    private var lastSpokenAt: Long = 0L

    @Synchronized
    fun shouldSpeak(text: String, urgent: Boolean, now: Long = System.currentTimeMillis()): Boolean {
        val normalized = SpeechTextTools.normalizeForComparison(text)
        if (normalized.isBlank()) return false
        if (urgent) {
            lastText = normalized
            lastSpokenAt = now
            return true
        }

        val withinWindow = now - lastSpokenAt < duplicateWindowMs
        val duplicate = withinWindow && similarity(lastText, normalized) >= similarityThreshold
        if (!duplicate) {
            lastText = normalized
            lastSpokenAt = now
        }
        return !duplicate
    }

    private fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val distance = levenshtein(a, b)
        return 1.0 - distance.toDouble() / maxOf(a.length, b.length)
    }

    private fun levenshtein(a: String, b: String): Int {
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
}
