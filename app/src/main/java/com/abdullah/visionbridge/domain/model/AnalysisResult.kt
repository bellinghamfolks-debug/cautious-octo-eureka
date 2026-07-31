package com.abdullah.visionbridge.domain.model

/**
 * Which engine produced a result. `LOCAL_OCR` is PP-OCRv5 on the device; `GEMINI` is the cloud.
 * There is no third value: the on-device engine reads text and never describes a scene, so a scene
 * description always comes from the cloud.
 */
enum class AnalysisSource { LOCAL_OCR, GEMINI }

data class AnalysisResult(
    val text: String,
    val source: AnalysisSource,
    val language: String = "und",
    val urgent: Boolean = false,
    val createdAtMillis: Long = System.currentTimeMillis(),
)
