package com.abdullah.visionbridge.data.ocr

import android.graphics.Bitmap
import android.os.SystemClock
import com.abdullah.visionbridge.data.diagnostics.DiagnosticsHub
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

/** Fast on-device OCR for Latin-script text. Arabic recovery is delegated to Gemini. */
class LocalTextRecognizer {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun recognize(bitmap: Bitmap): String {
        val started = SystemClock.elapsedRealtime()
        DiagnosticsHub.stage("LOCAL_OCR_START", mapOf("width" to bitmap.width, "height" to bitmap.height))
        return try {
            val text = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await().text.trim()
            DiagnosticsHub.stage("LOCAL_OCR_COMPLETE", mapOf(
                "durationMs" to (SystemClock.elapsedRealtime() - started),
                "textLength" to text.length,
                "text" to text,
                "blank" to text.isBlank(),
            ))
            text
        } catch (error: Throwable) {
            DiagnosticsHub.failure(
                "LOCAL_OCR",
                error,
                mapOf("durationMs" to (SystemClock.elapsedRealtime() - started)),
            )
            throw error
        }
    }

    fun close() = recognizer.close()
}
