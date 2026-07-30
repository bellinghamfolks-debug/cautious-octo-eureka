package com.abdullah.visionbridge.data.diagnostics

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Produces a non-reconstructive visual fingerprint for diagnostics.
 *
 * No bitmap, thumbnail, pixel grid, or encoded image leaves this function. The output contains only
 * aggregate quality measurements and a one-way 64-bit difference hash. This is enough to explain
 * darkness, overexposure, blur, occlusion, black camera bars, target changes, and crop mistakes
 * without retaining a viewable copy of the user's screen.
 */
internal object VisualFingerprintAnalyzer {
    private var previousHash: Long? = null
    private var sequence = 0L

    @Synchronized
    fun analyze(
        bitmap: Bitmap,
        role: String,
        frameId: String,
        reason: String? = null,
    ): Map<String, Any?> {
        val sampled = scaleForAnalysis(bitmap)
        return try {
            val width = sampled.width
            val height = sampled.height
            val colors = IntArray(width * height)
            sampled.getPixels(colors, 0, width, 0, 0, width, height)

            val luminance = IntArray(colors.size)
            val histogram = IntArray(256)
            var saturationTotal = 0.0
            var dark = 0
            var bright = 0
            var clippedBlack = 0
            var clippedWhite = 0

            colors.forEachIndexed { index, color ->
                val red = Color.red(color)
                val green = Color.green(color)
                val blue = Color.blue(color)
                val gray = (red * 299 + green * 587 + blue * 114) / 1000
                luminance[index] = gray
                histogram[gray]++
                if (gray <= DARK_LUMA) dark++
                if (gray >= BRIGHT_LUMA) bright++
                if (gray <= CLIPPED_BLACK_LUMA) clippedBlack++
                if (gray >= CLIPPED_WHITE_LUMA) clippedWhite++

                val max = maxOf(red, green, blue)
                val min = minOf(red, green, blue)
                saturationTotal += if (max == 0) 0.0 else (max - min).toDouble() / max
            }

            val total = luminance.size.coerceAtLeast(1)
            val mean = luminance.average()
            val variance = luminance.fold(0.0) { sum, value ->
                val delta = value - mean
                sum + delta * delta
            } / total
            val stdDev = sqrt(variance)
            val p05 = percentile(histogram, total, 0.05)
            val p50 = percentile(histogram, total, 0.50)
            val p95 = percentile(histogram, total, 0.95)
            val edge = edgeMetrics(luminance, width, height)
            val focusVariance = laplacianVariance(luminance, width, height, 0, height)
            val entropy = entropy(histogram, total)
            val bars = detectBlackBars(luminance, width, height)
            val hash = differenceHash(luminance, width, height)
            val prior = previousHash
            val hammingDistance = prior?.let { java.lang.Long.bitCount(it xor hash) }
            previousHash = hash
            sequence++

            val darkRatio = dark.toDouble() / total
            val brightRatio = bright.toDouble() / total
            val qualityClass = classifyQuality(
                darkRatio = darkRatio,
                brightRatio = brightRatio,
                dynamicRange = p95 - p05,
                edgeDensity = edge.combined,
                focusVariance = focusVariance,
            )

            mapOf(
                "fingerprintVersion" to FINGERPRINT_VERSION,
                "fingerprintSequence" to sequence,
                "privacyDesign" to "aggregate_metrics_and_one_way_hash_only",
                "containsImage" to false,
                "containsPixelGrid" to false,
                "role" to role,
                "frameId" to frameId,
                "reason" to reason,
                "sourceWidth" to bitmap.width,
                "sourceHeight" to bitmap.height,
                "sampleWidth" to width,
                "sampleHeight" to height,
                "meanLuminance" to mean,
                "luminanceStdDev" to stdDev,
                "luminanceP05" to p05,
                "luminanceP50" to p50,
                "luminanceP95" to p95,
                "dynamicRangeP95P05" to (p95 - p05),
                "darkPixelRatio" to darkRatio,
                "brightPixelRatio" to brightRatio,
                "clippedBlackRatio" to clippedBlack.toDouble() / total,
                "clippedWhiteRatio" to clippedWhite.toDouble() / total,
                "meanSaturation" to saturationTotal / total,
                "entropyBits" to entropy,
                "horizontalEdgeDensity" to edge.horizontal,
                "verticalEdgeDensity" to edge.vertical,
                "combinedEdgeDensity" to edge.combined,
                "laplacianFocusVariance" to focusVariance,
                "blackBarTopFraction" to bars.topFraction,
                "blackBarBottomFraction" to bars.bottomFraction,
                "estimatedContentTopFraction" to bars.topFraction,
                "estimatedContentBottomFraction" to (1.0 - bars.bottomFraction),
                "differenceHash64" to unsignedHex(hash),
                "previousHashHammingDistance" to hammingDistance,
                "qualityClass" to qualityClass,
                "likelySmoothOcclusion" to (
                    focusVariance < SMOOTH_OCCLUSION_MAX_FOCUS &&
                        edge.combined < SMOOTH_OCCLUSION_MAX_EDGE &&
                        (brightRatio > 0.04 || stdDev < 22.0)
                    ),
                "regions" to mapOf(
                    "top" to regionMetrics(luminance, width, height, 0.0, 0.22),
                    "center" to regionMetrics(luminance, width, height, 0.22, 0.78),
                    "bottom" to regionMetrics(luminance, width, height, 0.78, 1.0),
                ),
            )
        } finally {
            if (sampled !== bitmap) sampled.recycle()
        }
    }

    @Synchronized
    fun reset() {
        previousHash = null
        sequence = 0L
    }

    private fun scaleForAnalysis(source: Bitmap): Bitmap {
        val largest = maxOf(source.width, source.height).coerceAtLeast(1)
        if (largest <= MAX_SAMPLE_EDGE) return source
        val ratio = MAX_SAMPLE_EDGE.toFloat() / largest
        return Bitmap.createScaledBitmap(
            source,
            (source.width * ratio).roundToInt().coerceAtLeast(MIN_SAMPLE_EDGE),
            (source.height * ratio).roundToInt().coerceAtLeast(MIN_SAMPLE_EDGE),
            true,
        )
    }

    private fun regionMetrics(
        values: IntArray,
        width: Int,
        height: Int,
        startFraction: Double,
        endFraction: Double,
    ): Map<String, Any?> {
        val startY = (height * startFraction).roundToInt().coerceIn(0, height - 1)
        val endY = (height * endFraction).roundToInt().coerceIn(startY + 1, height)
        var count = 0
        var sum = 0.0
        var squareSum = 0.0
        var edges = 0
        var edgeComparisons = 0
        for (y in startY until endY) {
            val row = y * width
            for (x in 0 until width) {
                val value = values[row + x].toDouble()
                sum += value
                squareSum += value * value
                count++
                if (x > 0) {
                    if (kotlin.math.abs(values[row + x] - values[row + x - 1]) >= EDGE_DELTA) edges++
                    edgeComparisons++
                }
            }
        }
        val mean = if (count == 0) 0.0 else sum / count
        val variance = if (count == 0) 0.0 else (squareSum / count - mean * mean).coerceAtLeast(0.0)
        return mapOf(
            "meanLuminance" to mean,
            "luminanceStdDev" to sqrt(variance),
            "edgeDensity" to if (edgeComparisons == 0) 0.0 else edges.toDouble() / edgeComparisons,
            "focusVariance" to laplacianVariance(values, width, height, startY, endY),
        )
    }

    private fun edgeMetrics(values: IntArray, width: Int, height: Int): EdgeMetrics {
        var horizontal = 0
        var vertical = 0
        var horizontalComparisons = 0
        var verticalComparisons = 0
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val index = row + x
                if (x > 0) {
                    if (kotlin.math.abs(values[index] - values[index - 1]) >= EDGE_DELTA) horizontal++
                    horizontalComparisons++
                }
                if (y > 0) {
                    if (kotlin.math.abs(values[index] - values[index - width]) >= EDGE_DELTA) vertical++
                    verticalComparisons++
                }
            }
        }
        val horizontalRatio = if (horizontalComparisons == 0) 0.0 else horizontal.toDouble() / horizontalComparisons
        val verticalRatio = if (verticalComparisons == 0) 0.0 else vertical.toDouble() / verticalComparisons
        return EdgeMetrics(horizontalRatio, verticalRatio, (horizontalRatio + verticalRatio) / 2.0)
    }

    private fun laplacianVariance(
        values: IntArray,
        width: Int,
        height: Int,
        startY: Int,
        endY: Int,
    ): Double {
        if (width < 3 || height < 3) return 0.0
        val first = startY.coerceAtLeast(1)
        val lastExclusive = endY.coerceAtMost(height - 1)
        var count = 0L
        var sum = 0.0
        var squareSum = 0.0
        for (y in first until lastExclusive) {
            val row = y * width
            for (x in 1 until width - 1) {
                val index = row + x
                val laplacian =
                    values[index - width] + values[index + width] +
                        values[index - 1] + values[index + 1] -
                        4 * values[index]
                val value = laplacian.toDouble()
                sum += value
                squareSum += value * value
                count++
            }
        }
        if (count == 0L) return 0.0
        val mean = sum / count
        return (squareSum / count - mean * mean).coerceAtLeast(0.0)
    }

    private fun detectBlackBars(values: IntArray, width: Int, height: Int): BlackBars {
        fun rowIsBlack(rowIndex: Int): Boolean {
            val offset = rowIndex * width
            var sum = 0.0
            var squareSum = 0.0
            for (x in 0 until width) {
                val value = values[offset + x].toDouble()
                sum += value
                squareSum += value * value
            }
            val mean = sum / width
            val variance = (squareSum / width - mean * mean).coerceAtLeast(0.0)
            return mean <= BLACK_BAR_MAX_MEAN && sqrt(variance) <= BLACK_BAR_MAX_STD_DEV
        }

        var topRows = 0
        while (topRows < height / 2 && rowIsBlack(topRows)) topRows++
        var bottomRows = 0
        while (bottomRows < height / 2 && rowIsBlack(height - 1 - bottomRows)) bottomRows++
        return BlackBars(
            topFraction = topRows.toDouble() / height,
            bottomFraction = bottomRows.toDouble() / height,
        )
    }

    private fun differenceHash(values: IntArray, width: Int, height: Int): Long {
        var result = 0L
        var bit = 0
        for (yIndex in 0 until HASH_ROWS) {
            val y = if (HASH_ROWS == 1) 0 else yIndex * (height - 1) / (HASH_ROWS - 1)
            for (xIndex in 0 until HASH_COLUMNS - 1) {
                val x1 = xIndex * (width - 1) / (HASH_COLUMNS - 1)
                val x2 = (xIndex + 1) * (width - 1) / (HASH_COLUMNS - 1)
                if (values[y * width + x1] > values[y * width + x2]) {
                    result = result or (1L shl bit)
                }
                bit++
            }
        }
        return result
    }

    private fun percentile(histogram: IntArray, total: Int, fraction: Double): Int {
        val target = (total * fraction).roundToInt().coerceAtLeast(1)
        var cumulative = 0
        histogram.forEachIndexed { value, count ->
            cumulative += count
            if (cumulative >= target) return value
        }
        return 255
    }

    private fun entropy(histogram: IntArray, total: Int): Double {
        if (total <= 0) return 0.0
        return histogram.fold(0.0) { sum, count ->
            if (count == 0) sum
            else {
                val probability = count.toDouble() / total
                sum - probability * (ln(probability) / LN_2)
            }
        }
    }

    private fun classifyQuality(
        darkRatio: Double,
        brightRatio: Double,
        dynamicRange: Int,
        edgeDensity: Double,
        focusVariance: Double,
    ): String = when {
        darkRatio >= 0.965 && edgeDensity < 0.010 -> "almost_black"
        brightRatio >= 0.70 && edgeDensity < 0.012 -> "overexposed"
        dynamicRange <= 14 && edgeDensity < 0.008 -> "blank_low_contrast"
        focusVariance < MIN_FOCUS_VARIANCE -> "blurred_or_occluded"
        else -> "usable"
    }

    private fun unsignedHex(value: Long): String =
        java.lang.Long.toUnsignedString(value, 16).padStart(16, '0')

    private data class EdgeMetrics(
        val horizontal: Double,
        val vertical: Double,
        val combined: Double,
    )

    private data class BlackBars(
        val topFraction: Double,
        val bottomFraction: Double,
    )

    private const val FINGERPRINT_VERSION = 1
    private const val MAX_SAMPLE_EDGE = 72
    private const val MIN_SAMPLE_EDGE = 8
    private const val HASH_ROWS = 8
    private const val HASH_COLUMNS = 9
    private const val EDGE_DELTA = 24
    private const val DARK_LUMA = 12
    private const val BRIGHT_LUMA = 248
    private const val CLIPPED_BLACK_LUMA = 3
    private const val CLIPPED_WHITE_LUMA = 252
    private const val BLACK_BAR_MAX_MEAN = 18.0
    private const val BLACK_BAR_MAX_STD_DEV = 10.0
    private const val MIN_FOCUS_VARIANCE = 950.0
    private const val SMOOTH_OCCLUSION_MAX_FOCUS = 2_000.0
    private const val SMOOTH_OCCLUSION_MAX_EDGE = 0.018
    private val LN_2 = ln(2.0)
}
