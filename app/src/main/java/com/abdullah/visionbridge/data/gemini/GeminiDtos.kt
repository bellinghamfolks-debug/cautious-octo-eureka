package com.abdullah.visionbridge.data.gemini

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class GenerateContentRequest(
    val contents: List<GeminiContent>,
    @SerialName("generationConfig") val generationConfig: GenerationConfig,
)

@Serializable
data class GeminiContent(val role: String = "user", val parts: List<GeminiPart>)

@Serializable
data class GeminiPart(
    val text: String? = null,
    @SerialName("inline_data") val inlineData: InlineData? = null,
)

@Serializable
data class InlineData(
    @SerialName("mime_type") val mimeType: String,
    val data: String,
)

/**
 * Stable OCR uses HIGH media resolution, while comprehensive scene descriptions use MEDIUM with a
 * compact output budget. Those are the smart-quality lanes and use `low`, the fastest level accepted
 * by Gemini 3.8/3.7. FAST_TEXT and BRIEF scenes are routed to Flash-Lite and retain `minimal`.
 */
internal fun adaptiveThinkingLevel(maxOutputTokens: Int, mediaResolution: String): String = when {
    mediaResolution == "MEDIA_RESOLUTION_HIGH" -> "low"
    mediaResolution == "MEDIA_RESOLUTION_MEDIUM" && maxOutputTokens <= 400 -> "low"
    else -> "minimal"
}

@Serializable
data class ThinkingConfig(
    @SerialName("thinkingLevel") val thinkingLevel: String,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class GenerationConfig(
    @SerialName("maxOutputTokens") val maxOutputTokens: Int = 700,
    @SerialName("responseMimeType") val responseMimeType: String = "text/plain",
    // Kept in the constructor so existing call sites remain source-compatible, but intentionally
    // excluded from Gemini 3.x payloads. Model defaults are safer than legacy sampling overrides.
    @Transient val temperature: Double = 0.0,
    @SerialName("mediaResolution") val mediaResolution: String = "MEDIA_RESOLUTION_HIGH",
    // kotlinx.serialization omits default-valued properties unless explicitly forced. Gemini 3.8
    // must actually receive `low`; fast Flash-Lite lanes must actually receive `minimal`.
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @SerialName("thinkingConfig") val thinkingConfig: ThinkingConfig = ThinkingConfig(
        thinkingLevel = adaptiveThinkingLevel(maxOutputTokens, mediaResolution),
    ),
)

@Serializable
data class GenerateContentResponse(
    val candidates: List<Candidate> = emptyList(),
    val error: GeminiError? = null,
)

@Serializable
data class Candidate(val content: CandidateContent? = null)

@Serializable
data class CandidateContent(val parts: List<ResponsePart> = emptyList())

@Serializable
data class ResponsePart(val text: String? = null)

@Serializable
data class GeminiError(val code: Int? = null, val message: String? = null)
