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
     * Returns the unit vector that most nearly satisfies `A v = 0`, for the symmetric positive
     * semi-definite `A = MᵀM` of a homogeneous system.
     *
     * This is the eigenvector of the smallest eigenvalue. Inverse iteration finds it without a full
     * singular value decomposition: solving `(A + εI) x = v` repeatedly amplifies the component
     * along the smallest eigenvalue faster than any other, so a handful of passes converge.
     */
    fun smallestEigenvector(a: Array<DoubleArray>, iterations: Int = 16): DoubleArray? {
        val n = a.size
        var trace = 0.0
        for (i in 0 until n) trace += a[i][i]
        val epsilon = (trace / n).coerceAtLeast(1e-12) * 1e-9
        val regularised = Array(n) { row -> a[row].copyOf() }
        for (i in 0 until n) regularised[i][i] += epsilon

        // A deterministic, non-degenerate start: a constant vector is orthogonal to too many
        // structured null spaces to be a safe seed.
        var v = DoubleArray(n) { index -> 1.0 + 0.37 * index - 0.11 * (index % 3) }
        if (normalise(v) == null) return null

        repeat(iterations) {
            // A solve that fails, or a result that collapses to zero, means the iteration has
            // already reached the null space as closely as this matrix allows.
            val next = solve(regularised, v) ?: return v
            if (normalise(next) == null) return v
            v = next
        }
        return v
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
}
