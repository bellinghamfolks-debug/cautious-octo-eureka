package com.abdullah.visionbridge.capture.vision

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The small dense solvers the estimators need, written out rather than pulled in.
 *
 * Lucas-Kanade needs a 6×6 symmetric solve per iteration and the homography fit needs the null
 * vector of a 9×9 normal-equation matrix. Both are small enough that a direct method is exact and
 * fast, and neither justifies a native dependency in an accessibility app that must stay small.
 */
internal object LinearAlgebra {

    /**
     * Solves `A x = b` by Gaussian elimination with partial pivoting, in place.
     *
     * Returns null when the system is singular, which for Lucas-Kanade means the patch has no
     * texture in some direction and the step must be abandoned rather than guessed.
     */
    fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size
        val matrix = Array(n) { row -> a[row].copyOf() }
        val rhs = b.copyOf()

        for (column in 0 until n) {
            var pivotRow = column
            var pivotValue = abs(matrix[column][column])
            for (row in column + 1 until n) {
                val candidate = abs(matrix[row][column])
                if (candidate > pivotValue) {
                    pivotValue = candidate
                    pivotRow = row
                }
            }
            if (pivotValue < SINGULAR) return null
            if (pivotRow != column) {
                val swap = matrix[column]
                matrix[column] = matrix[pivotRow]
                matrix[pivotRow] = swap
                val swapRhs = rhs[column]
                rhs[column] = rhs[pivotRow]
                rhs[pivotRow] = swapRhs
            }
            val pivot = matrix[column][column]
            for (row in column + 1 until n) {
                val factor = matrix[row][column] / pivot
                if (factor == 0.0) continue
                for (k in column until n) matrix[row][k] -= factor * matrix[column][k]
                rhs[row] -= factor * rhs[column]
            }
        }

        val x = DoubleArray(n)
        for (row in n - 1 downTo 0) {
            var sum = rhs[row]
            for (k in row + 1 until n) sum -= matrix[row][k] * x[k]
            x[row] = sum / matrix[row][row]
        }
        return if (x.all { it.isFinite() }) x else null
    }

    /**
     * Returns the unit right-singular vector of the smallest singular value of [rows] — the vector
     * that most nearly satisfies `M v = 0`.
     *
     * This is a **one-sided Jacobi SVD**, and it replaced inverse power iteration on the normal
     * equations. The old approach worked, but it was the textbook thing not to do: forming `MᵀM`
     * squares the condition number, so a design matrix conditioned at 10⁶ — routine for a homography
     * from points that are nearly collinear or clustered in one part of the frame — arrives at the
     * solver conditioned at 10¹², which is at the edge of what double precision can carry. Hartley
     * normalisation exists precisely to keep that number small, and then squaring it gives most of
     * the benefit straight back.
     *
     * Jacobi never forms `MᵀM`. It orthogonalises the columns of `M` in place by a sequence of plane
     * rotations, each chosen to annihilate one off-diagonal inner product. The accumulated rotations
     * are `V`; the column norms that remain are the singular values. The relevant property is that
     * every operation is an orthogonal transform, so the condition number is neither squared nor
     * degraded at any point — and for the small tall matrices here (a handful of correspondences ×
     * 9 columns) it converges in a few sweeps and costs microseconds.
     *
     * One-sided Jacobi is also the most accurate practical SVD for exactly this shape: it computes
     * small singular values to high *relative* accuracy, which is the whole question when the answer
     * *is* the smallest one.
     *
     * @param rows the design matrix `M`, one row per equation. Not modified.
     */
    fun smallestSingularVector(rows: Array<DoubleArray>, sweeps: Int = JACOBI_SWEEPS): DoubleArray? {
        if (rows.isEmpty()) return null
        val columns = rows[0].size
        if (columns == 0) return null
        if (rows.any { it.size != columns }) return null

        // Work on a copy of M; V accumulates the rotations and starts as the identity.
        val m = Array(rows.size) { row -> rows[row].copyOf() }
        val v = Array(columns) { row -> DoubleArray(columns) { column -> if (row == column) 1.0 else 0.0 } }

        repeat(sweeps) {
            var offDiagonal = 0.0
            for (p in 0 until columns - 1) {
                for (q in p + 1 until columns) {
                    var alpha = 0.0
                    var beta = 0.0
                    var gamma = 0.0
                    for (row in m.indices) {
                        val a = m[row][p]
                        val b = m[row][q]
                        alpha += a * a
                        beta += b * b
                        gamma += a * b
                    }
                    if (!gamma.isFinite() || !alpha.isFinite() || !beta.isFinite()) return null
                    offDiagonal = maxOf(offDiagonal, abs(gamma))
                    // Already orthogonal to within precision: nothing to rotate away.
                    if (abs(gamma) <= JACOBI_TOLERANCE * sqrt(alpha * beta)) continue

                    // The rotation that zeroes this inner product, in the numerically stable form
                    // that avoids cancellation when the two column norms are close.
                    val zeta = (beta - alpha) / (2.0 * gamma)
                    val t = if (zeta >= 0.0) {
                        1.0 / (zeta + sqrt(1.0 + zeta * zeta))
                    } else {
                        -1.0 / (-zeta + sqrt(1.0 + zeta * zeta))
                    }
                    val c = 1.0 / sqrt(1.0 + t * t)
                    val s = c * t

                    for (row in m.indices) {
                        val a = m[row][p]
                        val b = m[row][q]
                        m[row][p] = c * a - s * b
                        m[row][q] = s * a + c * b
                    }
                    for (row in 0 until columns) {
                        val a = v[row][p]
                        val b = v[row][q]
                        v[row][p] = c * a - s * b
                        v[row][q] = s * a + c * b
                    }
                }
            }
            if (offDiagonal <= JACOBI_TOLERANCE) return@repeat
        }

        // The singular values are the norms of the now-orthogonal columns; the answer is the
        // column of V belonging to the smallest of them.
        var smallest = -1
        var smallestNorm = Double.MAX_VALUE
        for (column in 0 until columns) {
            var sum = 0.0
            for (row in m.indices) sum += m[row][column] * m[row][column]
            val norm = sqrt(sum)
            if (!norm.isFinite()) return null
            if (norm < smallestNorm) {
                smallestNorm = norm
                smallest = column
            }
        }
        if (smallest < 0) return null

        val answer = DoubleArray(columns) { row -> v[row][smallest] }
        return if (normalise(answer) == null) null else answer
    }

    /** Scales [vector] to unit length in place, returning its previous length, or null if zero. */
    private fun normalise(vector: DoubleArray): Double? {
        var sum = 0.0
        for (value in vector) {
            if (!value.isFinite()) return null
            sum += value * value
        }
        val length = sqrt(sum)
        if (length < SINGULAR) return null
        for (index in vector.indices) vector[index] /= length
        return length
    }

    private const val SINGULAR = 1e-12

    /** Enough sweeps for a 9-column system; convergence is quadratic once the columns are close. */
    private const val JACOBI_SWEEPS = 30
    private const val JACOBI_TOLERANCE = 1e-14
}
