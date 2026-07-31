package com.abdullah.visionbridge.data.localvlm

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.abdullah.visionbridge.data.diagnostics.DiagnosticHub
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Owns the native VLM: one model, one context, one inference at a time.
 *
 * Memory is the binding constraint on a phone, so the lifecycle is explicit
 * rather than lazy-forever. Weights are mmap'd and can be evicted by the kernel;
 * the KV cache is cleared after every frame; and [release] fully unloads the
 * model when capture stops or the system reports memory pressure. A foreground
 * capture service holding two gigabytes it is not using is a service Android
 * will kill mid-sentence.
 */
class LocalVlmEngine(
    context: Context,
    private val modelStore: LocalVlmModelStore,
) {
    /** Receives UTF-8-safe fragments; returning false stops generation. */
    fun interface TokenListener {
        fun onToken(fragment: String): Boolean
    }

    class NotInstalledException : IllegalStateException(
        "نموذج الذكاء المحلي غير مثبت. ثبّت ملفي النموذج من الإعدادات."
    )

    class LoadFailedException : IllegalStateException(
        "تعذر تحميل النموذج المحلي. تأكد من صحة الملفين ومن توفر ذاكرة كافية."
    )

    /**
     * The APK was built without the native engine.
     *
     * The raw platform message for this is "dlopen failed: library
     * libvisionbridge_vlm.so not found", which tells a blind user nothing they
     * can act on. It is replaced with the actual remedy: install the build that
     * contains the engine.
     */
    class EngineMissingFromBuildException : IllegalStateException(
        "هذه النسخة من التطبيق لا تتضمن المحرك المحلي. ثبّت نسخة APK التي تحتوي المحرك المحلي، " +
            "أو أوقف مفتاح الذكاء المحلي لتعود إلى Gemini السحابي."
    )

    private val appContext = context.applicationContext
    private val lifecycleMutex = Mutex()
    private val inferenceMutex = Mutex()

    @Volatile
    private var handle: Long = 0L

    @Volatile
    private var nativeLibraryAvailable: Boolean? = null

    val isLoaded: Boolean get() = handle != 0L

    /**
     * True when this build actually contains the native engine.
     *
     * Separate from [ensureLoaded] on purpose: it needs no model files and costs
     * nothing after the first call, so the UI can answer "will this work at all"
     * the moment the user turns the switch on, rather than letting them install
     * gigabytes of weights and only discover the truth at the first frame. That
     * is exactly what happened when a build published an APK carrying the whole
     * local-engine interface but no libvisionbridge_vlm.so.
     */
    fun isEngineAvailableInBuild(): Boolean {
        nativeLibraryAvailable?.let { return it }
        val available = try {
            System.loadLibrary(NATIVE_LIBRARY)
            true
        } catch (missing: UnsatisfiedLinkError) {
            DiagnosticHub.record(
                "LOCAL_VLM_NATIVE_LIBRARY_MISSING",
                mapOf(
                    "library" to NATIVE_LIBRARY,
                    "platformMessage" to missing.message,
                    "cause" to "apk_built_without_local_vlm_engine",
                ),
            )
            false
        }
        nativeLibraryAvailable = available
        return available
    }

    /**
     * Loads the model on an IO thread if it is not already resident.
     *
     * Safe to call before every frame: the mutex plus the handle check make
     * repeat calls free once loaded.
     */
    suspend fun ensureLoaded(): Result<Unit> = lifecycleMutex.withLock {
        if (handle != 0L) return@withLock Result.success(Unit)
        if (!modelStore.isReady) return@withLock Result.failure(NotInstalledException())

        withContext(Dispatchers.IO) {
            runCatching {
                if (!isEngineAvailableInBuild()) throw EngineMissingFromBuildException()

                val threads = inferenceThreadCount()
                val started = SystemClock.elapsedRealtimeNanos()
                DiagnosticHub.record(
                    "LOCAL_VLM_LOAD_STARTED",
                    mapOf(
                        "threads" to threads,
                        "contextTokens" to CONTEXT_TOKENS,
                        "availableMemoryMb" to availableMemoryMb(),
                    ),
                )

                val created = nativeLoad(
                    modelStore.fileFor(LocalVlmModelStore.Artifact.WEIGHTS).absolutePath,
                    modelStore.fileFor(LocalVlmModelStore.Artifact.PROJECTOR).absolutePath,
                    threads,
                    CONTEXT_TOKENS,
                    GPU_LAYERS,
                )
                if (created == 0L) throw LoadFailedException()
                handle = created

                DiagnosticHub.record(
                    "LOCAL_VLM_LOAD_COMPLETED",
                    mapOf(
                        "durationMs" to (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0,
                        "availableMemoryMb" to availableMemoryMb(),
                    ),
                )
            }.onFailure { error ->
                DiagnosticHub.failure("LOCAL_VLM_LOAD", error)
                handle = 0L
            }
        }
    }

    /**
     * Runs one frame to completion and returns the raw model output.
     *
     * The caller is responsible for sanitizing; this returns exactly what the
     * model produced so diagnostics can show the difference.
     */
    suspend fun generate(
        bitmap: Bitmap,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        longEdgePixels: Int,
        listener: TokenListener,
    ): String = inferenceMutex.withLock {
        val current = handle
        if (current == 0L) throw NotInstalledException()

        val prepared = scaleForVision(bitmap, longEdgePixels)
        val rgb = try {
            toPackedRgb(prepared)
        } finally {
            if (prepared !== bitmap) prepared.recycle()
        }

        withContext(Dispatchers.Default) {
            // Captured here rather than queried inside the listener: the listener is
            // invoked from native code and cannot suspend.
            val job = coroutineContext[Job]
            val started = SystemClock.elapsedRealtimeNanos()
            DiagnosticHub.record(
                "LOCAL_VLM_GENERATE_STARTED",
                mapOf(
                    "imageWidth" to rgb.width,
                    "imageHeight" to rgb.height,
                    "maxTokens" to maxTokens,
                    "temperature" to temperature,
                ),
            )

            // A cancelled coroutine must stop native decoding, not wait for it.
            val guardedListener = TokenListener { fragment ->
                if (job?.isActive == false) false else listener.onToken(fragment)
            }

            val output = try {
                nativeGenerate(
                    current,
                    rgb.bytes,
                    rgb.width,
                    rgb.height,
                    prompt,
                    maxTokens,
                    temperature,
                    TOP_P,
                    REPEAT_PENALTY,
                    REPEAT_LAST_N,
                    SEED,
                    guardedListener,
                )
            } catch (cancellation: CancellationException) {
                nativeCancel(current)
                throw cancellation
            }

            DiagnosticHub.record(
                "LOCAL_VLM_GENERATE_COMPLETED",
                mapOf(
                    "durationMs" to (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0,
                    "outputCharacters" to output.length,
                ),
            )
            output
        }
    }

    /** Stops the in-flight generation without unloading the model. */
    fun cancel() {
        val current = handle
        if (current != 0L) nativeCancel(current)
    }

    /** Fully unloads the model and returns its memory to the system. */
    suspend fun release(reason: String) = lifecycleMutex.withLock {
        val current = handle
        if (current == 0L) return@withLock
        handle = 0L
        withContext(Dispatchers.IO) {
            nativeFree(current)
            DiagnosticHub.record(
                "LOCAL_VLM_RELEASED",
                mapOf("reason" to reason, "availableMemoryMb" to availableMemoryMb()),
            )
        }
    }

    /**
     * Leaves one core free so the capture pipeline, TTS and the UI thread are not
     * starved while the model decodes. Big-core-only counts perform worse than
     * this in practice because of thermal throttling during a long transcription.
     */
    private fun inferenceThreadCount(): Int =
        (Runtime.getRuntime().availableProcessors() - 1).coerceIn(2, 6)

    private fun availableMemoryMb(): Long {
        val info = ActivityManager.MemoryInfo()
        appContext.getSystemService(ActivityManager::class.java)?.getMemoryInfo(info)
        return info.availMem / (1024 * 1024)
    }

    private class PackedRgb(val bytes: ByteArray, val width: Int, val height: Int)

    /**
     * Caps the long edge before the vision encoder sees the frame.
     *
     * Qwen-VL style encoders tokenize at native resolution, so a full-resolution
     * screenshot turns into thousands of image tokens: minutes of prefill and an
     * out-of-memory kill. Reading gets a higher cap than description because
     * small glyphs are exactly what it must not lose.
     */
    private fun scaleForVision(source: Bitmap, longEdgePixels: Int): Bitmap {
        val longest = max(source.width, source.height)
        if (longest <= longEdgePixels) return source
        val ratio = longEdgePixels.toFloat() / longest
        val width = (source.width * ratio).roundToInt().coerceAtLeast(MIN_EDGE_PIXELS)
        val height = (source.height * ratio).roundToInt().coerceAtLeast(MIN_EDGE_PIXELS)
        return Bitmap.createScaledBitmap(source, width, height, true)
    }

    /** Converts to the tightly packed 8-bit RGB buffer libmtmd expects. */
    private fun toPackedRgb(bitmap: Bitmap): PackedRgb {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val bytes = ByteArray(width * height * 3)
        var target = 0
        for (pixel in pixels) {
            bytes[target++] = ((pixel shr 16) and 0xFF).toByte()
            bytes[target++] = ((pixel shr 8) and 0xFF).toByte()
            bytes[target++] = (pixel and 0xFF).toByte()
        }
        return PackedRgb(bytes, width, height)
    }

    private external fun nativeLoad(
        modelPath: String,
        mmprojPath: String,
        threads: Int,
        contextTokens: Int,
        gpuLayers: Int,
    ): Long

    private external fun nativeGenerate(
        handle: Long,
        rgb: ByteArray,
        width: Int,
        height: Int,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        repeatPenalty: Float,
        repeatLastN: Int,
        seed: Int,
        listener: TokenListener,
    ): String

    private external fun nativeFree(handle: Long)

    private external fun nativeCancel(handle: Long)

    companion object {
        private const val NATIVE_LIBRARY = "visionbridge_vlm"

        /** One image plus one page of text. Larger buys nothing and costs KV memory. */
        private const val CONTEXT_TOKENS = 4_096

        /**
         * CPU only. Android GPU backends for llama.cpp remain unreliable across
         * vendors, and a wrong answer read aloud confidently is worse than a slow
         * one. Raise deliberately after testing on target hardware.
         */
        private const val GPU_LAYERS = 0

        private const val TOP_P = 0.9f
        private const val REPEAT_PENALTY = 1.12f
        private const val REPEAT_LAST_N = 320
        private const val SEED = 0

        private const val MIN_EDGE_PIXELS = 28

        /** Long-edge caps, chosen against prefill cost rather than image quality. */
        const val READ_LONG_EDGE_PIXELS = 1_024
        const val DESCRIBE_LONG_EDGE_PIXELS = 672
    }
}
