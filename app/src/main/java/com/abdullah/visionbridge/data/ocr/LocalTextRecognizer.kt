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
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

/**
 * Two-lane on-device OCR.
 *
 * ML Kit returns Latin text very quickly, while a pre-warmed Tesseract LSTM instance reads Arabic.
 * The first useful lane is emitted immediately; the merged result follows when both lanes finish.
 * Diagnostics include text geometry and confidence summaries so image files are not required.
 */
class LocalTextRecognizer(context: Context) {
    private val appContext = context.applicationContext
    private val latinRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val tesseractMutex = Mutex()
    private val warmupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val requestSequence = AtomicLong(0L)

    @Volatile
    private var arabicEngine: TessBaseAPI? = null

    init {
        warmupScope.launch {
            val started = SystemClock.elapsedRealtimeNanos()
            DiagnosticHub.record("TESSERACT_WARMUP_STARTED")
            runCatching { tesseractMutex.withLock { ensureArabicEngineLocked() } }
                .onSuccess {
                    DiagnosticHub.record(
                        "TESSERACT_WARMUP_COMPLETED",
                        mapOf("durationMs" to elapsedMs(started)),
                    )
                }
                .onFailure { error ->
                    DiagnosticHub.failure(
                        "TESSERACT_WARMUP",
                        error,
                        mapOf("durationMs" to elapsedMs(started)),
                    )
                }
        }
    }

    suspend fun recognize(
        bitmap: Bitmap,
        onPartial: (suspend (text: String, source: String) -> Unit)? = null,
    ): String {
        val requestId = requestSequence.incrementAndGet()
        val emit: suspend (String, String) -> Unit = onPartial ?: { text, source ->
            InstantLocalOcrBridge.publish(requestId, text, source)
        }

        return supervisorScope {
            val latin = async(Dispatchers.Default) {
                runCatching { recognizeLatin(bitmap) }
                    .onFailure { error -> DiagnosticHub.failure("MLKIT_PROCESS", error) }
                    .getOrDefault("")
            }
            val arabic = async(Dispatchers.Default) {
                runCatching { recognizeArabic(bitmap) }
                    .onFailure { error -> DiagnosticHub.failure("TESSERACT_PROCESS", error) }
                    .getOrDefault("")
            }

            var firstText = ""
            select<Unit> {
                latin.onAwait { text ->
                    if (text.isNotBlank()) {
                        firstText = text
                        emit(text, "MLKIT_LATIN")
                    }
                }
                arabic.onAwait { text ->
                    if (text.isNotBlank()) {
                        firstText = text
                        emit(text, "TESSERACT_ARABIC")
                    }
                }
            }

            val latinText = latin.await()
            val arabicText = arabic.await()
            val merged = LocalOcrTextMerger.merge(arabicText, latinText)
            DiagnosticHub.record(
                "HYBRID_LOCAL_OCR_MERGED",
                mapOf(
                    "requestId" to requestId,
                    "latinLength" to latinText.length,
                    "arabicLength" to arabicText.length,
                    "mergedLength" to merged.length,
                    "mergedLineCount" to merged.lineSequence().count { it.isNotBlank() },
                    "mergedWordCount" to wordCount(merged),
                    "arabicCharacterCount" to merged.count(::isArabic),
                    "latinCharacterCount" to merged.count(::isLatin),
                    "text" to merged,
                ),
            )
            if (merged.isNotBlank() && LocalOcrTextMerger.novel(firstText, merged).isNotBlank()) {
                emit(merged, "HYBRID_LOCAL_FINAL")
            }
            merged
        }
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

    private suspend fun recognizeArabic(bitmap: Bitmap): String = tesseractMutex.withLock {
        val trace = currentCoroutineContext()[DiagnosticTrace]
        val started = SystemClock.elapsedRealtimeNanos()
        val prepared = scaleToMaxEdge(bitmap, ARABIC_MAX_EDGE)
        DiagnosticHub.record(
            "TESSERACT_PROCESS_STARTED",
            trace.fieldsOrEmpty(
                mapOf(
                    "inputWidth" to bitmap.width,
                    "inputHeight" to bitmap.height,
                    "width" to prepared.width,
                    "height" to prepared.height,
                ),
            ),
        )
        try {
            val engine = ensureArabicEngineLocked()
            engine.setImage(prepared)
            val text = engine.getUTF8Text().orEmpty().trim()
            val confidence = runCatching { engine.meanConfidence() }.getOrDefault(-1)
            engine.clear()
            DiagnosticHub.record(
                "TESSERACT_PROCESS_COMPLETED",
                trace.fieldsOrEmpty(
                    mapOf(
                        "durationMs" to elapsedMs(started),
                        "textLength" to text.length,
                        "lineCount" to text.lineSequence().count { it.isNotBlank() },
                        "wordCount" to wordCount(text),
                        "meanConfidence" to confidence,
                        "arabicCharacterCount" to text.count(::isArabic),
                        "latinCharacterCount" to text.count(::isLatin),
                        "digitCount" to text.count(Char::isDigit),
                        "text" to text,
                        "blank" to text.isBlank(),
                    ),
                ),
            )
            text
        } finally {
            if (prepared !== bitmap) prepared.recycle()
        }
    }

    private fun textCoverage(result: Text, width: Int, height: Int): Double {
        val frameArea = width.toDouble() * height.toDouble()
        if (frameArea <= 0.0) return 0.0
        val estimatedArea = result.textBlocks.sumOf { block ->
            block.boundingBox?.let { it.width().coerceAtLeast(0).toLong() * it.height().coerceAtLeast(0).toLong() }
                ?: 0L
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

    private fun ensureArabicEngineLocked(): TessBaseAPI {
        arabicEngine?.let { return it }
        val dataRoot = File(appContext.filesDir, "tesseract").apply { mkdirs() }
        val tessdataDirectory = File(dataRoot, "tessdata").apply { mkdirs() }
        val trainedData = File(tessdataDirectory, "ara.traineddata")
        if (!trainedData.isFile || trainedData.length() !in MIN_TESSDATA_BYTES..MAX_TESSDATA_BYTES) {
            val temporary = File(tessdataDirectory, "ara.traineddata.copying")
            temporary.delete()
            appContext.assets.open("tessdata/ara.traineddata").use { input ->
                temporary.outputStream().buffered().use { output -> input.copyTo(output) }
            }
            check(temporary.length() in MIN_TESSDATA_BYTES..MAX_TESSDATA_BYTES) {
                "ملف القراءة العربية داخل التطبيق غير مكتمل"
            }
            if (!temporary.renameTo(trainedData)) {
                temporary.copyTo(trainedData, overwrite = true)
                temporary.delete()
            }
        }

        val engine = TessBaseAPI()
        check(engine.init(dataRoot.absolutePath, "ara")) {
            "تعذر تشغيل محرك القراءة العربية المحلي"
        }
        arabicEngine = engine
        return engine
    }

    fun close() {
        warmupScope.cancel()
        latinRecognizer.close()
        arabicEngine?.recycle()
        arabicEngine = null
    }

    private fun scaleToMaxEdge(source: Bitmap, maxEdge: Int): Bitmap {
        val largest = maxOf(source.width, source.height)
        if (largest <= maxEdge) return source
        val ratio = maxEdge.toFloat() / largest
        return Bitmap.createScaledBitmap(
            source,
            (source.width * ratio).roundToInt().coerceAtLeast(1),
            (source.height * ratio).roundToInt().coerceAtLeast(1),
            true,
        )
    }

    private fun wordCount(text: String): Int = text
        .trim()
        .split(Regex("\\s+"))
        .count { it.isNotBlank() }

    private fun isArabic(value: Char): Boolean = value in '\u0600'..'\u06FF'
    private fun isLatin(value: Char): Boolean = value in 'A'..'Z' || value in 'a'..'z'

    private fun elapsedMs(started: Long): Double =
        (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0

    private fun DiagnosticTrace?.fieldsOrEmpty(extra: Map<String, Any?> = emptyMap()): Map<String, Any?> =
        this?.fields(extra) ?: extra

    private companion object {
        const val ARABIC_MAX_EDGE = 1_600
        const val MIN_TESSDATA_BYTES = 1_000_000L
        const val MAX_TESSDATA_BYTES = 3_000_000L
    }
}
