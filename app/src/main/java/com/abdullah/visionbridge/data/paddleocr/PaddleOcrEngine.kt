package com.abdullah.visionbridge.data.paddleocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.os.SystemClock
import com.abdullah.visionbridge.data.diagnostics.DiagnosticHub
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
    private val modelStore: PaddleOcrModelStore,
) {
    class NotInstalledException(missing: List<PaddleOcrModelStore.Artifact>) :
        IllegalStateException(
            "ملفات القارئ المحلي غير مكتملة. الناقص: " +
                missing.joinToString("، ") { it.label } +
                ". ثبّتها من الإعدادات."
        )

    class LoadFailedException(cause: Throwable) : IllegalStateException(
        "تعذر تحميل ملفات القارئ المحلي. تأكد من أنها ملفات ONNX صالحة وأن القاموس يطابق النموذج.",
        cause,
    )

    private class Sessions(
        val environment: OrtEnvironment,
        val detection: OrtSession,
        val orientation: OrtSession,
        val arabic: OrtSession,
        val latin: OrtSession,
        val arabicDictionary: List<String>,
        val latinDictionary: List<String>,
    ) {
        fun close() {
            runCatching { detection.close() }
            runCatching { orientation.close() }
            runCatching { arabic.close() }
            runCatching { latin.close() }
        }
    }

    private val lifecycleMutex = Mutex()
    private val inferenceMutex = Mutex()
    private val mismatchWarned = mutableSetOf<OcrScript>()

    @Volatile
    private var sessions: Sessions? = null

    val isLoaded: Boolean get() = sessions != null

    suspend fun ensureLoaded(): Result<Unit> = lifecycleMutex.withLock {
        if (sessions != null) return@withLock Result.success(Unit)
        val missing = modelStore.missing()
        if (missing.isNotEmpty()) return@withLock Result.failure(NotInstalledException(missing))

        withContext(Dispatchers.IO) {
            runCatching {
                val started = SystemClock.elapsedRealtimeNanos()
                val environment = OrtEnvironment.getEnvironment()
                val options = OrtSession.SessionOptions().apply {
                    // Leave a core for capture, speech and the UI, exactly as the previous engine
                    // did: starving them is what makes an assistive app feel broken.
                    setIntraOpNumThreads(inferenceThreadCount())
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                }

                val loaded = Sessions(
                    environment = environment,
                    detection = environment.createSession(
                        modelStore.fileFor(PaddleOcrModelStore.Artifact.DETECTION).absolutePath,
                        options,
                    ),
                    orientation = environment.createSession(
                        modelStore.fileFor(PaddleOcrModelStore.Artifact.ORIENTATION).absolutePath,
                        options,
                    ),
                    arabic = environment.createSession(
                        modelStore.fileFor(PaddleOcrModelStore.Artifact.ARABIC_RECOGNITION).absolutePath,
                        options,
                    ),
                    latin = environment.createSession(
                        modelStore.fileFor(PaddleOcrModelStore.Artifact.ENGLISH_RECOGNITION).absolutePath,
                        options,
                    ),
                    arabicDictionary =
                        modelStore.readDictionary(PaddleOcrModelStore.Artifact.ARABIC_DICTIONARY),
                    latinDictionary =
                        modelStore.readDictionary(PaddleOcrModelStore.Artifact.ENGLISH_DICTIONARY),
                )
                sessions = loaded

                DiagnosticHub.record(
                    "PPOCR_LOAD_COMPLETED",
                    mapOf(
                        "durationMs" to (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0,
                        "arabicDictionarySize" to loaded.arabicDictionary.size,
                        "latinDictionarySize" to loaded.latinDictionary.size,
                        "threads" to inferenceThreadCount(),
                    ),
                )
            }.recoverCatching { error ->
                DiagnosticHub.failure("PPOCR_LOAD", error)
                sessions = null
                throw if (error is NotInstalledException) error else LoadFailedException(error)
            }
        }
    }

    suspend fun release(reason: String) = lifecycleMutex.withLock {
        val current = sessions ?: return@withLock
        sessions = null
        withContext(Dispatchers.IO) {
            current.close()
            DiagnosticHub.record("PPOCR_RELEASED", mapOf("reason" to reason))
        }
    }

    /** Reads every text line in [bitmap] and returns the page in visual reading order. */
    suspend fun read(bitmap: Bitmap): PaddleOcrResult = inferenceMutex.withLock {
        val active = sessions ?: throw NotInstalledException(modelStore.missing())

        withContext(Dispatchers.Default) {
            val started = SystemClock.elapsedRealtimeNanos()
            val boxes = detect(active, bitmap)
            val detectMs = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
            DiagnosticHub.record(
                "PPOCR_DETECTION_COMPLETED",
                mapOf(
                    "durationMs" to detectMs,
                    "boxCount" to boxes.size,
                    "sourceWidth" to bitmap.width,
                    "sourceHeight" to bitmap.height,
                ),
            )
            if (boxes.isEmpty()) return@withContext PaddleOcrResult("", 0f, 0)

            val recognitionStarted = SystemClock.elapsedRealtimeNanos()
            val lines = TextLineOrdering.groupIntoLines(boxes).mapNotNull { line ->
                readLine(active, bitmap, line)
            }
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

    private fun detect(active: Sessions, bitmap: Bitmap): List<TextBox> {
        val input = OcrImagePreprocessor.prepareForDetection(bitmap, DETECTION_LONG_EDGE)
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
    ): List<LineReading>? {
        // Box and reading stay paired. Recognition drops boxes it cannot read, so ordering the boxes
        // separately and matching them back up by position would shift every word after the first
        // failure onto the wrong box.
        val read = line.mapNotNull { box -> readBox(active, bitmap, box)?.let { box to it } }
        if (read.isEmpty()) return null

        val joined = read.joinToString(" ") { it.second.text }
        val direction = BilingualLineSelector.isRightToLeft(joined)
        return TextLineOrdering.orderWithinLineBy(read, direction) { it.first }.map { it.second }
    }

    private fun readBox(active: Sessions, bitmap: Bitmap, box: TextBox): LineReading? {
        val padded = box.expanded(BOX_PADDING_PIXELS, bitmap.width, bitmap.height)
        val cropped = OcrImagePreprocessor.cropLine(
            source = bitmap,
            box = padded,
            targetHeight = RECOGNITION_HEIGHT,
            maxWidth = RECOGNITION_MAX_WIDTH,
        ) ?: return null

        val upright = uprightCrop(active, cropped)
        try {
            val crop = OcrImagePreprocessor.toRecognitionTensor(upright)

            // The Arabic head is multilingual, so it runs first on every crop and can settle the
            // line by itself. Consulting the English head on a line already read as Arabic cannot
            // change the answer — see BilingualLineSelector — so skipping it is free accuracy-wise
            // and halves recognition time on an Arabic page.
            val arabic =
                recognize(active, crop, active.arabic, active.arabicDictionary, OcrScript.ARABIC)
            if (arabic != null && BilingualLineSelector.isDecisiveArabic(arabic)) return arabic
            val latin =
                recognize(active, crop, active.latin, active.latinDictionary, OcrScript.LATIN)
            return BilingualLineSelector.select(arabic, latin)
        } finally {
            if (upright !== cropped) upright.recycle()
            cropped.recycle()
        }
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
        warnOnceIfDictionaryMismatched(script, classes, dictionary.size)
        val decoded = CtcDecoder.decode(output, steps, classes, dictionary)
        if (decoded.text.isBlank()) null
        else LineReading(decoded.text, decoded.confidence, script)
    }

    /**
     * A dictionary from a different model than the one installed beside it decodes to plausible-
     * looking rubbish rather than failing, because every class index still maps to *some* character.
     * The class count is the one place the mismatch is visible, so it is reported the first time it
     * is seen instead of being left for the user to discover by listening.
     */
    private fun warnOnceIfDictionaryMismatched(script: OcrScript, classes: Int, entries: Int) {
        if (classes == entries + 1 || !mismatchWarned.add(script)) return
        DiagnosticHub.record(
            "PPOCR_DICTIONARY_MISMATCH",
            mapOf(
                "script" to script.name,
                "modelClasses" to classes,
                "dictionaryEntries" to entries,
                "expectedEntries" to classes - 1,
            ),
        )
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
        /**
         * Detector input cap. PP-OCR detects small text well at this size, and unlike the previous
         * engine the cost here is one convolutional pass rather than hundreds of decoding steps, so
         * a larger image costs milliseconds instead of minutes.
         */
        const val DETECTION_LONG_EDGE = 960

        /** PP-OCRv5 recognizers take a fixed 48-pixel input height. */
        const val RECOGNITION_HEIGHT = 48
        const val RECOGNITION_MAX_WIDTH = 640
        const val BOX_PADDING_PIXELS = 3

        /** The orientation classifier's fixed input, and PaddleOCR's own 0.9 decision threshold. */
        const val ORIENTATION_HEIGHT = 48
        const val ORIENTATION_WIDTH = 192
        const val ORIENTATION_CONFIDENCE = 0.9f
    }
}

data class PaddleOcrResult(val text: String, val confidence: Float, val lineCount: Int)
