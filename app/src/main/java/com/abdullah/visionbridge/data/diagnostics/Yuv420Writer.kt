package com.abdullah.visionbridge.data.diagnostics

import java.nio.ByteBuffer

/**
 * Converts ARGB pixels into the three planes a video encoder takes, whatever their layout.
 *
 * Encoders differ in how they lay out 4:2:0 input — separate U and V planes on one device,
 * interleaved on another — and `MediaCodec.getInputImage` describes the layout instead of fixing
 * it. Writing through each plane's own row and pixel stride is what lets one converter feed all of
 * them, and keeps it testable without a device.
 *
 * BT.601 limited range, which is what an encoder assumes when it is told nothing else.
 */
object Yuv420Writer {
    class Plane(val buffer: ByteBuffer, val rowStride: Int, val pixelStride: Int)

    fun write(argb: IntArray, width: Int, height: Int, y: Plane, u: Plane, v: Plane) {
        require(argb.size >= width * height) { "pixel array smaller than the picture" }
        for (row in 0 until height) {
            val source = row * width
            val target = row * y.rowStride
            for (column in 0 until width) {
                val pixel = argb[source + column]
                val r = pixel shr 16 and 0xFF
                val g = pixel shr 8 and 0xFF
                val b = pixel and 0xFF
                y.buffer.put(target + column * y.pixelStride, luma(r, g, b).toByte())
            }
        }
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        for (row in 0 until chromaHeight) {
            for (column in 0 until chromaWidth) {
                // The average of the 2×2 block, so a one-pixel line still tints its block.
                var r = 0
                var g = 0
                var b = 0
                var count = 0
                for (dy in 0..1) {
                    val sourceRow = row * 2 + dy
                    if (sourceRow >= height) continue
                    for (dx in 0..1) {
                        val sourceColumn = column * 2 + dx
                        if (sourceColumn >= width) continue
                        val pixel = argb[sourceRow * width + sourceColumn]
                        r += pixel shr 16 and 0xFF
                        g += pixel shr 8 and 0xFF
                        b += pixel and 0xFF
                        count++
                    }
                }
                r /= count
                g /= count
                b /= count
                u.buffer.put(row * u.rowStride + column * u.pixelStride, chromaBlue(r, g, b).toByte())
                v.buffer.put(row * v.rowStride + column * v.pixelStride, chromaRed(r, g, b).toByte())
            }
        }
    }

    fun luma(r: Int, g: Int, b: Int): Int = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
    fun chromaBlue(r: Int, g: Int, b: Int): Int = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
    fun chromaRed(r: Int, g: Int, b: Int): Int = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
}
