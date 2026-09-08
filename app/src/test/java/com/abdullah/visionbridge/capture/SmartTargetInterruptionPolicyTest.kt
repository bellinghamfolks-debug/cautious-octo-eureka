package com.abdullah.visionbridge.capture

import com.abdullah.visionbridge.domain.model.AnalysisMode
import org.junit.Assert.assertEquals
import org.junit.Test

class SmartTargetInterruptionPolicyTest {
    @Test
    fun initialFrameOnlyEstablishesReference() {
        val result = SmartTargetInterruptionPolicy.evaluate(
            decision(reason = "initial_frame", changed = true),
            AnalysisMode.TEXT_READING,
        )
        assertEquals(SmartTargetInterruptionPolicy.Action.NONE, result.action)
    }

    @Test
    fun trackedCameraMotionNeverInterruptsEvenWhenUnalignedDifferenceIsLarge() {
        val result = SmartTargetInterruptionPolicy.evaluate(
            decision(
                reason = "tracked_through_motion",
                changed = false,
                dissimilarity = 0.08,
                unaligned = 0.80,
                coverage = 0.90,
            ),
            AnalysisMode.TEXT_READING,
        )
        assertEquals(SmartTargetInterruptionPolicy.Action.NONE, result.action)
    }

    @Test
    fun firstCandidateWaitsForTemporalConsensus() {
        val result = SmartTargetInterruptionPolicy.evaluate(
            decision(reason = "awaiting_target_consensus", changed = false, dissimilarity = 0.60),
            AnalysisMode.TEXT_READING,
        )
        assertEquals(SmartTargetInterruptionPolicy.Action.NONE, result.action)
        assertEquals("POSSIBLE", result.confidence)
    }

    @Test
    fun confirmedModerateTextChangeFinishesCurrentPhrase() {
        val result = SmartTargetInterruptionPolicy.evaluate(
            decision(
                reason = "new_target_confirmed",
                changed = true,
                dissimilarity = 0.32,
                chroma = 20.0,
                coverage = 0.82,
            ),
            AnalysisMode.TEXT_READING,
        )
        assertEquals(SmartTargetInterruptionPolicy.Action.DEFERRED, result.action)
    }

    @Test
    fun strongTextReplacementInterruptsImmediately() {
        val result = SmartTargetInterruptionPolicy.evaluate(
            decision(
                reason = "new_target_confirmed",
                changed = true,
                dissimilarity = 0.52,
                chroma = 15.0,
                coverage = 0.80,
            ),
            AnalysisMode.TEXT_READING,
        )
        assertEquals(SmartTargetInterruptionPolicy.Action.IMMEDIATE, result.action)
    }

    @Test
    fun sceneChangeUsesMoreResponsiveThreshold() {
        val result = SmartTargetInterruptionPolicy.evaluate(
            decision(
                reason = "new_target_confirmed",
                changed = true,
                dissimilarity = 0.39,
                chroma = 10.0,
                coverage = 0.78,
            ),
            AnalysisMode.SCENE_DESCRIPTION,
        )
        assertEquals(SmartTargetInterruptionPolicy.Action.IMMEDIATE, result.action)
    }

    @Test
    fun subjectLeavingFrameInterruptsImmediately() {
        val result = SmartTargetInterruptionPolicy.evaluate(
            decision(
                reason = "new_target_confirmed",
                changed = true,
                dissimilarity = 0.30,
                chroma = 10.0,
                coverage = 0.30,
            ),
            AnalysisMode.TEXT_READING,
        )
        assertEquals(SmartTargetInterruptionPolicy.Action.IMMEDIATE, result.action)
        assertEquals("subject_left_frame", result.reason)
    }

    private fun decision(
        reason: String,
        changed: Boolean,
        dissimilarity: Double? = null,
        chroma: Double? = null,
        coverage: Double? = null,
        unaligned: Double? = null,
        method: String = "lucas_kanade",
    ) = VisualTargetTracker.Decision(
        targetChanged = changed,
        reason = reason,
        trackId = 1L,
        dissimilarity = dissimilarity,
        chromaDifference = chroma,
        coverage = coverage,
        unalignedDissimilarity = unaligned,
        method = method,
        translationX = 0.0,
        translationY = 0.0,
        scale = 1.0,
        rotationDegrees = 0.0,
        projective = 0.0,
        featureInlierRatio = null,
        consecutiveCandidateFrames = if (reason == "awaiting_target_consensus") 1 else 2,
    )
}
