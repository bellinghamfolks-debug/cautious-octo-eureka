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
    /**
     * One sentence about the surroundings, produced by the same request that produced [text] when
     * reading-with-description is on. Empty otherwise. It is kept apart from [text] rather than
     * appended to it because whether it is worth speaking depends on what the user is looking at by
     * the time the reading finishes — a question only the coordinator can answer.
     */
    val sceneTail: String = "",
)
