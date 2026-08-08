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

    /**
     * Halves the plane with a separable 5-tap Gaussian, which is the anti-alias a pyramid needs.
     *
     * This replaced a 2×2 box average. A box filter is a poor low-pass: its frequency response has
     * large side lobes, so energy above the new Nyquist limit is not removed but folded back as
     * aliasing. On a page of text — which is nothing but high-frequency structure at a regular pitch
     * — that folding creates moiré that is *stable between frames*, and a tracker registering two
     * such levels can lock onto the alias rather than the text. The Burt–Adelson kernel
     * `[1 4 6 4 1]/16` is the standard answer and has been since 1983; the cost is four extra
     * multiply-adds per output pixel, on planes of at most 128×128.
     *
     * Separable, so it is two one-dimensional passes rather than a 5×5 convolution: 10 taps per
     * pixel instead of 25.
     */
    fun halved(): ImagePlane {
        val newWidth = max(width / 2, 1)
        val newHeight = max(height / 2, 1)
        val size = newWidth * newHeight
        val y = FloatArray(size)
        val cb = FloatArray(size)
        val cr = FloatArray(size)

        // Horizontal pass into a full-height, half-width intermediate.
        val midY = FloatArray(newWidth * height)
        val midCb = FloatArray(newWidth * height)
        val midCr = FloatArray(newWidth * height)
        for (row in 0 until height) {
            val rowBase = row * width
            val midBase = row * newWidth
            for (column in 0 until newWidth) {
                val centre = column * 2
                var sumY = 0f
                var sumCb = 0f
                var sumCr = 0f
                for (tap in -2..2) {
                    val source = rowBase + (centre + tap).coerceIn(0, width - 1)
                    val weight = GAUSSIAN_5[tap + 2]
                    sumY += luma[source] * weight
                    sumCb += chromaBlue[source] * weight
                    sumCr += chromaRed[source] * weight
                }
                midY[midBase + column] = sumY
                midCb[midBase + column] = sumCb
                midCr[midBase + column] = sumCr
            }
        }

        // Vertical pass into the half-size result.
        for (row in 0 until newHeight) {
            val centre = row * 2
            for (column in 0 until newWidth) {
                var sumY = 0f
                var sumCb = 0f
                var sumCr = 0f
                for (tap in -2..2) {
                    val source = (centre + tap).coerceIn(0, height - 1) * newWidth + column
                    val weight = GAUSSIAN_5[tap + 2]
                    sumY += midY[source] * weight
                    sumCb += midCb[source] * weight
                    sumCr += midCr[source] * weight
                }
                val index = row * newWidth + column
                y[index] = sumY
                cb[index] = sumCb
                cr[index] = sumCr
            }
        }
        return ImagePlane(newWidth, newHeight, y, cb, cr)
    }

    companion object {
        /** Burt & Adelson's binomial kernel, `[1 4 6 4 1] / 16`. */
        private val GAUSSIAN_5 = floatArrayOf(1f / 16f, 4f / 16f, 6f / 16f, 4f / 16f, 1f / 16f)

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
