package com.abdullah.visionbridge.capture.vision

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * A 3×3 projective transform, row-major.
 *
 * One type carries every geometry the tracker needs: a translation is an affine is a homography,
 * so the Lucas-Kanade stage and the feature-matching stage produce the same kind of answer and can
 * be compared against each other on equal terms.
 *
 * ```
 * | m0 m1 m2 |
 * | m3 m4 m5 |
 * | m6 m7 m8 |
 * ```
 */
class Warp(val m: DoubleArray) {
    init {
        require(m.size == 9) { "a projective transform has nine coefficients" }
    }

    /** Maps a point, dividing through by the third coordinate. Null when the point is at infinity. */
    fun apply(x: Double, y: Double): DoubleArray? {
        val w = m[6] * x + m[7] * y + m[8]
        if (abs(w) < NEAR_ZERO) return null
        return doubleArrayOf(
            (m[0] * x + m[1] * y + m[2]) / w,
            (m[3] * x + m[4] * y + m[5]) / w,
        )
    }

    operator fun times(other: Warp): Warp {
        val r = DoubleArray(9)
        for (row in 0 until 3) {
            for (column in 0 until 3) {
                var sum = 0.0
                for (k in 0 until 3) sum += m[row * 3 + k] * other.m[k * 3 + column]
                r[row * 3 + column] = sum
            }
        }
        return Warp(r)
    }

    fun inverse(): Warp? {
        val a = m
        val c0 = a[4] * a[8] - a[5] * a[7]
        val c1 = a[5] * a[6] - a[3] * a[8]
        val c2 = a[3] * a[7] - a[4] * a[6]
        val determinant = a[0] * c0 + a[1] * c1 + a[2] * c2
        if (abs(determinant) < NEAR_ZERO) return null
        val inverseDeterminant = 1.0 / determinant
        return Warp(
            doubleArrayOf(
                c0 * inverseDeterminant,
                (a[2] * a[7] - a[1] * a[8]) * inverseDeterminant,
                (a[1] * a[5] - a[2] * a[4]) * inverseDeterminant,
                c1 * inverseDeterminant,
                (a[0] * a[8] - a[2] * a[6]) * inverseDeterminant,
                (a[2] * a[3] - a[0] * a[5]) * inverseDeterminant,
                c2 * inverseDeterminant,
                (a[1] * a[6] - a[0] * a[7]) * inverseDeterminant,
                (a[0] * a[4] - a[1] * a[3]) * inverseDeterminant,
            ),
        )
    }

    /** Rescales a transform estimated at one pyramid level for use at another. */
    fun scaledBy(factor: Double): Warp = SCALE(factor) * this * SCALE(1.0 / factor)

    /** True when the transform is a plausible view of the same scene rather than a collapse. */
    fun isPlausible(): Boolean {
        if (m.any { !it.isFinite() }) return false
        val determinant = m[0] * m[4] - m[1] * m[3]
        if (abs(determinant) < MIN_AREA_RATIO || abs(determinant) > MAX_AREA_RATIO) return false
        // A projective term this large folds the plane over inside the frame.
        return hypot(m[6], m[7]) < MAX_PROJECTIVE_TERM
    }

    /** Translation in pixels of the image centre, and the scale and rotation implied by the linear part. */
    fun describe(width: Int, height: Int): Description {
        val centreX = width / 2.0
        val centreY = height / 2.0
        val moved = apply(centreX, centreY) ?: doubleArrayOf(centreX, centreY)
        val scaleX = hypot(m[0], m[3])
        val scaleY = hypot(m[1], m[4])
        return Description(
            translationX = moved[0] - centreX,
            translationY = moved[1] - centreY,
            scale = (scaleX + scaleY) / 2.0,
            rotationDegrees = Math.toDegrees(kotlin.math.atan2(m[3], m[0])),
            projective = hypot(m[6], m[7]),
        )
    }

    data class Description(
        val translationX: Double,
        val translationY: Double,
        val scale: Double,
        val rotationDegrees: Double,
        val projective: Double,
    )

    companion object {
        private const val NEAR_ZERO = 1e-12

        /** A view of the same scene never shrinks past a quarter or grows past four times. */
        private const val MIN_AREA_RATIO = 0.25
        private const val MAX_AREA_RATIO = 4.0
        private const val MAX_PROJECTIVE_TERM = 0.01

        fun identity() = Warp(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0))

        fun translation(dx: Double, dy: Double) =
            Warp(doubleArrayOf(1.0, 0.0, dx, 0.0, 1.0, dy, 0.0, 0.0, 1.0))

        /** Affine from its six free coefficients, in the same order as the matrix. */
        fun affine(p: DoubleArray) = Warp(
            doubleArrayOf(p[0], p[1], p[2], p[3], p[4], p[5], 0.0, 0.0, 1.0),
        )

        /** A similarity about ([centreX], [centreY]): rotate, scale, then translate. */
        fun similarity(
            centreX: Double,
            centreY: Double,
            degrees: Double,
            scale: Double,
            dx: Double,
            dy: Double,
        ): Warp {
            val radians = Math.toRadians(degrees)
            val a = scale * cos(radians)
            val b = -scale * sin(radians)
            return Warp(
                doubleArrayOf(
                    a, b, centreX - a * centreX - b * centreY + dx,
                    -b, a, centreY + b * centreX - a * centreY + dy,
                    0.0, 0.0, 1.0,
                ),
            )
        }

        private fun SCALE(factor: Double) =
            Warp(doubleArrayOf(factor, 0.0, 0.0, 0.0, factor, 0.0, 0.0, 0.0, 1.0))
    }
}
