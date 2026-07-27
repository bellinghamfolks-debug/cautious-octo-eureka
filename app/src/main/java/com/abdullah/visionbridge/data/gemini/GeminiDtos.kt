package com.abdullah.visionbridge.data.gemini

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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

@Serializable
data class GenerationConfig(
    @SerialName("maxOutputTokens") val maxOutputTokens: Int = 700,
    @SerialName("responseMimeType") val responseMimeType: String = "application/json",
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

@Serializable
data class ParsedVisionResponse(
    val spokenText: String = "",
    val detectedLanguage: String = "und",
    val urgent: Boolean = false,
)
