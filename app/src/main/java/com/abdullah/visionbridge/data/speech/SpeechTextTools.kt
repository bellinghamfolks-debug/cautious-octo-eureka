package com.abdullah.visionbridge.data.speech

import java.text.Normalizer
import java.util.Locale

enum class SpeechLanguage(val locale: Locale) {
    ARABIC(Locale.Builder().setLanguage("ar").setRegion("SA").build()),
    ENGLISH(Locale.US),
}

data class SpeechSegment(val text: String, val language: SpeechLanguage)

object SpeechTextTools {
    private val arabicRange = Regex("[\\u0600-\\u06FF\\u0750-\\u077F\\u08A0-\\u08FF]")
    private val latinRange = Regex("[A-Za-z]")
    private val arabicMarks = Regex("[\\u0610-\\u061A\\u064B-\\u065F\\u0670\\u06D6-\\u06ED]")
    private val comparisonSeparators = Regex("[\\p{P}\\p{S}\\s]+")

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

    /**
     * Canonical form for OCR comparison. It intentionally ignores punctuation, spacing,
     * Arabic diacritics, tatweel, common Alef variants, and Arabic/Latin digit shapes.
     */
    fun normalizeForComparison(text: String): String {
        val unicodeNormalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
        val canonical = buildString(unicodeNormalized.length) {
            unicodeNormalized.lowercase(Locale.ROOT).forEach { character ->
                append(
                    when (character) {
                        'أ', 'إ', 'آ', 'ٱ' -> 'ا'
                        'ى' -> 'ي'
                        'ـ' -> ' '
                        '٠' -> '0'
                        '١' -> '1'
                        '٢' -> '2'
                        '٣' -> '3'
                        '٤' -> '4'
                        '٥' -> '5'
                        '٦' -> '6'
                        '٧' -> '7'
                        '٨' -> '8'
                        '٩' -> '9'
                        '۰' -> '0'
                        '۱' -> '1'
                        '۲' -> '2'
                        '۳' -> '3'
                        '۴' -> '4'
                        '۵' -> '5'
                        '۶' -> '6'
                        '۷' -> '7'
                        '۸' -> '8'
                        '۹' -> '9'
                        else -> character
                    }
                )
            }
        }
        return canonical
            .replace(arabicMarks, "")
            .replace(comparisonSeparators, " ")
            .trim()
    }

    fun tokensForComparison(text: String): List<String> =
        normalizeForComparison(text).split(' ').filter { it.isNotBlank() }

}
