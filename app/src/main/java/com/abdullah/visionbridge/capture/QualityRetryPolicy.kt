package com.abdullah.visionbridge.capture

/** Pure per-target policy. Speech state is deliberately not an input to visual scheduling. */
class QualityRetryPolicy {
    data class Quality(val sharpness: Double, val contrast: Double, val textDensity: Double,
                       val cropCompleteness: Double, val stableForMs: Long) {
        val score: Double get() = kotlin.math.ln(1 + sharpness.coerceAtLeast(0.0)) * .45 +
            contrast.coerceIn(0.0,128.0)/128 + textDensity.coerceIn(0.0,1.0) +
            cropCompleteness.coerceIn(0.0,1.0)
    }
    data class Decision(val submit: Boolean, val reason: String)
    private var lastSubmittedAt: Long? = null
    private var lastScore = Double.NEGATIVE_INFINITY
    private var reliableScore: Double? = null

    fun consider(q: Quality, nowMs: Long, stable: Boolean): Decision {
        if (q.sharpness < 12 || q.contrast < 10 || q.cropCompleteness < .5)
            return Decision(false, "insufficient_quality")
        if (stable && q.stableForMs < 180) return Decision(false, "awaiting_stability")
        if (reliableScore?.let { q.score < it + .3 } == true)
            return Decision(false, "reliable_target_duplicate")
        val elapsed = lastSubmittedAt?.let { nowMs - it }
        if (elapsed != null && elapsed < if (q.score >= lastScore + .25) 250 else 1000)
            return Decision(false, "retry_cooldown")
        return Decision(true, if (elapsed == null) "first_stable_candidate" else "retry_or_improved_candidate")
    }
    fun submitted(q: Quality, nowMs: Long) { lastSubmittedAt = nowMs; lastScore = q.score }
    fun result(reliable: Boolean) { if (reliable) reliableScore = lastScore }
    fun reset() { lastSubmittedAt = null; lastScore = Double.NEGATIVE_INFINITY; reliableScore = null }
}
