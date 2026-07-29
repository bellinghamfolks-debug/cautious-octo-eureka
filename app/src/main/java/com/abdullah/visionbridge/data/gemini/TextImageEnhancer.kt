package com.abdullah.visionbridge.data.gemini

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import com.abdullah.visionbridge.domain.model.AnalysisMode
import com.abdullah.visionbridge.domain.model.CaptureProfile
import com.abdullah.visionbridge.domain.model.SceneDescriptionStyle
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/** Prepares one frame and reports the latency and parameters of every image-processing phase. */
class TextImageEnhancer {
    data class EncodedImage(
        val bytes: ByteArray,
        val mimeType: String,
        val outputWidth: Int,
        val outputHeight: Int,
        val format: String,
        val quality: Int,
        val scaleMs: Double,
        val enhancementMs: Double,
        val compressionMs: Double,
        val contrastLowPercentile: Int? = null,
        val contrastHighPercentile: Int? = null,
    )

    private data class EnhancementResult(
        val bitmap: Bitmap,
        val lowPercentile: Int,
        val highPercentile: Int,
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

    /**
     * Accurate OCR keeps colour and fine text, while removing only detected dark camera chrome.
     *
     * The previous lossless full-screen PNG cost roughly two seconds to encode and included Leica
     * labels, zoom controls and a large black shutter panel. A high-quality colour JPEG of the actual
     * preview region is substantially smaller and faster, while quality 94 preserves document and UI
     * text far better than the old fast profile.
     */
    private fun prepareAccurateText(source: Bitmap): EncodedImage {
        val scaleStarted = SystemClock.elapsedRealtimeNanos()
        val content = cropDetectedCameraChrome(source)
        val scaled = scaleForAccurateText(content)
        val scaleMs = elapsedMs(scaleStarted)
        return try {
            val compressionStarted = SystemClock.elapsedRealtimeNanos()
            val bytes = compress(scaled, Bitmap.CompressFormat.JPEG, ACCURATE_TEXT_JPEG_QUALITY)
            val compressionMs = elapsedMs(compressionStarted)
            EncodedImage(
                bytes = bytes,
                mimeType = "image/jpeg",
                outputWidth = scaled.width,
                outputHeight = scaled.height,
                format = "JPEG_COLOR_FOCUSED",
                quality = ACCURATE_TEXT_JPEG_QUALITY,
                scaleMs = scaleMs,
                enhancementMs = 0.0,
                compressionMs = compressionMs,
            )
        } finally {
            if (scaled !== content) scaled.recycle()
            if (content !== source) content.recycle()
        }
    }

    /** Fast text keeps lightweight contrast enhancement for captions and rapidly changing UI. */
    private fun prepareFastText(source: Bitmap): EncodedImage {
        val scaleStarted = SystemClock.elapsedRealtimeNanos()
        val content = cropDetectedCameraChrome(source)
        val scaled = scaleForFastText(content)
        val scaleMs = elapsedMs(scaleStarted)

        val enhancementStarted = SystemClock.elapsedRealtimeNanos()
        val enhancement = enhanceTextContrast(scaled)
        val enhancementMs = elapsedMs(enhancementStarted)
        if (scaled !== content) scaled.recycle()
        if (content !== source) content.recycle()

        return try {
            val compressionStarted = SystemClock.elapsedRealtimeNanos()
            val bytes = compress(enhancement.bitmap, Bitmap.CompressFormat.JPEG, FAST_TEXT_JPEG_QUALITY)
            val compressionMs = elapsedMs(compressionStarted)
            EncodedImage(
                bytes = bytes,
                mimeType = "image/jpeg",
                outputWidth = enhancement.bitmap.width,
                outputHeight = enhancement.bitmap.height,
                format = "JPEG_ENHANCED_FOCUSED",
                quality = FAST_TEXT_JPEG_QUALITY,
                scaleMs = scaleMs,
                enhancementMs = enhancementMs,
                compressionMs = compressionMs,
                contrastLowPercentile = enhancement.lowPercentile,
                contrastHighPercentile = enhancement.highPercentile,
            )
        } finally {
            enhancement.bitmap.recycle()
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
        val scaleStarted = SystemClock.elapsedRealtimeNanos()
        val content = cropDetectedCameraChrome(source)
        val scaled = scaleToMaxEdge(content, maxEdge)
        val scaleMs = elapsedMs(scaleStarted)
        return try {
            val compressionStarted = SystemClock.elapsedRealtimeNanos()
            val bytes = compress(scaled, Bitmap.CompressFormat.JPEG, quality)
            val compressionMs = elapsedMs(compressionStarted)
            EncodedImage(
                bytes = bytes,
                mimeType = "image/jpeg",
                outputWidth = scaled.width,
                outputHeight = scaled.height,
                format = "JPEG_FOCUSED",
                quality = quality,
                scaleMs = scaleMs,
                enhancementMs = 0.0,
                compressionMs = compressionMs,
            )
        } finally {
            if (scaled !== content) scaled.recycle()
            if (content !== source) content.recycle()
        }
    }

    /**
     * Detects broad dark bands at the top and bottom instead of applying a fixed crop. This preserves
     * ordinary full-screen documents, yet removes the Xiaomi camera chrome seen in diagnostics. The
     * crop is accepted only when enough image content remains, so a genuinely dark scene is retained.
     */
    private fun cropDetectedCameraChrome(source: Bitmap): Bitmap {
        if (source.height < MIN_CROP_HEIGHT || source.width < MIN_CROP_WIDTH) return source

        val sampleStepX = (source.width / CROP_HORIZONTAL_SAMPLES).coerceAtLeast(1)
        val darkRatios = DoubleArray(source.height)
        for (y in 0 until source.height) {
            var dark = 0
            var samples = 0
            var x = 0
            while (x < source.width) {
                val color = source.getPixel(x, y)
                val gray = (
                    Color.red(color) * 299 +
                        Color.green(color) * 587 +
                        Color.blue(color) * 114
                    ) / 1000
                if (gray <= CAMERA_CHROME_DARK_LUMA) dark++
                samples++
                x += sampleStepX
            }
            darkRatios[y] = if (samples == 0) 0.0 else dark.toDouble() / samples
        }

        fun smoothedDarkRatio(row: Int): Double {
            val start = (row - CROP_SMOOTH_RADIUS).coerceAtLeast(0)
            val end = (row + CROP_SMOOTH_RADIUS).coerceAtMost(source.height - 1)
            var total = 0.0
            for (index in start..end) total += darkRatios[index]
            return total / (end - start + 1)
        }

        val maxTop = (source.height * MAX_TOP_CROP_FRACTION).roundToInt()
        var top = 0
        var lightRun = 0
        for (y in 0 until maxTop) {
            if (smoothedDarkRatio(y) >= CAMERA_CHROME_DARK_RATIO) {
                top = y + 1
                lightRun = 0
            } else {
                lightRun++
                if (top > 0 && lightRun >= CROP_EXIT_RUN_ROWS) break
            }
        }

        val minBottom = (source.height * MIN_BOTTOM_CONTENT_FRACTION).roundToInt()
        var bottom = source.height
        lightRun = 0
        for (y in source.height - 1 downTo minBottom) {
            if (smoothedDarkRatio(y) >= CAMERA_CHROME_DARK_RATIO) {
                bottom = y
                lightRun = 0
            } else {
                lightRun++
                if (bottom < source.height && lightRun >= CROP_EXIT_RUN_ROWS) break
            }
        }

        val margin = (source.height * CROP_SAFETY_MARGIN_FRACTION).roundToInt()
        val safeTop = (top - margin).coerceAtLeast(0)
        val safeBottom = (bottom + margin).coerceAtMost(source.height)
        val removed = safeTop + (source.height - safeBottom)
        val remaining = safeBottom - safeTop
        val minimumRemaining = (source.height * MIN_REMAINING_CONTENT_FRACTION).roundToInt()
        val minimumRemoved = (source.height * MIN_REMOVED_FRACTION).roundToInt()

        if (removed < minimumRemoved || remaining < minimumRemaining) return source
        return Bitmap.createBitmap(source, 0, safeTop, source.width, remaining)
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

    /** Percentile contrast stretching plus a five-point unsharp mask. */
    private fun enhanceTextContrast(source: Bitmap): EnhancementResult {
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

        return EnhancementResult(
            bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888),
            lowPercentile = low,
            highPercentile = high,
        )
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

    private fun elapsedMs(startedAtNanos: Long): Double =
        (SystemClock.elapsedRealtimeNanos() - startedAtNanos) / 1_000_000.0

    private companion object {
        const val MIN_ACCURATE_TEXT_EDGE = 2_000
        const val MAX_ACCURATE_TEXT_EDGE = 2_400
        const val ACCURATE_TEXT_UPSCALE_FACTOR = 1.5
        const val ACCURATE_TEXT_JPEG_QUALITY = 94

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

        const val MIN_CROP_WIDTH = 320
        const val MIN_CROP_HEIGHT = 640
        const val CROP_HORIZONTAL_SAMPLES = 96
        const val CROP_SMOOTH_RADIUS = 5
        const val CAMERA_CHROME_DARK_LUMA = 45
        const val CAMERA_CHROME_DARK_RATIO = 0.68
        const val MAX_TOP_CROP_FRACTION = 0.24
        const val MIN_BOTTOM_CONTENT_FRACTION = 0.62
        const val CROP_EXIT_RUN_ROWS = 12
        const val CROP_SAFETY_MARGIN_FRACTION = 0.012
        const val MIN_REMAINING_CONTENT_FRACTION = 0.45
        const val MIN_REMOVED_FRACTION = 0.08
    }
}
