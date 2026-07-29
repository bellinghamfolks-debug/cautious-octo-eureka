package com.abdullah.visionbridge.data.diagnostics

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import com.abdullah.visionbridge.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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

/**
 * Durable append-only diagnostic black box.
 *
 * Every event is flushed and fsynced before returning, so an app crash or forced stop cannot erase
 * already-written evidence. API keys and authorization headers must never be passed to this class.
 */
class DiagnosticRecorder(context: Context) {
    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, "diagnostic_black_box").apply { mkdirs() }
    private val sessions = File(root, "sessions").apply { mkdirs() }
    private val mutex = Mutex()
    private val processId = UUID.randomUUID().toString()

    @Volatile private var sessionId: String? = null
    @Volatile private var sessionDir: File? = null
    @Volatile private var eventSequence = 0L

    suspend fun startSession(settings: Map<String, Any?>): String = mutex.withLock {
        val id = "${timestamp()}_${UUID.randomUUID()}"
        val dir = File(sessions, id).apply { mkdirs(); File(this, "frames").mkdirs() }
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
            put("startedAtElapsedMs", SystemClock.elapsedRealtime())
            put("settings", JSONObject(settings))
        })
        appendLocked("SESSION_START", mapOf("settings" to settings))
        pruneLocked()
        id
    }

    suspend fun endSession(reason: String) = record("SESSION_END", mapOf("reason" to reason))

    suspend fun record(type: String, fields: Map<String, Any?> = emptyMap()) = mutex.withLock {
        ensureSessionLocked()
        appendLocked(type, fields)
    }

    suspend fun recordFailure(stage: String, error: Throwable, fields: Map<String, Any?> = emptyMap()) {
        record("FAILURE", fields + mapOf(
            "stage" to stage,
            "exception" to error::class.java.name,
            "message" to (error.message ?: ""),
            "stackTrace" to error.stackTraceToString().take(MAX_STACK_CHARS),
        ))
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
            val frameDir = File(dir, "frames")
            val file = File(frameDir, "${frameId}_${stage}.webp")
            FileOutputStream(file).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, output))
                output.fd.sync()
            }
            appendLocked("FRAME_SAVED", metadata + mapOf(
                "frameId" to frameId,
                "stage" to stage,
                "file" to "frames/${file.name}",
                "width" to bitmap.width,
                "height" to bitmap.height,
                "allocationBytes" to bitmap.allocationByteCount,
                "sha256" to sha256(file),
                "fileBytes" to file.length(),
            ))
        }
    }

    suspend fun export(includeImages: Boolean): File = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureSessionLocked()
            val exportDir = File(appContext.cacheDir, "diagnostic_exports").apply { mkdirs() }
            val output = File(exportDir, "VisionBridge-diagnostics-${timestamp()}${if (includeImages) "-with-images" else ""}.zip")
            ZipOutputStream(FileOutputStream(output)).use { zip ->
                sessions.listFiles()?.sortedBy { it.name }?.forEach { session ->
                    session.walkTopDown().filter { it.isFile }.forEach { file ->
                        if (!includeImages && file.parentFile?.name == "frames") return@forEach
                        val relative = file.relativeTo(root).path
                        zip.putNextEntry(ZipEntry(relative))
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
                val manifest = JSONObject().apply {
                    put("schemaVersion", SCHEMA_VERSION)
                    put("exportedAtEpochMs", System.currentTimeMillis())
                    put("includesImages", includeImages)
                    put("sessionCount", sessions.listFiles()?.size ?: 0)
                    put("warning", "May contain screen images and recognized text. API keys are intentionally excluded.")
                }.toString(2).toByteArray()
                zip.putNextEntry(ZipEntry("export_manifest.json"))
                zip.write(manifest)
                zip.closeEntry()
            }
            output
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
        sessionDir = File(sessions, id).apply { mkdirs(); File(this, "frames").mkdirs() }
        eventSequence = 0L
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
            put("elapsedRealtimeMs", SystemClock.elapsedRealtime())
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

    private fun writeJsonDurably(file: File, json: JSONObject) {
        FileOutputStream(file).use { stream ->
            stream.write(json.toString(2).toByteArray())
            stream.flush()
            stream.fd.sync()
        }
    }

    private fun pruneLocked() {
        val all = sessions.listFiles()?.sortedByDescending { it.lastModified() }.orEmpty()
        var bytes = all.sumOf { directoryBytes(it) }
        all.drop(MAX_SESSIONS).forEach { old -> bytes -= directoryBytes(old); old.deleteRecursively() }
        all.asReversed().forEach { old ->
            if (bytes <= MAX_TOTAL_BYTES) return@forEach
            if (old == sessionDir) return@forEach
            bytes -= directoryBytes(old)
            old.deleteRecursively()
        }
    }

    private fun directoryBytes(file: File): Long = if (file.isFile) file.length() else file.listFiles()?.sumOf(::directoryBytes) ?: 0L

    private fun jsonValue(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is Map<*, *> -> JSONObject(value.mapKeys { it.key.toString() })
        is Iterable<*> -> value.toList()
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

    private fun timestamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())

    private companion object {
        const val SCHEMA_VERSION = 1
        const val MAX_SESSIONS = 20
        const val MAX_TOTAL_BYTES = 750L * 1024L * 1024L
        const val MAX_STACK_CHARS = 40_000
    }
}
