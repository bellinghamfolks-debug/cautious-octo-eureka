package com.abdullah.visionbridge.capture.vision

/**
 * One captured frame, prepared for tracking.
 *
 * Features are computed on demand. The common case — a steady hand, a small drift — is settled by
 * Lucas-Kanade alone, and detecting corners across a scale space for it would be work thrown away
 * on every single frame.
 */
class TrackedFrame(val pyramid: FramePyramid) {

    /**
     * The level everything is decided on.
     *
     * Not the finest. Whether two frames show the same subject is a question about structure, and
     * structure survives a halving; running the aligner and the similarity measure one level down
     * cuts their cost by four for no measurable loss of judgement.
     */
    val analysisLevel: Int = if (pyramid.depth > 1) 1 else 0

    val plane: ImagePlane get() = pyramid[analysisLevel]

    /**
     * Corners detected across the whole pyramid, with their positions expressed in analysis-level
     * coordinates. The limits are intentionally conservative for the live path: enough points for
     * rotation/zoom recovery without spending hundreds of milliseconds describing the same camera
     * motion that Gemini's semantic gate will check again.
     */
    val features: List<Feature> by lazy(LazyThreadSafetyMode.NONE) {
        val all = ArrayList<Feature>(MAX_TOTAL_FEATURES)
        for (level in pyramid.levels.indices) {
            val scale = Math.pow(2.0, (level - analysisLevel).toDouble()).toFloat()
            for (feature in Features.detect(pyramid[level], MAX_FEATURES_PER_LEVEL)) {
                all.add(
                    Feature(
                        x = feature.x * scale,
                        y = feature.y * scale,
                        score = feature.score,
                        angle = feature.angle,
                        descriptor = feature.descriptor,
                    ),
                )
            }
            if (all.size >= MAX_TOTAL_FEATURES) break
        }
        all
    }

    private companion object {
        const val MAX_FEATURES_PER_LEVEL = 100
        const val MAX_TOTAL_FEATURES = 240
    }
}
