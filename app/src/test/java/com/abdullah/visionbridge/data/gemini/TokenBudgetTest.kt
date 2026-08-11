package com.abdullah.visionbridge.data.gemini

import com.abdullah.visionbridge.domain.model.AnalysisMode
import com.abdullah.visionbridge.domain.model.SceneDescriptionStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The token ceilings, checked against the reasoning passes actually observed in the field.
 *
 * These are not arbitrary numbers to be pinned for their own sake. Every assertion here restates
 * the defect that produced it: a ceiling chosen for the answer alone, on a model that charges its
 * reasoning to the same budget, is a ceiling the answer never reaches.
 */
class TokenBudgetTest {

    /** The largest reasoning pass measured on scene description in the 2026-08-10 session. */
    private val observedSceneReasoning = 346

    /** The largest reasoning pass measured on text reading in the same session. */
    private val observedReadingReasoning = 1_076

    /** The smallest reasoning pass measured anywhere in that session. */
    private val smallestObservedReasoning = 122

    private fun ceiling(mode: AnalysisMode, style: SceneDescriptionStyle) =
        TokenBudget.maxOutputTokens(mode, style)

    // region the ceiling must survive a full reasoning pass

    @Test
    fun `a comprehensive description has room for the reasoning and the description`() {
        val room = ceiling(AnalysisMode.SCENE_DESCRIPTION, SceneDescriptionStyle.COMPREHENSIVE)
        assertTrue(
            "a 75-word description cannot fit in ${room - observedSceneReasoning} tokens",
            room - observedSceneReasoning >= 400,
        )
    }

    /**
     * The old brief ceiling was 96, below the smallest reasoning pass ever observed — so brief
     * style could not emit a single word of description under any conditions.
     */
    @Test
    fun `a brief description is not smaller than the reasoning it must pay for`() {
        val room = ceiling(AnalysisMode.SCENE_DESCRIPTION, SceneDescriptionStyle.BRIEF)
        assertTrue("brief cannot even afford to think", room > smallestObservedReasoning)
        assertTrue("brief cannot afford a sentence", room - observedSceneReasoning >= 200)
    }

    @Test
    fun `reading a page has room for the reasoning and a page`() {
        val room = ceiling(AnalysisMode.TEXT_READING, SceneDescriptionStyle.COMPREHENSIVE)
        assertTrue(
            "a dense page cannot fit in ${room - observedReadingReasoning} tokens",
            room - observedReadingReasoning >= 2_000,
        )
    }

    @Test
    fun `every ceiling clears the reasoning headroom the policy promises`() {
        for (mode in AnalysisMode.entries) {
            for (style in SceneDescriptionStyle.entries) {
                assertTrue(
                    "$mode/$style leaves no headroom",
                    ceiling(mode, style) > TokenBudget.REASONING_HEADROOM,
                )
            }
        }
    }

    // endregion

    // region relative order

    @Test
    fun `reading a page is allowed more than describing a room`() {
        assertTrue(
            ceiling(AnalysisMode.TEXT_READING, SceneDescriptionStyle.BRIEF) >
                ceiling(AnalysisMode.SCENE_DESCRIPTION, SceneDescriptionStyle.COMPREHENSIVE),
        )
    }

    @Test
    fun `a comprehensive description is allowed more than a brief one`() {
        assertTrue(
            ceiling(AnalysisMode.SCENE_DESCRIPTION, SceneDescriptionStyle.COMPREHENSIVE) >
                ceiling(AnalysisMode.SCENE_DESCRIPTION, SceneDescriptionStyle.BRIEF),
        )
    }

    /** The style must not change what reading a page is allowed; only the mode does. */
    @Test
    fun `the description style does not change the reading ceiling`() {
        assertEquals(
            ceiling(AnalysisMode.TEXT_READING, SceneDescriptionStyle.COMPREHENSIVE),
            ceiling(AnalysisMode.TEXT_READING, SceneDescriptionStyle.BRIEF),
        )
    }

    // endregion

    // region reasoning level

    @Test
    fun `every mode asks for shallow reasoning because every mode is perception`() {
        for (mode in AnalysisMode.entries) {
            for (style in SceneDescriptionStyle.entries) {
                assertEquals(TokenBudget.LOW, TokenBudget.thinkingLevel(mode, style))
            }
        }
    }

    // endregion

    // region truncation

    @Test
    fun `a budget exhaustion is a truncation`() {
        assertTrue(TokenBudget.wasTruncated("MAX_TOKENS"))
        assertTrue(TokenBudget.wasTruncated("max_tokens"))
    }

    @Test
    fun `a clean stop is not a truncation`() {
        assertFalse(TokenBudget.wasTruncated("STOP"))
    }

    /** A missing finish reason is a stream still in progress, not a finished truncated answer. */
    @Test
    fun `an absent finish reason is not a truncation`() {
        assertFalse(TokenBudget.wasTruncated(null))
        assertFalse(TokenBudget.wasTruncated(""))
    }

    // endregion
}
