package com.abdullah.visionbridge.data.diagnostics

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Builds one bounded, problem-focused diagnostic archive instead of exporting the whole historical
 * black box. The retained store can remain detailed, while the shared ZIP stays small enough for
 * messaging and support channels.
 */
internal class CompactDiagnosticExporter(
    context: Context,
    private val root: File,
    private val sessionsDir: File,
) {
    private val appContext = context.applicationContext

    private data class EventRecord(
        val json: JSONObject,
        val type: String,
        val epochMs: Long,
        val sequence: Long,
    )

    private enum class EvidenceKind { FRAME, PREVIEW }

    private data class EvidenceCandidate(
        val kind: EvidenceKind,
        val source: File,
        val relativePath: String,
        val epochMs: Long,
        val frameId: String,
        val reason: String?,
    )

    private data class SessionSnapshot(
        val directory: File,
        val events: List<EventRecord>,
        val markerEpochMs: Long?,
        val latestEpochMs: Long,
    )

    private data class ExportProfile(
        val id: String,
        val maxFrames: Int,
        val maxPreviews: Int,
        val frameMaxEdge: Int,
        val previewMaxEdge: Int,
        val frameQuality: Int,
        val previewQuality: Int,
        val maxEvents: Int,
        val maxStringChars: Int,
    )

    fun export(): File {
        val snapshot = selectSnapshot()
            ?: error("لا توجد جلسة تشخيص قابلة للتصدير")
        val anchorEpochMs = snapshot.markerEpochMs ?: snapshot.latestEpochMs
        val windowStart = if (snapshot.markerEpochMs != null) {
            anchorEpochMs - PROBLEM_WINDOW_BEFORE_MS
        } else {
            anchorEpochMs - FALLBACK_WINDOW_MS
        }
        val windowEnd = if (snapshot.markerEpochMs != null) {
            anchorEpochMs + PROBLEM_WINDOW_AFTER_MS
        } else {
            anchorEpochMs
        }
        val candidates = evidenceCandidates(snapshot.directory, snapshot.events)

        val exportDir = File(appContext.cacheDir, "diagnostic_exports").apply { mkdirs() }
        exportDir.listFiles()?.filter { it.isFile }?.forEach { it.delete() }
        val stamp = timestamp()

        var lastOutput: File? = null
        PROFILES.forEachIndexed { index, profile ->
            val output = File(exportDir, "VisionBridge-smart-diagnostics-$stamp-${profile.id}.zip")
            buildArchive(
                output = output,
                snapshot = snapshot,
                candidates = candidates,
                anchorEpochMs = anchorEpochMs,
                windowStart = windowStart,
                windowEnd = windowEnd,
                profile = profile,
            )
            lastOutput?.delete()
            lastOutput = output
            if (output.length() <= TARGET_ARCHIVE_BYTES || index == PROFILES.lastIndex) {
                File(exportDir, "${output.name}.sha256.txt").writeText(
                    "${sha256(output)}  ${output.name}\n"
                )
                return output
            }
        }
        return checkNotNull(lastOutput)
    }

    private fun selectSnapshot(): SessionSnapshot? {
        val snapshots = sessionsDir.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { session ->
                val events = readEvents(session)
                if (events.isEmpty()) return@mapNotNull null
                SessionSnapshot(
                    directory = session,
                    events = events,
                    markerEpochMs = events
                        .asSequence()
                        .filter { it.type == "USER_MARKED_PROBLEM" }
                        .maxOfOrNull { it.epochMs },
                    latestEpochMs = events.maxOf { it.epochMs },
                )
            }
            .orEmpty()
        if (snapshots.isEmpty()) return null

        return snapshots
            .filter { it.markerEpochMs != null }
            .maxByOrNull { it.markerEpochMs ?: Long.MIN_VALUE }
            ?: snapshots.maxByOrNull { it.latestEpochMs }
    }

    private fun readEvents(session: File): List<EventRecord> {
        val eventFile = File(session, "events.jsonl")
        if (!eventFile.exists()) return emptyList()
        val output = ArrayList<EventRecord>()
        eventFile.forEachLine { line ->
            runCatching { JSONObject(line) }.getOrNull()?.let { json ->
                output += EventRecord(
                    json = json,
                    type = json.optString("type", "UNKNOWN"),
                    epochMs = json.optLong("epochMs", 0L),
                    sequence = json.optLong("sequence", output.size.toLong()),
                )
            }
        }
        return output.sortedBy { it.sequence }
    }

    private fun evidenceCandidates(
        session: File,
        events: List<EventRecord>,
    ): List<EvidenceCandidate> {
        val fromEvents = events.mapNotNull { event ->
            val kind = when (event.type) {
                "FRAME_SAVED" -> EvidenceKind.FRAME
                "FRAME_PREVIEW_SAVED" -> EvidenceKind.PREVIEW
                else -> return@mapNotNull null
            }
            val relative = event.json.optString("file", "")
            if (relative.isBlank()) return@mapNotNull null
            val source = File(session, relative)
            if (!source.isFile || source.length() <= 0L) return@mapNotNull null
            EvidenceCandidate(
                kind = kind,
                source = source,
                relativePath = relative.replace(File.separatorChar, '/'),
                epochMs = event.epochMs.takeIf { it > 0L } ?: source.lastModified(),
                frameId = event.json.optString("frameId", source.nameWithoutExtension),
                reason = event.json.optString("reason", "").takeIf { it.isNotBlank() },
            )
        }.distinctBy { it.relativePath }
        if (fromEvents.isNotEmpty()) return fromEvents

        return buildList {
            listOf("frames" to EvidenceKind.FRAME, "previews" to EvidenceKind.PREVIEW).forEach { (name, kind) ->
                File(session, name).listFiles()?.filter { it.isFile }?.forEach { source ->
                    add(
                        EvidenceCandidate(
                            kind = kind,
                            source = source,
                            relativePath = source.relativeTo(session).path.replace(File.separatorChar, '/'),
                            epochMs = source.lastModified(),
                            frameId = source.nameWithoutExtension,
                            reason = null,
                        )
                    )
                }
            }
        }
    }

    private fun buildArchive(
        output: File,
        snapshot: SessionSnapshot,
        candidates: List<EvidenceCandidate>,
        anchorEpochMs: Long,
        windowStart: Long,
        windowEnd: Long,
        profile: ExportProfile,
    ) {
        val focusedEvents = focusedEvents(
            events = snapshot.events,
            windowStart = windowStart,
            windowEnd = windowEnd,
            maxEvents = profile.maxEvents,
        )
        val selectedEvidence = selectEvidence(
            candidates = candidates,
            anchorEpochMs = anchorEpochMs,
            windowStart = windowStart,
            windowEnd = windowEnd,
            profile = profile,
        )
        val selectedPaths = selectedEvidence.mapTo(linkedSetOf()) { it.relativePath }
        val imageManifest = JSONArray()
        val imageErrors = JSONArray()

        ZipOutputStream(FileOutputStream(output)).use { zip ->
            zip.setLevel(Deflater.BEST_COMPRESSION)
            val device = File(snapshot.directory, "device.json")
            if (device.isFile) copyFile(zip, device, "session/device.json")

            selectedEvidence.forEachIndexed { index, evidence ->
                runCatching {
                    writeEvidenceImage(
                        zip = zip,
                        evidence = evidence,
                        index = index,
                        anchorEpochMs = anchorEpochMs,
                        profile = profile,
                    )
                }.onSuccess { imageManifest.put(it) }
                    .onFailure { error ->
                        imageErrors.put(JSONObject().apply {
                            put("sourceFile", evidence.relativePath)
                            put("error", error.message ?: error::class.java.name)
                        })
                    }
            }

            val compactEvents = buildString {
                focusedEvents.forEach { event ->
                    append(compactObject(event.json, profile.maxStringChars).toString())
                    append('\n')
                }
            }
            writeText(zip, "session/events_focus.jsonl", compactEvents)

            val typeCounts = linkedMapOf<String, Int>()
            focusedEvents.forEach { event ->
                typeCounts[event.type] = (typeCounts[event.type] ?: 0) + 1
            }
            writeText(zip, "diagnostic_summary.json", JSONObject().apply {
                put("schemaVersion", EXPORT_SCHEMA_VERSION)
                put("selectedSessionId", snapshot.directory.name)
                put("problemMarkerFound", snapshot.markerEpochMs != null)
                put("anchorEpochMs", anchorEpochMs)
                put("windowStartEpochMs", windowStart)
                put("windowEndEpochMs", windowEnd)
                put("originalEventCount", snapshot.events.size)
                put("focusedEventCount", focusedEvents.size)
                put("originalEvidenceCount", candidates.size)
                put("includedEvidenceCount", imageManifest.length())
                put("eventTypeCounts", JSONObject(typeCounts as Map<*, *>))
                put("evidence", imageManifest)
                put("imageExportErrors", imageErrors)
            }.toString(2))

            writeText(zip, "omitted_evidence.json", JSONObject().apply {
                put("reason", "The shared archive is intentionally bounded and focused around the latest problem marker.")
                put("originalEvidenceCount", candidates.size)
                put("includedSourceCount", selectedPaths.size)
                put("omittedSourceCount", (candidates.size - selectedPaths.size).coerceAtLeast(0))
                put("fullBlackBoxRemainsOnDevice", true)
            }.toString(2))

            writeText(zip, "export_manifest.json", JSONObject().apply {
                put("schemaVersion", EXPORT_SCHEMA_VERSION)
                put("exportedAtEpochMs", System.currentTimeMillis())
                put("exportMode", "problem_focused_bounded")
                put("profile", profile.id)
                put("targetArchiveBytes", TARGET_ARCHIVE_BYTES)
                put("singleArchive", true)
                put("selectedSessionOnly", true)
                put("focusedTimeWindow", true)
                put("reencodedImages", true)
                put("includedImageCount", imageManifest.length())
                put("includedEventCount", focusedEvents.size)
                put("privacyWarning", "Contains screen images, recognized text, model output and timing data. API keys are excluded.")
            }.toString(2))
            writeText(zip, "README_AR.txt", README_AR)
        }
    }

    private fun focusedEvents(
        events: List<EventRecord>,
        windowStart: Long,
        windowEnd: Long,
        maxEvents: Int,
    ): List<EventRecord> {
        val eligible = events.filter { event ->
            event.epochMs in windowStart..windowEnd || event.type in GLOBAL_CONTEXT_TYPES
        }
        if (eligible.size <= maxEvents) return eligible

        val mandatory = eligible.filter { it.type in MUST_KEEP_TYPES }
        val mandatoryBounded = if (mandatory.size <= maxEvents) {
            mandatory
        } else {
            evenlySample(mandatory, maxEvents)
        }
        val room = (maxEvents - mandatoryBounded.size).coerceAtLeast(0)
        val mandatorySequences = mandatoryBounded.mapTo(hashSetOf()) { it.sequence }
        val sampled = evenlySample(
            eligible.filterNot { it.sequence in mandatorySequences },
            room,
        )
        return (mandatoryBounded + sampled)
            .distinctBy { it.sequence }
            .sortedBy { it.sequence }
            .take(maxEvents)
    }

    private fun selectEvidence(
        candidates: List<EvidenceCandidate>,
        anchorEpochMs: Long,
        windowStart: Long,
        windowEnd: Long,
        profile: ExportProfile,
    ): List<EvidenceCandidate> {
        val inWindow = candidates.filter { it.epochMs in windowStart..windowEnd }
        val pool = if (inWindow.isNotEmpty()) inWindow else candidates
        val byDistance = pool.sortedWith(
            compareBy<EvidenceCandidate> { abs(it.epochMs - anchorEpochMs) }
                .thenBy { it.epochMs }
        )
        val frames = byDistance.filter { it.kind == EvidenceKind.FRAME }.take(profile.maxFrames)
        val previews = byDistance.filter { it.kind == EvidenceKind.PREVIEW }.take(profile.maxPreviews)
        return (frames + previews).distinctBy { it.relativePath }.sortedBy { it.epochMs }
    }

    private fun <T> evenlySample(values: List<T>, maximum: Int): List<T> {
        if (maximum <= 0 || values.isEmpty()) return emptyList()
        if (values.size <= maximum) return values
        if (maximum == 1) return listOf(values.last())
        return (0 until maximum)
            .map { index ->
                val sourceIndex = (
                    index.toLong() * (values.lastIndex).toLong() / (maximum - 1).toLong()
                    ).toInt()
                values[sourceIndex]
            }
            .distinct()
    }

    private fun writeEvidenceImage(
        zip: ZipOutputStream,
        evidence: EvidenceCandidate,
        index: Int,
        anchorEpochMs: Long,
        profile: ExportProfile,
    ): JSONObject {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(evidence.source.absolutePath, bounds)
        check(bounds.outWidth > 0 && bounds.outHeight > 0) {
            "تعذر قراءة أبعاد ${evidence.source.name}"
        }
        val maxEdge = if (evidence.kind == EvidenceKind.FRAME) {
            profile.frameMaxEdge
        } else {
            profile.previewMaxEdge
        }
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxEdge)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(evidence.source.absolutePath, decodeOptions)
            ?: error("تعذر فك صورة ${evidence.source.name}")
        val scaled = scaleToMaxEdge(decoded, maxEdge)
        val quality = if (evidence.kind == EvidenceKind.FRAME) {
            profile.frameQuality
        } else {
            profile.previewQuality
        }
        val kindDir = if (evidence.kind == EvidenceKind.FRAME) "selected" else "rejected"
        val entryPath = "evidence/$kindDir/${(index + 1).toString().padStart(2, '0')}_${safeName(evidence.frameId)}.jpg"
        try {
            zip.putNextEntry(ZipEntry(entryPath))
            check(scaled.compress(Bitmap.CompressFormat.JPEG, quality, zip)) {
                "تعذر ضغط صورة التشخيص"
            }
            zip.closeEntry()
            return JSONObject().apply {
                put("kind", evidence.kind.name.lowercase(Locale.ROOT))
                put("sourceFile", evidence.relativePath)
                put("exportedFile", entryPath)
                put("frameId", evidence.frameId)
                put("reason", evidence.reason ?: JSONObject.NULL)
                put("epochMs", evidence.epochMs)
                put("distanceFromAnchorMs", evidence.epochMs - anchorEpochMs)
                put("sourceBytes", evidence.source.length())
                put("sourceWidth", bounds.outWidth)
                put("sourceHeight", bounds.outHeight)
                put("exportedWidth", scaled.width)
                put("exportedHeight", scaled.height)
                put("jpegQuality", quality)
            }
        } finally {
            if (scaled !== decoded) scaled.recycle()
            decoded.recycle()
        }
    }

    private fun calculateInSampleSize(width: Int, height: Int, maxEdge: Int): Int {
        var sample = 1
        while (maxOf(width / sample, height / sample) > maxEdge * 2) sample *= 2
        return sample.coerceAtLeast(1)
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

    private fun compactObject(source: JSONObject, maxStringChars: Int): JSONObject {
        val output = JSONObject()
        val keys = source.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = source.opt(key)
            when (value) {
                is String -> {
                    if (value.length > maxStringChars) {
                        output.put(key, value.take(maxStringChars))
                        output.put("${key}Truncated", true)
                        output.put("${key}OriginalChars", value.length)
                    } else {
                        output.put(key, value)
                    }
                }
                is JSONObject -> output.put(key, compactObject(value, maxStringChars))
                is JSONArray -> output.put(key, compactArray(value, maxStringChars))
                else -> output.put(key, value)
            }
        }
        return output
    }

    private fun compactArray(source: JSONArray, maxStringChars: Int): JSONArray {
        val output = JSONArray()
        val limit = minOf(source.length(), MAX_ARRAY_ITEMS)
        for (index in 0 until limit) {
            when (val value = source.opt(index)) {
                is String -> output.put(value.take(maxStringChars))
                is JSONObject -> output.put(compactObject(value, maxStringChars))
                is JSONArray -> output.put(compactArray(value, maxStringChars))
                else -> output.put(value)
            }
        }
        if (source.length() > limit) {
            output.put(JSONObject().apply {
                put("truncated", true)
                put("originalItems", source.length())
            })
        }
        return output
    }

    private fun copyFile(zip: ZipOutputStream, source: File, path: String) {
        zip.putNextEntry(ZipEntry(path))
        source.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    private fun writeText(zip: ZipOutputStream, path: String, text: String) {
        zip.putNextEntry(ZipEntry(path))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
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

    private fun safeName(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())

    private companion object {
        const val EXPORT_SCHEMA_VERSION = 3
        const val TARGET_ARCHIVE_BYTES = 9L * 1024L * 1024L
        const val PROBLEM_WINDOW_BEFORE_MS = 25_000L
        const val PROBLEM_WINDOW_AFTER_MS = 12_000L
        const val FALLBACK_WINDOW_MS = 60_000L
        const val MAX_ARRAY_ITEMS = 48

        val GLOBAL_CONTEXT_TYPES = setOf(
            "SESSION_START",
            "SETTINGS_CHANGED",
            "PROJECTION_STARTED",
            "CAPTURE_CONTENT_RESIZED",
            "PROJECTION_RELEASED",
            "USER_MARKED_PROBLEM",
            "FAILURE",
        )
        val MUST_KEEP_TYPES = GLOBAL_CONTEXT_TYPES + setOf(
            "FRAME_SAVED",
            "FRAME_PREVIEW_SAVED",
            "FRAME_CHANGE_DECISION",
            "VISUAL_TARGET_DECISION",
            "FRAME_SELECTED_FOR_ANALYSIS",
            "GEMINI_ANALYSIS_REQUESTED",
            "MODEL_FINAL_TEXT_AVAILABLE",
            "TEXT_DISPLAYED",
            "CLOUD_ANALYSIS_CANCELLED",
            "FINAL_RESULT_SUPPRESSED",
            "OCR_TRUST_REJECTED",
            "NETWORK_ROUTE_SELECTED",
            "HTTP_REQUEST_STARTED",
            "FIRST_SSE_EVENT_RECEIVED",
            "SSE_CONNECTION_CLOSED",
            "TTS_REQUESTED",
            "UTTERANCE_STARTED",
            "UTTERANCE_COMPLETED",
        )

        val PROFILES = listOf(
            ExportProfile(
                id = "standard",
                maxFrames = 8,
                maxPreviews = 8,
                frameMaxEdge = 2_200,
                previewMaxEdge = 900,
                frameQuality = 88,
                previewQuality = 64,
                maxEvents = 700,
                maxStringChars = 8_000,
            ),
            ExportProfile(
                id = "compact",
                maxFrames = 6,
                maxPreviews = 6,
                frameMaxEdge = 1_600,
                previewMaxEdge = 760,
                frameQuality = 80,
                previewQuality = 58,
                maxEvents = 480,
                maxStringChars = 4_000,
            ),
            ExportProfile(
                id = "minimal",
                maxFrames = 4,
                maxPreviews = 4,
                frameMaxEdge = 1_200,
                previewMaxEdge = 640,
                frameQuality = 72,
                previewQuality = 52,
                maxEvents = 300,
                maxStringChars = 2_000,
            ),
            ExportProfile(
                id = "emergency",
                maxFrames = 2,
                maxPreviews = 2,
                frameMaxEdge = 960,
                previewMaxEdge = 520,
                frameQuality = 65,
                previewQuality = 46,
                maxEvents = 160,
                maxStringChars = 900,
            ),
        )

        val README_AR = """
            حزمة التشخيص الذكية لتطبيق VisionBridge

            هذه ليست نسخة كاملة من جميع الجلسات القديمة. صُممت الحزمة لتكون صغيرة وقابلة للإرسال:
            - تختار أحدث جلسة تحتوي علامة «حدثت مشكلة الآن».
            - تركّز على الأحداث قبل المشكلة وبعدها مباشرة.
            - تختار عدداً محدوداً من أقرب الصور الكاملة والمعاينات المرفوضة.
            - تعيد ضغط الصور داخل الحزمة دون تعديل ملفات الصندوق الأسود الأصلية على الجهاز.
            - إذا تجاوز الحجم المستهدف، تعيد البناء تلقائياً بمستوى ضغط أعلى.

            المحتويات:
            - session/device.json: الجهاز والإصدار والإعدادات.
            - session/events_focus.jsonl: الأحداث المهمة ضمن نافذة المشكلة.
            - evidence/selected: الصور التي دخلت التحليل.
            - evidence/rejected: عينات من الصور التي رفضها النظام.
            - diagnostic_summary.json: ملخص مترابط للأحداث والصور.
            - omitted_evidence.json: عدد الأدلة التي لم تدخل بسبب حد الحجم.
            - export_manifest.json: سياسة التصدير ومستوى الضغط المستخدم.

            يبقى الصندوق الأسود الكامل محفوظاً داخل التطبيق، ولا تُسجّل مفاتيح Gemini أو ترويسات التفويض.
        """.trimIndent()
    }
}
