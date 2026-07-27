package com.abdullah.visionbridge.domain.model

enum class AnalysisSource { LOCAL_OCR, GEMINI }

data class AnalysisResult(
    val text: String,
    val source: AnalysisSource,
    val language: String = "und",
    val urgent: Boolean = false,
    val createdAtMillis: Long = System.currentTimeMillis(),
)
