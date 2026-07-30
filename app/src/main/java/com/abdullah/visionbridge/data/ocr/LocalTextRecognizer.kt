package com.abdullah.visionbridge.data.ocr

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.abdullah.visionbridge.data.diagnostics.DiagnosticHub
import com.abdullah.visionbridge.data.diagnostics.DiagnosticTrace
import com.google.mlkit.vision.common.InputImage
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
        val text = latinRecognizer.process(InputImage.fromBitmap(bitmap, 0)).await().text.trim()
        DiagnosticHub.record(
            "MLKIT_PROCESS_COMPLETED",
            trace.fieldsOrEmpty(
                mapOf(
                    "durationMs" to elapsedMs(started),
                    "textLength" to text.length,
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
            engine.clear()
            DiagnosticHub.record(
                "TESSERACT_PROCESS_COMPLETED",
                trace.fieldsOrEmpty(
                    mapOf(
                        "durationMs" to elapsedMs(started),
                        "textLength" to text.length,
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
