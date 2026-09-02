package com.abdullah.visionbridge.data.gemini

import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Gemini38CompatibilityTest {
    private val json = Json { explicitNulls = false }

    @Test
    fun `generation config uses low thinking accepted by Gemini 3_8`() {
        val encoded = json.encodeToString(
            GenerationConfig.serializer(),
            GenerationConfig(),
        )
        assertTrue(encoded.contains("\"thinkingLevel\":\"low\""))
        assertFalse(encoded.contains("\"thinkingLevel\":\"minimal\""))
    }

    @Test
    fun `generation config has no unsupported candidate count`() {
        val encoded = json.encodeToString(
            GenerationConfig.serializer(),
            GenerationConfig(),
        )
        assertFalse(encoded.contains("candidateCount"))
        assertFalse(encoded.contains("candidate_count"))
    }
}
