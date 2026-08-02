package com.abdullah.visionbridge.data.diagnostics

import android.graphics.Bitmap
import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Process-wide ordered diagnostic actor.
 *
 * The recorder is always active once the application container is created. Visual evidence is stored
 * as non-reconstructive aggregate fingerprints only; no bitmap copy, thumbnail, or encoded image is
 * queued or written. Export remains a queue barrier, so every event submitted before it is included.
 */
object DiagnosticHub {
    private const val FATAL_FLUSH_TIMEOUT_MS = 2_000L

    /**
     * Ten seconds. Frequent enough that a stall of any consequence is bracketed by two beats, rare
     * enough that a long session adds a few hundred events rather than a few thousand.
     */
    private const val HEARTBEAT_INTERVAL_MS = 10_000L

    private sealed interface Command {
        data class Record(val type: String, val fields: Map<String, Any?>) : Command
        data class Failure(
            val stage: String,
            val error: Throwable,
            val fields: Map<String, Any?>,
        ) : Command
        data class StartSession(
            val settings: Map<String, Any?>,
            val completion: CompletableDeferred<String>,
        ) : Command
        data class EndSession(
            val reason: String,
            val completion: CompletableDeferred<Unit>,
        ) : Command
        data class MarkProblem(
            val note: String,
            val nearestTrace: DiagnosticTrace?,
            val completion: CompletableDeferred<Unit>?,
        ) : Command
        data class Export(val completion: CompletableDeferred<File>) : Command
        data class Status(val completion: CompletableDeferred<DiagnosticRecorder.StorageStatus>) : Command
        data class Flush(val completion: CompletableDeferred<Unit>) : Command
        data class Fatal(val error: Throwable, val completion: CompletableDeferred<Unit>) : Command
    }

    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val latestTrace = AtomicReference<DiagnosticTrace?>(null)

    @Volatile
    private var recorder: DiagnosticRecorder? = null

    init {
        scope.launch {
            for (command in commands) handle(command)
        }
        scope.launch { beatWhileAlive() }
    }

    /**
     * A steady pulse, so a hole in the timeline can be read.
     *
     * A bundle once contained 216 seconds during which the app recorded nothing at all, and there
     * was no way to tell a frozen process from an idle one — the difference between a bug and a
     * user putting the phone down. A beat that should have arrived and did not is proof the process
     * was not being scheduled; the wall clock and the monotonic clock are both carried so a clock
     * change cannot be mistaken for either.
     */
    private suspend fun beatWhileAlive() {
        var lastWallMs = System.currentTimeMillis()
        var lastElapsedMs = SystemClock.elapsedRealtime()
        while (currentCoroutineContext().isActive) {
            delay(HEARTBEAT_INTERVAL_MS)
            val wallMs = System.currentTimeMillis()
            val elapsedMs = SystemClock.elapsedRealtime()
            val sinceElapsed = elapsedMs - lastElapsedMs
            record(
                "PROCESS_HEARTBEAT",
                mapOf(
                    "intervalMs" to HEARTBEAT_INTERVAL_MS,
                    "sinceLastBeatElapsedMs" to sinceElapsed,
                    "sinceLastBeatWallMs" to (wallMs - lastWallMs),
                    // Zero on a healthy pulse. Anything above it counts the beats that never ran.
                    "missedBeats" to
                        ((sinceElapsed - HEARTBEAT_INTERVAL_MS) / HEARTBEAT_INTERVAL_MS)
                            .coerceAtLeast(0L),
                ),
            )
            lastWallMs = wallMs
            lastElapsedMs = elapsedMs
        }
    }

    @Volatile
    private var evidence: EvidenceStore? = null

    fun initialize(value: DiagnosticRecorder) {
        recorder = value
        evidence = value.evidenceStore
        record(
            "DIAGNOSTIC_HUB_INITIALIZED",
            mapOf(
                "automaticRecording" to true,
                "requiresProblemButton" to false,
                "storesImages" to false,
                "visualEvidence" to "aggregate_fingerprint",
            ),
        )
    }

    /** Turns failure-frame capture on or off. Off is the only default. */
    fun setEvidenceCapture(enabled: Boolean) {
        val store = evidence ?: return
        if (store.enabled == enabled) return
        store.enabled = enabled
        if (!enabled) store.clear()
        record("EVIDENCE_CAPTURE_SETTING", mapOf("enabled" to enabled))
    }

    /**
     * Keeps the frame behind a named failure, when the user has switched capture on.
     *
     * Called where a bitmap is still in hand and something has demonstrably gone wrong. Without
     * this, a page that was not read cannot be told apart from a page that was read and discarded,
     * and those two need opposite repairs.
     */
    fun evidence(bitmap: Bitmap, frameId: String, reason: String, fields: Map<String, Any?> = emptyMap()) {
        val store = evidence ?: return
        if (!store.enabled) return
        val name = store.capture(bitmap, frameId, reason) ?: return
        record(
            "EVIDENCE_FRAME_CAPTURED",
            fields + mapOf("frameId" to frameId, "reason" to reason, "file" to name),
        )
    }

    fun observeTrace(trace: DiagnosticTrace) {
        latestTrace.set(trace)
    }

    fun record(type: String, fields: Map<String, Any?> = emptyMap()) {
        commands.trySend(Command.Record(type, fields))
    }

    fun failure(stage: String, error: Throwable, fields: Map<String, Any?> = emptyMap()) {
        commands.trySend(Command.Failure(stage, error, fields))
    }

    /**
     * Records the selected visual input without retaining the image itself.
     *
     * Existing callers keep passing the live bitmap, but it is sampled synchronously into aggregate
     * measurements and a one-way hash. The bitmap is never copied, encoded, or owned by diagnostics.
     */
    fun frame(
        bitmap: Bitmap,
        frameId: String,
        stage: String,
        metadata: Map<String, Any?> = emptyMap(),
    ) {
        recordVisualFingerprint(
            bitmap = bitmap,
            frameId = frameId,
            role = "selected_input",
            reason = stage,
            eventType = "FRAME_VISUAL_FINGERPRINT",
            metadata = metadata,
        )
    }

    /** Records a representative rejected frame as metrics only, never as a preview image. */
    fun preview(
        bitmap: Bitmap,
        frameId: String,
        reason: String,
        metadata: Map<String, Any?> = emptyMap(),
    ) {
        recordVisualFingerprint(
            bitmap = bitmap,
            frameId = frameId,
            role = "rejected_or_throttled",
            reason = reason,
            eventType = "DROPPED_FRAME_VISUAL_FINGERPRINT",
            metadata = metadata,
        )
    }

    private fun recordVisualFingerprint(
        bitmap: Bitmap,
        frameId: String,
        role: String,
        reason: String,
        eventType: String,
        metadata: Map<String, Any?>,
    ) {
        val started = SystemClock.elapsedRealtimeNanos()
        runCatching {
            VisualFingerprintAnalyzer.analyze(
                bitmap = bitmap,
                role = role,
                frameId = frameId,
                reason = reason,
            )
        }.onSuccess { fingerprint ->
            val completed = SystemClock.elapsedRealtimeNanos()
            record(
                eventType,
                metadata + fingerprint + mapOf(
                    "fingerprintComputationMs" to (completed - started) / 1_000_000.0,
                    "fingerprintCompletedElapsedNanos" to completed,
                    "fingerprintSinceCaptureMs" to sinceCaptureFromMetadata(metadata, completed),
                ),
            )
        }.onFailure { error ->
            failure(
                "VISUAL_FINGERPRINT",
                error,
                metadata + mapOf(
                    "frameId" to frameId,
                    "role" to role,
                    "reason" to reason,
                    "storesImage" to false,
                ),
            )
        }
    }

    suspend fun startSession(settings: Map<String, Any?>): String {
        val completion = CompletableDeferred<String>()
        commands.send(Command.StartSession(settings, completion))
        return completion.await()
    }

    suspend fun endSession(reason: String) {
        val completion = CompletableDeferred<Unit>()
        commands.send(Command.EndSession(reason, completion))
        completion.await()
    }

    /** Optional manual label. Automatic recording and anomaly detection do not depend on this. */
    suspend fun markProblem(note: String = "") {
        val completion = CompletableDeferred<Unit>()
        commands.send(Command.MarkProblem(note, latestTrace.get(), completion))
        completion.await()
    }

    fun markProblemAsync(note: String = "") {
        commands.trySend(Command.MarkProblem(note, latestTrace.get(), null))
    }

    suspend fun export(): File {
        val completion = CompletableDeferred<File>()
        commands.send(Command.Export(completion))
        return completion.await()
    }

    suspend fun storageStatus(): DiagnosticRecorder.StorageStatus {
        val completion = CompletableDeferred<DiagnosticRecorder.StorageStatus>()
        commands.send(Command.Status(completion))
        return completion.await()
    }

    suspend fun flush() {
        val completion = CompletableDeferred<Unit>()
        commands.send(Command.Flush(completion))
        completion.await()
    }

    fun recordFatalBlocking(error: Throwable) {
        val target = recorder ?: return
        val completion = CompletableDeferred<Unit>()
        val accepted = commands.trySend(Command.Fatal(error, completion)).isSuccess
        if (!accepted) {
            target.recordFatalBlocking(error)
            return
        }
        runBlocking(Dispatchers.IO) {
            val completed = withTimeoutOrNull(FATAL_FLUSH_TIMEOUT_MS) {
                completion.await()
                true
            } ?: false
            if (!completed) target.recordFatalBlocking(error)
        }
    }

    fun sinceCaptureMs(capturedAtElapsedNanos: Long): Double =
        (SystemClock.elapsedRealtimeNanos() - capturedAtElapsedNanos)
            .coerceAtLeast(0L) / 1_000_000.0

    private suspend fun handle(command: Command) {
        val target = recorder
        if (target == null) {
            completeWithoutRecorder(command)
            return
        }

        when (command) {
            is Command.Record -> runCatching { target.record(command.type, command.fields) }
            is Command.Failure -> runCatching {
                target.recordFailure(command.stage, command.error, command.fields)
            }
            is Command.StartSession -> complete(command.completion) {
                VisualFingerprintAnalyzer.reset()
                target.startSession(command.settings)
            }
            is Command.EndSession -> complete(command.completion) {
                target.endSession(command.reason)
            }
            is Command.MarkProblem -> {
                val fields = command.nearestTrace?.fields(
                    mapOf(
                        "note" to command.note,
                        "nearestTraceId" to command.nearestTrace.traceId,
                        "nearestFrameId" to command.nearestTrace.frameId,
                        "markerAgeFromNearestCaptureMs" to sinceCaptureMs(
                            command.nearestTrace.capturedAtElapsedNanos
                        ),
                        "manualMarkerOptional" to true,
                    ),
                ) ?: mapOf(
                    "note" to command.note,
                    "nearestTraceAvailable" to false,
                    "manualMarkerOptional" to true,
                )
                val result = runCatching { target.record("USER_MARKED_PROBLEM", fields) }
                command.completion?.let { completion ->
                    val failure = result.exceptionOrNull()
                    if (failure == null) completion.complete(Unit)
                    else completion.completeExceptionally(failure)
                }
            }
            is Command.Export -> complete(command.completion) { target.export() }
            is Command.Status -> complete(command.completion) { target.storageStatus() }
            is Command.Flush -> complete(command.completion) { target.flush() }
            is Command.Fatal -> complete(command.completion) {
                target.recordFailure("UNCAUGHT_EXCEPTION", command.error)
                target.flush()
            }
        }
    }

    private fun completeWithoutRecorder(command: Command) {
        val error = IllegalStateException("Diagnostic recorder is not initialized")
        when (command) {
            is Command.StartSession -> command.completion.completeExceptionally(error)
            is Command.EndSession -> command.completion.completeExceptionally(error)
            is Command.Export -> command.completion.completeExceptionally(error)
            is Command.Status -> command.completion.completeExceptionally(error)
            is Command.Flush -> command.completion.complete(Unit)
            is Command.Fatal -> command.completion.completeExceptionally(error)
            is Command.MarkProblem -> command.completion?.completeExceptionally(error)
            else -> Unit
        }
    }

    private suspend fun <T> complete(
        deferred: CompletableDeferred<T>,
        operation: suspend () -> T,
    ) {
        try {
            deferred.complete(operation())
        } catch (error: Throwable) {
            deferred.completeExceptionally(error)
        }
    }

    private fun sinceCaptureFromMetadata(
        metadata: Map<String, Any?>,
        nowNanos: Long,
    ): Double? {
        val captured = (metadata["capturedAtElapsedNanos"] as? Number)?.toLong() ?: return null
        return (nowNanos - captured).coerceAtLeast(0L) / 1_000_000.0
    }
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
