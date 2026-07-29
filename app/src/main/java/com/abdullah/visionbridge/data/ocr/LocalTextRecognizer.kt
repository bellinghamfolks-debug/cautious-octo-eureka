package com.abdullah.visionbridge.data.ocr

import android.graphics.Bitmap
import android.os.SystemClock
import com.abdullah.visionbridge.data.diagnostics.DiagnosticHub
import com.abdullah.visionbridge.data.diagnostics.DiagnosticTrace
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.tasks.await

/** Fast on-device OCR for Latin-script text. Arabic recovery is delegated to Gemini. */
class LocalTextRecognizer {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun recognize(bitmap: Bitmap): String {
        val trace = currentCoroutineContext()[DiagnosticTrace]
        val started = SystemClock.elapsedRealtimeNanos()
        DiagnosticHub.record(
            "MLKIT_PROCESS_STARTED",
            trace.fieldsOrEmpty(mapOf("width" to bitmap.width, "height" to bitmap.height)),
        )
        return try {
            val text = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await().text.trim()
            DiagnosticHub.record(
                "MLKIT_PROCESS_COMPLETED",
                trace.fieldsOrEmpty(
                    mapOf(
                        "durationMs" to (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0,
                        "textLength" to text.length,
                        "text" to text,
                        "blank" to text.isBlank(),
                    ),
                ),
            )
            text
        } catch (error: Throwable) {
            DiagnosticHub.failure(
                "MLKIT_PROCESS",
                error,
                trace.fieldsOrEmpty(
                    mapOf("durationMs" to (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0),
                ),
            )
            throw error
        }
    }

    fun close() = recognizer.close()

    private fun DiagnosticTrace?.fieldsOrEmpty(extra: Map<String, Any?> = emptyMap()): Map<String, Any?> =
        this?.fields(extra) ?: extra
}
