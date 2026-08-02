package com.abdullah.visionbridge.capture

import kotlin.math.sqrt

/**
 * Decides whether the thing in front of the user has been *replaced*, as opposed to having *moved*.
 *
 * The previous answer was a whole-frame pixel difference compared cell by cell at the same index.
 * It could not tell the two apart, because it never measured motion. In one 467-second device
 * session, with the user holding a single perfume bottle the entire time, it declared 439 new
 * targets — a median of one every 343 milliseconds. Recognition of that bottle takes 2,231
 * milliseconds, so every result was invalidated roughly six times over before it existed, speech
 * was cut off 153 times, and 153 recognitions of a perfectly legible label produced 200 characters
 * of audio.
 *
 * The rules here are the three the evidence demands:
 *
 * 1. **Estimate the motion.** Search integer cell shifts for the alignment that best explains this
 *    frame in terms of the last one.
 * 2. **Measure what is left.** Compare only the overlap, after alignment. A hand that drifted
 *    leaves almost nothing behind; a bottle swapped for a page leaves everything.
 * 3. **Require agreement over time.** One frame is a glint, a blink, a passing hand. A new target
 *    must be visible, and consistent with itself, across several consecutive frames before the
 *    reading in progress is thrown away.
 *
 * A [Decision.trackId] that survives motion is what lets everything downstream stop treating a
 * steady hand as a stream of new subjects.
 */
class VisualTargetTracker(
    private val minimumResidualMean: Double,
    private val minimumResidualRatio: Double,
    private val framesToConfirm: Int = DEFAULT_FRAMES_TO_CONFIRM,
    private val maximumShiftCells: Int = DEFAULT_MAXIMUM_SHIFT_CELLS,
) {
    data class Decision(
        val targetChanged: Boolean,
        val reason: String,
        val trackId: Long,
        /** Difference remaining once motion has been accounted for; null on the first frame. */
        val alignedMeanAbsoluteDifference: Double?,
        val alignedChangedCellRatio: Double?,
        /** What the old index-aligned comparison would have reported, kept for diagnostics. */
        val unalignedMeanAbsoluteDifference: Double?,
        val unalignedChangedCellRatio: Double?,
        val motionXCells: Int,
        val motionYCells: Int,
        val motionCells: Double,
        val consecutiveCandidateFrames: Int,
    )

    private var accepted: FrameSignature? = null
    private var candidate: FrameSignature? = null
    private var candidateStreak = 0
    private var trackId = 0L

    @Synchronized
    fun evaluate(signature: FrameSignature): Decision {
        val previous = accepted
        if (previous == null) {
            accept(signature)
            return Decision(
                targetChanged = true,
                reason = "initial_frame",
                trackId = trackId,
                alignedMeanAbsoluteDifference = null,
                alignedChangedCellRatio = null,
                unalignedMeanAbsoluteDifference = null,
                unalignedChangedCellRatio = null,
                motionXCells = 0,
                motionYCells = 0,
                motionCells = 0.0,
                consecutiveCandidateFrames = 0,
            )
        }

        val unaligned = previous.compareShifted(signature, 0, 0)
        val motion = estimateMotion(previous, signature)
        val aligned = motion.overlap

        if (!exceedsThresholds(aligned)) {
            // Same subject, wherever it has drifted to. The reference moves with it so a slow
            // drift can never accumulate into a false replacement.
            accept(signature)
            return decision(
                changed = false,
                reason = if (motion.magnitude > 0.0) "tracked_through_motion" else "target_unchanged",
                unaligned = unaligned,
                motion = motion,
            )
        }

        // Something is genuinely different. Before believing it, require the next frames to agree
        // with each other: a hand passing over the page, a glint, or one badly exposed frame all
        // look like a new target for exactly one frame.
        val previousCandidate = candidate
        val consistent = previousCandidate != null &&
            !exceedsThresholds(estimateMotion(previousCandidate, signature).overlap)
        candidateStreak = if (consistent) candidateStreak + 1 else 1
        candidate = signature

        if (candidateStreak < framesToConfirm) {
            return decision(
                changed = false,
                reason = "awaiting_target_consensus",
                unaligned = unaligned,
                motion = motion,
            )
        }

        accept(signature)
        return decision(
            changed = true,
            reason = "new_target_confirmed",
            unaligned = unaligned,
            motion = motion,
        )
    }

    @Synchronized
    fun reset() {
        accepted = null
        candidate = null
        candidateStreak = 0
    }

    /** The identity of the subject currently being tracked. */
    @Synchronized
    fun currentTrackId(): Long = trackId

    private fun accept(signature: FrameSignature) {
        if (accepted == null || candidateStreak >= framesToConfirm) trackId++
        accepted = signature
        candidate = null
        candidateStreak = 0
    }

    private fun exceedsThresholds(overlap: FrameSignature.Overlap): Boolean {
        // Too little overlap to judge: the frame moved further than the search can follow, which is
        // itself evidence that the user is sweeping rather than reading.
        if (overlap.cells == 0) return true
        return overlap.meanAbsoluteDifference >= minimumResidualMean ||
            overlap.changedCellRatio >= minimumResidualRatio
    }

    /**
     * Finds the integer cell shift that best explains [current] in terms of [reference].
     *
     * An exhaustive search over a small grid, which on 32×32 cells and a ±6 range is a few hundred
     * thousand integer operations — far cheaper than the recognition pass it protects, and free of
     * the convergence failures a gradient method has on the high-contrast edges of printed text.
     */
    private fun estimateMotion(reference: FrameSignature, current: FrameSignature): Motion {
        var best = Motion(0, 0, reference.compareShifted(current, 0, 0))
        for (dy in -maximumShiftCells..maximumShiftCells) {
            for (dx in -maximumShiftCells..maximumShiftCells) {
                if (dx == 0 && dy == 0) continue
                val overlap = reference.compareShifted(current, dx, dy)
                // Require a real overlap, so a shift that simply compares four cells cannot win.
                if (overlap.cells < reference.luminance.size / MINIMUM_OVERLAP_DIVISOR) continue
                if (overlap.meanAbsoluteDifference < best.overlap.meanAbsoluteDifference) {
                    best = Motion(dx, dy, overlap)
                }
            }
        }
        return best
    }

    private fun decision(
        changed: Boolean,
        reason: String,
        unaligned: FrameSignature.Overlap,
        motion: Motion,
    ) = Decision(
        targetChanged = changed,
        reason = reason,
        trackId = trackId,
        alignedMeanAbsoluteDifference = motion.overlap.meanAbsoluteDifference,
        alignedChangedCellRatio = motion.overlap.changedCellRatio,
        unalignedMeanAbsoluteDifference = unaligned.meanAbsoluteDifference,
        unalignedChangedCellRatio = unaligned.changedCellRatio,
        motionXCells = motion.dx,
        motionYCells = motion.dy,
        motionCells = motion.magnitude,
        consecutiveCandidateFrames = candidateStreak,
    )

    private data class Motion(val dx: Int, val dy: Int, val overlap: FrameSignature.Overlap) {
        val magnitude: Double get() = sqrt((dx * dx + dy * dy).toDouble())
    }

    companion object {
        /**
         * Two consecutive frames that agree with each other. One frame is a glint or a hand passing
         * over the page; two are a decision the user made. At the capture rates in the device logs
         * this costs between 0.2 and 0.7 seconds before a genuinely new subject is picked up, which
         * is far less than the 2.2 seconds recognition takes anyway.
         */
        const val DEFAULT_FRAMES_TO_CONFIRM = 2

        /**
         * A fifth of the grid. On 32 cells that is a shift of six, or roughly a fifth of the frame
         * between two captures — more movement than a person reading a label ever produces, and
         * past it the user is sweeping the glasses rather than holding something still.
         */
        const val DEFAULT_MAXIMUM_SHIFT_CELLS = 6

        /** A candidate alignment must cover at least a third of the reference to count. */
        const val MINIMUM_OVERLAP_DIVISOR = 3
    }
}
