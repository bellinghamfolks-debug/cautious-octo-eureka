package com.abdullah.visionbridge.data.vision

import java.text.Normalizer
import java.util.Locale

/** Optical evidence is independent of the model's self-reported confidence. */
object TextGroundingGate {
    data class Evidence(val text: String, val confidence: Float, val detectedBoxes: Int)
    data class Decision(val accepted: Boolean, val retry: Boolean, val reason: String)

    fun evaluate(text: String, confidence: Int, legible: Boolean, inferred: Boolean,
                 optical: Evidence): Decision {
        val words = tokens(text)
        if (text.trim().matches(Regex("(?is)^(NO_TEXT|NO_CHANGE)(\\b.*)?$")) || words.isEmpty()) {
            return Decision(false, true, if (optical.detectedBoxes > 0 || optical.text.isNotBlank())
                "no_text_conflicts_with_optical_evidence" else "no_reliable_text")
        }
        if (!legible || inferred || confidence < 90) return Decision(false, true, "model_quality_rejected")
        if (optical.confidence < .80f || optical.text.isBlank()) return Decision(false, true, "optical_evidence_insufficient")
        val remaining = tokens(optical.text).groupingBy { it }.eachCount().toMutableMap()
        for (word in words) {
            val available = remaining[word] ?: 0
            if (available == 0) return Decision(false, true, "ungrounded_token")
            remaining[word] = available - 1
        }
        return Decision(true, false, "all_tokens_optically_supported")
    }

    private fun tokens(text: String): List<String> {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT).replace(Regex("[\\p{M}ـ]"), "")
            .map { ch -> if (ch.isDigit()) Character.digit(ch, 10).let {
                if (it >= 0) ('0'.code + it).toChar() else ch
            } else ch }.joinToString("")
        return Regex("[\\p{L}\\p{N}]+").findAll(normalized).map { it.value }.toList()
    }
}
