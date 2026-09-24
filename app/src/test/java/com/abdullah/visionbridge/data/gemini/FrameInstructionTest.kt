package com.abdullah.visionbridge.data.gemini

import com.abdullah.visionbridge.domain.model.AnalysisMode
import com.abdullah.visionbridge.domain.model.AppSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The control lines the model is asked to emit before the user's own content.
 *
 * A `QUALITY|` line is the model stating its own verdict on legibility and confidence, and it has
 * to be written out in full before a single character of the page can be. Build 48 measured the
 * cost from the far end: 155-332 ms between Gemini's first chunk and the first text that could be
 * spoken. That is a fair price for strict verification, which exists to refuse an uncertain
 * reading, and no price at all for a mode that will speak whatever it reads anyway.
 *
 * The other half of the rule is the one that can silence a turn outright: the accumulator's
 * `requireQualityHeader` must agree with the prompt. Asking for a line the prompt never requested
 * leaves `ocrAccepted` false for the whole stream, and every character is dropped while the request
 * itself succeeds.
 */
class FrameInstructionTest {

    private fun settings(
        mode: AnalysisMode = AnalysisMode.TEXT_READING,
        trustGate: Boolean = false,
    ) = AppSettings(mode = mode, trustGateEnabled = trustGate)

    private fun instruction(settings: AppSettings) = FrameTurnTransport.instruction(settings)

    // region the fast path pays for nothing it does not use

    @Test
    fun `reading without the trust gate does not ask for a quality line`() {
        assertFalse(instruction(settings()).contains("QUALITY|"))
    }

    @Test
    fun `reading without the trust gate still asks for the language line`() {
        // META carries the language the speech layer picks a voice from, and costs one short line.
        assertTrue(instruction(settings()).contains("META|language="))
    }

    @Test
    fun `describing never asks for a quality line`() {
        for (trustGate in listOf(false, true)) {
            val text = instruction(settings(AnalysisMode.SCENE_DESCRIPTION, trustGate))
            assertFalse("scene description paid for a quality verdict", text.contains("QUALITY|"))
        }
    }

    // endregion

    // region strict verification keeps its heavier protocol

    @Test
    fun `reading with the trust gate asks for the quality line`() {
        assertTrue(instruction(settings(trustGate = true)).contains("QUALITY|"))
    }

    @Test
    fun `only a gated reading is strict`() {
        assertTrue(FrameTurnTransport.trustStrict(settings(trustGate = true)))
        assertFalse(FrameTurnTransport.trustStrict(settings()))
        assertFalse(
            FrameTurnTransport.trustStrict(settings(AnalysisMode.SCENE_DESCRIPTION, trustGate = true))
        )
    }

    // endregion

    /**
     * The agreement that matters. If these two ever disagree the turn succeeds, the model answers
     * correctly, and the user hears nothing — the hardest failure in this codebase to see from the
     * outside, because every stage reports success.
     */
    @Test
    fun `the prompt and the parser agree about the quality line for every configuration`() {
        for (mode in AnalysisMode.entries) {
            for (trustGate in listOf(false, true)) {
                val current = settings(mode, trustGate)
                val asked = instruction(current).contains("QUALITY|")
                val required = FrameTurnTransport.trustStrict(current)
                assertTrue(
                    "$mode trustGate=$trustGate: prompt asks=$asked but parser requires=$required",
                    asked == required,
                )
            }
        }
    }
}
