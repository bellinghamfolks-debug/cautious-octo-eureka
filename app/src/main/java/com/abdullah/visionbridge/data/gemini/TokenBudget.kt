package com.abdullah.visionbridge.data.gemini

import com.abdullah.visionbridge.domain.model.AnalysisMode
import com.abdullah.visionbridge.domain.model.SceneDescriptionStyle

/**
 * How many tokens a request may spend, and how much of that the model is allowed to spend thinking.
 *
 * ## The defect this exists to prevent
 *
 * Gemini 3 models reason before they answer, and **the reasoning is charged against
 * `maxOutputTokens`**. A budget chosen for the answer alone is therefore not a budget for the
 * answer at all: the model thinks until the ceiling is reached and the response is cut off with
 * `finishReason: MAX_TOKENS`, mid-word, or before a single answer token is emitted.
 *
 * This is not hypothetical. In a field session on 2026-08-10, scene description ran with a ceiling
 * of 360 tokens; every one of the 36 responses came back `MAX_TOKENS`, with a median of 343 tokens
 * spent thinking and 13 left for the description. The user heard "تق", "أ", "شخص ين" — the first
 * few characters of a sentence that was never allowed to finish. Brief style was worse: its ceiling
 * of 96 sat below the smallest reasoning pass ever observed (122), so it could not have produced a
 * description under any circumstances.
 *
 * Two independent guards follow from that, and both are needed:
 *
 * 1. [thinkingLevel] tells the model not to reason deeply in the first place. Describing what is in
 *    front of a camera and transcribing a label are perception, not deduction; the default level is
 *    tuned for problems that are neither.
 * 2. [maxOutputTokens] leaves room for a full reasoning pass *plus* the answer even if the level is
 *    ignored or unsupported — so the reading still completes when the first guard does not apply.
 *
 * The ceilings below are the measured worst-case reasoning pass plus the longest answer each mode
 * can legitimately produce, not round numbers.
 */
object TokenBudget {

    /**
     * The ceiling on reasoning **and** answer together.
     *
     * Reading a dense page is the one case where the answer itself is long: a screen of Arabic and
     * Latin runs to well over a thousand tokens, and cutting it off leaves the user hearing half a
     * page and the retry loop starting again from the top.
     */
    fun maxOutputTokens(
        mode: AnalysisMode,
        style: SceneDescriptionStyle,
        withSceneTail: Boolean = false,
    ): Int = when (mode) {
        // The tail is one sentence, but it arrives *after* the page. If the ceiling does not grow
        // with it, adding a description would silently cost the last lines of the transcription —
        // the failure this whole class was written to make impossible.
        AnalysisMode.TEXT_READING ->
            if (withSceneTail) TEXT_READING_CEILING + SCENE_TAIL_ALLOWANCE else TEXT_READING_CEILING
        AnalysisMode.SCENE_DESCRIPTION -> when (style) {
            SceneDescriptionStyle.COMPREHENSIVE -> COMPREHENSIVE_CEILING
            SceneDescriptionStyle.BRIEF -> BRIEF_CEILING
        }
    }

    /**
     * The reasoning depth to ask for. Every task this app sends is a perception task under a
     * latency deadline someone is waiting through, so the lowest useful level is the right one
     * everywhere; the value is a function rather than a constant because that is a claim about the
     * work, and a future mode that genuinely reasons would need to say so.
     */
    fun thinkingLevel(mode: AnalysisMode, style: SceneDescriptionStyle): String = when (mode) {
        AnalysisMode.TEXT_READING -> LOW
        AnalysisMode.SCENE_DESCRIPTION -> when (style) {
            SceneDescriptionStyle.COMPREHENSIVE -> LOW
            SceneDescriptionStyle.BRIEF -> LOW
        }
    }

    /**
     * Whether the model stopped because it ran out of budget rather than because it had finished.
     *
     * Anything that is not a clean stop is worth surfacing, but this one is worth surfacing loudly:
     * a truncated answer is indistinguishable from a short answer to everyone downstream — the
     * speech buffer speaks it, the ledger records it as read, and the user hears a confident
     * fragment. Only the finish reason tells them apart.
     */
    fun wasTruncated(finishReason: String?): Boolean =
        finishReason != null && finishReason.equals(MAX_TOKENS, ignoreCase = true)

    /** Reasoning headroom that must remain available whatever the answer costs. */
    const val REASONING_HEADROOM = 1_200

    /**
     * A page of mixed Arabic and Latin text, plus the reasoning pass. The largest reasoning pass
     * seen in the field on this mode was 1076 tokens.
     */
    const val TEXT_READING_CEILING = 4_000

    /** A paragraph-length description of a room, plus the reasoning pass. */
    const val COMPREHENSIVE_CEILING = 1_800

    /** One sentence, plus the reasoning pass — which is the part that used to not fit at all. */
    const val BRIEF_CEILING = 1_400

    /** One Arabic sentence, with room for the marker and for the model to be a little generous. */
    const val SCENE_TAIL_ALLOWANCE = 300

    /** Perception under a deadline. The API's own default is several times this. */
    const val LOW = "LOW"

    /** The finish reason that means the answer was cut off rather than completed. */
    const val MAX_TOKENS = "MAX_TOKENS"
}
