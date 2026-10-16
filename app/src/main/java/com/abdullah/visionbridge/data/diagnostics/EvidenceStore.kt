package com.abdullah.visionbridge.data.diagnostics

import android.graphics.Bitmap
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Keeps the actual frames behind a session, when the user has asked for them.
 *
 * The bundle otherwise carries no pixels at all, and that limit has a cost: given a page that was
 * not read, there is no way to tell "the text was never detected" from "it was detected and thrown
 * away", and those two need opposite repairs. Guessing between them is what turns one diagnosis
 * into many attempts.
 *
 * The rules this operates under are the ones that make it safe to offer:
 *
 * - **Off unless switched on.** No frame is ever written by default.
 * - **Bounded, hard.** One byte budget for all of it, [EvidenceVideoPlan.EVIDENCE_BYTE_CAP],
 *   enforced here rather than promised in a comment. Past it, capture stops and the bundle says
 *   how many frames were skipped.
 * - **Declared.** The count is in the manifest and in the archive's own readme, so a bundle that
 *   contains screen images can never look like one that does not.
 *
 * Frames are stored as video, not as pictures: one H.265 stream (H.264 where the device has no
 * H.265 encoder) for the one-a-second timeline and one for every analysis input, in 30-second MP4
 * segments. Ten minutes that took about 290 MB as JPEGs fit in about 22 MB; see
 * [EvidenceVideoPlan] for the measurement and the trade. Every frame stays addressable: the event
 * that kept it names `segment.mp4#t=seconds`, and `frames.jsonl` beside the segments lists each
 * frame with its reason, its time and its source size. A device that will start no video encoder
 * at all falls back to small, sparser JPEGs under the same budget.
 */
// DENSE_DIAGNOSTIC_EVIDENCE_V381
// TEN_MINUTE_DIAGNOSTIC_TIMELINE_V382
// EVERY_ANALYSIS_INPUT_EVIDENCE_V383
// COMPRESSED_VIDEO_EVIDENCE_V316
class EvidenceStore(val directory: File) {

    @Volatile var currentSessionId: String = "unassigned_${java.util.UUID.randomUUID()}"

    private val lock = Any()

    /**
     * Frames that events point at but that are gone for a known reason: a segment cut short by the
     * process dying, or frames the user deleted. Outside [directory], so deleting every frame
     * still leaves the directory empty.
     */
    private val absentLedger = File(directory.parentFile, "evidence_absent.txt")

    private val written = AtomicInteger(0)
    private val timelineWritten = AtomicInteger(0)
    private val analysisInputWritten = AtomicInteger(0)
    private val supplementalWritten = AtomicInteger(0)
    private val analysisInputSkipped = AtomicInteger(0)
    private val skipped = AtomicInteger(0)
    private val bytes = AtomicLong(0L)
    private val segmentsFinished = AtomicInteger(0)
    private val segmentsLost = AtomicInteger(0)

    private class OpenSegment(
        val sessionId: String,
        val relativePath: String,
        val segment: EvidenceVideoSegment,
        val startedAtNanos: Long,
        var countedBytes: Long = 0L,
    )

    private val open = mutableMapOf<EvidenceVideoPlan.Stream, OpenSegment>()
    private val segmentNumbers = mutableMapOf<String, Int>()
    private val lastJpegAtMs = mutableMapOf<EvidenceVideoPlan.Stream, Long>()
    private var windowStartedAtMs = 0L
    private var lastBitrateAtMs = 0L
    private var bytesAtBitrate = 0L
    private var framesSinceBitrate = 0
    private var requestedBitrate = 0
    private val correction = EvidenceVideoPlan.RateCorrection()
    private var encoderFailures = 0
    private var videoUnavailable = false

    @Volatile private var format: String = "none_yet"
    @Volatile private var encoderName: String? = null
    @Volatile private var targetBitrate: Int = 0
    @Volatile private var lastSkipReason: String? = null

    init {
        // A `.part` is a segment whose process died before the muxer wrote its index. It cannot be
        // played, so it goes — but the frames that pointed at it are named as lost, not missing.
        directory.walkTopDown().filter { it.isFile && it.name.endsWith(".mp4.part") }.forEach { part ->
            val finalPath = part.relativeTo(directory).invariantSeparatorsPath.removeSuffix(".part")
            appendAbsent(finalPath, "segment_lost_process_death")
            segmentsLost.incrementAndGet()
            part.delete()
        }
        // Persisted evidence survives process death; its privacy declaration must survive too.
        mediaFiles().forEach { bytes.addAndGet(it.length()) }
        directory.walkTopDown().filter { it.isFile && it.extension.equals("jpg", true) }.forEach { count(roleOf(it.name)) }
        directory.walkTopDown().filter { it.isFile && it.name == INDEX_FILE }.forEach { index ->
            index.forEachLine { line ->
                val entry = runCatching { JSONObject(line) }.getOrNull() ?: return@forEachLine
                val segment = File(directory, EvidenceVideoPlan.referencedFile(entry.optString("reference")))
                if (segment.isFile) count(roleOf(entry.optString("reason")))
            }
        }
    }

    @Volatile
    var enabled: Boolean = false
        set(value) {
            if (value && !field) synchronized(lock) { windowStartedAtMs = 0L }
            field = value
        }

    /** The images and videos themselves, for the archive inventory. */
    fun mediaFiles(): List<File> = directory.walkTopDown()
        .filter { it.isFile && (it.extension.equals("jpg", true) || it.extension.equals("mp4", true)) }
        .toList()

    /** Everything that belongs in the bundle: the media plus the indexes that explain it. */
    fun files(): List<File> = directory.walkTopDown()
        .filter { it.isFile && !it.name.endsWith(".part") }
        .toList()

    /** References that are gone for a reason the bundle can state, with that reason. */
    fun absentReferences(): Map<String, String> = synchronized(lock) {
        if (!absentLedger.isFile) return emptyMap()
        absentLedger.readLines().mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size == 2) parts[0] to parts[1] else null
        }.toMap()
    }

    /** Why the last [capture] that returned null wrote nothing. */
    fun lastSkipReason(): String? = lastSkipReason

    /**
     * Stores [bitmap] as evidence for [reason], if capture is on and the budget allows.
     *
     * Returns the reference recorded — `session/inputs-0003.mp4#t=12.345` for video, a file name
     * for the JPEG fallback — or null when nothing was written, which is never an error.
     */
    fun capture(bitmap: Bitmap, frameId: String, reason: String, capturedWhileEnabled: Boolean = false, sessionId: String = currentSessionId): String? = synchronized(lock) {
        if (!enabled && !capturedWhileEnabled) return null
        lastSkipReason = null
        val role = roleOf(reason)
        if (written.get() >= MAX_FRAMES || !EvidenceVideoPlan.hasRoom(bytes.get())) return skip(role, "evidence_budget_full")
        val now = SystemClock.elapsedRealtime()
        if (windowStartedAtMs == 0L) windowStartedAtMs = now
        val stream = EvidenceVideoPlan.Stream.forReason(reason)
        if (!videoUnavailable) {
            val reference = try {
                encodeVideo(bitmap, frameId, reason, sessionId, stream, now)
            } catch (error: Exception) {
                recoverFromEncoderFailure(stream)
                null
            }
            if (reference != null) {
                count(role)
                return reference
            }
            if (!videoUnavailable) return skip(role, lastSkipReason ?: "encoder_busy")
        }
        return captureJpeg(bitmap, frameId, reason, sessionId, stream, role, now)
    }

    /** Closes every growing segment so the files on disk are complete. Called before an export. */
    fun finishOpenSegments() = synchronized(lock) {
        open.keys.toList().forEach { finishSegment(it) }
    }

    fun frameCount(): Int = written.get()

    /** Frames stored for one session: indexed video frames plus any JPEG fallback frames. */
    fun frameCountForSession(sessionId: String): Int {
        val dir = File(directory, sessionId.replace(UNSAFE, "_"))
        val jpegs = dir.listFiles().orEmpty().count { it.extension.equals("jpg", true) }
        val index = File(dir, INDEX_FILE)
        val frames = if (index.isFile) index.useLines { lines -> lines.count { it.isNotBlank() } } else 0
        return jpegs + frames
    }

    /** What the manifest must say, so a bundle with images cannot be mistaken for one without. */
    fun manifest(): Map<String, Any?> = mapOf(
        "evidenceCaptureEnabled" to enabled,
        "evidenceFrameCount" to written.get(),
        "evidenceFramesSkipped" to skipped.get(),
        "evidenceBytes" to bytes.get(),
        "evidenceFrameLimit" to MAX_FRAMES,
        "evidenceByteLimit" to EvidenceVideoPlan.EVIDENCE_BYTE_CAP,
        "evidenceExportByteLimit" to EvidenceVideoPlan.EXPORT_BYTE_LIMIT,
        "evidenceCaptureMode" to "ten_minute_compressed_video_timeline_plus_every_input",
        "evidenceFormat" to format,
        "evidenceEncoder" to encoderName,
        "evidenceLongEdgeLimitPx" to EvidenceVideoPlan.LONG_EDGE_PX,
        "evidenceSegmentSeconds" to EvidenceVideoPlan.SEGMENT_MS / 1_000,
        "evidenceKeyframeIntervalSeconds" to EvidenceVideoPlan.KEYFRAME_INTERVAL_S,
        "evidenceTargetBitrateBps" to targetBitrate,
        "evidenceRequestedBitrateBps" to requestedBitrate,
        "evidenceEncoderRateRatio" to correction.ratio,
        "evidenceSegmentsFinished" to segmentsFinished.get(),
        "evidenceSegmentsLost" to segmentsLost.get(),
        "evidenceFrameIndex" to "evidence/<session>/$INDEX_FILE",
        "evidenceReferenceFormat" to "<session>/<stream>-NNNN.mp4#t=<seconds>",
        "evidenceTimelineIntervalMs" to 1_000L,
        "evidenceTimelineWindowMs" to EvidenceVideoPlan.WINDOW_MS,
        "evidenceTimelineTargetFrames" to 600,
        "evidenceTimelineFrameCount" to timelineWritten.get(),
        "evidenceAnalysisInputCapturePolicy" to "every_selected_input",
        "evidenceAnalysisInputFrameCount" to analysisInputWritten.get(),
        "evidenceAnalysisInputFramesSkipped" to analysisInputSkipped.get(),
        "evidenceSupplementalFrameCount" to supplementalWritten.get(),
    )

    /**
     * Deletes every stored frame. With [recordAsDiscarded], the events that still point at them
     * are told why the frames are gone; a full history wipe deletes those events too, and with
     * them any need to explain.
     */
    fun clear(recordAsDiscarded: Boolean = false) = synchronized(lock) {
        open.values.forEach { runCatching { it.segment.abandon() } }
        open.clear()
        if (recordAsDiscarded) {
            mediaFiles().forEach { appendAbsent(it.relativeTo(directory).invariantSeparatorsPath, "discarded_by_user") }
        } else {
            absentLedger.delete()
        }
        check(directory.listFiles()?.all { it.deleteRecursively() } != false) { "تعذر حذف بعض صور التشخيص" }
        listOf(written, timelineWritten, analysisInputWritten, supplementalWritten, analysisInputSkipped,
            skipped, segmentsFinished, segmentsLost).forEach { it.set(0) }
        bytes.set(0L)
        segmentNumbers.clear()
        lastJpegAtMs.clear()
        windowStartedAtMs = 0L
        lastBitrateAtMs = 0L
        bytesAtBitrate = 0L
        framesSinceBitrate = 0
    }

    private fun encodeVideo(
        bitmap: Bitmap,
        frameId: String,
        reason: String,
        sessionId: String,
        stream: EvidenceVideoPlan.Stream,
        nowMs: Long,
    ): String? {
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        open[stream]?.let { current ->
            val age = (nowNanos - current.startedAtNanos) / 1_000_000L
            if (current.sessionId != sessionId || age >= EvidenceVideoPlan.SEGMENT_MS ||
                !EvidenceVideoPlan.fits(current.segment.canvas, bitmap.width, bitmap.height)
            ) {
                finishSegment(stream)
            }
        }
        val current = open[stream] ?: openSegment(stream, sessionId, bitmap, nowMs, nowNanos) ?: return null
        val before = current.segment.bytesWritten
        val presentationUs = current.segment.encode(bitmap, (nowNanos - current.startedAtNanos) / 1_000L)
        account(current, current.segment.bytesWritten - before)
        if (presentationUs == null) {
            lastSkipReason = "encoder_busy"
            return null
        }
        adjustBitrate(nowMs)
        val reference = EvidenceVideoPlan.reference(current.relativePath, presentationUs)
        appendIndex(
            sessionId,
            JSONObject().apply {
                put("reference", reference)
                put("frameId", frameId)
                put("reason", reason)
                put("stream", stream.fileStem)
                put("presentationMs", presentationUs / 1_000L)
                put("frameInSegment", current.segment.inputFrames - 1)
                put("epochMs", System.currentTimeMillis())
                put("sourceWidth", bitmap.width)
                put("sourceHeight", bitmap.height)
                put("encodedWidth", current.segment.canvas.width)
                put("encodedHeight", current.segment.canvas.height)
            },
        )
        return reference
    }

    private fun openSegment(
        stream: EvidenceVideoPlan.Stream,
        sessionId: String,
        bitmap: Bitmap,
        nowMs: Long,
        nowNanos: Long,
    ): OpenSegment? {
        val safeSession = sessionId.replace(UNSAFE, "_")
        val key = "$safeSession/${stream.fileStem}"
        val number = segmentNumbers.getOrPut(key) {
            File(directory, safeSession).listFiles().orEmpty().count { it.name.startsWith(stream.fileStem + "-") }
        } + 1
        segmentNumbers[key] = number
        val relativePath = "$safeSession/${stream.fileStem}-${number.toString().padStart(4, '0')}.mp4"
        targetBitrate = EvidenceVideoPlan.totalBitrate(bytes.get(), nowMs - windowStartedAtMs)
        requestedBitrate = correction.request(targetBitrate)
        val segment = EvidenceVideoSegment.open(
            File(directory, relativePath),
            EvidenceVideoPlan.canvasFor(bitmap.width, bitmap.height),
            stream,
            EvidenceVideoPlan.streamBitrate(stream, requestedBitrate),
        )
        if (segment == null) {
            // Not one encoder on this device would start. The JPEG fallback takes over for good.
            videoUnavailable = true
            format = "jpeg_fallback"
            return null
        }
        format = if (segment.mime == android.media.MediaFormat.MIMETYPE_VIDEO_HEVC) "hevc_mp4_segments" else "avc_mp4_segments"
        encoderName = segment.codecName
        return OpenSegment(sessionId, relativePath, segment, nowNanos).also { open[stream] = it }
    }

    private fun finishSegment(stream: EvidenceVideoPlan.Stream) {
        val current = open.remove(stream) ?: return
        val segment = current.segment
        val file = try {
            segment.finish()
        } catch (error: Exception) {
            runCatching { segment.abandon() }
            null
        }
        // The finished file includes the container's own index, which the running count did not.
        bytes.addAndGet((file?.length() ?: 0L) - current.countedBytes)
        if (file == null) {
            if (segment.inputFrames > 0) {
                appendAbsent(current.relativePath, "segment_failed_to_finish")
                segmentsLost.incrementAndGet()
            }
            return
        }
        segmentsFinished.incrementAndGet()
        appendLine(
            File(directory, "${current.sessionId.replace(UNSAFE, "_")}/$SEGMENTS_FILE"),
            JSONObject().apply {
                put("segment", current.relativePath)
                put("encoder", segment.codecName)
                put("mime", segment.mime)
                put("width", segment.canvas.width)
                put("height", segment.canvas.height)
                put("framesIn", segment.inputFrames)
                // Lower than framesIn when the encoder itself dropped frames to hold its rate.
                put("framesOut", segment.outputFrames)
                put("bytes", file.length())
            }.toString(),
        )
    }

    private fun account(current: OpenSegment, delta: Long) {
        if (delta == 0L) return
        current.countedBytes += delta
        bytes.addAndGet(delta)
    }

    /**
     * Every few seconds: learn how far the encoders missed what they were asked for, then
     * re-spread what is left of the budget over what is left of the window.
     */
    private fun adjustBitrate(nowMs: Long) {
        framesSinceBitrate++
        if (lastBitrateAtMs == 0L) {
            lastBitrateAtMs = nowMs
            bytesAtBitrate = bytes.get()
            return
        }
        val interval = nowMs - lastBitrateAtMs
        if (interval < BITRATE_UPDATE_MS) return
        val spent = (bytes.get() - bytesAtBitrate) * 8.0
        // Only an interval with frames arriving near the nominal rate says anything about the encoder.
        val nominalFrames = EvidenceVideoPlan.Stream.entries.sumOf { it.nominalFrameRate } * interval / 1_000.0
        if (requestedBitrate > 0 && framesSinceBitrate >= nominalFrames * ACTIVE_FRACTION) {
            correction.observe(requestedBitrate * interval / 1_000.0, spent)
        }
        lastBitrateAtMs = nowMs
        bytesAtBitrate = bytes.get()
        framesSinceBitrate = 0
        targetBitrate = EvidenceVideoPlan.totalBitrate(bytes.get(), nowMs - windowStartedAtMs)
        val request = correction.request(targetBitrate)
        if (requestedBitrate != 0 && kotlin.math.abs(request - requestedBitrate) < requestedBitrate / 10) return
        requestedBitrate = request
        open.forEach { (stream, current) -> current.segment.setBitrate(EvidenceVideoPlan.streamBitrate(stream, request)) }
    }

    private fun recoverFromEncoderFailure(stream: EvidenceVideoPlan.Stream) {
        lastSkipReason = "encoder_failed"
        finishSegment(stream)
        if (++encoderFailures >= MAX_ENCODER_FAILURES) {
            open.keys.toList().forEach { finishSegment(it) }
            videoUnavailable = true
            format = "jpeg_fallback"
        }
    }

    /**
     * The last resort, for a device that will start no video encoder. Smaller and sparser than
     * the old JPEG evidence so it lives inside the same budget: 720 px, quality 35, the timeline
     * every two seconds and inputs every three.
     */
    private fun captureJpeg(
        bitmap: Bitmap,
        frameId: String,
        reason: String,
        sessionId: String,
        stream: EvidenceVideoPlan.Stream,
        role: Role,
        nowMs: Long,
    ): String? {
        val spacing = if (stream == EvidenceVideoPlan.Stream.TIMELINE) 2_000L else 3_000L
        val last = lastJpegAtMs[stream]
        if (last != null && nowMs - last < spacing) return skip(role, "jpeg_fallback_spacing")
        var incomplete: File? = null
        return runCatching {
            val safeReason = reason.replace(UNSAFE, "_").take(40)
            val name = "${sessionId.replace(UNSAFE, "_")}/${frameId.replace(UNSAFE, "_")}-$safeReason-${java.util.UUID.randomUUID()}.jpg"
            val file = File(directory, name)
            file.parentFile?.mkdirs()
            check(file.createNewFile()) { "Evidence filename collision" }
            incomplete = file
            FileOutputStream(file).use { output ->
                val scaled = scaled(bitmap, JPEG_FALLBACK_EDGE)
                try {
                    check(scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_FALLBACK_QUALITY, output)) { "could not encode evidence frame" }
                } finally {
                    if (scaled !== bitmap) scaled.recycle()
                }
            }
            bytes.addAndGet(file.length())
            lastJpegAtMs[stream] = nowMs
            count(role)
            incomplete = null
            name
        }.getOrElse {
            incomplete?.delete()
            skip(role, "jpeg_write_failed")
        }
    }

    private enum class Role { TIMELINE, ANALYSIS_INPUT, SUPPLEMENTAL }

    private fun roleOf(reason: String): Role = when {
        reason.contains("timeline_1s") -> Role.TIMELINE
        reason.contains("analysis_input_") -> Role.ANALYSIS_INPUT
        else -> Role.SUPPLEMENTAL
    }

    private fun count(role: Role) {
        written.incrementAndGet()
        when (role) {
            Role.TIMELINE -> timelineWritten.incrementAndGet()
            Role.ANALYSIS_INPUT -> analysisInputWritten.incrementAndGet()
            Role.SUPPLEMENTAL -> supplementalWritten.incrementAndGet()
        }
    }

    private fun skip(role: Role, reason: String): String? {
        lastSkipReason = reason
        skipped.incrementAndGet()
        if (role == Role.ANALYSIS_INPUT) analysisInputSkipped.incrementAndGet()
        return null
    }

    private fun appendIndex(sessionId: String, entry: JSONObject) =
        appendLine(File(directory, "${sessionId.replace(UNSAFE, "_")}/$INDEX_FILE"), entry.toString())

    private fun appendAbsent(path: String, reason: String) =
        runCatching { appendLine(absentLedger, "$path\t$reason") }

    private fun appendLine(file: File, line: String) {
        file.parentFile?.mkdirs()
        FileOutputStream(file, true).use { it.write((line + "\n").toByteArray(Charsets.UTF_8)) }
    }

    private fun scaled(bitmap: Bitmap, longEdgeLimit: Int): Bitmap {
        val longEdge = maxOf(bitmap.width, bitmap.height)
        if (longEdge <= longEdgeLimit) return bitmap
        val factor = longEdgeLimit.toDouble() / longEdge
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * factor).toInt().coerceAtLeast(1),
            (bitmap.height * factor).toInt().coerceAtLeast(1),
            true,
        )
    }

    companion object {
        /** Per-session list of every stored frame: reference, frame id, reason, time, sizes. */
        const val INDEX_FILE = "frames.jsonl"

        /** Per-session list of finished segments, with the encoder's own frame count. */
        const val SEGMENTS_FILE = "segments.jsonl"

        /** A ceiling on the index, far above what ten minutes produce; the byte cap binds first. */
        private const val MAX_FRAMES = 8_000
        private const val BITRATE_UPDATE_MS = 5_000L
        private const val ACTIVE_FRACTION = 0.6
        private const val MAX_ENCODER_FAILURES = 3
        private const val JPEG_FALLBACK_EDGE = 720
        private const val JPEG_FALLBACK_QUALITY = 35
        private val UNSAFE = Regex("[^A-Za-z0-9_-]")
    }
}
