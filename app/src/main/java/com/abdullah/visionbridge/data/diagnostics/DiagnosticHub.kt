package com.abdullah.visionbridge.data.diagnostics

import android.graphics.Bitmap
import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Process-wide diagnostic actor.
 *
 * Producers append commands to one ordered channel; a single IO writer executes them in the same
 * order. Export is a queue command, so everything submitted before it is guaranteed to be included.
 */
object DiagnosticHub {
    private const val FATAL_FLUSH_TIMEOUT_MS = 2_000L

    private sealed interface Command {
        data class Record(val type: String, val fields: Map<String, Any?>) : Command
        data class Failure(
            val stage: String,
            val error: Throwable,
            val fields: Map<String, Any?>,
        ) : Command
        data class Frame(
            val bitmap: Bitmap,
            val frameId: String,
            val stage: String,
            val metadata: Map<String, Any?>,
        ) : Command
        data class Preview(
            val bitmap: Bitmap,
            val frameId: String,
            val reason: String,
            val metadata: Map<String, Any?>,
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
    }

    fun initialize(value: DiagnosticRecorder) {
        recorder = value
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

    fun frame(
        bitmap: Bitmap,
        frameId: String,
        stage: String,
        metadata: Map<String, Any?> = emptyMap(),
    ) {
        val copyStarted = SystemClock.elapsedRealtimeNanos()
        val safeCopy = runCatching { bitmap.copy(Bitmap.Config.ARGB_8888, false) }.getOrElse { error ->
            failure(
                "DIAGNOSTIC_FRAME_COPY",
                error,
                metadata + mapOf("frameId" to frameId, "stage" to stage),
            )
            return
        }
        val copyEnded = SystemClock.elapsedRealtimeNanos()
        val enriched = metadata + mapOf(
            "diagnosticBitmapCopyMs" to (copyEnded - copyStarted) / 1_000_000.0,
            "diagnosticCopyCompletedElapsedNanos" to copyEnded,
            "diagnosticCopySinceCaptureMs" to sinceCaptureFromMetadata(metadata, copyEnded),
        )
        if (commands.trySend(Command.Frame(safeCopy, frameId, stage, enriched)).isFailure) {
            safeCopy.recycle()
        }
    }

    fun preview(
        bitmap: Bitmap,
        frameId: String,
        reason: String,
        metadata: Map<String, Any?> = emptyMap(),
    ) {
        val copyStarted = SystemClock.elapsedRealtimeNanos()
        val safeCopy = runCatching { bitmap.copy(Bitmap.Config.ARGB_8888, false) }.getOrElse { error ->
            failure(
                "DIAGNOSTIC_PREVIEW_COPY",
                error,
                metadata + mapOf("frameId" to frameId, "reason" to reason),
            )
            return
        }
        val copyEnded = SystemClock.elapsedRealtimeNanos()
        val enriched = metadata + mapOf(
            "diagnosticBitmapCopyMs" to (copyEnded - copyStarted) / 1_000_000.0,
            "diagnosticCopyCompletedElapsedNanos" to copyEnded,
            "diagnosticCopySinceCaptureMs" to sinceCaptureFromMetadata(metadata, copyEnded),
        )
        if (commands.trySend(Command.Preview(safeCopy, frameId, reason, enriched)).isFailure) {
            safeCopy.recycle()
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
            recycleIfNeeded(command)
            return
        }

        when (command) {
            is Command.Record -> runCatching { target.record(command.type, command.fields) }
            is Command.Failure -> runCatching {
                target.recordFailure(command.stage, command.error, command.fields)
            }
            is Command.Frame -> try {
                target.recordFrame(command.bitmap, command.frameId, command.stage, command.metadata)
            } catch (error: Throwable) {
                runCatching {
                    target.recordFailure(
                        "SAVE_DIAGNOSTIC_FRAME",
                        error,
                        command.metadata + mapOf(
                            "frameId" to command.frameId,
                            "stage" to command.stage,
                        ),
                    )
                }
            } finally {
                command.bitmap.recycle()
            }
            is Command.Preview -> try {
                target.recordPreviewFrame(
                    command.bitmap,
                    command.frameId,
                    command.reason,
                    command.metadata,
                )
            } catch (error: Throwable) {
                runCatching {
                    target.recordFailure(
                        "SAVE_DIAGNOSTIC_PREVIEW",
                        error,
                        command.metadata + mapOf(
                            "frameId" to command.frameId,
                            "reason" to command.reason,
                        ),
                    )
                }
            } finally {
                command.bitmap.recycle()
            }
            is Command.StartSession -> complete(command.completion) {
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
                    ),
                ) ?: mapOf(
                    "note" to command.note,
                    "nearestTraceAvailable" to false,
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
            is Command.Flush -> command.completion.complete(Unit)
            is Command.Fatal -> complete(command.completion) {
                target.recordFailure("UNCAUGHT_EXCEPTION", command.error)
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

    private fun recycleIfNeeded(command: Command) {
        when (command) {
            is Command.Frame -> command.bitmap.recycle()
            is Command.Preview -> command.bitmap.recycle()
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
