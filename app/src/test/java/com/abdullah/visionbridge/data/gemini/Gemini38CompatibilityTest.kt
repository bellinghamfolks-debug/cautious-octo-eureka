package com.abdullah.visionbridge.data.gemini

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Gemini38CompatibilityTest {
    private val json = Json { explicitNulls = false }

    @Test
    fun `stable OCR and comprehensive scene use low thinking for Gemini 3_8`() {
        assertEquals(
            "low",
            adaptiveThinkingLevel(
                maxOutputTokens = 900,
                mediaResolution = "MEDIA_RESOLUTION_HIGH",
            ),
        )
        assertEquals(
            "low",
            adaptiveThinkingLevel(
                maxOutputTokens = 360,
                mediaResolution = "MEDIA_RESOLUTION_MEDIUM",
            ),
        )
    }

    @Test
    fun `fast OCR and brief scene retain minimal thinking on Flash Lite lane`() {
        assertEquals(
            "minimal",
            adaptiveThinkingLevel(
                maxOutputTokens = 900,
                mediaResolution = "MEDIA_RESOLUTION_MEDIUM",
            ),
        )
        assertEquals(
            "minimal",
            adaptiveThinkingLevel(
                maxOutputTokens = 96,
                mediaResolution = "MEDIA_RESOLUTION_LOW",
            ),
        )
    }

    @Test
    fun `default generation config serializes 3_8 compatible low thinking`() {
        val encoded = json.encodeToString(
            GenerationConfig.serializer(),
            GenerationConfig(),
        )
        assertTrue(encoded.contains("\"thinkingLevel\":\"low\""))
        assertFalse(encoded.contains("\"thinkingLevel\":\"minimal\""))
    }

    @Test
    fun `generation payload excludes legacy sampling and candidate count fields`() {
        val encoded = json.encodeToString(
            GenerationConfig.serializer(),
            GenerationConfig(temperature = 0.1),
        )
        assertFalse(encoded.contains("temperature"))
        assertFalse(encoded.contains("candidateCount"))
        assertFalse(encoded.contains("candidate_count"))
    }
}
