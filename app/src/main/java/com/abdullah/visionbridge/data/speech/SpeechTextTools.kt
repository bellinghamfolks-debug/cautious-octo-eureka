package com.abdullah.visionbridge.data.speech

import java.util.Locale

enum class SpeechLanguage(val locale: Locale) {
    ARABIC(Locale.Builder().setLanguage("ar").setRegion("SA").build()),
    ENGLISH(Locale.US),
}

data class SpeechSegment(val text: String, val language: SpeechLanguage)

object SpeechTextTools {
    private val arabicRange = Regex("[\\u0600-\\u06FF\\u0750-\\u077F\\u08A0-\\u08FF]")
    private val latinRange = Regex("[A-Za-z]")

    fun segment(text: String): List<SpeechSegment> {
        val tokens = text.trim().split(Regex("(?<=\\s)|(?=\\s)|(?<=[.!?؟،,:;\\n])"))
        if (tokens.isEmpty()) return emptyList()

        val output = mutableListOf<SpeechSegment>()
        val current = StringBuilder()
        var currentLanguage: SpeechLanguage? = null

        fun flush() {
            val value = current.toString().trim()
            if (value.isNotEmpty() && currentLanguage != null) {
                output += SpeechSegment(value, currentLanguage!!)
            }
            current.clear()
        }

        for (token in tokens) {
            val language = when {
                arabicRange.containsMatchIn(token) -> SpeechLanguage.ARABIC
                latinRange.containsMatchIn(token) -> SpeechLanguage.ENGLISH
                else -> currentLanguage ?: dominantLanguage(text)
            }
            if (currentLanguage != null && currentLanguage != language) flush()
            currentLanguage = language
            current.append(token)
        }
        flush()
        return output
    }

    fun dominantLanguage(text: String): SpeechLanguage {
        val arabic = text.count { it in '\u0600'..'\u06FF' || it in '\u0750'..'\u077F' }
        val latin = text.count { it in 'A'..'Z' || it in 'a'..'z' }
        return if (arabic >= latin) SpeechLanguage.ARABIC else SpeechLanguage.ENGLISH
    }

    fun normalizeForComparison(text: String): String = text
        .lowercase(Locale.ROOT)
        .replace(Regex("[\\p{Punct}\\s]+"), " ")
        .trim()
}
