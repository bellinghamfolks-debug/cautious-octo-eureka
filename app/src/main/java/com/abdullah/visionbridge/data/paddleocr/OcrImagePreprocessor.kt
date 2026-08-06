package com.abdullah.visionbridge.data.paddleocr

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Image preparation for the PP-OCR models, implemented directly rather than through OpenCV.
 *
 * Only a handful of operations are needed — scale to a multiple of 32, crop, scale a crop to a
 * fixed height, rotate, and pack pixels into a normalized CHW tensor — and writing them out keeps a
 * large native dependency out of an APK that already ships four models.
 *
 * The two normalizations below are not interchangeable. PP-OCR's detector is trained with ImageNet
 * statistics, while its recognition and orientation heads are trained with a plain symmetric
 * `(x/255 - 0.5) / 0.5`. Feeding a recognizer ImageNet-normalized pixels puts its input at more than
 * twice the scale it expects, and it answers with confident nonsense rather than an error.
 */
object OcrImagePreprocessor {

    /**
     * A small luminance plane for measuring text size, not for recognising anything.
     *
     * Separate from the detection path because it answers a different question — how big is the
     * text — and answering it needs far fewer pixels than reading it does.
     */
    fun probePlane(source: Bitmap, longEdge: Int): com.abdullah.visionbridge.capture.vision.ImagePlane {
        val scale = longEdge.toFloat() / maxOf(source.width, source.height)
        val width = (source.width * scale).toInt().coerceAtLeast(1)
        val height = (source.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(source, width, height, true)
        return try {
            val pixels = IntArray(width * height)
            scaled.getPixels(pixels, 0, width, 0, 0, width, height)
            com.abdullah.visionbridge.capture.vision.ImagePlane.fromArgb(width, height, pixels)
        } finally {
            if (scaled !== source) scaled.recycle()
        }
    }

    /** The detector's input side must be a multiple of this. */
    private const val SIZE_MULTIPLE = 32

    /** ImageNet normalization — the detector only. */
    private val DETECTION_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val DETECTION_STD = floatArrayOf(0.229f, 0.224f, 0.225f)

    /** Symmetric normalization — the recognition and orientation heads. */
    private val SYMMETRIC_MEAN = floatArrayOf(0.5f, 0.5f, 0.5f)
    private val SYMMETRIC_STD = floatArrayOf(0.5f, 0.5f, 0.5f)

    class Tensor(val data: FloatArray, val width: Int, val height: Int)

    /**
     * Scales [source] so its long edge is at most [maxLongEdge], rounding both sides up to a
     * multiple of 32, and returns the CHW tensor plus the factors that map detector coordinates
     * back to source pixels.
     */
    fun prepareForDetection(source: Bitmap, maxLongEdge: Int): DetectionInput {
        val longest = max(source.width, source.height).coerceAtLeast(1)
        val ratio = min(1f, maxLongEdge.toFloat() / longest)
        val width = roundToMultiple((source.width * ratio).roundToInt())
        val height = roundToMultiple((source.height * ratio).roundToInt())

        val scaled = Bitmap.createScaledBitmap(source, width, height, true)
        return try {
            DetectionInput(
                tensor = toTensor(scaled, DETECTION_MEAN, DETECTION_STD),
                scaleX = source.width.toFloat() / width,
                scaleY = source.height.toFloat() / height,
            )
        } finally {
            if (scaled !== source) scaled.recycle()
        }
    }

    class DetectionInput(val tensor: Tensor, val scaleX: Float, val scaleY: Float)

    /**
     * Crops one detected box and scales it to the recognizer's fixed height, preserving aspect
     * ratio.
     *
     * The recognizers take a fixed height and a variable width up to a maximum. Stretching a short
     * word to the full width distorts the glyphs badly enough to change the reading, so the crop is
     * scaled by height alone.
     *
     * A bitmap is returned rather than a tensor because the orientation classifier has to see the
     * crop before the recognizer does, and may hand back a rotated one.
     */
    fun cropLine(
        source: Bitmap,
        box: TextBox,
        targetHeight: Int,
        maxWidth: Int,
    ): Bitmap? {
        val left = box.left.coerceIn(0, source.width - 1)
        val top = box.top.coerceIn(0, source.height - 1)
        val width = box.width.coerceAtMost(source.width - left)
        val height = box.height.coerceAtMost(source.height - top)
        if (width < 2 || height < 2) return null

        val crop = Bitmap.createBitmap(source, left, top, width, height)
        return try {
            val scaledWidth = ((width.toFloat() / height) * targetHeight)
                .roundToInt()
                .coerceIn(1, maxWidth)
            Bitmap.createScaledBitmap(crop, scaledWidth, targetHeight, true)
        } finally {
            crop.recycle()
        }
    }

    /** Packs an upright line crop for a recognition head. */
    fun toRecognitionTensor(crop: Bitmap): Tensor =
        toTensor(crop, SYMMETRIC_MEAN, SYMMETRIC_STD)

    /**
     * Packs a line crop for the orientation classifier, which takes one fixed input size. A crop
     * narrower than that is padded rather than stretched, matching PaddleOCR's own preprocessing.
     */
    fun toOrientationTensor(crop: Bitmap, targetHeight: Int, targetWidth: Int): Tensor {
        val scaledWidth = ((crop.width.toFloat() / crop.height) * targetHeight)
            .roundToInt()
            .coerceIn(1, targetWidth)
        val scaled = Bitmap.createScaledBitmap(crop, scaledWidth, targetHeight, true)
        return try {
            toTensor(scaled, SYMMETRIC_MEAN, SYMMETRIC_STD, paddedWidth = targetWidth)
        } finally {
            if (scaled !== crop) scaled.recycle()
        }
    }

    /** Packs a bitmap into a normalized CHW float tensor, optionally padding the width. */
    private fun toTensor(
        bitmap: Bitmap,
        mean: FloatArray,
        std: FloatArray,
        paddedWidth: Int = bitmap.width,
    ): Tensor {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val plane = paddedWidth * height
        val data = FloatArray(3 * plane)
        for (y in 0 until height) {
            val rowStart = y * width
            val outRow = y * paddedWidth
            for (x in 0 until width) {
                val pixel = pixels[rowStart + x]
                val red = ((pixel shr 16) and 0xFF) / 255f
                val green = ((pixel shr 8) and 0xFF) / 255f
                val blue = (pixel and 0xFF) / 255f
                val index = outRow + x
                data[index] = (red - mean[0]) / std[0]
                data[plane + index] = (green - mean[1]) / std[1]
                data[2 * plane + index] = (blue - mean[2]) / std[2]
            }
        }
        return Tensor(data, paddedWidth, height)
    }

    /**
     * Straightens a tilted page before detection.
     *
     * Applied to the whole frame rather than to a line crop. A wide strip rotated on its own gains
     * so much vertical padding that the text becomes a sliver of the crop's height and reads worse
     * than the tilt did; the page keeps its proportions.
     */
    fun rotate(bitmap: Bitmap, degrees: Float): Bitmap {
        if (degrees == 0f) return bitmap
        val matrix = android.graphics.Matrix().apply { postRotate(-degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /** Rotates a crop by 180 degrees, for lines the orientation classifier reports upside down. */
    fun rotate180(bitmap: Bitmap): Bitmap {
        val matrix = android.graphics.Matrix().apply { postRotate(180f) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun roundToMultiple(value: Int): Int =
        max(SIZE_MULTIPLE, ((value + SIZE_MULTIPLE - 1) / SIZE_MULTIPLE) * SIZE_MULTIPLE)
}
