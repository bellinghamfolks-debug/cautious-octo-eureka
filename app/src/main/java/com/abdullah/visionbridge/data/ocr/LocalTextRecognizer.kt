package com.abdullah.visionbridge.data.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import com.abdullah.visionbridge.data.diagnostics.DiagnosticHub
import com.abdullah.visionbridge.data.diagnostics.DiagnosticTrace
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/**
 * Safe on-device OCR lane.
 *
 * ML Kit Latin is fast and reliable enough to publish immediately. The former Arabic-only
 * Tesseract lane was deliberately removed from user-facing output after real-device diagnostics
 * proved that it transliterated clear English screens into invented Arabic letters and digits with
 * very low confidence. Arabic and mixed text continue through Gemini until a trustworthy local
 * Arabic recognizer is available.
 */
class LocalTextRecognizer(context: Context) {
    private val appContext = context.applicationContext
    private val latinRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val requestSequence = AtomicLong(0L)

    init {
        DiagnosticHub.record(
            "LOCAL_OCR_SAFETY_POLICY_ACTIVE",
            mapOf(
                "packageName" to appContext.packageName,
                "userFacingLocalScripts" to listOf("LATIN"),
                "arabicLocalUserOutputEnabled" to false,
                "arabicFallback" to "GEMINI",
                "reason" to "tesseract_arabic_hallucinated_on_english_input",
            ),
        )
    }

    suspend fun recognize(
        bitmap: Bitmap,
        onPartial: (suspend (text: String, source: String) -> Unit)? = null,
    ): String {
        val requestId = requestSequence.incrementAndGet()
        val emit: suspend (String, String) -> Unit = onPartial ?: { text, source ->
            InstantLocalOcrBridge.publish(requestId, text, source)
        }

        val text = withContext(Dispatchers.Default) {
            runCatching { recognizeLatin(bitmap) }
                .onFailure { error -> DiagnosticHub.failure("MLKIT_PROCESS", error) }
                .getOrDefault("")
        }

        if (text.isNotBlank()) {
            emit(text, "MLKIT_LATIN")
        }

        DiagnosticHub.record(
            "LOCAL_OCR_COMPLETED",
            currentCoroutineContext()[DiagnosticTrace].fieldsOrEmpty(
                mapOf(
                    "requestId" to requestId,
                    "text" to text,
                    "textLength" to text.length,
                    "blank" to text.isBlank(),
                    "userFacingLocalScripts" to listOf("LATIN"),
                    "arabicLocalSuppressedBySafetyPolicy" to true,
                    "arabicFallback" to "GEMINI",
                ),
            ),
        )
        return text
    }

    private suspend fun recognizeLatin(bitmap: Bitmap): String {
        val trace = currentCoroutineContext()[DiagnosticTrace]
        val started = SystemClock.elapsedRealtimeNanos()
        DiagnosticHub.record(
            "MLKIT_PROCESS_STARTED",
            trace.fieldsOrEmpty(mapOf("width" to bitmap.width, "height" to bitmap.height)),
        )
        val result = latinRecognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
        val text = result.text.trim()
        val blockGeometry = result.textBlocks.mapIndexed { blockIndex, block ->
            mapOf(
                "blockIndex" to blockIndex,
                "textLength" to block.text.length,
                "lineCount" to block.lines.size,
                "boundingBox" to normalizedBox(block.boundingBox, bitmap.width, bitmap.height),
                "lines" to block.lines.mapIndexed { lineIndex, line ->
                    mapOf(
                        "lineIndex" to lineIndex,
                        "textLength" to line.text.length,
                        "elementCount" to line.elements.size,
                        "boundingBox" to normalizedBox(line.boundingBox, bitmap.width, bitmap.height),
                        "recognizedText" to line.text,
                    )
                },
            )
        }
        DiagnosticHub.record(
            "MLKIT_PROCESS_COMPLETED",
            trace.fieldsOrEmpty(
                mapOf(
                    "durationMs" to elapsedMs(started),
                    "textLength" to text.length,
                    "lineCount" to result.textBlocks.sumOf { it.lines.size },
                    "blockCount" to result.textBlocks.size,
                    "wordCount" to wordCount(text),
                    "arabicCharacterCount" to text.count(::isArabic),
                    "latinCharacterCount" to text.count(::isLatin),
                    "textCoverage" to textCoverage(result, bitmap.width, bitmap.height),
                    "textGeometry" to blockGeometry,
                    "text" to text,
                    "blank" to text.isBlank(),
                ),
            ),
        )
        return text
    }

    private fun textCoverage(result: Text, width: Int, height: Int): Double {
        val frameArea = width.toDouble() * height.toDouble()
        if (frameArea <= 0.0) return 0.0
        val estimatedArea = result.textBlocks.sumOf { block ->
            block.boundingBox?.let {
                it.width().coerceAtLeast(0).toLong() * it.height().coerceAtLeast(0).toLong()
            } ?: 0L
        }
        return (estimatedArea / frameArea).coerceIn(0.0, 1.0)
    }

    private fun normalizedBox(rect: Rect?, width: Int, height: Int): Map<String, Double>? {
        rect ?: return null
        if (width <= 0 || height <= 0) return null
        return mapOf(
            "left" to (rect.left.toDouble() / width).coerceIn(0.0, 1.0),
            "top" to (rect.top.toDouble() / height).coerceIn(0.0, 1.0),
            "right" to (rect.right.toDouble() / width).coerceIn(0.0, 1.0),
            "bottom" to (rect.bottom.toDouble() / height).coerceIn(0.0, 1.0),
            "width" to (rect.width().toDouble() / width).coerceIn(0.0, 1.0),
            "height" to (rect.height().toDouble() / height).coerceIn(0.0, 1.0),
        )
    }

    fun close() {
        latinRecognizer.close()
    }

    private fun wordCount(text: String): Int = text
        .trim()
        .split(Regex("\\s+"))
        .count { it.isNotBlank() }

    private fun isArabic(value: Char): Boolean = value in '\u0600'..'\u06FF'
    private fun isLatin(value: Char): Boolean = value in 'A'..'Z' || value in 'a'..'z'

    private fun elapsedMs(started: Long): Double =
        (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0

    private fun DiagnosticTrace?.fieldsOrEmpty(
        extra: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> = this?.fields(extra) ?: extra
}
