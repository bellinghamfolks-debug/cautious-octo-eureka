package com.abdullah.visionbridge.data.diagnostics

import android.graphics.Bitmap
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Non-blocking entry point used by capture, OCR, Gemini and speech layers.
 * All writes are serialized by [DiagnosticRecorder]; failures in diagnostics never crash the app.
 */
object DiagnosticHub {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var recorder: DiagnosticRecorder? = null

    fun initialize(value: DiagnosticRecorder) {
        recorder = value
    }

    fun record(type: String, fields: Map<String, Any?> = emptyMap()) {
        val target = recorder ?: return
        scope.launch { runCatching { target.record(type, fields) } }
    }

    fun failure(stage: String, error: Throwable, fields: Map<String, Any?> = emptyMap()) {
        val target = recorder ?: return
        scope.launch { runCatching { target.recordFailure(stage, error, fields) } }
    }

    fun frame(bitmap: Bitmap, frameId: String, stage: String, metadata: Map<String, Any?> = emptyMap()) {
        val target = recorder ?: return
        val safeCopy = runCatching { bitmap.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull() ?: return
        scope.launch {
            try {
                target.recordFrame(safeCopy, frameId, stage, metadata)
            } finally {
                safeCopy.recycle()
            }
        }
    }

    fun preview(bitmap: Bitmap, frameId: String, reason: String, metadata: Map<String, Any?> = emptyMap()) {
        val target = recorder ?: return
        val safeCopy = runCatching { bitmap.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull() ?: return
        scope.launch {
            try {
                target.recordPreviewFrame(safeCopy, frameId, reason, metadata)
            } finally {
                safeCopy.recycle()
            }
        }
    }

    fun sinceCaptureMs(capturedAtElapsedNanos: Long): Double =
        (SystemClock.elapsedRealtimeNanos() - capturedAtElapsedNanos).coerceAtLeast(0L) / 1_000_000.0
}

/** One immutable trace identity follows the same visual frame through every coroutine layer. */
data class DiagnosticTrace(
    val traceId: String,
    val frameId: String,
    val capturedAtEpochMs: Long,
    val capturedAtElapsedNanos: Long,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<DiagnosticTrace>

    fun fields(extra: Map<String, Any?> = emptyMap()): Map<String, Any?> = mapOf(
        "traceId" to traceId,
        "frameId" to frameId,
        "capturedAtEpochMs" to capturedAtEpochMs,
        "capturedAtElapsedNanos" to capturedAtElapsedNanos,
        "sinceCaptureMs" to DiagnosticHub.sinceCaptureMs(capturedAtElapsedNanos),
    ) + extra
}
