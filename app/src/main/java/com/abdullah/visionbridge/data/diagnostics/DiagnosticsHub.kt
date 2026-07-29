package com.abdullah.visionbridge.data.diagnostics

import android.graphics.Bitmap
import android.os.SystemClock
import com.abdullah.visionbridge.domain.model.AppSettings
import com.abdullah.visionbridge.domain.model.CaptureState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/** Process-wide low-overhead bridge into the durable recorder. */
object DiagnosticsHub {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val frameCounter = AtomicLong(0)
    private val lastImageAt = AtomicLong(0)

    @Volatile private var recorder: DiagnosticRecorder? = null
    @Volatile private var latestSettings: AppSettings = AppSettings()

    fun initialize(value: DiagnosticRecorder) {
        recorder = value
        scope.launch { value.record("PROCESS_INITIALIZED") }
    }

    fun settings(value: AppSettings) {
        latestSettings = value
        scope.launch {
            recorder?.record("SETTINGS_SNAPSHOT", settingsMap(value))
        }
    }

    fun runtime(value: CaptureState) {
        scope.launch {
            recorder?.record("RUNTIME_STATE", mapOf(
                "isRunning" to value.isRunning,
                "isProcessing" to value.isProcessing,
                "status" to value.status,
                "error" to value.error,
                "lastResultText" to value.lastResult?.text,
                "lastResultSource" to value.lastResult?.source?.name,
                "lastResultLanguage" to value.lastResult?.language,
                "lastResultUrgent" to value.lastResult?.urgent,
            ))
        }
    }

    fun frameConverted(bitmap: Bitmap) {
        val id = frameCounter.incrementAndGet()
        val now = SystemClock.elapsedRealtime()
        val metadata = mapOf(
            "frameId" to id,
            "width" to bitmap.width,
            "height" to bitmap.height,
            "allocationBytes" to bitmap.allocationByteCount,
            "mode" to latestSettings.mode.name,
            "captureProfile" to latestSettings.captureProfile.name,
            "sceneStyle" to latestSettings.sceneDescriptionStyle.name,
            "trustGate" to latestSettings.trustGateEnabled,
        )
        scope.launch { recorder?.record("FRAME_CONVERTED", metadata) }

        // Preserve representative visual evidence without writing every 160 ms frame to flash.
        val previous = lastImageAt.get()
        if (now - previous < IMAGE_SAMPLE_INTERVAL_MS || !lastImageAt.compareAndSet(previous, now)) return
        val copy = runCatching { bitmap.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull() ?: return
        scope.launch {
            try {
                recorder?.recordFrame(copy, id.toString(), "captured", metadata)
            } catch (error: Throwable) {
                recorder?.recordFailure("SAVE_CAPTURED_FRAME", error, metadata)
            } finally {
                copy.recycle()
            }
        }
    }

    fun stage(type: String, fields: Map<String, Any?> = emptyMap()) {
        scope.launch { recorder?.record(type, fields) }
    }

    fun failure(stage: String, error: Throwable, fields: Map<String, Any?> = emptyMap()) {
        scope.launch { recorder?.recordFailure(stage, error, fields) }
    }

    fun settingsMap(value: AppSettings): Map<String, Any?> = mapOf(
        "mode" to value.mode.name,
        "model" to value.model,
        "forceCellular" to value.forceCellular,
        "speechEnabled" to value.speechEnabled,
        "localOcrEnabled" to value.localOcrEnabled,
        "trustGateEnabled" to value.trustGateEnabled,
        "captureProfile" to value.captureProfile.name,
        "interruptSpeechOnVisualChange" to value.interruptSpeechOnVisualChange,
        "sceneDescriptionStyle" to value.sceneDescriptionStyle.name,
        "speechRate" to value.speechRate,
        "frameIntervalMs" to value.frameIntervalMs,
        "cloudOcrIntervalMs" to value.cloudOcrIntervalMs,
        "sceneIntervalMs" to value.sceneIntervalMs,
    )

    private const val IMAGE_SAMPLE_INTERVAL_MS = 750L
}
