package com.abdullah.visionbridge.data.diagnostics

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.abdullah.visionbridge.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Continuous, automatic, image-free diagnostic flight recorder. */
class DiagnosticRecorder(context: Context) {
    data class StorageStatus(
        val sessionCount: Int,
        val totalBytes: Long,
        val imageCount: Int,
        val currentSessionId: String?,
    )

    private data class TraceStats(
        val sessionId: String,
        val traceId: String,
        var frameId: String = "",
        var capturedAtEpochMs: Long = 0L,
        var eventCount: Int = 0,
        var firstLocalMs: Double? = null,
        var firstCloudRequestMs: Double? = null,
        var firstCloudChunkMs: Double? = null,
        var firstDisplayedMs: Double? = null,
        var maxLocalChars: Int = 0,
        var maxModelChars: Int = 0,
        var maxDisplayedChars: Int = 0,
        var cancellationCount: Int = 0,
        var fingerprintCount: Int = 0,
        val dropReasons: MutableSet<String> = linkedSetOf(),
        val qualityClasses: MutableSet<String> = linkedSetOf(),
        val findingCodes: MutableSet<String> = linkedSetOf(),
    )

    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, "diagnostic_black_box").apply { mkdirs() }
    private val sessions = File(root, "sessions").apply { mkdirs() }
    private val mutex = Mutex()
    private val processId = UUID.randomUUID().toString()

    @Volatile private var sessionId: String? = null
    @Volatile private var sessionDir: File? = null
    @Volatile private var eventSequence = 0L

    private var eventStream: FileOutputStream? = null
    private var eventWriter: BufferedWriter? = null
    private var lastDurableSyncNanos = 0L
    private var eventsSinceDurableSync = 0

    init {
        runCatching { purgeLegacyImagesWithoutLock() }
    }

    suspend fun startSession(settings: Map<String, Any?>): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            closeWriterLocked()
            val id = "${timestamp()}_${UUID.randomUUID()}"
            val dir = File(sessions, id).apply { mkdirs() }
            sessionId = id
            sessionDir = dir
            eventSequence = 0L
            writeDeviceFileLocked(dir, id, settings)
            openWriterLocked()
            appendLocked("SESSION_START", mapOf("settings" to settings), forceDurable = true)
            appendLocked(
                "AUTOMATIC_DIAGNOSTICS_ACTIVE",
                mapOf(
                    "requiresManualProblemMarker" to false,
                    "continuousRecording" to true,
                    "storesImages" to false,
                    "visualEvidence" to "aggregate_metrics_and_one_way_hash",
                    "retentionDays" to RETENTION_DAYS,
                    "maximumRawBytes" to MAX_TOTAL_BYTES,
                ),
                forceDurable = true,
            )
            val retention = pruneRetainedSessionsLocked()
            if (retention.isNotEmpty()) appendLocked("RETENTION_APPLIED", retention, forceDurable = true)
            id
        }
    }

    suspend fun endSession(reason: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureSessionLocked()
            appendLocked("SESSION_END", mapOf("reason" to reason), forceDurable = true)
            durableSyncLocked()
        }
    }

    suspend fun record(type: String, fields: Map<String, Any?> = emptyMap()) = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureSessionLocked()
            appendLocked(type, fields)
        }
    }

    suspend fun markProblem(note: String = "") = record(
        "USER_MARKED_PROBLEM",
        mapOf(
            "note" to note.take(MAX_NOTE_CHARS),
            "optionalMarker" to true,
            "automaticRecordingAlreadyActive" to true,
        ),
    )

    suspend fun recordFailure(stage: String, error: Throwable, fields: Map<String, Any?> = emptyMap()) {
        record(
            "FAILURE",
            fields + mapOf(
                "stage" to stage,
                "exception" to error::class.java.name,
                "message" to (error.message ?: ""),
                "stackTrace" to error.stackTraceToString().take(MAX_STACK_CHARS),
            ),
        )
    }

    fun recordFatalBlocking(error: Throwable) {
        runCatching {
            runBlocking(Dispatchers.IO) {
                recordFailure("UNCAUGHT_EXCEPTION", error)
                flush()
            }
        }
    }

    suspend fun flush() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (sessionDir != null) durableSyncLocked()
        }
    }

    suspend fun export(): File = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureSessionLocked()
            appendLocked(
                "EXPORT_STARTED",
                mapOf(
                    "automaticRecording" to true,
                    "includesImages" to false,
                    "manualMarkerRequired" to false,
                ),
                forceDurable = true,
            )
            durableSyncLocked()
            purgeLegacyImagesLocked()

            val exportDir = File(appContext.cacheDir, "diagnostic_exports").apply { mkdirs() }
            exportDir.listFiles()?.filter { it.isFile }?.forEach { it.delete() }
            val output = File(
                exportDir,
                "VisionBridge-automatic-diagnostics-${timestamp()}-NO-IMAGES.zip",
            )
            val summary = buildSummaryLocked()
            val findings = buildAutomaticFindingsLocked()
            val traceAnalysis = buildTraceAnalysisLocked()

            ZipOutputStream(FileOutputStream(output)).use { zip ->
                zip.setLevel(Deflater.BEST_COMPRESSION)
                sessionFiles().forEach { session ->
                    session.walkTopDown()
                        .filter { it.isFile && !isImageFile(it) }
                        .forEach { file ->
                            val relative = file.relativeTo(root).path.replace(File.separatorChar, '/')
                            zip.putNextEntry(ZipEntry(relative))
                            file.inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                        }
                }
                writeZipText(zip, "diagnostic_summary.json", summary.toString(2))
                writeZipText(zip, "automatic_findings.json", findings.toString(2))
                writeZipText(zip, "trace_analysis.json", traceAnalysis.toString(2))
                writeZipText(zip, "README_AR.txt", README_AR)
                writeZipText(
                    zip,
                    "export_manifest.json",
                    JSONObject().apply {
                        put("schemaVersion", SCHEMA_VERSION)
                        put("exportedAtEpochMs", System.currentTimeMillis())
                        put("automaticContinuousRecording", true)
                        put("manualProblemMarkerRequired", false)
                        put("includesImages", false)
                        put("includesThumbnails", false)
                        put("includesPixelGrids", false)
                        put("includesVisualFingerprints", true)
                        put("visualFingerprintsReconstructable", false)
                        put("includesRecognizedText", true)
                        put("includesModelOutput", true)
                        put("includesQueueAndCancellationTimeline", true)
                        put("includesNetworkTiming", true)
                        put("includesSpeechTiming", true)
                        put("includesAutomaticFindings", true)
                        put("includesPerTraceAnalysis", true)
                        put("sessionCount", sessionFiles().size)
                        put("retentionDays", RETENTION_DAYS)
                        put("maximumRawBytes", MAX_TOTAL_BYTES)
                        put(
                            "privacyWarning",
                            "Contains recognized text, model output, app settings and timing data. It contains no screen images and excludes API keys and authorization headers.",
                        )
                    }.toString(2),
                )
            }
            File(exportDir, "${output.name}.sha256.txt").writeText(
                "${sha256(output)}  ${output.name}\n",
            )
            output
        }
    }

    suspend fun storageStatus(): StorageStatus = withContext(Dispatchers.IO) {
        mutex.withLock {
            purgeLegacyImagesLocked()
            val all = sessionFiles()
            StorageStatus(
                sessionCount = all.size,
                totalBytes = all.sumOf(::directoryBytes),
                imageCount = 0,
                currentSessionId = sessionId,
            )
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            closeWriterLocked()
            sessions.deleteRecursively()
            sessions.mkdirs()
            sessionId = null
            sessionDir = null
            eventSequence = 0L
        }
    }

    private fun ensureSessionLocked() {
        if (sessionDir != null) {
            if (eventWriter == null) openWriterLocked()
            return
        }
        val id = "automatic_${timestamp()}_${UUID.randomUUID()}"
        val dir = File(sessions, id).apply { mkdirs() }
        sessionId = id
        sessionDir = dir
        eventSequence = 0L
        writeDeviceFileLocked(dir, id, emptyMap())
        openWriterLocked()
        appendLocked(
            "AUTOMATIC_SESSION_RECOVERED",
            mapOf(
                "reason" to "event_arrived_before_capture_session_start",
                "storesImages" to false,
            ),
            forceDurable = true,
        )
    }

    private fun writeDeviceFileLocked(dir: File, id: String, settings: Map<String, Any?>) {
        writeJsonDurably(
            File(dir, "device.json"),
            JSONObject().apply {
                put("schemaVersion", SCHEMA_VERSION)
                put("sessionId", id)
                put("processId", processId)
                put("appVersion", BuildConfig.VERSION_NAME)
                put("versionCode", BuildConfig.VERSION_CODE)
                put("manufacturer", Build.MANUFACTURER)
                put("model", Build.MODEL)
                put("device", Build.DEVICE)
                put("sdk", Build.VERSION.SDK_INT)
                put("abi", Build.SUPPORTED_ABIS.joinToString(","))
                put("startedAtEpochMs", System.currentTimeMillis())
                put("startedAtElapsedRealtimeNanos", SystemClock.elapsedRealtimeNanos())
                put("automaticDiagnostics", true)
                put("storesImages", false)
                put("settings", JSONObject(settings))
            },
        )
    }

    private fun appendLocked(
        type: String,
        fields: Map<String, Any?>,
        forceDurable: Boolean = false,
        deriveFindings: Boolean = true,
    ) {
        if (eventWriter == null) openWriterLocked()
        val event = JSONObject().apply {
            put("schemaVersion", SCHEMA_VERSION)
            put("sequence", ++eventSequence)
            put("sessionId", sessionId)
            put("processId", processId)
            put("type", type)
            put("epochMs", System.currentTimeMillis())
            put("elapsedRealtimeNanos", SystemClock.elapsedRealtimeNanos())
            put("thread", Thread.currentThread().name)
            fields.forEach { (key, value) -> put(key, jsonValue(value)) }
        }
        eventWriter?.append(event.toString())?.append('\n')
        eventWriter?.flush()
        eventsSinceDurableSync++
        val now = SystemClock.elapsedRealtimeNanos()
        if (
            forceDurable ||
            eventsSinceDurableSync >= MAX_EVENTS_BETWEEN_FSYNC ||
            now - lastDurableSyncNanos >= MAX_NANOS_BETWEEN_FSYNC
        ) {
            durableSyncLocked()
        }

        if (deriveFindings && type != "AUTO_DIAGNOSTIC_FINDING") {
            deriveFindings(type, fields).forEach { finding ->
                appendLocked(
                    type = "AUTO_DIAGNOSTIC_FINDING",
                    fields = finding + mapOf(
                        "relatedType" to type,
                        "relatedTraceId" to fields["traceId"],
                        "relatedFrameId" to fields["frameId"],
                    ),
                    deriveFindings = false,
                )
            }
        }
    }

    private fun deriveFindings(type: String, fields: Map<String, Any?>): List<Map<String, Any?>> {
        val output = mutableListOf<Map<String, Any?>>()
        val timingCandidates = TIMING_FIELDS.mapNotNull { key ->
            (fields[key] as? Number)?.toDouble()?.let { key to it }
        }
        val slowest = timingCandidates.maxByOrNull { it.second }
        if (slowest != null && slowest.second >= SLOW_STAGE_MS) {
            output += finding(
                code = if (slowest.second >= CRITICAL_STAGE_MS) {
                    "CRITICAL_PIPELINE_STALL"
                } else {
                    "SLOW_PIPELINE_STAGE"
                },
                severity = if (slowest.second >= CRITICAL_STAGE_MS) "critical" else "warning",
                explanation = "تجاوزت مرحلة ${slowest.first} الحد الزمني المتوقع",
                details = mapOf("timingField" to slowest.first, "valueMs" to slowest.second),
            )
        }

        if (type == "FAILURE") {
            output += finding(
                code = "APP_STAGE_FAILURE",
                severity = "critical",
                explanation = "سجل التطبيق استثناءً في إحدى مراحل المعالجة",
                details = mapOf(
                    "stage" to fields["stage"],
                    "exception" to fields["exception"],
                    "message" to fields["message"],
                ),
            )
        }

        if (type.contains("CANCELLED") || type.contains("CANCELED")) {
            output += finding(
                code = "ANALYSIS_CANCELLED",
                severity = "warning",
                explanation = "أُلغي تحليل قبل اكتمال النتيجة",
                details = mapOf("reason" to fields["reason"]),
            )
        }

        if (
            type in OCR_COMPLETION_EVENTS &&
            (fields["blank"] == true || ((fields["textLength"] as? Number)?.toInt() ?: 0) == 0)
        ) {
            output += finding(
                code = "LOCAL_OCR_RETURNED_BLANK",
                severity = "warning",
                explanation = "انتهى محرك قراءة محلي دون استخراج نص",
                details = mapOf("engineEvent" to type, "durationMs" to fields["durationMs"]),
            )
        }

        if (type.endsWith("VISUAL_FINGERPRINT")) {
            val quality = fields["qualityClass"]?.toString().orEmpty()
            if (quality.isNotBlank() && quality != "usable") {
                output += finding(
                    code = "VISUAL_INPUT_${quality.uppercase(Locale.US)}",
                    severity = "warning",
                    explanation = "تشير القياسات البصرية إلى أن اللقطة غير مناسبة للقراءة",
                    details = mapOf(
                        "qualityClass" to quality,
                        "focusVariance" to fields["laplacianFocusVariance"],
                        "dynamicRange" to fields["dynamicRangeP95P05"],
                        "darkPixelRatio" to fields["darkPixelRatio"],
                        "brightPixelRatio" to fields["brightPixelRatio"],
                    ),
                )
            }
            if (fields["likelySmoothOcclusion"] == true) {
                output += finding(
                    code = "LIKELY_FINGER_OR_SMOOTH_OCCLUSION",
                    severity = "warning",
                    explanation = "اللقطة تبدو محجوبة بسطح أملس أو إصبع أو اهتزاز شديد",
                    details = mapOf(
                        "focusVariance" to fields["laplacianFocusVariance"],
                        "edgeDensity" to fields["combinedEdgeDensity"],
                    ),
                )
            }
        }

        val reason = fields["reason"]?.toString().orEmpty()
        if (type in DROP_EVENTS && reason.isNotBlank() && reason !in EXPECTED_DROP_REASONS) {
            output += finding(
                code = "UNEXPECTED_FRAME_OR_QUEUE_DROP",
                severity = "warning",
                explanation = "فُقد إطار أو طلب لسبب غير اعتيادي",
                details = mapOf("reason" to reason),
            )
        }
        return output
    }

    private fun finding(
        code: String,
        severity: String,
        explanation: String,
        details: Map<String, Any?>,
    ): Map<String, Any?> = mapOf(
        "code" to code,
        "severity" to severity,
        "explanationArabic" to explanation,
        "details" to details,
    )

    private fun openWriterLocked() {
        val dir = sessionDir ?: return
        val file = File(dir, "events.jsonl")
        eventStream = FileOutputStream(file, true)
        eventWriter = BufferedWriter(
            OutputStreamWriter(eventStream, Charsets.UTF_8),
            WRITER_BUFFER_CHARS,
        )
        lastDurableSyncNanos = SystemClock.elapsedRealtimeNanos()
        eventsSinceDurableSync = 0
    }

    private fun durableSyncLocked() {
        eventWriter?.flush()
        eventStream?.fd?.sync()
        lastDurableSyncNanos = SystemClock.elapsedRealtimeNanos()
        eventsSinceDurableSync = 0
    }

    private fun closeWriterLocked() {
        runCatching { durableSyncLocked() }
        runCatching { eventWriter?.close() }
        runCatching { eventStream?.close() }
        eventWriter = null
        eventStream = null
        eventsSinceDurableSync = 0
    }

    private fun buildSummaryLocked(): JSONObject {
        val sessionSummaries = JSONArray()
        val globalCounts = linkedMapOf<String, Int>()
        var globalEvents = 0
        var globalFindings = 0
        var globalFingerprints = 0

        sessionFiles().forEach { session ->
            val counts = linkedMapOf<String, Int>()
            var eventCount = 0
            var findingCount = 0
            var fingerprintCount = 0
            var firstEpoch: Long? = null
            var lastEpoch: Long? = null
            forEachEvent(session) { event ->
                eventCount++
                val type = event.optString("type", "UNKNOWN")
                counts[type] = (counts[type] ?: 0) + 1
                globalCounts[type] = (globalCounts[type] ?: 0) + 1
                if (type == "AUTO_DIAGNOSTIC_FINDING") findingCount++
                if (type.endsWith("VISUAL_FINGERPRINT")) fingerprintCount++
                val epoch = event.optLong("epochMs", 0L)
                if (epoch > 0L) {
                    if (firstEpoch == null) firstEpoch = epoch
                    lastEpoch = epoch
                }
            }
            globalEvents += eventCount
            globalFindings += findingCount
            globalFingerprints += fingerprintCount
            sessionSummaries.put(
                JSONObject().apply {
                    put("sessionId", session.name)
                    put("eventCount", eventCount)
                    put("automaticFindingCount", findingCount)
                    put("visualFingerprintCount", fingerprintCount)
                    put("imageCount", 0)
                    put("firstEpochMs", firstEpoch ?: JSONObject.NULL)
                    put("lastEpochMs", lastEpoch ?: JSONObject.NULL)
                    put("bytes", directoryBytes(session))
                    put("eventTypeCounts", JSONObject(counts as Map<*, *>))
                },
            )
        }

        return JSONObject().apply {
            put("schemaVersion", SCHEMA_VERSION)
            put("generatedAtEpochMs", System.currentTimeMillis())
            put("automaticContinuousRecording", true)
            put("manualMarkerRequired", false)
            put("containsImages", false)
            put("sessionCount", sessionSummaries.length())
            put("eventCount", globalEvents)
            put("automaticFindingCount", globalFindings)
            put("visualFingerprintCount", globalFingerprints)
            put("eventTypeCounts", JSONObject(globalCounts as Map<*, *>))
            put("sessions", sessionSummaries)
        }
    }

    private fun buildAutomaticFindingsLocked(): JSONObject {
        val findings = JSONArray()
        val codeCounts = linkedMapOf<String, Int>()
        val severityCounts = linkedMapOf<String, Int>()
        sessionFiles().forEach { session ->
            forEachEvent(session) { event ->
                if (event.optString("type") == "AUTO_DIAGNOSTIC_FINDING") {
                    val code = event.optString("code", "UNKNOWN")
                    val severity = event.optString("severity", "unknown")
                    codeCounts[code] = (codeCounts[code] ?: 0) + 1
                    severityCounts[severity] = (severityCounts[severity] ?: 0) + 1
                    findings.put(
                        JSONObject(event.toString()).apply {
                            put("sourceSessionId", session.name)
                        },
                    )
                }
            }
        }
        return JSONObject().apply {
            put("schemaVersion", SCHEMA_VERSION)
            put("generatedAtEpochMs", System.currentTimeMillis())
            put("findingCount", findings.length())
            put("codeCounts", JSONObject(codeCounts as Map<*, *>))
            put("severityCounts", JSONObject(severityCounts as Map<*, *>))
            put("findings", findings)
        }
    }

    private fun buildTraceAnalysisLocked(): JSONObject {
        val traces = linkedMapOf<String, TraceStats>()
        sessionFiles().forEach { session ->
            forEachEvent(session) { event ->
                val traceId = event.optString("traceId", event.optString("frameId", ""))
                if (traceId.isNotBlank()) {
                    val key = "${session.name}:$traceId"
                    val stats = traces.getOrPut(key) { TraceStats(session.name, traceId) }
                    updateTraceStats(stats, event)
                }
            }
        }

        val traceArray = JSONArray()
        traces.values
            .sortedWith(compareBy<TraceStats> { it.capturedAtEpochMs }.thenBy { it.traceId })
            .forEach { stats -> traceArray.put(traceJson(stats)) }
        return JSONObject().apply {
            put("schemaVersion", SCHEMA_VERSION)
            put("generatedAtEpochMs", System.currentTimeMillis())
            put("traceCount", traceArray.length())
            put("traces", traceArray)
        }
    }

    private fun updateTraceStats(stats: TraceStats, event: JSONObject) {
        stats.eventCount++
        if (stats.frameId.isBlank()) stats.frameId = event.optString("frameId", "")
        if (stats.capturedAtEpochMs == 0L) {
            stats.capturedAtEpochMs = event.optLong("capturedAtEpochMs", 0L)
        }
        val type = event.optString("type")
        val since = event.optDoubleOrNull("sinceCaptureMs")
        val textLength = when {
            event.has("textLength") -> event.optInt("textLength", 0)
            event.has("text") -> event.optString("text").length
            else -> 0
        }
        when (type) {
            "MLKIT_PROCESS_COMPLETED",
            "TESSERACT_PROCESS_COMPLETED",
            "LOCAL_OCR_COMPLETED",
            "INSTANT_LOCAL_OCR_PUBLISHED" -> {
                stats.maxLocalChars = maxOf(stats.maxLocalChars, textLength)
                stats.firstLocalMs = minNullable(stats.firstLocalMs, since)
            }
            "GEMINI_ANALYSIS_REQUESTED" ->
                stats.firstCloudRequestMs = minNullable(stats.firstCloudRequestMs, since)
            "MODEL_TEXT_CHUNK_EMITTED" ->
                stats.firstCloudChunkMs = minNullable(stats.firstCloudChunkMs, since)
            "MODEL_FINAL_TEXT_AVAILABLE" ->
                stats.maxModelChars = maxOf(stats.maxModelChars, textLength)
            "TEXT_DISPLAYED" -> {
                stats.maxDisplayedChars = maxOf(stats.maxDisplayedChars, textLength)
                stats.firstDisplayedMs = minNullable(stats.firstDisplayedMs, since)
            }
        }
        if (type.contains("CANCELLED") || type.contains("CANCELED")) stats.cancellationCount++
        if (type in DROP_EVENTS) {
            val reason = event.optString("reason")
            if (reason.isNotBlank()) stats.dropReasons += reason
        }
        if (type.endsWith("VISUAL_FINGERPRINT")) {
            stats.fingerprintCount++
            val quality = event.optString("qualityClass")
            if (quality.isNotBlank()) stats.qualityClasses += quality
        }
        if (type == "AUTO_DIAGNOSTIC_FINDING") {
            val code = event.optString("code")
            if (code.isNotBlank()) stats.findingCodes += code
        }
    }

    private fun traceJson(stats: TraceStats): JSONObject {
        val strongestEvidence = maxOf(stats.maxLocalChars, stats.maxModelChars)
        val suspected = mutableListOf<String>()
        if (strongestEvidence > 0 && stats.maxDisplayedChars == 0) suspected += "no_text_displayed"
        if (
            strongestEvidence >= INCOMPLETE_EVIDENCE_MIN_CHARS &&
            stats.maxDisplayedChars < strongestEvidence * INCOMPLETE_DISPLAY_RATIO
        ) {
            suspected += "displayed_text_much_shorter_than_extracted_text"
        }
        if ((stats.firstDisplayedMs ?: 0.0) >= SLOW_STAGE_MS) suspected += "slow_first_useful_response"
        if ((stats.firstCloudChunkMs ?: 0.0) >= CLOUD_STALL_MS) suspected += "slow_first_cloud_chunk"
        if (stats.cancellationCount >= 2) suspected += "repeated_cancellation"
        if (stats.qualityClasses.any { it != "usable" }) suspected += "poor_visual_input"

        return JSONObject().apply {
            put("sessionId", stats.sessionId)
            put("traceId", stats.traceId)
            put("frameId", stats.frameId)
            put("capturedAtEpochMs", stats.capturedAtEpochMs)
            put("eventCount", stats.eventCount)
            put("fingerprintCount", stats.fingerprintCount)
            put(
                "timingsMs",
                JSONObject().apply {
                    putNullable("firstLocal", stats.firstLocalMs)
                    putNullable("firstCloudRequest", stats.firstCloudRequestMs)
                    putNullable("firstCloudChunk", stats.firstCloudChunkMs)
                    putNullable("firstDisplayed", stats.firstDisplayedMs)
                },
            )
            put(
                "textLengths",
                JSONObject().apply {
                    put("maxLocal", stats.maxLocalChars)
                    put("maxModel", stats.maxModelChars)
                    put("maxDisplayed", stats.maxDisplayedChars)
                },
            )
            put("cancellationCount", stats.cancellationCount)
            put("dropReasons", JSONArray(stats.dropReasons.toList()))
            put("qualityClasses", JSONArray(stats.qualityClasses.toList()))
            put("automaticFindingCodes", JSONArray(stats.findingCodes.toList()))
            put("suspectedIssues", JSONArray(suspected))
        }
    }

    private fun pruneRetainedSessionsLocked(): Map<String, Any?> {
        purgeLegacyImagesLocked()
        val retained = sessionFiles().sortedBy { it.lastModified() }.toMutableList()
        val deleted = mutableListOf<Map<String, Any?>>()
        val cutoff = System.currentTimeMillis() -
            RETENTION_DAYS.toLong() * 24L * 60L * 60L * 1_000L

        retained.toList().forEach { candidate ->
            if (candidate != sessionDir && candidate.lastModified() < cutoff) {
                val bytes = directoryBytes(candidate)
                if (candidate.deleteRecursively()) {
                    retained.remove(candidate)
                    deleted += mapOf(
                        "sessionId" to candidate.name,
                        "bytes" to bytes,
                        "reason" to "age_limit",
                    )
                }
            }
        }

        var total = retained.sumOf(::directoryBytes)
        retained.toList().forEach { candidate ->
            if (total > MAX_TOTAL_BYTES && candidate != sessionDir) {
                val bytes = directoryBytes(candidate)
                if (candidate.deleteRecursively()) {
                    total -= bytes
                    deleted += mapOf(
                        "sessionId" to candidate.name,
                        "bytes" to bytes,
                        "reason" to "size_limit",
                    )
                }
            }
        }
        return if (deleted.isEmpty()) {
            emptyMap()
        } else {
            mapOf(
                "deletedSessions" to deleted,
                "retainedBytes" to total,
                "eventLogsWereRolledByPolicy" to true,
            )
        }
    }

    private fun purgeLegacyImagesWithoutLock() {
        sessionFiles().forEach { session ->
            File(session, "frames").deleteRecursively()
            File(session, "previews").deleteRecursively()
            session.walkTopDown()
                .filter { it.isFile && isImageFile(it) }
                .forEach { it.delete() }
        }
    }

    private fun purgeLegacyImagesLocked() = purgeLegacyImagesWithoutLock()

    private fun sessionFiles(): List<File> =
        sessions.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }.orEmpty()

    private fun forEachEvent(session: File, action: (JSONObject) -> Unit) {
        val file = File(session, "events.jsonl")
        if (!file.isFile) return
        file.forEachLine { line ->
            val event = runCatching { JSONObject(line) }.getOrNull()
            if (event != null) action(event)
        }
    }

    private fun isImageFile(file: File): Boolean {
        if (file.parentFile?.name in setOf("frames", "previews")) return true
        return file.extension.lowercase(Locale.US) in IMAGE_EXTENSIONS
    }

    private fun writeJsonDurably(file: File, json: JSONObject) {
        FileOutputStream(file).use { stream ->
            stream.write(json.toString(2).toByteArray(Charsets.UTF_8))
            stream.flush()
            stream.fd.sync()
        }
    }

    private fun writeZipText(zip: ZipOutputStream, path: String, text: String) {
        zip.putNextEntry(ZipEntry(path))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun directoryBytes(file: File): Long =
        if (file.isFile) file.length() else file.listFiles()?.sumOf(::directoryBytes) ?: 0L

    private fun jsonValue(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is Map<*, *> -> JSONObject(
            value.mapKeys { it.key.toString() }.mapValues { jsonValue(it.value) },
        )
        is Iterable<*> -> JSONArray(value.map(::jsonValue))
        is Array<*> -> JSONArray(value.map(::jsonValue))
        is Number, is Boolean, is String -> value
        else -> value.toString()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun JSONObject.optDoubleOrNull(name: String): Double? =
        if (has(name) && !isNull(name)) {
            optDouble(name).takeIf { !it.isNaN() }
        } else {
            null
        }

    private fun JSONObject.putNullable(name: String, value: Double?) {
        put(name, value ?: JSONObject.NULL)
    }

    private fun minNullable(current: Double?, candidate: Double?): Double? = when {
        candidate == null -> current
        current == null -> candidate
        else -> minOf(current, candidate)
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())

    private companion object {
        const val SCHEMA_VERSION = 3
        const val RETENTION_DAYS = 14
        const val MAX_TOTAL_BYTES = 256L * 1024L * 1024L
        const val MAX_STACK_CHARS = 80_000
        const val MAX_NOTE_CHARS = 4_000
        const val WRITER_BUFFER_CHARS = 64 * 1024
        const val MAX_EVENTS_BETWEEN_FSYNC = 24
        const val MAX_NANOS_BETWEEN_FSYNC = 1_000_000_000L
        const val SLOW_STAGE_MS = 2_000.0
        const val CRITICAL_STAGE_MS = 20_000.0
        const val CLOUD_STALL_MS = 10_000.0
        const val INCOMPLETE_EVIDENCE_MIN_CHARS = 60
        const val INCOMPLETE_DISPLAY_RATIO = 0.45

        val TIMING_FIELDS = listOf(
            "sinceCaptureMs",
            "durationMs",
            "dispatchDurationMs",
            "fingerprintSinceCaptureMs",
            "responseHeadersSinceCaptureMs",
            "firstEventSinceCaptureMs",
            "totalDurationMs",
        )
        val OCR_COMPLETION_EVENTS = setOf(
            "MLKIT_PROCESS_COMPLETED",
            "TESSERACT_PROCESS_COMPLETED",
            "LOCAL_OCR_COMPLETED",
        )
        val DROP_EVENTS = setOf("FRAME_DROPPED", "CLOUD_FRAME_DROPPED")
        val EXPECTED_DROP_REASONS = setOf(
            "capture_interval_throttle",
            "pending_snapshot_interval",
            "minimum_cloud_launch_interval",
            "below_fast_change_thresholds",
            "below_stable_change_thresholds",
            "waiting_for_target_settling",
        )
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "heic", "bmp", "gif")

        val README_AR = """
            حزمة التشخيص التلقائي الشامل لتطبيق رفيق الرؤية

            التسجيل:
            - يبدأ تلقائياً، ولا يحتاج الضغط على «حدثت مشكلة الآن».
            - الحزمة لا تحتوي أي صورة أو معاينة أو شبكة بكسلات.

            بديل الصور:
            - بصمة تغيّر أحادية الاتجاه لا يمكن تحويلها إلى صورة.
            - قياسات السطوع والتباين والحدة والحواف والضبابية والانسداد المحتمل
              والأشرطة السوداء ومناطق أعلى ووسط وأسفل الشاشة.
            - هندسة كتل وأسطر OCR كنسب موضعية، مع ثقة Tesseract.

            الملفات:
            - sessions/*/device.json: معلومات الجهاز والإصدار والإعدادات.
            - sessions/*/events.jsonl: الخط الزمني الخام الكامل والمرتب.
            - diagnostic_summary.json: ملخص جميع الجلسات والأحداث.
            - automatic_findings.json: الأعطال المكتشفة تلقائياً.
            - trace_analysis.json: تحليل كل لقطة من الالتقاط حتى النص المعروض.
            - export_manifest.json: وصف الحزمة وسياسة الخصوصية.

            الخصوصية:
            - لا توجد صور شاشة.
            - قد توجد النصوص المقروءة ونتائج Gemini لمعرفة النقص.
            - لا تُحفظ مفاتيح Gemini أو ترويسات التفويض.

            الاحتفاظ:
            - آخر 14 يوماً بحد أقصى 256 ميجابايت من البيانات النصية.
        """.trimIndent()
    }
}
