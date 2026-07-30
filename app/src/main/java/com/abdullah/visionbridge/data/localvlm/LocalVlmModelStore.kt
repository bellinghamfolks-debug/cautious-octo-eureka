package com.abdullah.visionbridge.data.localvlm

import android.content.Context
import android.net.Uri
import com.abdullah.visionbridge.data.diagnostics.DiagnosticHub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Locates and installs the two GGUF files the local engine needs.
 *
 * The weights are never bundled in the APK. A usable bilingual VLM is one to
 * three gigabytes, which is far past any store limit and would be dead weight
 * for the majority of users who stay on the cloud engine. They live in app
 * private storage instead, installed once by the user through the system file
 * picker, and are deleted with the app.
 */
class LocalVlmModelStore(context: Context) {

    enum class Artifact(val fileName: String) {
        /** Quantized language + vision weights. */
        WEIGHTS("vlm-model.gguf"),

        /** Multimodal projector that maps image features into the text embedding space. */
        PROJECTOR("vlm-mmproj.gguf"),
    }

    sealed interface Status {
        data class Ready(val weightsBytes: Long, val projectorBytes: Long) : Status
        data class Incomplete(val missing: List<Artifact>) : Status
    }

    private val appContext = context.applicationContext
    private val directory = File(appContext.filesDir, "models").apply { mkdirs() }

    fun fileFor(artifact: Artifact): File = File(directory, artifact.fileName)

    fun status(): Status {
        val missing = Artifact.entries.filter { artifact ->
            val file = fileFor(artifact)
            !file.isFile || file.length() < MINIMUM_PLAUSIBLE_BYTES
        }
        return if (missing.isEmpty()) {
            Status.Ready(
                weightsBytes = fileFor(Artifact.WEIGHTS).length(),
                projectorBytes = fileFor(Artifact.PROJECTOR).length(),
            )
        } else {
            Status.Incomplete(missing)
        }
    }

    val isReady: Boolean get() = status() is Status.Ready

    /**
     * Copies a user-picked file into private storage.
     *
     * Streamed in chunks and written to a temporary file first, so an interrupted
     * import cannot leave a half-written model that then fails to load with a
     * confusing error.
     */
    suspend fun install(artifact: Artifact, source: Uri): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val target = fileFor(artifact)
            val staging = File(directory, "${artifact.fileName}.partial")
            staging.delete()

            appContext.contentResolver.openInputStream(source)?.use { input ->
                staging.outputStream().use { output -> input.copyTo(output, COPY_BUFFER_BYTES) }
            } ?: error("تعذر فتح الملف المحدد")

            if (staging.length() < MINIMUM_PLAUSIBLE_BYTES) {
                staging.delete()
                error("الملف صغير جداً ولا يبدو ملف نموذج صالحاً")
            }
            if (!isGguf(staging)) {
                staging.delete()
                error("الملف ليس بصيغة GGUF")
            }

            target.delete()
            if (!staging.renameTo(target)) {
                staging.delete()
                error("تعذر حفظ ملف النموذج")
            }
            DiagnosticHub.record(
                "LOCAL_VLM_ARTIFACT_INSTALLED",
                mapOf("artifact" to artifact.name, "bytes" to target.length()),
            )
            target
        }.onFailure { error ->
            DiagnosticHub.failure("LOCAL_VLM_ARTIFACT_INSTALL", error, mapOf("artifact" to artifact.name))
        }
    }

    fun delete(artifact: Artifact): Boolean = fileFor(artifact).delete()

    fun deleteAll() = Artifact.entries.forEach { delete(it) }

    /** GGUF files start with the ASCII magic "GGUF". */
    private fun isGguf(file: File): Boolean = runCatching {
        file.inputStream().use { stream ->
            val magic = ByteArray(4)
            stream.read(magic) == 4 && magic.decodeToString() == "GGUF"
        }
    }.getOrDefault(false)

    private companion object {
        const val MINIMUM_PLAUSIBLE_BYTES = 1_000_000L
        const val COPY_BUFFER_BYTES = 1 shl 20
    }
}
