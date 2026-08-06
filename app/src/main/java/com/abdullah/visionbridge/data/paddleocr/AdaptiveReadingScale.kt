package com.abdullah.visionbridge.data.paddleocr

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Chooses the detector's working resolution from the size of the text actually in front of the user.
 *
 * The setting this replaces asked the user to pick between "fast", "balanced" and "maximum", which
 * is asking them to solve an equation they cannot see the terms of. The equation is small and the
 * app knows every term:
 *
 * A differentiable-binarisation detector finds text reliably when a line is roughly 12 to 40 pixels
 * tall *in its own input*. Text `h` pixels tall in a capture whose long edge is `S` arrives at the
 * detector, working at long edge `E`, with height `h·E/S`. Setting that equal to the target height
 * and solving for `E`:
 *
 * ```
 *   E* = S · targetHeight / h
 * ```
 *
 * So the ideal resolution depends on one measurable quantity — how tall the text is — and nothing
 * else. A label held close (h≈200 px) needs 300; a page at arm's length (h≈40) needs 1490; a sign
 * across a room (h≈14) needs everything the capture has. Those are exactly the three presets, and
 * they were never a preference: they were three points on a curve the app can evaluate itself.
 *
 * Three things make it work on a moving hand rather than on a still photograph:
 *
 * - **Feed-forward from the tracker.** [VisualTargetTracker][com.abdullah.visionbridge.capture.VisualTargetTracker]
 *   already measures how much the subject grew or shrank between frames. Applying that to the last
 *   measured height anticipates a zoom instead of correcting it one frame late, which is the
 *   difference between adapting while someone moves and adapting after they stop.
 * - **A ladder, not a continuum.** ONNX Runtime re-plans when an input shape changes, so the
 *   resolution snaps to a small set of rungs and a deadband keeps it from stepping back and forth
 *   over a boundary.
 * - **Bracketing when nothing is found.** No text and text at the wrong scale look identical from
 *   the outside, so an empty pass alternates coarser and finer rather than sitting where it is.
 *
 * Pure, and driven only by numbers the pipeline already produces.
 */
class AdaptiveReadingScale(
    private val sourceLongEdge: Int,
    private val ladder: IntArray = DEFAULT_LADDER,
) {
    /**
     * [reason] names why this resolution was chosen, so a diagnostic bundle shows the controller
     * reasoning rather than a bare number.
     */
    data class Decision(
        val detectionLongEdge: Int,
        val recognitionMaxWidth: Int,
        val reason: String,
        val estimatedTextHeight: Float?,
        val idealLongEdge: Int?,
    )

    private var current: Int = ladder[ladder.size / 2]
    private var lastMeasuredHeight: Float? = null
    private var emptyPasses = 0

    /**
     * Where the search started. Bracketing has to widen around a fixed point; measuring the offset
     * from wherever the last step landed turns the search into a random walk that only ever goes
     * one way, which is how the first version of this climbed to the top of the ladder and stayed.
     */
    private var searchOrigin = -1

    /**
     * The resolution to use for the next frame.
     *
     * @param measuredTextHeight median text height in source pixels from the last completed pass,
     *   or null when the last pass found nothing.
     * @param subjectScale how much the subject grew since the frame that produced that measurement.
     *   1.0 when unknown.
     */
    @Synchronized
    fun next(measuredTextHeight: Float?, subjectScale: Double = 1.0): Decision {
        if (measuredTextHeight == null || measuredTextHeight <= 0f) return searchForText()

        emptyPasses = 0
        searchOrigin = -1
        // Anticipate the zoom rather than trail it: the text is already this much bigger.
        val predicted = (measuredTextHeight * subjectScale.toFloat())
            .coerceAtLeast(MIN_CREDIBLE_HEIGHT)
        lastMeasuredHeight = predicted

        val ideal = (sourceLongEdge * TARGET_DETECTED_HEIGHT / predicted).roundToInt()
        val chosen = snap(ideal)
        val moved = chosen != current
        current = chosen
        return decide(
            reason = when {
                !moved -> "held"
                abs(subjectScale - 1.0) > FEED_FORWARD_NOTICE -> "followed_subject_scale"
                else -> "matched_text_height"
            },
            height = predicted,
            ideal = ideal,
        )
    }

    /**
     * Nothing was found. Widen the search rather than keep asking the same question: text too small
     * for this resolution and no text at all are indistinguishable from the outside, and only one
     * of them is fixed by looking harder.
     */
    private fun searchForText(): Decision {
        emptyPasses++
        lastMeasuredHeight = null
        if (searchOrigin < 0) {
            searchOrigin = ladder.indexOfFirst { it >= current }.coerceAtLeast(0)
        }
        // Finer first — small distant text is the case worth paying for — then coarser, widening
        // each time, so both explanations are tested within a few frames: +1, -1, +2, -2, ...
        val magnitude = (emptyPasses + 1) / 2
        val direction = if (emptyPasses % 2 == 1) 1 else -1
        current = ladder[(searchOrigin + magnitude * direction).coerceIn(0, ladder.size - 1)]
        return decide(
            reason = if (emptyPasses == 1) "nothing_found_searching" else "nothing_found_bracketing",
            height = null,
            ideal = null,
        )
    }

    /** The resolution the controller would use with no new information. */
    @Synchronized
    fun currentLongEdge(): Int = current

    @Synchronized
    fun reset() {
        current = ladder[ladder.size / 2]
        lastMeasuredHeight = null
        emptyPasses = 0
        searchOrigin = -1
    }

    /**
     * Snaps to the nearest rung, with a deadband so a resolution sitting near a boundary does not
     * step back and forth every frame and force the runtime to re-plan each time.
     */
    private fun snap(ideal: Int): Int {
        val clamped = ideal.coerceIn(ladder.first(), ladder.last())
        if (abs(clamped - current).toDouble() / current < DEADBAND) return current
        return ladder.minByOrNull { abs(it - clamped) } ?: current
    }

    private fun decide(reason: String, height: Float?, ideal: Int?) = Decision(
        detectionLongEdge = current,
        // The crop width cap has to rise with the detector's reach, or a full-width line found at
        // high resolution is squeezed horizontally on its way to the recogniser and connected
        // Arabic letters are destroyed by the squashing.
        recognitionMaxWidth = (current * CROP_WIDTH_FACTOR).roundToInt().coerceAtLeast(MIN_CROP_WIDTH),
        reason = reason,
        estimatedTextHeight = height,
        idealLongEdge = ideal,
    )

    companion object {
        /**
         * Where a differentiable-binarisation detector is happiest. Below about twelve pixels a line
         * stops being separable from its neighbours; far above forty the map is mostly interior and
         * the extra pixels buy nothing but time.
         */
        const val TARGET_DETECTED_HEIGHT = 22f

        /** Rungs roughly a third apart: fine enough to track a hand, coarse enough to stay put. */
        val DEFAULT_LADDER = intArrayOf(640, 832, 1088, 1440, 1856, 2304, 2688)

        /** A measured height below this is noise, not text. */
        const val MIN_CREDIBLE_HEIGHT = 4f

        /** Ignore a proposed change smaller than this fraction of the current resolution. */
        const val DEADBAND = 0.18

        /** Above this the change is attributed to the subject moving rather than to a re-measure. */
        const val FEED_FORWARD_NOTICE = 0.04

        const val CROP_WIDTH_FACTOR = 1.1
        const val MIN_CROP_WIDTH = 640
    }
}
