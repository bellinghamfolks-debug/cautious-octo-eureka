package com.abdullah.visionbridge.data.paddleocr

import android.content.Context
import android.net.Uri
import com.abdullah.visionbridge.data.diagnostics.DiagnosticHub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Locates and installs the PP-OCRv5 models and their character dictionaries.
 *
 * Six files rather than one bundle, because each is independently replaceable: a better Arabic
 * recognizer can be dropped in without touching detection, and a dictionary must always match the
 * recognizer it was exported with or every character comes out shifted by one.
 *
 * Nothing is bundled in the APK. The files are installed once by the user through the system file
 * picker into app-private storage, and are removed when the app is uninstalled.
 */
class PaddleOcrModelStore(context: Context) {

    enum class Artifact(val fileName: String, val label: String, val minimumBytes: Long) {
        DETECTION("ppocr-det.onnx", "كاشف النص", 500_000L),
        ARABIC_RECOGNITION("ppocr-rec-ar.onnx", "تعرّف العربية", 500_000L),
        ENGLISH_RECOGNITION("ppocr-rec-en.onnx", "تعرّف الإنجليزية", 500_000L),
        ORIENTATION("ppocr-cls.onnx", "مصحّح الاتجاه", 100_000L),
        ARABIC_DICTIONARY("ppocr-dict-ar.txt", "قاموس العربية", 200L),
        ENGLISH_DICTIONARY("ppocr-dict-en.txt", "قاموس الإنجليزية", 200L),
    }

    private val appContext = context.applicationContext
    private val directory = File(appContext.filesDir, "ppocr").apply { mkdirs() }

    fun fileFor(artifact: Artifact): File = File(directory, artifact.fileName)

    fun isInstalled(artifact: Artifact): Boolean {
        val file = fileFor(artifact)
        return file.isFile && file.length() >= artifact.minimumBytes
    }

    fun missing(): List<Artifact> = Artifact.entries.filterNot(::isInstalled)

    val isReady: Boolean get() = missing().isEmpty()

    fun installedBytes(artifact: Artifact): Long =
        fileFor(artifact).takeIf { it.isFile }?.length() ?: 0L

    /**
     * Copies a user-picked file into private storage.
     *
     * Written to a staging file and renamed on success, so an interrupted copy can never leave a
     * truncated model that then fails to load with an unhelpful error. The magic bytes are checked
     * too: an ONNX file starts with a protobuf field header, and a dictionary must be readable text.
     */
    suspend fun install(artifact: Artifact, source: Uri): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                val target = fileFor(artifact)
                val staging = File(directory, "${artifact.fileName}.partial")
                staging.delete()

                appContext.contentResolver.openInputStream(source)?.use { input ->
                    staging.outputStream().use { output -> input.copyTo(output, COPY_BUFFER_BYTES) }
                } ?: error("تعذر فتح الملف المحدد")

                if (staging.length() < artifact.minimumBytes) {
                    staging.delete()
                    error("الملف صغير جداً ولا يطابق ${artifact.label}")
                }
                if (!matchesExpectedFormat(artifact, staging)) {
                    staging.delete()
                    error("الملف لا يطابق الصيغة المتوقعة لـ${artifact.label}")
                }

                target.delete()
                if (!staging.renameTo(target)) {
                    staging.delete()
                    error("تعذر حفظ ${artifact.label}")
                }
                DiagnosticHub.record(
                    "PPOCR_ARTIFACT_INSTALLED",
                    mapOf("artifact" to artifact.name, "bytes" to target.length()),
                )
                target
            }.onFailure { error ->
                DiagnosticHub.failure(
                    "PPOCR_ARTIFACT_INSTALL",
                    error,
                    mapOf("artifact" to artifact.name),
                )
            }
        }

    fun deleteAll() {
        Artifact.entries.forEach { fileFor(it).delete() }
        DiagnosticHub.record("PPOCR_ARTIFACTS_DELETED")
    }

    /** Reads a dictionary, one symbol per line. Order is the model's class order and must be kept. */
    /**
     * Reads a PaddleOCR dictionary as the characters above the CTC blank.
     *
     * The released mobile recognition models are all trained with `use_space_char=True`, which makes
     * PaddleOCR's own decoder append a space as the last class, after the file's contents. The file
     * does not contain that space, so a decoder that reads the file literally is one class short and
     * silently drops every space between words — a whole screen would come out as one run-on word.
     *
     * Appending it is safe for an export built without the space class too: that model simply never
     * emits an index high enough to select it.
     */
    fun readDictionary(artifact: Artifact): List<String> =
        fileFor(artifact).readLines(Charsets.UTF_8).map { it.trimEnd('\r', '\n') } + " "

    private fun matchesExpectedFormat(artifact: Artifact, file: File): Boolean = runCatching {
        val head = ByteArray(16)
        val read = file.inputStream().use { it.read(head) }
        if (read <= 0) return false
        when (artifact) {
            Artifact.ARABIC_DICTIONARY, Artifact.ENGLISH_DICTIONARY ->
                // A dictionary is UTF-8 text; a model file is not decodable as such.
                head.decodeToString().none { it.code in 0..8 }
            else ->
                // ONNX is protobuf: the first field tag for ir_version is 0x08.
                head[0] == 0x08.toByte()
        }
    }.getOrDefault(false)

    private companion object {
        const val COPY_BUFFER_BYTES = 1 shl 20
    }
}
