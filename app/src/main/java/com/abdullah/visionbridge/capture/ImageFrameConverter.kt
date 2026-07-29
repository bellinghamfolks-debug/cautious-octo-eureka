package com.abdullah.visionbridge.capture

import android.graphics.Bitmap
import android.media.Image
import java.nio.ByteBuffer

object ImageFrameConverter {
    /** Copies an RGBA_8888 ImageReader frame and removes row padding safely. */
    fun toBitmap(image: Image): Bitmap {
        val plane = image.planes.firstOrNull() ?: error("إطار الشاشة لا يحتوي بيانات")
        val buffer: ByteBuffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val paddedWidth = image.width + rowPadding / pixelStride

        buffer.rewind()
        val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(buffer)
        if (paddedWidth == image.width) return padded

        val cropped = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        padded.recycle()
        return cropped
    }
}
