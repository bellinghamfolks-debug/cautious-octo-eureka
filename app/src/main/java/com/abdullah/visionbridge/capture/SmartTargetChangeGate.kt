package com.abdullah.visionbridge.capture

import android.graphics.Bitmap
import android.os.SystemClock
import com.abdullah.visionbridge.domain.model.AnalysisMode
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Lightweight target identity gate for the Live path.
 *
 * The heavy VisualTargetTracker is excellent for the local OCR lane but is too expensive to run on
 * every Live candidate. This gate samples only the already-cropped eSight camera viewport and asks a
 * narrower question: did the user actually move to a different subject, or did the same subject
 * merely shift, zoom a little, or experience a lighting change?
 *
 * It deliberately requires temporal agreement before declaring a new target. The answer is one of
 * three actions:
 *  - KEEP: same target / uncertain transient; keep speaking.
 *  - DEFER: a new target is confirmed, but the current sentence may finish.
 *  - INTERRUPT: a strongly different target is confirmed and current speech may be retired.
 */
class SmartTargetChangeGate {
    enum class Action { KEEP, DEFER, INTERRUPT }

    data class Decision(
        val action: Action,
        val reason: String,
        val distance: Double?,
        val candidateDistance: Double?,
        val candidateFrames: Int,
        val candidateAgeMs: Long,
    )

    private data class Signature(
        val normalizedLuma: FloatArray,
        val edges: FloatArray,
        val chromaU: FloatArray,
        val chromaV: FloatArray,
    )

    private var reference: Signature? = null
    private var candidate: Signature? = null
    private var candidateFrames = 0
    private var candidateSinceMs = 0L

    @Synchronized
    fun evaluate(
        bitmap: Bitmap,
        mode: AnalysisMode,
        nowMs: Long = SystemClock.elapsedRealtime(),
    ): Decision {
        val current = signature(bitmap)
        val previous = reference
        if (previous == null) {
            reference = current
            clearCandidate()
            return Decision(Action.KEEP, "initial_target", null, null, 0, 0L)
        }

        val distance = distance(previous, current)
        if (distance <= SAME_TARGET_MAX) {
            // Follow ordinary hand motion and small zoom without slowly learning a completely new
            // object in one jump. The comparison itself already searches small translations.
            reference = current
            clearCandidate()
            return Decision(Action.KEEP, "same_target_motion_or_lighting", distance, null, 0, 0L)
        }

        if (distance < NEW_TARGET_CANDIDATE_MIN) {
            clearCandidate()
            return Decision(Action.KEEP, "uncertain_change_ignored", distance, null, 0, 0L)
        }

        val priorCandidate = candidate
        val candidateDistance = priorCandidate?.let { distance(it, current) }
        val consistent = priorCandidate != null && candidateDistance != null &&
            candidateDistance <= CANDIDATE_CONSISTENCY_MAX

        if (consistent) {
            candidateFrames += 1
        } else {
            candidateFrames = 1
            candidateSinceMs = nowMs
        }
        candidate = current

        val ageMs = (nowMs - candidateSinceMs).coerceAtLeast(0L)
        val strong = distance >= STRONG_NEW_TARGET_MIN
        val framesNeeded = when {
            strong -> STRONG_CONFIRM_FRAMES
            mode == AnalysisMode.SCENE_DESCRIPTION -> SCENE_MODERATE_CONFIRM_FRAMES
            else -> TEXT_MODERATE_CONFIRM_FRAMES
        }
        val ageNeeded = if (strong) STRONG_CONFIRM_AGE_MS else MODERATE_CONFIRM_AGE_MS

        if (candidateFrames < framesNeeded || ageMs < ageNeeded) {
            return Decision(
                Action.KEEP,
                if (strong) "strong_change_awaiting_consensus" else "moderate_change_awaiting_consensus",
                distance,
                candidateDistance,
                candidateFrames,
                ageMs,
            )
        }

        reference = current
        clearCandidate()
        return if (strong) {
            Decision(Action.INTERRUPT, "strong_new_target_confirmed", distance, candidateDistance, framesNeeded, ageMs)
        } else {
            Decision(Action.DEFER, "new_target_confirmed_finish_current_sentence", distance, candidateDistance, framesNeeded, ageMs)
        }
    }

    @Synchronized
    fun reset() {
        reference = null
        clearCandidate()
    }

    private fun clearCandidate() {
        candidate = null
        candidateFrames = 0
        candidateSinceMs = 0L
    }

    private fun signature(source: Bitmap): Signature {
        val tiny = Bitmap.createScaledBitmap(source, GRID_WIDTH, GRID_HEIGHT, true)
        val pixels = IntArray(GRID_WIDTH * GRID_HEIGHT)
        tiny.getPixels(pixels, 0, GRID_WIDTH, 0, 0, GRID_WIDTH, GRID_HEIGHT)
        if (tiny !== source) tiny.recycle()

        val luma = FloatArray(pixels.size)
        val u = FloatArray(pixels.size)
        val v = FloatArray(pixels.size)
        var mean = 0.0
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = ((p ushr 16) and 0xff) / 255f
            val g = ((p ushr 8) and 0xff) / 255f
            val b = (p and 0xff) / 255f
            val y = 0.299f * r + 0.587f * g + 0.114f * b
            luma[i] = y
            // Colour residuals are centred on luminance, making them much less sensitive to a
            // global exposure change than raw RGB.
            u[i] = b - y
            v[i] = r - y
            mean += y
        }
        mean /= max(1, luma.size)

        var variance = 0.0
        for (value in luma) {
            val d = value - mean
            variance += d * d
        }
        val std = sqrt(variance / max(1, luma.size)).coerceAtLeast(MIN_LUMA_STD)
        val normalized = FloatArray(luma.size) { i ->
            ((luma[i] - mean) / std).toFloat().coerceIn(-LUMA_Z_CLAMP, LUMA_Z_CLAMP)
        }

        val edges = FloatArray(luma.size)
        for (y in 0 until GRID_HEIGHT) {
            for (x in 0 until GRID_WIDTH) {
                val i = y * GRID_WIDTH + x
                val dx = if (x > 0) abs(normalized[i] - normalized[i - 1]) else 0f
                val dy = if (y > 0) abs(normalized[i] - normalized[i - GRID_WIDTH]) else 0f
                edges[i] = min(EDGE_CLAMP, dx + dy)
            }
        }
        return Signature(normalized, edges, u, v)
    }

    /** Best low-resolution distance after a small translation search. */
    private fun distance(a: Signature, b: Signature): Double {
        var best = Double.POSITIVE_INFINITY
        for (dy in -MAX_SHIFT..MAX_SHIFT) {
            for (dx in -MAX_SHIFT..MAX_SHIFT) {
                var lumaDiff = 0.0
                var edgeDiff = 0.0
                var chromaDiff = 0.0
                var count = 0
                for (y in 0 until GRID_HEIGHT) {
                    val by = y + dy
                    if (by !in 0 until GRID_HEIGHT) continue
                    for (x in 0 until GRID_WIDTH) {
                        val bx = x + dx
                        if (bx !in 0 until GRID_WIDTH) continue
                        val ai = y * GRID_WIDTH + x
                        val bi = by * GRID_WIDTH + bx
                        lumaDiff += abs(a.normalizedLuma[ai] - b.normalizedLuma[bi]) / (2.0 * LUMA_Z_CLAMP)
                        edgeDiff += abs(a.edges[ai] - b.edges[bi]) / EDGE_CLAMP
                        chromaDiff += (
                            abs(a.chromaU[ai] - b.chromaU[bi]) +
                                abs(a.chromaV[ai] - b.chromaV[bi])
                            ) / 2.0
                        count++
                    }
                }
                if (count == 0) continue
                val score = LUMA_WEIGHT * (lumaDiff / count) +
                    EDGE_WEIGHT * (edgeDiff / count) +
                    CHROMA_WEIGHT * (chromaDiff / count)
                if (score < best) best = score
            }
        }
        return best.coerceIn(0.0, 1.0)
    }

    companion object {
        /**
         * Local PP-OCR already paid for full geometric registration. Use that richer evidence to
         * choose whether a confirmed replacement is strong enough to cut speech immediately.
         */
        fun actionForTrackedTarget(decision: VisualTargetTracker.Decision): Action {
            if (!decision.targetChanged || decision.reason == "initial_frame") return Action.KEEP
            val dissimilarity = decision.dissimilarity ?: 0.0
            val chroma = decision.chromaDifference ?: 0.0
            val coverage = decision.coverage ?: 1.0
            val unaligned = decision.unalignedDissimilarity ?: dissimilarity
            val strong = dissimilarity >= 0.38 || chroma >= 34.0 || coverage < 0.50 ||
                (dissimilarity >= 0.30 && unaligned >= 0.58)
            return if (strong) Action.INTERRUPT else Action.DEFER
        }

        internal fun actionForConfirmedDistance(distance: Double, strong: Boolean): Action =
            if (strong || distance >= STRONG_NEW_TARGET_MIN) Action.INTERRUPT else Action.DEFER

        private const val GRID_WIDTH = 32
        private const val GRID_HEIGHT = 18
        private const val MAX_SHIFT = 2
        private const val MIN_LUMA_STD = 0.08
        private const val LUMA_Z_CLAMP = 2.5f
        private const val EDGE_CLAMP = 5.0f
        private const val LUMA_WEIGHT = 0.68
        private const val EDGE_WEIGHT = 0.22
        private const val CHROMA_WEIGHT = 0.10

        private const val SAME_TARGET_MAX = 0.19
        private const val NEW_TARGET_CANDIDATE_MIN = 0.28
        private const val STRONG_NEW_TARGET_MIN = 0.46
        private const val CANDIDATE_CONSISTENCY_MAX = 0.23

        private const val STRONG_CONFIRM_FRAMES = 2
        private const val TEXT_MODERATE_CONFIRM_FRAMES = 3
        private const val SCENE_MODERATE_CONFIRM_FRAMES = 2
        private const val STRONG_CONFIRM_AGE_MS = 110L
        private const val MODERATE_CONFIRM_AGE_MS = 240L
    }
}
