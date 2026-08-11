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
    @SerialName("responseMimeType") val responseMimeType: String = "text/plain",
    val temperature: Double = 0.0,
    @SerialName("candidateCount") val candidateCount: Int = 1,
    @SerialName("mediaResolution") val mediaResolution: String = "MEDIA_RESOLUTION_HIGH",
    /**
     * Null means "do not send the field at all" — the serializer is configured with
     * `explicitNulls = false`. That matters because it is also the shape of the retry: a request
     * rejected for carrying this field is repeated without it.
     */
    @SerialName("thinkingConfig") val thinkingConfig: ThinkingConfig? = null,
)

/** See [TokenBudget] for why the level is set rather than left to the model's default. */
@Serializable
data class ThinkingConfig(
    @SerialName("thinkingLevel") val thinkingLevel: String,
)

@Serializable
data class GenerateContentResponse(
    val candidates: List<Candidate> = emptyList(),
    val error: GeminiError? = null,
    @SerialName("usageMetadata") val usageMetadata: UsageMetadata? = null,
)

@Serializable
data class Candidate(
    val content: CandidateContent? = null,
    /**
     * `STOP` when the model finished, `MAX_TOKENS` when it was cut off. Discarding this is what let
     * a session of truncated descriptions look like a session of short ones.
     */
    @SerialName("finishReason") val finishReason: String? = null,
)

@Serializable
data class CandidateContent(val parts: List<ResponsePart> = emptyList())

@Serializable
data class ResponsePart(
    val text: String? = null,
    /**
     * True on a part that carries the model's reasoning rather than its answer. Reasoning must
     * never reach the speech buffer: it is written to be read by no one, and when a response is
     * truncated the tail of it can arrive looking exactly like an answer.
     */
    val thought: Boolean? = null,
)

/**
 * Token accounting for the response. [thoughtsTokenCount] is the field that makes a truncation
 * diagnosable rather than merely visible: it says where the budget went.
 */
@Serializable
data class UsageMetadata(
    @SerialName("promptTokenCount") val promptTokenCount: Int? = null,
    @SerialName("candidatesTokenCount") val candidatesTokenCount: Int? = null,
    @SerialName("thoughtsTokenCount") val thoughtsTokenCount: Int? = null,
    @SerialName("totalTokenCount") val totalTokenCount: Int? = null,
)

@Serializable
data class GeminiError(val code: Int? = null, val message: String? = null)
