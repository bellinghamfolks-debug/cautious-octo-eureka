package com.abdullah.visionbridge.data.gemini

import android.graphics.Bitmap
import android.graphics.Color
import com.abdullah.visionbridge.domain.model.AnalysisMode
import com.abdullah.visionbridge.domain.model.CaptureProfile
import com.abdullah.visionbridge.domain.model.SceneDescriptionStyle
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * Prepares one captured frame for Gemini. The encoder adapts to the user's requested latency:
 * stable OCR keeps a lossless high-resolution rendition, fast OCR uses a smaller high-quality JPEG,
 * and brief scene description uses the lightest useful image. No second frame is used.
 */
class TextImageEnhancer {
    data class EncodedImage(
        val bytes: ByteArray,
        val mimeType: String,
    )

    fun prepare(
        source: Bitmap,
        mode: AnalysisMode,
        captureProfile: CaptureProfile,
        sceneDescriptionStyle: SceneDescriptionStyle,
    ): EncodedImage = when (mode) {
        AnalysisMode.TEXT_READING -> if (captureProfile == CaptureProfile.FAST_TEXT) {
            prepareFastText(source)
        } else {
            prepareAccurateText(source)
        }
        AnalysisMode.SCENE_DESCRIPTION -> prepareScene(source, sceneDescriptionStyle)
    }

    private fun prepareAccurateText(source: Bitmap): EncodedImage {
        val scaled = scaleForAccurateText(source)
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

    private fun prepareFastText(source: Bitmap): EncodedImage {
        val scaled = scaleForFastText(source)
        val enhanced = enhanceTextContrast(scaled)
        if (scaled !== source) scaled.recycle()
        return try {
            EncodedImage(
                bytes = compress(enhanced, Bitmap.CompressFormat.JPEG, FAST_TEXT_JPEG_QUALITY),
                mimeType = "image/jpeg",
            )
        } finally {
            enhanced.recycle()
        }
    }

    private fun prepareScene(
        source: Bitmap,
        style: SceneDescriptionStyle,
    ): EncodedImage {
        val maxEdge = if (style == SceneDescriptionStyle.BRIEF) {
            BRIEF_SCENE_EDGE
        } else {
            COMPREHENSIVE_SCENE_EDGE
        }
        val quality = if (style == SceneDescriptionStyle.BRIEF) {
            BRIEF_SCENE_JPEG_QUALITY
        } else {
            COMPREHENSIVE_SCENE_JPEG_QUALITY
        }
        val scaled = scaleToMaxEdge(source, maxEdge)
        return try {
            EncodedImage(
                bytes = compress(scaled, Bitmap.CompressFormat.JPEG, quality),
                mimeType = "image/jpeg",
            )
        } finally {
            if (scaled !== source) scaled.recycle()
        }
    }

    private fun scaleForAccurateText(source: Bitmap): Bitmap {
        val largest = maxOf(source.width, source.height)
        val target = when {
            largest < MIN_ACCURATE_TEXT_EDGE -> (largest * ACCURATE_TEXT_UPSCALE_FACTOR).roundToInt()
                .coerceAtMost(MIN_ACCURATE_TEXT_EDGE)
            largest > MAX_ACCURATE_TEXT_EDGE -> MAX_ACCURATE_TEXT_EDGE
            else -> largest
        }
        return scaleToEdge(source, target)
    }

    private fun scaleForFastText(source: Bitmap): Bitmap {
        val largest = maxOf(source.width, source.height)
        val target = when {
            largest < MIN_FAST_TEXT_EDGE -> (largest * FAST_TEXT_UPSCALE_FACTOR).roundToInt()
                .coerceAtMost(MIN_FAST_TEXT_EDGE)
            largest > MAX_FAST_TEXT_EDGE -> MAX_FAST_TEXT_EDGE
            else -> largest
        }
        return scaleToEdge(source, target)
    }

    /**
     * Fast O(n) enhancement using percentile contrast stretching plus a five-point unsharp mask.
     * Two integer arrays are reused to keep peak memory practical on a live phone capture.
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
        val range = high - low
        for (index in luminance.indices) {
            luminance[index] = ((luminance[index] - low) * 255 / range).coerceIn(0, 255)
        }

        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val index = row + x
                val center = luminance[index]
                val left = luminance[row + (x - 1).coerceAtLeast(0)]
                val right = luminance[row + (x + 1).coerceAtMost(width - 1)]
                val up = luminance[(y - 1).coerceAtLeast(0) * width + x]
                val down = luminance[(y + 1).coerceAtMost(height - 1) * width + x]
                val blur = (center * 4 + left + right + up + down) / 8
                val sharpened = (center + (center - blur) * SHARPEN_AMOUNT).roundToInt()
                    .coerceIn(0, 255)
                pixels[index] = Color.rgb(sharpened, sharpened, sharpened)
            }
        }

        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
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
        return if (largest <= maxEdge) source else scaleToEdge(source, maxEdge)
    }

    private fun scaleToEdge(source: Bitmap, targetEdge: Int): Bitmap {
        val largest = maxOf(source.width, source.height)
        if (targetEdge == largest) return source
        val ratio = targetEdge.toFloat() / largest
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
        const val MIN_ACCURATE_TEXT_EDGE = 2_000
        const val MAX_ACCURATE_TEXT_EDGE = 2_800
        const val ACCURATE_TEXT_UPSCALE_FACTOR = 1.6

        const val MIN_FAST_TEXT_EDGE = 1_440
        const val MAX_FAST_TEXT_EDGE = 1_920
        const val FAST_TEXT_UPSCALE_FACTOR = 1.25
        const val FAST_TEXT_JPEG_QUALITY = 90

        const val BRIEF_SCENE_EDGE = 896
        const val COMPREHENSIVE_SCENE_EDGE = 1_152
        const val BRIEF_SCENE_JPEG_QUALITY = 64
        const val COMPREHENSIVE_SCENE_JPEG_QUALITY = 70

        const val MIN_CONTRAST_RANGE = 42
        const val SHARPEN_AMOUNT = 0.72
    }
}
