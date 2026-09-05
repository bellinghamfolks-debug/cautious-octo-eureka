package com.abdullah.visionbridge.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class SmartTargetChangeGateTest {
    @Test
    fun initialTrackedFrameNeverInterrupts() {
        val action = SmartTargetChangeGate.actionForTrackedTarget(
            decision(
                targetChanged = true,
                reason = "initial_frame",
                dissimilarity = null,
                chroma = null,
                coverage = null,
                unaligned = null,
            ),
        )
        assertEquals(SmartTargetChangeGate.Action.KEEP, action)
    }

    @Test
    fun moderateConfirmedReplacementLetsCurrentSentenceFinish() {
        val action = SmartTargetChangeGate.actionForTrackedTarget(
            decision(
                targetChanged = true,
                reason = "new_target_confirmed",
                dissimilarity = 0.29,
                chroma = 20.0,
                coverage = 0.80,
                unaligned = 0.40,
            ),
        )
        assertEquals(SmartTargetChangeGate.Action.DEFER, action)
    }

    @Test
    fun stronglyDifferentConfirmedReplacementInterrupts() {
        val action = SmartTargetChangeGate.actionForTrackedTarget(
            decision(
                targetChanged = true,
                reason = "new_target_confirmed",
                dissimilarity = 0.44,
                chroma = 40.0,
                coverage = 0.42,
                unaligned = 0.70,
            ),
        )
        assertEquals(SmartTargetChangeGate.Action.INTERRUPT, action)
    }

    @Test
    fun distancePolicySeparatesModerateAndStrongChanges() {
        assertEquals(
            SmartTargetChangeGate.Action.DEFER,
            SmartTargetChangeGate.actionForConfirmedDistance(0.34, strong = false),
        )
        assertEquals(
            SmartTargetChangeGate.Action.INTERRUPT,
            SmartTargetChangeGate.actionForConfirmedDistance(0.55, strong = false),
        )
    }

    private fun decision(
        targetChanged: Boolean,
        reason: String,
        dissimilarity: Double?,
        chroma: Double?,
        coverage: Double?,
        unaligned: Double?,
    ) = VisualTargetTracker.Decision(
        targetChanged = targetChanged,
        reason = reason,
        trackId = 1L,
        dissimilarity = dissimilarity,
        chromaDifference = chroma,
        coverage = coverage,
        unalignedDissimilarity = unaligned,
        method = "test",
        translationX = 0.0,
        translationY = 0.0,
        scale = 1.0,
        rotationDegrees = 0.0,
        projective = 0.0,
        featureInlierRatio = null,
        consecutiveCandidateFrames = 2,
    )
}
