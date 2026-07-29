package com.abdullah.visionbridge.capture

import android.graphics.Bitmap
import android.media.Image
import com.abdullah.visionbridge.data.diagnostics.DiagnosticsHub
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
        val result = if (paddedWidth == image.width) {
            padded
        } else {
            Bitmap.createBitmap(padded, 0, 0, image.width, image.height).also { padded.recycle() }
        }
        DiagnosticsHub.frameConverted(result)
        return result
    }
}
