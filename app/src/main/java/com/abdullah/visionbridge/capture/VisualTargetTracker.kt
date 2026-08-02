package com.abdullah.visionbridge.capture

import com.abdullah.visionbridge.capture.vision.Features
import com.abdullah.visionbridge.capture.vision.Homography
import com.abdullah.visionbridge.capture.vision.LucasKanade
import com.abdullah.visionbridge.capture.vision.StructuralResidual
import com.abdullah.visionbridge.capture.vision.TrackedFrame
import com.abdullah.visionbridge.capture.vision.Warp

/**
 * Decides whether the subject in front of the user has been *replaced*, as opposed to having
 * *moved*.
 *
 * The version this replaces shrank each frame to a 24×24 grayscale grid, subtracted it from the
 * last one cell by cell, and called the result a change of subject. It had no notion of motion at
 * all, so a hand that drifted a few millimetres was indistinguishable from a page being swapped.
 * In one 467-second device session, with the user holding a single perfume bottle the whole time,
 * it declared 439 new targets — one every 343 milliseconds against a recognition pass that takes
 * 2,231 — cut speech off 153 times, and turned 153 correct readings of a legible label into 200
 * characters of audio.
 *
 * What runs now is a real registration pipeline:
 *
 * 1. **Lucas-Kanade**, pyramidal, over a six-parameter affine warp. Coarse levels give it the
 *    capture range for large motion, fine levels the sub-pixel precision. It answers translation,
 *    rotation and scale together, because a person holding an object does all three at once.
 * 2. **Feature matching and a homography** when Lucas-Kanade cannot get there — a large jump, a
 *    heavy rotation, a moment of occlusion. FAST corners with oriented BRIEF descriptors, matched
 *    with a ratio test and a cross-check, fitted by normalised DLT inside RANSAC. A homography
 *    also covers the case Lucas-Kanade's affine model cannot: a flat page now being viewed from an
 *    angle.
 * 3. **Structural similarity** on what is left after alignment, not a pixel difference. SSIM
 *    compares local luminance, contrast and correlation, so the same label under changed lighting
 *    reads as the same content while a different label at identical brightness does not. Colour is
 *    carried alongside, which the grayscale signature could not do at all.
 * 4. **Temporal consensus.** A single frame is a glint, a blink, a hand passing over the page. A
 *    new subject has to be visible, and consistent with itself, across consecutive frames before a
 *    reading in progress is thrown away.
 *
 * [Decision.trackId] is the identity that survives all of that, and it is what lets everything
 * downstream stop treating a steady hand as a stream of new subjects.
 */
class VisualTargetTracker(
    private val maximumDissimilarity: Double,
    private val maximumChromaDifference: Double,
    private val framesToConfirm: Int = DEFAULT_FRAMES_TO_CONFIRM,
) {
    data class Decision(
        val targetChanged: Boolean,
        val reason: String,
        val trackId: Long,
        /** Structural dissimilarity remaining after alignment, 0..1. Null on the first frame. */
        val dissimilarity: Double?,
        val chromaDifference: Double?,
        val coverage: Double?,
        /** What the same measurement says with no motion compensation at all. */
        val unalignedDissimilarity: Double?,
        /** Which estimator produced the alignment that was believed. */
        val method: String,
        val translationX: Double,
        val translationY: Double,
        val scale: Double,
        val rotationDegrees: Double,
        val projective: Double,
        val featureInlierRatio: Double?,
        val consecutiveCandidateFrames: Int,
    )

    private var reference: TrackedFrame? = null
    private var candidate: TrackedFrame? = null
    private var candidateStreak = 0
    private var trackId = 0L

    /** The warp from the previous frame, used to start the next search where motion left off. */
    private var motionPrior: Warp = Warp.identity()

    @Synchronized
    fun evaluate(frame: TrackedFrame): Decision {
        val previous = reference
        if (previous == null) {
            adopt(frame, advance = true)
            return Decision(
                targetChanged = true,
                reason = "initial_frame",
                trackId = trackId,
                dissimilarity = null,
                chromaDifference = null,
                coverage = null,
                unalignedDissimilarity = null,
                method = "none",
                translationX = 0.0,
                translationY = 0.0,
                scale = 1.0,
                rotationDegrees = 0.0,
                projective = 0.0,
                featureInlierRatio = null,
                consecutiveCandidateFrames = 0,
            )
        }

        val registration = register(previous, frame)
        val unaligned = StructuralResidual.measure(previous.plane, frame.plane, Warp.identity())

        if (registration != null && !isDifferentSubject(registration)) {
            // The same subject, wherever it has drifted, turned or zoomed to. The reference moves
            // with it, and the estimated warp seeds the next search, so continuous motion is
            // followed rather than accumulated into a false replacement.
            motionPrior = registration.warp
            adopt(frame, advance = false)
            return decide(false, trackedReason(registration), registration, unaligned)
        }

        // Something is genuinely different. Before believing it, require the next frame to agree
        // with this one: a hand crossing the page, a glint, or one badly exposed frame all look
        // like a new subject for exactly one frame, and each of those used to end a reading.
        val held = candidate
        val consistent = held != null && register(held, frame)?.let { !isDifferentSubject(it) } == true
        candidateStreak = if (consistent) candidateStreak + 1 else 1
        candidate = frame

        val describing = registration ?: Registration(
            warp = Warp.identity(),
            residual = unaligned,
            method = "none",
            inlierRatio = null,
        )
        if (candidateStreak < framesToConfirm) {
            return decide(false, "awaiting_target_consensus", describing, unaligned)
        }

        motionPrior = Warp.identity()
        adopt(frame, advance = true)
        return decide(true, "new_target_confirmed", describing, unaligned)
    }

    @Synchronized
    fun reset() {
        reference = null
        candidate = null
        candidateStreak = 0
        motionPrior = Warp.identity()
    }

    @Synchronized
    fun currentTrackId(): Long = trackId

    private fun adopt(frame: TrackedFrame, advance: Boolean) {
        if (advance) trackId++
        reference = frame
        candidate = null
        candidateStreak = 0
    }

    private class Registration(
        val warp: Warp,
        val residual: StructuralResidual.Result,
        val method: String,
        val inlierRatio: Double?,
    )

    /**
     * Estimates the transform between two frames, cheaply first and thoroughly only if needed.
     */
    private fun register(from: TrackedFrame, to: TrackedFrame): Registration? {
        // Aligned down to the analysis level rather than the finest, and started from the warp the
        // last frame produced so continuous motion is followed instead of re-derived.
        val level = from.analysisLevel
        val direct = LucasKanade.align(from.pyramid, to.pyramid, motionPrior, finestLevel = level)
            ?: LucasKanade.align(from.pyramid, to.pyramid, finestLevel = level)
        val directResidual = direct
            ?.takeIf { it.warp.isPlausible() }
            ?.let { Registration(it.warp, StructuralResidual.measure(from.plane, to.plane, it.warp), "lucas_kanade", null) }

        // Good enough: do not pay for feature detection.
        if (directResidual != null && !isDifferentSubject(directResidual)) return directResidual

        val fromFeatures = from.features
        val toFeatures = to.features
        if (fromFeatures.size < Homography.MINIMUM_MATCHES || toFeatures.size < Homography.MINIMUM_MATCHES) {
            return directResidual
        }
        val matches = Features.match(fromFeatures, toFeatures)
        val estimate = Homography.estimate(matches) ?: return directResidual

        // Features and the residual share a level, so the homography needs no rescaling here.
        if (!estimate.warp.isPlausible()) return directResidual
        val featureResidual = Registration(
            warp = estimate.warp,
            residual = StructuralResidual.measure(from.plane, to.plane, estimate.warp),
            method = "homography",
            inlierRatio = estimate.inlierRatio,
        )

        if (directResidual == null) return featureResidual
        return if (featureResidual.residual.dissimilarity < directResidual.residual.dissimilarity) {
            featureResidual
        } else {
            directResidual
        }
    }

    private fun isDifferentSubject(registration: Registration): Boolean {
        val residual = registration.residual
        // Too little overlap left to judge: the subject moved further than any transform could
        // follow, which is itself evidence the user has swept away from it.
        if (!residual.usable) return true
        return residual.dissimilarity >= maximumDissimilarity ||
            residual.chromaDifference >= maximumChromaDifference
    }

    private fun trackedReason(registration: Registration): String {
        val motion = registration.warp.describe(1, 1)
        val moved = kotlin.math.abs(motion.translationX) > STILL ||
            kotlin.math.abs(motion.translationY) > STILL ||
            kotlin.math.abs(motion.scale - 1.0) > STILL_SCALE ||
            kotlin.math.abs(motion.rotationDegrees) > STILL_DEGREES
        return if (moved) "tracked_through_motion" else "target_unchanged"
    }

    private fun decide(
        changed: Boolean,
        reason: String,
        registration: Registration,
        unaligned: StructuralResidual.Result,
    ): Decision {
        val plane = reference?.plane
        val description = registration.warp.describe(plane?.width ?: 1, plane?.height ?: 1)
        return Decision(
            targetChanged = changed,
            reason = reason,
            trackId = trackId,
            dissimilarity = registration.residual.dissimilarity,
            chromaDifference = registration.residual.chromaDifference,
            coverage = registration.residual.coverage,
            unalignedDissimilarity = unaligned.dissimilarity,
            method = registration.method,
            translationX = description.translationX,
            translationY = description.translationY,
            scale = description.scale,
            rotationDegrees = description.rotationDegrees,
            projective = description.projective,
            featureInlierRatio = registration.inlierRatio,
            consecutiveCandidateFrames = candidateStreak,
        )
    }

    companion object {
        /**
         * Two consecutive frames that agree with each other. One frame is a glint or a hand passing
         * over the page; two are a decision the user made. At the capture rates in the device logs
         * this costs between 0.2 and 0.7 seconds before a genuinely new subject is picked up, far
         * less than the 2.2 seconds recognition takes anyway.
         */
        const val DEFAULT_FRAMES_TO_CONFIRM = 2

        private const val STILL = 0.75
        private const val STILL_SCALE = 0.02
        private const val STILL_DEGREES = 0.5
    }
}
