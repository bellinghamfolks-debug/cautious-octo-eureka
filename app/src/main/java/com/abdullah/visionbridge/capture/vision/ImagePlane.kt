package com.abdullah.visionbridge.capture.vision

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * One level of a frame: luminance plus the two chroma channels, as floats in 0..255.
 *
 * Colour is carried rather than thrown away. The previous signature reduced every frame to a
 * grayscale grid, which cannot see a red label replaced by a blue one of the same layout, and
 * cannot use colour to hold a lock on a subject whose luminance is flat.
 */
class ImagePlane(
    val width: Int,
    val height: Int,
    val luma: FloatArray,
    val chromaBlue: FloatArray,
    val chromaRed: FloatArray,
) {
    init {
        val expected = width * height
        require(width > 0 && height > 0) { "an image plane needs a positive size" }
        require(luma.size == expected && chromaBlue.size == expected && chromaRed.size == expected) {
            "every channel must hold $expected samples"
        }
    }

    fun luma(x: Int, y: Int): Float = luma[y * width + x]

    fun contains(x: Double, y: Double): Boolean =
        x >= 0.0 && y >= 0.0 && x <= width - 1.0 && y <= height - 1.0

    /** Bilinear sample of luminance. Callers check [contains] first. */
    fun sampleLuma(x: Double, y: Double): Float = sample(luma, x, y)

    fun sampleChromaBlue(x: Double, y: Double): Float = sample(chromaBlue, x, y)

    fun sampleChromaRed(x: Double, y: Double): Float = sample(chromaRed, x, y)

    private fun sample(channel: FloatArray, x: Double, y: Double): Float {
        val clampedX = min(max(x, 0.0), width - 1.0)
        val clampedY = min(max(y, 0.0), height - 1.0)
        val x0 = floor(clampedX).toInt()
        val y0 = floor(clampedY).toInt()
        val x1 = min(x0 + 1, width - 1)
        val y1 = min(y0 + 1, height - 1)
        val fx = (clampedX - x0).toFloat()
        val fy = (clampedY - y0).toFloat()
        val top = channel[y0 * width + x0] * (1 - fx) + channel[y0 * width + x1] * fx
        val bottom = channel[y1 * width + x0] * (1 - fx) + channel[y1 * width + x1] * fx
        return top * (1 - fy) + bottom * fy
    }

    /** Central-difference gradient of luminance, clamped at the border. */
    fun gradientX(x: Int, y: Int): Float {
        val left = luma[y * width + max(x - 1, 0)]
        val right = luma[y * width + min(x + 1, width - 1)]
        return (right - left) * 0.5f
    }

    fun gradientY(x: Int, y: Int): Float {
        val up = luma[max(y - 1, 0) * width + x]
        val down = luma[min(y + 1, height - 1) * width + x]
        return (down - up) * 0.5f
    }

    /** Halves the plane with a 2×2 box filter, which is the anti-alias a pyramid needs. */
    fun halved(): ImagePlane {
        val newWidth = max(width / 2, 1)
        val newHeight = max(height / 2, 1)
        val size = newWidth * newHeight
        val y = FloatArray(size)
        val cb = FloatArray(size)
        val cr = FloatArray(size)
        for (row in 0 until newHeight) {
            val sourceRow0 = min(row * 2, height - 1)
            val sourceRow1 = min(row * 2 + 1, height - 1)
            for (column in 0 until newWidth) {
                val sourceColumn0 = min(column * 2, width - 1)
                val sourceColumn1 = min(column * 2 + 1, width - 1)
                val a = sourceRow0 * width + sourceColumn0
                val b = sourceRow0 * width + sourceColumn1
                val c = sourceRow1 * width + sourceColumn0
                val d = sourceRow1 * width + sourceColumn1
                val index = row * newWidth + column
                y[index] = (luma[a] + luma[b] + luma[c] + luma[d]) * 0.25f
                cb[index] = (chromaBlue[a] + chromaBlue[b] + chromaBlue[c] + chromaBlue[d]) * 0.25f
                cr[index] = (chromaRed[a] + chromaRed[b] + chromaRed[c] + chromaRed[d]) * 0.25f
            }
        }
        return ImagePlane(newWidth, newHeight, y, cb, cr)
    }

    companion object {
        /**
         * Builds a plane from packed sRGB, converting to Y'CbCr so luminance and colour can be
         * weighed separately.
         */
        fun fromArgb(width: Int, height: Int, pixels: IntArray): ImagePlane {
            val size = width * height
            val luma = FloatArray(size)
            val cb = FloatArray(size)
            val cr = FloatArray(size)
            for (index in 0 until size) {
                val pixel = pixels[index]
                val r = ((pixel shr 16) and 0xFF).toFloat()
                val g = ((pixel shr 8) and 0xFF).toFloat()
                val b = (pixel and 0xFF).toFloat()
                val y = 0.299f * r + 0.587f * g + 0.114f * b
                luma[index] = y
                cb[index] = 128f + 0.5f * (b - y) / (1f - 0.114f)
                cr[index] = 128f + 0.5f * (r - y) / (1f - 0.299f)
            }
            return ImagePlane(width, height, luma, cb, cr)
        }
    }
}

/**
 * A frame at several resolutions, finest first.
 *
 * Coarse levels give a wide capture range for large motion; fine levels give the precision that
 * decides whether what is left after alignment is a different subject or the same one. Neither
 * alone is enough, which is why a single small grid could never do this job.
 */
class FramePyramid(val levels: List<ImagePlane>) {
    init {
        require(levels.isNotEmpty()) { "a pyramid needs at least one level" }
    }

    val finest: ImagePlane get() = levels.first()
    val coarsest: ImagePlane get() = levels.last()
    val depth: Int get() = levels.size

    operator fun get(level: Int): ImagePlane = levels[level]

    companion object {
        fun of(base: ImagePlane, depth: Int): FramePyramid {
            val levels = ArrayList<ImagePlane>(depth)
            levels.add(base)
            var current = base
            while (levels.size < depth && current.width > MIN_LEVEL_SIZE && current.height > MIN_LEVEL_SIZE) {
                current = current.halved()
                levels.add(current)
            }
            return FramePyramid(levels)
        }

        /**
         * Below this a level carries no usable structure and actively poisons the estimate.
         *
         * Measured: on a page of text, aligning at 16×16 alone reported a translation of 2.98 cells
         * where the truth was 0.375 — and because a coarse level's answer is multiplied by two at
         * every step down, that error arrived at full resolution as a 24-pixel shift. 32×32 alone
         * reports 0.718 against a truth of 0.750.
         */
        const val MIN_LEVEL_SIZE = 32
    }
}
