package com.abdullah.visionbridge.capture.vision

import kotlin.random.Random

/**
 * Constructed scenes with a known ground truth, so registration can be checked against the exact
 * transform that produced a frame rather than against a guess.
 *
 * The pages here have irregular line pitch, paragraph breaks, headings and a figure block. An
 * earlier version used a perfectly periodic stack of identical lines, and every alignment method
 * has multiple equal minima on a periodic signal — the failures that produced belonged to the test,
 * not to the code under it.
 */
object VisionScenes {

    const val SIZE = 128

    fun page(seed: Int = 7, colourShift: Int = 0): ImagePlane {
        val random = Random(seed)
        val pixels = IntArray(SIZE * SIZE) { PAPER }

        fun put(x: Int, y: Int, rgb: Int) {
            if (x in 0 until SIZE && y in 0 until SIZE) pixels[y * SIZE + x] = rgb
        }

        fun block(x0: Int, y0: Int, x1: Int, y1: Int, rgb: Int) {
            for (y in y0 until y1) for (x in x0 until x1) put(x, y, rgb)
        }

        block(6, 4, SIZE - 6, 15, HEADER + colourShift)
        block(SIZE - 44, 20, SIZE - 10, 52, FIGURE)
        repeat(40) {
            val x = SIZE - 42 + random.nextInt(30)
            val y = 22 + random.nextInt(28)
            block(x, y, x + 3, y + 3, FIGURE_INK)
        }

        var row = 20
        var paragraph = 0
        while (row < SIZE - 12) {
            val heading = paragraph % 3 == 0
            val pitch = if (heading) 7 else 5 + random.nextInt(2)
            val thickness = if (heading) 3 else 2
            val rightEdge = if (row < 52) SIZE - 48 else SIZE - 8
            var column = 8
            while (column < rightEdge - 4) {
                val word = 3 + random.nextInt(if (heading) 6 else 10)
                val ink = if (heading) HEADING_INK else (0xFF000000.toInt() or (random.nextInt(50) * 0x010101))
                for (i in 0 until word) for (t in 0 until thickness) put(column + i, row + t, ink)
                column += word + 2 + random.nextInt(3)
            }
            row += pitch
            if (random.nextInt(4) == 0) {
                row += 4
                paragraph++
            }
        }
        return ImagePlane.fromArgb(SIZE, SIZE, pixels)
    }

    /** A perfume bottle: a bright label band with dark type, on a dark background. */
    fun bottle(seed: Int = 3): ImagePlane {
        val random = Random(seed)
        val pixels = IntArray(SIZE * SIZE) { 0xFF1C1C22.toInt() }
        for (y in 30 until 96) {
            for (x in 40 until 88) pixels[y * SIZE + x] = 0xFFE8E4DA.toInt()
        }
        var row = 44
        while (row < 86) {
            var column = 46
            while (column < 80) {
                val word = 3 + random.nextInt(6)
                for (i in 0 until word) {
                    for (t in 0 until 3) {
                        val x = column + i
                        val y = row + t
                        if (x in 0 until SIZE && y in 0 until SIZE) pixels[y * SIZE + x] = 0xFF141414.toInt()
                    }
                }
                column += word + 3
            }
            row += 9
        }
        return ImagePlane.fromArgb(SIZE, SIZE, pixels)
    }

    fun flat(level: Int): ImagePlane {
        val value = level.toFloat()
        val size = SIZE * SIZE
        return ImagePlane(SIZE, SIZE, FloatArray(size) { value }, FloatArray(size) { 128f }, FloatArray(size) { 128f })
    }

    /** Applies a known transform by inverse sampling, so the answer is exactly recoverable. */
    fun transform(plane: ImagePlane, warp: Warp): ImagePlane {
        val inverse = warp.inverse() ?: return plane
        val size = SIZE * SIZE
        val luma = FloatArray(size)
        val cb = FloatArray(size)
        val cr = FloatArray(size)
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                val index = y * SIZE + x
                val source = inverse.apply(x.toDouble(), y.toDouble())
                if (source != null && plane.contains(source[0], source[1])) {
                    luma[index] = plane.sampleLuma(source[0], source[1])
                    cb[index] = plane.sampleChromaBlue(source[0], source[1])
                    cr[index] = plane.sampleChromaRed(source[0], source[1])
                } else {
                    luma[index] = 242f
                    cb[index] = 128f
                    cr[index] = 128f
                }
            }
        }
        return ImagePlane(SIZE, SIZE, luma, cb, cr)
    }

    fun withNoise(plane: ImagePlane, amplitude: Float, seed: Int): ImagePlane {
        val random = Random(seed)
        val size = plane.luma.size
        return ImagePlane(
            plane.width,
            plane.height,
            FloatArray(size) {
                (plane.luma[it] + (random.nextFloat() * 2f - 1f) * amplitude).coerceIn(0f, 255f)
            },
            plane.chromaBlue,
            plane.chromaRed,
        )
    }

    /** Uniform brightness and contrast change: the same content under different light. */
    fun relit(plane: ImagePlane, gain: Float, offset: Float): ImagePlane = ImagePlane(
        plane.width,
        plane.height,
        FloatArray(plane.luma.size) { (plane.luma[it] * gain + offset).coerceIn(0f, 255f) },
        plane.chromaBlue,
        plane.chromaRed,
    )

    fun pyramid(plane: ImagePlane, depth: Int = 4): FramePyramid = FramePyramid.of(plane, depth)

    fun frame(plane: ImagePlane): TrackedFrame = TrackedFrame(pyramid(plane))

    private const val PAPER = 0xFFF2EFE8.toInt()
    private const val HEADER = 0xFF1E5AA8.toInt()
    private const val FIGURE = 0xFFB8CCE0.toInt()
    private const val FIGURE_INK = 0xFF335577.toInt()
    private const val HEADING_INK = 0xFF101010.toInt()
}
