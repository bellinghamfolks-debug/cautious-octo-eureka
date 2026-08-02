package com.abdullah.visionbridge.data.paddleocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.abdullah.visionbridge.data.diagnostics.DiagnosticHub
import com.abdullah.visionbridge.domain.model.LocalReadingQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer

/**
 * Runs PP-OCRv5 detection, orientation and the two recognizers through ONNX Runtime.
 *
 * Everything is Java-side: ONNX Runtime ships an Android AAR with a complete API, so this needs no
 * JNI bridge of its own and no CMake. That is a deliberate simplification over the engine it
 * replaces, which carried a hand-written C++ layer that had to be kept in step with an upstream
 * project's fastest-moving header.
 *
 * The pipeline is bounded and finite by construction, which is the property the previous engine
 * lacked: detection is one pass, and recognition is one pass per detected line. There is no
 * autoregressive decoding, so there is nothing that can run for minutes, loop, or refuse to be
 * cancelled between tokens.
 */
class PaddleOcrEngine(
    private val context: Context,
) {
    class NotPackagedException : IllegalStateException(
        "هذه النسخة من التطبيق لا تتضمن ملفات القارئ المحلي. ثبّت نسخة APK كاملة من البناء الرسمي."
    )

    class LoadFailedException(cause: Throwable) : IllegalStateException(
        "تعذر تشغيل القارئ المحلي على هذا الجهاز.",
        cause,
    )

    class DictionaryMissingException(model: String) : IllegalStateException(
        "النموذج $model لا يحمل قاموس محارفه بداخله، فلا يمكن فك ترميز مخرجاته."
    )

    private class Sessions(
        val environment: OrtEnvironment,
        val detection: OrtSession,
        val orientation: OrtSession,
        val arabic: OrtSession,
        val latin: OrtSession,
        val arabicDictionary: List<String>,
        val latinDictionary: List<String>,
        /**
         * Whether the Arabic head can also spell Latin, decided by reading its own dictionary
         * rather than assumed. The whole line-selection rule rests on this asymmetry, so it is
         * checked against the model that is actually loaded.
         */
        val arabicHeadIsMultilingual: Boolean,
    ) {
        fun close() {
            runCatching { detection.close() }
            runCatching { orientation.close() }
            runCatching { arabic.close() }
            runCatching { latin.close() }
        }
    }

    @Volatile
    private var xnnpackEnabled = false

    private val lifecycleMutex = Mutex()
    private val inferenceMutex = Mutex()

    @Volatile
    private var sessions: Sessions? = null

    val isLoaded: Boolean get() = sessions != null

    val isPackaged: Boolean get() = BundledOcrModels.allPresent(context)

    suspend fun ensureLoaded(): Result<Unit> = lifecycleMutex.withLock {
        if (sessions != null) return@withLock Result.success(Unit)
        if (!BundledOcrModels.allPresent(context)) {
            return@withLock Result.failure(NotPackagedException())
        }

        withContext(Dispatchers.IO) {
            runCatching {
                val started = SystemClock.elapsedRealtimeNanos()
                val environment = OrtEnvironment.getEnvironment()
                val options = OrtSession.SessionOptions().apply {
                    // Leave a core for capture, speech and the UI, exactly as the previous engine
                    // did: starving them is what makes an assistive app feel broken.
                    setIntraOpNumThreads(inferenceThreadCount())
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    // XNNPACK ships inside the onnxruntime-android AAR but is not used unless it is
                    // registered. It is ARM-optimised for exactly these convolutions, and the time
                    // it saves is what pays for letting the user raise the detector's resolution.
                    // Registration is best-effort: a device where it will not initialise must fall
                    // back to the default CPU path rather than lose on-device reading entirely.
                    xnnpackEnabled = runCatching { addXnnpack(emptyMap()) }.isSuccess
                }

                // Sessions are tracked as they open so that a failure part-way through — a
                // corrupt asset, a model without its dictionary — does not strand the ones already
                // holding native memory.
                val opened = mutableListOf<OrtSession>()
                fun session(name: String): OrtSession =
                    environment.createSession(BundledOcrModels.read(context, name), options)
                        .also(opened::add)

                val loaded = try {
                    val arabic = session(BundledOcrModels.ARABIC_RECOGNITION)
                    val latin = session(BundledOcrModels.ENGLISH_RECOGNITION)
                    val arabicDictionary =
                        dictionaryOf(arabic, BundledOcrModels.ARABIC_RECOGNITION)
                    val latinDictionary =
                        dictionaryOf(latin, BundledOcrModels.ENGLISH_RECOGNITION)
                    val multilingual = containsLatinLetters(arabicDictionary)
                    if (!multilingual) {
                        // The bundled models are pinned by checksum, so this cannot happen in a
                        // build that fetched what it was told to. If it ever does, the
                        // line-selection rule has lost its footing and that must be visible rather
                        // than silently absorbed.
                        DiagnosticHub.record(
                            "PPOCR_ARABIC_HEAD_NOT_MULTILINGUAL",
                            mapOf("arabicDictionarySize" to arabicDictionary.size),
                        )
                    }

                    Sessions(
                        environment = environment,
                        detection = session(BundledOcrModels.DETECTION),
                        orientation = session(BundledOcrModels.ORIENTATION),
                        arabic = arabic,
                        latin = latin,
                        arabicDictionary = arabicDictionary,
                        latinDictionary = latinDictionary,
                        arabicHeadIsMultilingual = multilingual,
                    )
                } catch (error: Throwable) {
                    opened.forEach { runCatching(it::close) }
                    throw error
                }

                sessions = loaded

                DiagnosticHub.record(
                    "PPOCR_LOAD_COMPLETED",
                    mapOf(
                        "durationMs" to (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0,
                        "arabicDictionarySize" to loaded.arabicDictionary.size,
                        "latinDictionarySize" to loaded.latinDictionary.size,
                        "arabicHeadIsMultilingual" to loaded.arabicHeadIsMultilingual,
                        "latinHeadCarriesArabic" to containsArabicLetters(loaded.latinDictionary),
                        "threads" to inferenceThreadCount(),
                        "xnnpack" to xnnpackEnabled,
                    ),
                )
            }.recoverCatching { error ->
                DiagnosticHub.failure("PPOCR_LOAD", error)
                sessions = null
                throw when (error) {
                    is NotPackagedException, is DictionaryMissingException -> error
                    else -> LoadFailedException(error)
                }
            }
        }
    }

    /**
     * Reads a recognition head's character list out of the model itself.
     *
     * RapidOCR's ONNX exports carry the dictionary in the model's metadata under `character`, which
     * removes the failure this pipeline previously had to defend against: a dictionary from one
     * model paired with the weights of another decodes to fluent nonsense rather than to an error,
     * because every class index still maps to some character. A dictionary that travels inside the
     * model cannot be paired with the wrong one.
     */
    private fun dictionaryOf(session: OrtSession, name: String): List<String> {
        val metadata = runCatching { session.metadata.customMetadata }.getOrNull().orEmpty()
        val raw = metadata[DICTIONARY_METADATA_KEY] ?: throw DictionaryMissingException(name)
        return RecognitionDictionary.parse(raw)
    }

    private fun containsLatinLetters(dictionary: List<String>): Boolean =
        dictionary.any { entry -> entry.any { it in 'a'..'z' || it in 'A'..'Z' } }

    private fun containsArabicLetters(dictionary: List<String>): Boolean =
        dictionary.any { entry -> entry.any { it in '\u0600'..'\u06FF' } }

    suspend fun release(reason: String) = lifecycleMutex.withLock {
        val current = sessions ?: return@withLock
        sessions = null
        withContext(Dispatchers.IO) {
            current.close()
            DiagnosticHub.record("PPOCR_RELEASED", mapOf("reason" to reason))
        }
    }

    /** Reads every text line in [bitmap] and returns the page in visual reading order. */
    suspend fun read(
        bitmap: Bitmap,
        quality: LocalReadingQuality = LocalReadingQuality.BALANCED,
    ): PaddleOcrResult = inferenceMutex.withLock {
        val active = sessions ?: throw NotPackagedException()

        withContext(Dispatchers.Default) {
            val started = SystemClock.elapsedRealtimeNanos()
            val boxes = detect(active, bitmap, quality)
            val detectMs = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
            DiagnosticHub.record(
                "PPOCR_DETECTION_COMPLETED",
                mapOf(
                    "durationMs" to detectMs,
                    "boxCount" to boxes.size,
                    "sourceWidth" to bitmap.width,
                    "sourceHeight" to bitmap.height,
                    "quality" to quality.name,
                    "detectionLongEdge" to quality.detectionLongEdge,
                    "xnnpack" to xnnpackEnabled,
                ),
            )
            if (boxes.isEmpty()) return@withContext PaddleOcrResult("", 0f, 0)

            val recognitionStarted = SystemClock.elapsedRealtimeNanos()
            // Merge before recognizing. Letterspaced type arrives as one blob per glyph, and a
            // recognizer shown a single letter has nothing to condition on.
            val grouped = TextLineOrdering.groupIntoLines(boxes).map(TextLineOrdering::mergeAdjacent)
            DiagnosticHub.record(
                "PPOCR_BOXES_MERGED",
                mapOf(
                    "detectedBoxes" to boxes.size,
                    "cropsAfterMerge" to grouped.sumOf { it.size },
                    "lines" to grouped.size,
                ),
            )
            val lines = grouped.mapNotNull { line -> readLine(active, bitmap, line, quality) }
            val text = PageAssembler.assemble(lines)
            DiagnosticHub.record(
                "PPOCR_RECOGNITION_COMPLETED",
                mapOf(
                    "durationMs" to
                        (SystemClock.elapsedRealtimeNanos() - recognitionStarted) / 1_000_000.0,
                    "lineCount" to lines.size,
                    "characters" to text.length,
                    "meanConfidence" to PageAssembler.meanConfidence(lines),
                ),
            )
            PaddleOcrResult(text, PageAssembler.meanConfidence(lines), lines.size)
        }
    }

    private fun detect(
        active: Sessions,
        bitmap: Bitmap,
        quality: LocalReadingQuality,
    ): List<TextBox> {
        val input = OcrImagePreprocessor.prepareForDetection(bitmap, quality.detectionLongEdge)
        val tensor = input.tensor
        return runTensor(
            active.environment,
            active.detection,
            tensor.data,
            longArrayOf(1, 3, tensor.height.toLong(), tensor.width.toLong()),
        ) { output, shape ->
            // DB emits [1, 1, H, W]; the last two dimensions are the probability map.
            val mapHeight = shape[shape.size - 2].toInt()
            val mapWidth = shape[shape.size - 1].toInt()
            DbPostProcessor.extractBoxes(
                probability = output,
                mapWidth = mapWidth,
                mapHeight = mapHeight,
                scaleX = input.scaleX * (tensor.width.toFloat() / mapWidth),
                scaleY = input.scaleY * (tensor.height.toFloat() / mapHeight),
                sourceWidth = bitmap.width,
                sourceHeight = bitmap.height,
            )
        }
    }

    /** Reads one visual line: every box in it, in the direction the line turns out to run. */
    private fun readLine(
        active: Sessions,
        bitmap: Bitmap,
        line: List<TextBox>,
        quality: LocalReadingQuality,
    ): List<LineReading>? {
        // Box and reading stay paired. Recognition drops boxes it cannot read, so ordering the boxes
        // separately and matching them back up by position would shift every word after the first
        // failure onto the wrong box.
        val read = line.mapNotNull { box -> readBox(active, bitmap, box, quality)?.let { box to it } }
        if (read.isEmpty()) return null

        val joined = read.joinToString(" ") { it.second.text }
        val direction = BilingualLineSelector.isRightToLeft(joined)
        return TextLineOrdering.orderWithinLineBy(read, direction) { it.first }.map { it.second }
    }

    private fun readBox(
        active: Sessions,
        bitmap: Bitmap,
        box: TextBox,
        quality: LocalReadingQuality,
    ): LineReading? {
        val padded = box.expanded(BOX_PADDING_PIXELS, bitmap.width, bitmap.height)
        val cropped = OcrImagePreprocessor.cropLine(
            source = bitmap,
            box = padded,
            targetHeight = RECOGNITION_HEIGHT,
            maxWidth = quality.recognitionMaxWidth,
        ) ?: return null

        val upright = uprightCrop(active, cropped)
        try {
            val crop = OcrImagePreprocessor.toRecognitionTensor(upright)

            // The Arabic head is multilingual, so it runs first and can settle a crop by itself —
            // but only one that is Arabic all the way through. The moment a Latin character appears
            // in its output the crop is mixed, and the English specialist reads Latin better than
            // the Arabic head does, so suppressing it there trades accuracy for speed on exactly
            // the content most likely to be a product name or a label the user needs exactly right.
            val arabic =
                recognize(active, crop, active.arabic, active.arabicDictionary, OcrScript.ARABIC)
            if (arabic != null && BilingualLineSelector.isPureConfidentArabic(arabic)) {
                recordLineDecision(arabic, arabic, null, englishHeadSkipped = true)
                return arabic
            }
            val latin =
                recognize(active, crop, active.latin, active.latinDictionary, OcrScript.LATIN)
            val chosen = BilingualLineSelector.select(arabic, latin)
            recordLineDecision(chosen, arabic, latin, englishHeadSkipped = false)
            return chosen
        } finally {
            if (upright !== cropped) upright.recycle()
            cropped.recycle()
        }
    }

    /**
     * Records what each head read for one crop and which reading won.
     *
     * Without this, a missing word is indistinguishable from a word the detector never found, a
     * crop both heads failed, and a crop where the wrong head was believed — four different bugs
     * that all look identical in the final text. The candidates are the only way to tell them
     * apart from a device the developer cannot hold.
     */
    private fun recordLineDecision(
        chosen: LineReading?,
        arabic: LineReading?,
        latin: LineReading?,
        englishHeadSkipped: Boolean,
    ) {
        DiagnosticHub.record(
            "PPOCR_LINE_DECISION",
            mapOf(
                "chosen" to chosen?.text,
                "chosenScript" to chosen?.script?.name,
                "chosenConfidence" to chosen?.confidence,
                "arabicCandidate" to arabic?.text,
                "arabicConfidence" to arabic?.confidence,
                "latinCandidate" to latin?.text,
                "latinConfidence" to latin?.confidence,
                "englishHeadSkipped" to englishHeadSkipped,
                "droppedEverything" to (chosen == null),
            ),
        )
    }

    /**
     * Turns an upside-down line the right way up before it reaches a recognizer.
     *
     * A mirrored screen can arrive rotated, and a recognizer shown a 180-degree line does not fail —
     * it returns whatever those shapes look like the right way up, which is the confidently-wrong
     * output this pipeline exists to avoid. The classifier's threshold is deliberately high: leaving
     * a line alone costs one bad line, while rotating a correctly-oriented one costs a good line.
     */
    private fun uprightCrop(active: Sessions, crop: Bitmap): Bitmap {
        val tensor = OcrImagePreprocessor.toOrientationTensor(
            crop,
            ORIENTATION_HEIGHT,
            ORIENTATION_WIDTH,
        )
        val upsideDown = runTensor(
            active.environment,
            active.orientation,
            tensor.data,
            longArrayOf(1, 3, tensor.height.toLong(), tensor.width.toLong()),
        ) { output, _ ->
            // The classifier emits [1, 2]: probability of 0 degrees, then of 180.
            output.size >= 2 && output[1] >= ORIENTATION_CONFIDENCE
        }
        return if (upsideDown) OcrImagePreprocessor.rotate180(crop) else crop
    }

    private fun recognize(
        active: Sessions,
        crop: OcrImagePreprocessor.Tensor,
        session: OrtSession,
        dictionary: List<String>,
        script: OcrScript,
    ): LineReading? = runTensor(
        active.environment,
        session,
        crop.data,
        longArrayOf(1, 3, crop.height.toLong(), crop.width.toLong()),
    ) { output, shape ->
        // Recognition heads emit [1, steps, classes].
        val steps = shape[shape.size - 2].toInt()
        val classes = shape[shape.size - 1].toInt()
        val decoded = CtcDecoder.decode(output, steps, classes, dictionary)
        // The decoder emits image-column order. Arabic reads the other way, so this is where a
        // correct recognition stops being backwards.
        val text = BidiTextOrder.toLogicalOrder(decoded.text)
        if (text.isBlank()) null else LineReading(text, decoded.confidence, script)
    }

    /** Runs one session and hands the flattened output plus its shape to [consume]. */
    private fun <T> runTensor(
        environment: OrtEnvironment,
        session: OrtSession,
        data: FloatArray,
        shape: LongArray,
        consume: (FloatArray, LongArray) -> T,
    ): T {
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(data), shape).use { input ->
            session.run(mapOf(session.inputNames.first() to input)).use { results ->
                val value = results[0]
                val outputShape = (value.info as ai.onnxruntime.TensorInfo).shape
                val flattened = flatten(value.value)
                return consume(flattened, outputShape)
            }
        }
    }

    /** ONNX Runtime returns nested arrays; the pipeline wants one flat buffer. */
    private fun flatten(value: Any?): FloatArray {
        val output = ArrayList<Float>()
        fun walk(node: Any?) {
            when (node) {
                is FloatArray -> node.forEach(output::add)
                is Array<*> -> node.forEach(::walk)
                is Float -> output.add(node)
            }
        }
        walk(value)
        return output.toFloatArray()
    }

    private fun inferenceThreadCount(): Int =
        (Runtime.getRuntime().availableProcessors() - 1).coerceIn(2, 6)

    companion object {
        /** PP-OCRv5 recognizers take a fixed 48-pixel input height. */
        const val RECOGNITION_HEIGHT = 48
        const val BOX_PADDING_PIXELS = 3

        /** The orientation classifier's fixed input, and PaddleOCR's own 0.9 decision threshold. */
        const val ORIENTATION_HEIGHT = 48
        const val ORIENTATION_WIDTH = 192
        const val ORIENTATION_CONFIDENCE = 0.9f

        /** Where RapidOCR's ONNX exports keep the recognizer's character list. */
        private const val DICTIONARY_METADATA_KEY = "character"
    }
}

data class PaddleOcrResult(val text: String, val confidence: Float, val lineCount: Int)
