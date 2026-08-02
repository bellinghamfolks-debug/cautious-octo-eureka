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
     * coordinates.
     *
     * Detecting on one level only was tried and is not enough. A BRIEF descriptor is steered by its
     * corner's orientation, so it survives rotation, but its sampling pattern is a fixed number of
     * pixels across — so the same corner seen 25% larger produces a different descriptor and does
     * not match. Measured: a 1.25× zoom of one page yielded too few matches to fit a homography at
     * all, and the tracker was one frame away from calling it a new subject. Detecting at every
     * level means a corner at full size in one frame can match itself at half size in another,
     * which is what makes matching scale invariant over the pyramid's range.
     */
    val features: List<Feature> by lazy(LazyThreadSafetyMode.NONE) {
        val all = ArrayList<Feature>(MAX_TOTAL_FEATURES)
        for (level in pyramid.levels.indices) {
            // Coordinates are rescaled so features from every level live in one frame of reference.
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
        const val MAX_FEATURES_PER_LEVEL = 140
        const val MAX_TOTAL_FEATURES = 360
    }
}
