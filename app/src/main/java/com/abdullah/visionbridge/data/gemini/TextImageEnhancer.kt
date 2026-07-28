package com.abdullah.visionbridge.data.gemini

import android.graphics.Bitmap
import android.graphics.Color
import com.abdullah.visionbridge.domain.model.AnalysisMode
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * Prepares one captured frame for Gemini. Text reading receives a lossless, high-resolution,
 * contrast-stretched and lightly sharpened rendition. Scene description keeps a smaller JPEG for
 * lower latency. No second frame or temporal voting is used.
 */
class TextImageEnhancer {
    data class EncodedImage(
        val bytes: ByteArray,
        val mimeType: String,
    )

    fun prepare(source: Bitmap, mode: AnalysisMode): EncodedImage = when (mode) {
        AnalysisMode.TEXT_READING -> prepareText(source)
        AnalysisMode.SCENE_DESCRIPTION -> prepareScene(source)
    }

    private fun prepareText(source: Bitmap): EncodedImage {
        val scaled = scaleForText(source)
        val enhanced = enhanceTextContrast(scaled)
        if (scaled !== source) scaled.recycle()
        return try {
            EncodedImage(
                bytes = compress(enhanced, Bitmap.CompressFormat.PNG, 100),
                mimeType = "image/png",
            )
        } finally {
            enhanced.recycle()
        }
    }

    private fun prepareScene(source: Bitmap): EncodedImage {
        val scaled = scaleToMaxEdge(source, MAX_SCENE_EDGE)
        return try {
            EncodedImage(
                bytes = compress(scaled, Bitmap.CompressFormat.JPEG, SCENE_JPEG_QUALITY),
                mimeType = "image/jpeg",
            )
        } finally {
            if (scaled !== source) scaled.recycle()
        }
    }

    private fun scaleForText(source: Bitmap): Bitmap {
        val largest = maxOf(source.width, source.height)
        val target = when {
            largest < MIN_TEXT_EDGE -> (largest * TEXT_UPSCALE_FACTOR).roundToInt()
                .coerceAtMost(MIN_TEXT_EDGE)
            largest > MAX_TEXT_EDGE -> MAX_TEXT_EDGE
            else -> largest
        }
        if (target == largest) return source.copy(Bitmap.Config.ARGB_8888, false)
        val ratio = target.toFloat() / largest
        return Bitmap.createScaledBitmap(
            source,
            (source.width * ratio).roundToInt().coerceAtLeast(1),
            (source.height * ratio).roundToInt().coerceAtLeast(1),
            true,
        )
    }

    /**
     * Fast O(n) enhancement using percentile contrast stretching plus a five-point unsharp mask.
     * It improves medium and small glyph edges without the latency and binary artifacts of a full
     * adaptive threshold pass.
     */
    private fun enhanceTextContrast(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        val size = width * height
        val pixels = IntArray(size)
        source.getPixels(pixels, 0, width, 0, 0, width, height)

        val luminance = IntArray(size)
        val histogram = IntArray(256)
        for (index in pixels.indices) {
            val color = pixels[index]
            val gray = (
                Color.red(color) * 299 +
                    Color.green(color) * 587 +
                    Color.blue(color) * 114
                ) / 1000
            luminance[index] = gray
            histogram[gray]++
        }

        val low = percentile(histogram, size, 0.01)
        val high = percentile(histogram, size, 0.99).coerceAtLeast(low + MIN_CONTRAST_RANGE)
        val stretched = IntArray(size)
        val range = high - low
        for (index in luminance.indices) {
            stretched[index] = ((luminance[index] - low) * 255 / range).coerceIn(0, 255)
        }

        val output = IntArray(size)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val index = row + x
                val center = stretched[index]
                val left = stretched[row + (x - 1).coerceAtLeast(0)]
                val right = stretched[row + (x + 1).coerceAtMost(width - 1)]
                val up = stretched[(y - 1).coerceAtLeast(0) * width + x]
                val down = stretched[(y + 1).coerceAtMost(height - 1) * width + x]
                val blur = (center * 4 + left + right + up + down) / 8
                val sharpened = (center + (center - blur) * SHARPEN_AMOUNT).roundToInt()
                    .coerceIn(0, 255)
                output[index] = Color.rgb(sharpened, sharpened, sharpened)
            }
        }

        return Bitmap.createBitmap(output, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun percentile(histogram: IntArray, total: Int, fraction: Double): Int {
        val target = (total * fraction).roundToInt().coerceAtLeast(1)
        var cumulative = 0
        for (value in histogram.indices) {
            cumulative += histogram[value]
            if (cumulative >= target) return value
        }
        return 255
    }

    private fun scaleToMaxEdge(source: Bitmap, maxEdge: Int): Bitmap {
        val largest = maxOf(source.width, source.height)
        if (largest <= maxEdge) return source
        val ratio = maxEdge.toFloat() / largest
        return Bitmap.createScaledBitmap(
            source,
            (source.width * ratio).roundToInt().coerceAtLeast(1),
            (source.height * ratio).roundToInt().coerceAtLeast(1),
            true,
        )
    }

    private fun compress(source: Bitmap, format: Bitmap.CompressFormat, quality: Int): ByteArray =
        ByteArrayOutputStream().use { output ->
            check(source.compress(format, quality, output)) { "تعذر تجهيز إطار الصورة" }
            output.toByteArray()
        }

    private companion object {
        const val MIN_TEXT_EDGE = 2_000
        const val MAX_TEXT_EDGE = 2_800
        const val TEXT_UPSCALE_FACTOR = 1.6
        const val MAX_SCENE_EDGE = 1_280
        const val SCENE_JPEG_QUALITY = 72
        const val MIN_CONTRAST_RANGE = 42
        const val SHARPEN_AMOUNT = 0.72
    }
}
