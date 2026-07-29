package com.abdullah.visionbridge.data.diagnostics

import android.content.Context
import android.graphics.Bitmap
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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.roundToInt

/**
 * Durable append-only diagnostic black box.
 *
 * Every completed event write is flushed and fsynced. Full selected frames are stored losslessly,
 * while occasional rejected-frame previews are stored as compact JPEGs. API keys, authorization
 * headers and request URLs containing keys must never be passed to this class.
 */
class DiagnosticRecorder(context: Context) {
    data class StorageStatus(
        val sessionCount: Int,
        val totalBytes: Long,
        val imageCount: Int,
        val currentSessionId: String?,
    )

    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, "diagnostic_black_box").apply { mkdirs() }
    private val sessions = File(root, "sessions").apply { mkdirs() }
    private val mutex = Mutex()
    private val processId = UUID.randomUUID().toString()

    @Volatile private var sessionId: String? = null
    @Volatile private var sessionDir: File? = null
    @Volatile private var eventSequence = 0L

    suspend fun startSession(settings: Map<String, Any?>): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            val id = "${timestamp()}_${UUID.randomUUID()}"
            val dir = File(sessions, id).apply {
                mkdirs()
                File(this, "frames").mkdirs()
                File(this, "previews").mkdirs()
            }
            sessionId = id
            sessionDir = dir
            eventSequence = 0L
            writeJsonDurably(File(dir, "device.json"), JSONObject().apply {
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
                put("settings", JSONObject(settings))
            })
            appendLocked("SESSION_START", mapOf("settings" to settings))
            pruneImagesLocked()
            id
        }
    }

    suspend fun endSession(reason: String) = record("SESSION_END", mapOf("reason" to reason))

    suspend fun record(type: String, fields: Map<String, Any?> = emptyMap()) = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureSessionLocked()
            appendLocked(type, fields)
        }
    }

    suspend fun markProblem(note: String = "") = record(
        "USER_MARKED_PROBLEM",
        mapOf("note" to note.take(MAX_NOTE_CHARS)),
    )

    suspend fun recordFailure(stage: String, error: Throwable, fields: Map<String, Any?> = emptyMap()) {
        record("FAILURE", fields + mapOf(
            "stage" to stage,
            "exception" to error::class.java.name,
            "message" to (error.message ?: ""),
            "stackTrace" to error.stackTraceToString().take(MAX_STACK_CHARS),
        ))
    }

    fun recordFatalBlocking(error: Throwable) {
        runCatching {
            runBlocking(Dispatchers.IO) {
                recordFailure("UNCAUGHT_EXCEPTION", error)
            }
        }
    }

    suspend fun recordFrame(
        bitmap: Bitmap,
        frameId: String,
        stage: String,
        metadata: Map<String, Any?> = emptyMap(),
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureSessionLocked()
            val dir = sessionDir ?: return@withLock
            val frameDir = File(dir, "frames").apply { mkdirs() }
            val extension = if (Build.VERSION.SDK_INT >= 30) "webp" else "png"
            val file = File(frameDir, "${safeName(frameId)}_${safeName(stage)}.$extension")
            val writeStarted = SystemClock.elapsedRealtimeNanos()
            FileOutputStream(file).use { output ->
                val ok = if (Build.VERSION.SDK_INT >= 30) {
                    bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, output)
                } else {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                }
                check(ok)
                output.flush()
                output.fd.sync()
            }
            val writeMs = nanosToMs(SystemClock.elapsedRealtimeNanos() - writeStarted)
            appendLocked("FRAME_SAVED", metadata + mapOf(
                "frameId" to frameId,
                "stage" to stage,
                "file" to "frames/${file.name}",
                "width" to bitmap.width,
                "height" to bitmap.height,
                "allocationBytes" to bitmap.allocationByteCount,
                "sha256" to sha256(file),
                "fileBytes" to file.length(),
                "diagnosticImageWriteMs" to writeMs,
                "lossless" to true,
            ))
        }
    }

    /** Stores a compact visual sample for a frame that was throttled or rejected before analysis. */
    suspend fun recordPreviewFrame(
        bitmap: Bitmap,
        frameId: String,
        reason: String,
        metadata: Map<String, Any?> = emptyMap(),
    ) = withContext(Dispatchers.IO) {
        val preview = scaleToMaxEdge(bitmap, PREVIEW_MAX_EDGE)
        try {
            mutex.withLock {
                ensureSessionLocked()
                val dir = sessionDir ?: return@withLock
                val previewDir = File(dir, "previews").apply { mkdirs() }
                val file = File(previewDir, "${safeName(frameId)}_${safeName(reason)}.jpg")
                val writeStarted = SystemClock.elapsedRealtimeNanos()
                FileOutputStream(file).use { output ->
                    check(preview.compress(Bitmap.CompressFormat.JPEG, PREVIEW_JPEG_QUALITY, output))
                    output.flush()
                    output.fd.sync()
                }
                appendLocked("FRAME_PREVIEW_SAVED", metadata + mapOf(
                    "frameId" to frameId,
                    "reason" to reason,
                    "file" to "previews/${file.name}",
                    "width" to preview.width,
                    "height" to preview.height,
                    "sha256" to sha256(file),
                    "fileBytes" to file.length(),
                    "diagnosticImageWriteMs" to nanosToMs(SystemClock.elapsedRealtimeNanos() - writeStarted),
                    "lossless" to false,
                ))
            }
        } finally {
            if (preview !== bitmap) preview.recycle()
        }
    }

    /**
     * Exports every retained event, selected-frame image and rejected-frame preview. There is no
     * image-free export path because a visual failure cannot be diagnosed reliably from text alone.
     */
    suspend fun export(): File = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureSessionLocked()
            appendLocked("EXPORT_STARTED", emptyMap())
            val exportDir = File(appContext.cacheDir, "diagnostic_exports").apply { mkdirs() }
            exportDir.listFiles()?.filter { it.isFile }?.forEach { it.delete() }
            val output = File(exportDir, "VisionBridge-complete-diagnostics-${timestamp()}-WITH-IMAGES.zip")
            val summary = buildSummaryLocked()
            ZipOutputStream(FileOutputStream(output)).use { zip ->
                sessions.listFiles()?.sortedBy { it.name }?.forEach { session ->
                    session.walkTopDown().filter { it.isFile }.forEach { file ->
                        val relative = file.relativeTo(root).path.replace(File.separatorChar, '/')
                        zip.putNextEntry(ZipEntry(relative))
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
                writeZipText(zip, "diagnostic_summary.json", summary.toString(2))
                writeZipText(zip, "README_AR.txt", README_AR)
                writeZipText(zip, "export_manifest.json", JSONObject().apply {
                    put("schemaVersion", SCHEMA_VERSION)
                    put("exportedAtEpochMs", System.currentTimeMillis())
                    put("includesImages", true)
                    put("includesExtractedText", true)
                    put("includesMonotonicTimeline", true)
                    put("sessionCount", sessions.listFiles()?.count { it.isDirectory } ?: 0)
                    put("archiveSha256ComputedAfterClose", true)
                    put("privacyWarning", "Contains screen images, recognized text, model output and timing data. API keys are excluded.")
                }.toString(2))
            }
            val sidecar = File(exportDir, "${output.name}.sha256.txt")
            sidecar.writeText("${sha256(output)}  ${output.name}\n")
            output
        }
    }

    suspend fun storageStatus(): StorageStatus = withContext(Dispatchers.IO) {
        mutex.withLock {
            val all = sessions.listFiles()?.filter { it.isDirectory }.orEmpty()
            StorageStatus(
                sessionCount = all.size,
                totalBytes = all.sumOf(::directoryBytes),
                imageCount = all.sumOf { session ->
                    session.walkTopDown().count { file ->
                        file.isFile && (file.parentFile?.name == "frames" || file.parentFile?.name == "previews")
                    }
                },
                currentSessionId = sessionId,
            )
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            sessions.deleteRecursively()
            sessions.mkdirs()
            sessionId = null
            sessionDir = null
            eventSequence = 0L
        }
    }

    private fun ensureSessionLocked() {
        if (sessionDir != null) return
        val id = "recovered_${timestamp()}_${UUID.randomUUID()}"
        sessionId = id
        sessionDir = File(sessions, id).apply {
            mkdirs()
            File(this, "frames").mkdirs()
            File(this, "previews").mkdirs()
        }
        eventSequence = File(sessionDir, "events.jsonl").takeIf { it.exists() }?.readLines()?.size?.toLong() ?: 0L
    }

    private fun appendLocked(type: String, fields: Map<String, Any?>) {
        val dir = sessionDir ?: return
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
        val file = File(dir, "events.jsonl")
        FileOutputStream(file, true).use { stream ->
            BufferedWriter(OutputStreamWriter(stream, Charsets.UTF_8)).use { writer ->
                writer.append(event.toString()).append('\n')
                writer.flush()
                stream.fd.sync()
            }
        }
    }

    private fun buildSummaryLocked(): JSONObject {
        val sessionSummaries = JSONArray()
        sessions.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.forEach { session ->
            val counts = linkedMapOf<String, Int>()
            val traceMilestones = linkedMapOf<String, MutableList<JSONObject>>()
            var eventCount = 0
            var firstEpoch: Long? = null
            var lastEpoch: Long? = null
            val eventFile = File(session, "events.jsonl")
            if (eventFile.exists()) {
                eventFile.forEachLine { line ->
                    runCatching { JSONObject(line) }.getOrNull()?.let { event ->
                        eventCount++
                        val type = event.optString("type", "UNKNOWN")
                        counts[type] = (counts[type] ?: 0) + 1
                        val epoch = event.optLong("epochMs", 0L)
                        if (epoch > 0L) {
                            if (firstEpoch == null) firstEpoch = epoch
                            lastEpoch = epoch
                        }
                        val traceId = event.optString("traceId", event.optString("frameId", ""))
                        if (traceId.isNotBlank()) {
                            traceMilestones.getOrPut(traceId) { mutableListOf() }.add(JSONObject().apply {
                                put("type", type)
                                put("epochMs", epoch)
                                if (event.has("sinceCaptureMs")) put("sinceCaptureMs", event.optDouble("sinceCaptureMs"))
                                if (event.has("text")) put("text", event.optString("text").take(MAX_SUMMARY_TEXT_CHARS))
                                if (event.has("reason")) put("reason", event.optString("reason"))
                            })
                        }
                    }
                }
            }
            sessionSummaries.put(JSONObject().apply {
                put("sessionId", session.name)
                put("eventCount", eventCount)
                put("firstEpochMs", firstEpoch ?: JSONObject.NULL)
                put("lastEpochMs", lastEpoch ?: JSONObject.NULL)
                put("bytes", directoryBytes(session))
                put("eventTypeCounts", JSONObject(counts as Map<*, *>))
                put("traceCount", traceMilestones.size)
                put("traces", JSONArray().apply {
                    traceMilestones.forEach { (traceId, milestones) ->
                        put(JSONObject().apply {
                            put("traceId", traceId)
                            put("milestones", JSONArray(milestones))
                        })
                    }
                })
            })
        }
        return JSONObject().apply {
            put("schemaVersion", SCHEMA_VERSION)
            put("generatedAtEpochMs", System.currentTimeMillis())
            put("sessions", sessionSummaries)
        }
    }

    /** Never removes event logs. If storage is extreme, only old images are evicted with a ledger. */
    private fun pruneImagesLocked() {
        val all = sessions.listFiles()?.filter { it.isDirectory }?.sortedBy { it.lastModified() }.orEmpty()
        var bytes = all.sumOf(::directoryBytes)
        if (bytes <= MAX_TOTAL_BYTES) return
        all.forEach { old ->
            if (bytes <= MAX_TOTAL_BYTES) return@forEach
            if (old == sessionDir) return@forEach
            val deleted = JSONArray()
            listOf(File(old, "previews"), File(old, "frames")).forEach { imageDir ->
                imageDir.listFiles()?.sortedBy { it.lastModified() }?.forEach { image ->
                    if (bytes <= MAX_TOTAL_BYTES) return@forEach
                    val size = image.length()
                    deleted.put(JSONObject().apply {
                        put("file", image.relativeTo(old).path)
                        put("sha256", runCatching { sha256(image) }.getOrDefault(""))
                        put("bytes", size)
                    })
                    if (image.delete()) bytes -= size
                }
            }
            if (deleted.length() > 0) {
                writeJsonDurably(File(old, "retention_evictions.json"), JSONObject().apply {
                    put("reason", "Diagnostic storage exceeded ${MAX_TOTAL_BYTES} bytes")
                    put("eventLogsPreserved", true)
                    put("deletedImages", deleted)
                })
            }
        }
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

    private fun directoryBytes(file: File): Long =
        if (file.isFile) file.length() else file.listFiles()?.sumOf(::directoryBytes) ?: 0L

    private fun jsonValue(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is Map<*, *> -> JSONObject(value.mapKeys { it.key.toString() }.mapValues { jsonValue(it.value) })
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

    private fun safeName(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)
    private fun nanosToMs(value: Long): Double = value / 1_000_000.0
    private fun timestamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())

    private companion object {
        const val SCHEMA_VERSION = 2
        const val MAX_TOTAL_BYTES = 2L * 1024L * 1024L * 1024L
        const val MAX_STACK_CHARS = 80_000
        const val MAX_NOTE_CHARS = 4_000
        const val MAX_SUMMARY_TEXT_CHARS = 1_000
        const val PREVIEW_MAX_EDGE = 720
        const val PREVIEW_JPEG_QUALITY = 58

        val README_AR = """
            حزمة الصندوق الأسود لتطبيق رفيق الرؤية

            المحتويات:
            - sessions/*/device.json: معلومات الجهاز والإصدار والإعدادات عند بداية الجلسة.
            - sessions/*/events.jsonl: خط زمني غير قابل للدمج، كل سطر حدث JSON مستقل.
            - sessions/*/frames: الصور الكاملة التي دخلت التحليل، محفوظة دون فقد بصري.
            - sessions/*/previews: عينات مصغرة من إطارات رُفضت أو أُسقطت قبل التحليل.
            - diagnostic_summary.json: ملخص آلي مرتب حسب الجلسة ومعرّف التتبع.
            - export_manifest.json: وصف الحزمة وتحذير الخصوصية.

            أهم الحقول الزمنية:
            - epochMs: وقت الساعة العادي.
            - elapsedRealtimeNanos: ساعة رتيبة لا تتأثر بتغيير الوقت.
            - sinceCaptureMs: التأخير منذ التقاط الصورة نفسها.

            للعثور على المشكلة اضغط داخل التطبيق «حدثت مشكلة الآن»، ثم شارك هذه الحزمة كاملة.
            لا تُسجّل مفاتيح Gemini أو ترويسات التفويض داخل الحزمة.
        """.trimIndent()
    }
}
