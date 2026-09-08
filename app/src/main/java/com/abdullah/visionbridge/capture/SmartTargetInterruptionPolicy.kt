package com.abdullah.visionbridge.capture

import com.abdullah.visionbridge.domain.model.AnalysisMode

/**
 * Converts a motion-compensated [VisualTargetTracker.Decision] into a speech decision.
 *
 * A confirmed new target is not automatically permission to cut speech. The tracker has already
 * removed camera translation, rotation, zoom and one-frame occlusions; this policy adds a second
 * semantic-strength layer:
 *
 * - NONE: same target, first reference frame, or only a candidate that has not reached consensus.
 * - DEFERRED: a real target change is confirmed, but the evidence is not strong enough to justify
 *   chopping the sentence currently being heard. Fresh analysis may start, while speech finishes.
 * - IMMEDIATE: the old subject is very clearly gone/replaced, so stale speech is stopped now.
 *
 * The scene thresholds are intentionally a little more responsive than text. A stale scene can be
 * safety-relevant; abandoning the last words of a document is usually more expensive.
 */
object SmartTargetInterruptionPolicy {
    enum class Action {
        NONE,
        DEFERRED,
        IMMEDIATE,
    }

    data class Result(
        val action: Action,
        val reason: String,
        val confidence: String,
    )

    fun evaluate(
        decision: VisualTargetTracker.Decision,
        mode: AnalysisMode,
    ): Result {
        if (decision.reason == "initial_frame") {
            return Result(Action.NONE, "establish_reference", "REFERENCE")
        }
        if (!decision.targetChanged) {
            return if (decision.reason == "awaiting_target_consensus") {
                Result(Action.NONE, "possible_change_waiting_for_consensus", "POSSIBLE")
            } else {
                Result(Action.NONE, decision.reason, "SAME_TARGET")
            }
        }

        val dissimilarity = decision.dissimilarity ?: 0.0
        val chroma = decision.chromaDifference ?: 0.0
        val coverage = decision.coverage ?: 1.0
        val unaligned = decision.unalignedDissimilarity ?: 0.0

        val strong = when (mode) {
            AnalysisMode.TEXT_READING ->
                dissimilarity >= TEXT_IMMEDIATE_DISSIMILARITY ||
                    chroma >= TEXT_IMMEDIATE_CHROMA ||
                    coverage <= TEXT_IMMEDIATE_MIN_COVERAGE ||
                    (decision.method == "none" && unaligned >= TEXT_IMMEDIATE_UNALIGNED)

            AnalysisMode.SCENE_DESCRIPTION ->
                dissimilarity >= SCENE_IMMEDIATE_DISSIMILARITY ||
                    chroma >= SCENE_IMMEDIATE_CHROMA ||
                    coverage <= SCENE_IMMEDIATE_MIN_COVERAGE ||
                    (decision.method == "none" && unaligned >= SCENE_IMMEDIATE_UNALIGNED)
        }

        return if (strong) {
            Result(Action.IMMEDIATE, strongestReason(decision, mode), "HIGH")
        } else {
            Result(Action.DEFERRED, "confirmed_change_finish_current_phrase", "CONFIRMED")
        }
    }

    private fun strongestReason(
        decision: VisualTargetTracker.Decision,
        mode: AnalysisMode,
    ): String {
        val dissimilarity = decision.dissimilarity ?: 0.0
        val chroma = decision.chromaDifference ?: 0.0
        val coverage = decision.coverage ?: 1.0
        val unaligned = decision.unalignedDissimilarity ?: 0.0
        val dissimilarityThreshold = if (mode == AnalysisMode.TEXT_READING) {
            TEXT_IMMEDIATE_DISSIMILARITY
        } else {
            SCENE_IMMEDIATE_DISSIMILARITY
        }
        val chromaThreshold = if (mode == AnalysisMode.TEXT_READING) {
            TEXT_IMMEDIATE_CHROMA
        } else {
            SCENE_IMMEDIATE_CHROMA
        }
        val coverageThreshold = if (mode == AnalysisMode.TEXT_READING) {
            TEXT_IMMEDIATE_MIN_COVERAGE
        } else {
            SCENE_IMMEDIATE_MIN_COVERAGE
        }
        val unalignedThreshold = if (mode == AnalysisMode.TEXT_READING) {
            TEXT_IMMEDIATE_UNALIGNED
        } else {
            SCENE_IMMEDIATE_UNALIGNED
        }

        return when {
            coverage <= coverageThreshold -> "subject_left_frame"
            dissimilarity >= dissimilarityThreshold -> "strong_structural_replacement"
            chroma >= chromaThreshold -> "strong_colour_subject_replacement"
            decision.method == "none" && unaligned >= unalignedThreshold -> "untrackable_scene_replacement"
            else -> "strong_confirmed_replacement"
        }
    }

    private const val TEXT_IMMEDIATE_DISSIMILARITY = 0.44
    private const val TEXT_IMMEDIATE_CHROMA = 48.0
    private const val TEXT_IMMEDIATE_MIN_COVERAGE = 0.42
    private const val TEXT_IMMEDIATE_UNALIGNED = 0.58

    private const val SCENE_IMMEDIATE_DISSIMILARITY = 0.36
    private const val SCENE_IMMEDIATE_CHROMA = 36.0
    private const val SCENE_IMMEDIATE_MIN_COVERAGE = 0.50
    private const val SCENE_IMMEDIATE_UNALIGNED = 0.48
}
