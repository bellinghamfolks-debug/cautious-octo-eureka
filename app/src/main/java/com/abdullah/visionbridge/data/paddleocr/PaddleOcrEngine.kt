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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
        "هذه النسخة لا تتضمن ملفات PP-OCRv5. ثبّت APK الكامل الصادر من البناء الرسمي."
    )

    class LoadFailedException(cause: Throwable) : IllegalStateException(
        "تعذر تشغيل PP-OCRv5 على هذا الجهاز.",
        cause,
    )

    class DictionaryMissingException(model: String) : IllegalStateException(
        "ملف PP-OCRv5 ($model) لا يتضمن قاموس المحارف المطلوب لفك النتائج."
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

    /** Resolution controller and the measurement that feeds it, both owned by the read path. */
    private var scaleController: AdaptiveReadingScale? = null
    private var scaleControllerSource = 0

    @Volatile
    private var lastTextHeight: Float? = null

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
                scaleController = null
                lastTextHeight = null
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

    /**
     * Frees the native sessions.
     *
     * The inference lock is taken as well as the lifecycle lock, because closing an OrtSession frees
     * native memory: a read still walking those sessions would not throw, it would fault the
     * process. Every caller of this is reachable while a page is being read — memory pressure, the
     * service being destroyed, and the user simply turning the local switch off mid-read.
     *
     * Lock order is lifecycle then inference everywhere, and a read never asks for the lifecycle
     * lock, so this cannot deadlock.
     */
    suspend fun release(reason: String) {
        lifecycleMutex.withLock {
            inferenceMutex.withLock {
                val current = sessions ?: return@withLock
                sessions = null
                scaleController = null
                lastTextHeight = null
                withContext(Dispatchers.IO) {
                    current.close()
                    DiagnosticHub.record("PPOCR_RELEASED", mapOf("reason" to reason))
                }
            }
        }
    }

    /**
     * Reads every text line in [bitmap] and returns the page in visual reading order.
     *
     * @param subjectScale how much the subject grew since the previous frame, from the visual
     *   tracker. Used to anticipate a zoom so the resolution follows a moving hand rather than
     *   trailing it by a frame.
     */
    suspend fun read(
        bitmap: Bitmap,
        quality: LocalReadingQuality = LocalReadingQuality.AUTO,
        subjectScale: Double = 1.0,
    ): PaddleOcrResult = inferenceMutex.withLock {
        val active = sessions ?: throw NotPackagedException()

        withContext(Dispatchers.Default) {
            val started = SystemClock.elapsedRealtimeNanos()
            var page = bitmap
            val scale = resolutionFor(bitmap, quality, subjectScale)
            var detection = detect(active, page, scale.detectionLongEdge)
            var boxes = detection.boxes

            // Deskew the page, then detect again. Straightening each crop instead pads a wide strip
            // vertically until the text is a sliver of the crop's height, which reads worse than
            // the tilt did: on a page tilted four degrees it produced no readable lines at all,
            // while deskewing the page and re-detecting read every line exactly.
            val skew = LineSkew.estimate(TextLineOrdering.groupIntoLines(boxes).map(::bounds))
            val deskew = skew.degrees
            if (deskew != 0f) {
                val straight = OcrImagePreprocessor.rotate(page, deskew)
                if (straight !== page) {
                    page = straight
                    detection = detect(active, page, scale.detectionLongEdge)
                    boxes = detection.boxes
                }
            }
            val detectMs = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
            DiagnosticHub.record(
                "PPOCR_DETECTION_COMPLETED",
                mapOf(
                    "durationMs" to detectMs,
                    "boxCount" to boxes.size,
                    "sourceWidth" to bitmap.width,
                    "sourceHeight" to bitmap.height,
                    "deskewDegrees" to deskew,
                    // The controller's reasoning, so a bundle shows why this resolution was used
                    // rather than only which one.
                    "adaptiveResolution" to quality.adaptive,
                    "resolutionReason" to scale.reason,
                    "measuredTextHeight" to scale.estimatedTextHeight,
                    "idealLongEdge" to scale.idealLongEdge,
                    "subjectScale" to subjectScale,
                    // Recorded alongside the correction so a bundle can be argued with rather than
                    // guessed at: two page reads in the field were rotated by nearly 24 degrees and
                    // the events gave no way to tell a genuine tilt from a coincidental column.
                    "skewMeasuredDegrees" to skew.measuredDegrees,
                    "skewCorrelation" to skew.correlation,
                    "skewLineCount" to skew.lineCount,
                    "skewAnchor" to skew.anchor,
                    "quality" to quality.name,
                    "detectionLongEdge" to scale.detectionLongEdge,
                    "xnnpack" to xnnpackEnabled,
                ) + detection.census.fields(),
            )
            if (boxes.isEmpty()) {
                lastTextHeight = null
                return@withContext PaddleOcrResult("", 0f, 0)
            }

            // The measurement that drives the next frame's resolution, straight from the boxes
            // this pass produced. Median, so one oversized heading cannot move it.
            lastTextHeight = boxes
                .map { (it.bottom - it.top).toFloat() }
                .filter { it > 0f }
                .sorted()
                .let { heights -> if (heights.isEmpty()) null else heights[heights.size / 2] }

            val recognitionStarted = SystemClock.elapsedRealtimeNanos()
            // Merge before recognizing. Letterspaced type arrives as one blob per glyph, and a
            // recognizer shown a single letter has nothing to condition on.
            // Skew is measured from a line's own boxes before they are merged, because merging
            // collapses the very arrangement the angle is read from.
            val grouped = TextLineOrdering.groupIntoLines(boxes).map(TextLineOrdering::mergeAdjacent)
            DiagnosticHub.record(
                "PPOCR_BOXES_MERGED",
                mapOf(
                    "detectedBoxes" to boxes.size,
                    "cropsAfterMerge" to grouped.sumOf { it.size },
                    "lines" to grouped.size,
                ),
            )
            var cropsRead = 0
            var capped = false
            val lines = grouped.mapNotNull { line ->
                // Cancellation is cooperative. Everything below this point is plain function calls
                // into ONNX Runtime with no suspension, so without an explicit check a stopped
                // capture, a changed setting or the lane's timeout could not interrupt a page part
                // way through — the exact "refuses to be cancelled" behaviour this engine replaced.
                currentCoroutineContext().ensureActive()
                if (cropsRead >= MAX_CROPS_PER_FRAME) {
                    capped = true
                    return@mapNotNull null
                }
                cropsRead += line.size
                readLine(active, page, line, scale.recognitionMaxWidth)
            }
            if (capped) {
                DiagnosticHub.record(
                    "PPOCR_FRAME_CROP_LIMIT_REACHED",
                    mapOf("limit" to MAX_CROPS_PER_FRAME, "cropsOffered" to grouped.sumOf { it.size }),
                )
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
            if (page !== bitmap) page.recycle()
            PaddleOcrResult(text, PageAssembler.meanConfidence(lines), lines.size)
        }
    }

    /** The rectangle enclosing a whole line, which is what page skew is measured from. */
    private fun bounds(line: List<TextBox>): TextBox = TextBox(
        left = line.minOf { it.left },
        top = line.minOf { it.top },
        right = line.maxOf { it.right },
        bottom = line.maxOf { it.bottom },
        confidence = line.maxOf { it.confidence },
    )

    /**
     * The resolution to detect at, solved from the size of the text rather than chosen from a menu.
     *
     * On the first frame of a subject there is no measurement yet, so a projection-profile probe
     * over a small working plane estimates the line height directly from the image. That is what
     * stops a distant sign from spending several frames climbing the ladder before it is legible.
     */
    private fun resolutionFor(
        bitmap: Bitmap,
        quality: LocalReadingQuality,
        subjectScale: Double,
    ): AdaptiveReadingScale.Decision {
        if (!quality.adaptive) {
            return AdaptiveReadingScale.Decision(
                detectionLongEdge = quality.detectionLongEdge,
                recognitionMaxWidth = quality.recognitionMaxWidth,
                reason = "fixed_by_user",
                estimatedTextHeight = null,
                idealLongEdge = null,
            )
        }
        val sourceLongEdge = maxOf(bitmap.width, bitmap.height)
        val controller = adaptiveScale(sourceLongEdge)
        val measured = lastTextHeight ?: probeTextHeight(bitmap, sourceLongEdge)
        return controller.next(measured, subjectScale)
    }

    /** One controller per capture geometry; a resize starts the solve again from the middle. */
    private fun adaptiveScale(sourceLongEdge: Int): AdaptiveReadingScale {
        val existing = scaleController
        if (existing != null && scaleControllerSource == sourceLongEdge) return existing
        val fresh = AdaptiveReadingScale(sourceLongEdge)
        scaleController = fresh
        scaleControllerSource = sourceLongEdge
        return fresh
    }

    /** Estimates the text height from the image itself, for the frames no measurement covers. */
    private fun probeTextHeight(bitmap: Bitmap, sourceLongEdge: Int): Float? {
        val plane = runCatching { OcrImagePreprocessor.probePlane(bitmap, PROBE_LONG_EDGE) }
            .getOrNull() ?: return null
        val estimate = TextScaleProbe.estimate(
            plane = plane,
            sourceScale = sourceLongEdge.toFloat() / maxOf(plane.width, plane.height),
        ) ?: return null
        DiagnosticHub.record(
            "PPOCR_TEXT_SCALE_PROBED",
            mapOf(
                "textHeightPixels" to estimate.textHeightPixels,
                "lineCount" to estimate.lineCount,
                "confidence" to estimate.confidence,
            ),
        )
        return estimate.textHeightPixels
    }

    private fun detect(
        active: Sessions,
        bitmap: Bitmap,
        detectionLongEdge: Int,
    ): DbPostProcessor.Detection {
        val input = OcrImagePreprocessor.prepareForDetection(bitmap, detectionLongEdge)
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
            DbPostProcessor.extract(
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
        maxCropWidth: Int,
    ): List<LineReading>? {
        // Box and reading stay paired. Recognition drops boxes it cannot read, so ordering the boxes
        // separately and matching them back up by position would shift every word after the first
        // failure onto the wrong box.
        val read = line.mapNotNull { box -> readBox(active, bitmap, box, maxCropWidth)?.let { box to it } }
        if (read.isEmpty()) return null

        val joined = read.joinToString(" ") { it.second.text }
        val direction = BilingualLineSelector.isRightToLeft(joined)
        return TextLineOrdering.orderWithinLineBy(read, direction) { it.first }.map { it.second }
    }

    private fun readBox(
        active: Sessions,
        bitmap: Bitmap,
        box: TextBox,
        maxCropWidth: Int,
    ): LineReading? {
        val padded = box.expanded(BOX_PADDING_PIXELS, bitmap.width, bitmap.height)
        val cropped = OcrImagePreprocessor.cropLine(
            source = bitmap,
            box = padded,
            targetHeight = RECOGNITION_HEIGHT,
            maxWidth = maxCropWidth,
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

        /**
         * Most crops one frame may be recognised into.
         *
         * Work per frame is otherwise unbounded in the number of detected regions, and a dense page
         * at the highest quality could hold the reader for tens of seconds. Reading most of a page
         * promptly serves a listener better than reading all of it far too late.
         */
        /** Long edge of the plane the text-height probe works on. Cheap, and enough to count lines. */
        const val PROBE_LONG_EDGE = 512

        const val MAX_CROPS_PER_FRAME = 120

        /** The orientation classifier's fixed input, and PaddleOCR's own 0.9 decision threshold. */
        const val ORIENTATION_HEIGHT = 48
        const val ORIENTATION_WIDTH = 192
        const val ORIENTATION_CONFIDENCE = 0.9f

        /** Where RapidOCR's ONNX exports keep the recognizer's character list. */
        private const val DICTIONARY_METADATA_KEY = "character"
    }
}

data class PaddleOcrResult(val text: String, val confidence: Float, val lineCount: Int)
