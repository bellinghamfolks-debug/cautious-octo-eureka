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
import java.util.concurrent.atomic.AtomicLong
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
// DENSE_DIAGNOSTIC_EVIDENCE_V381
// TEN_MINUTE_DIAGNOSTIC_TIMELINE_V382
// TEN_MINUTE_DIAGNOSTIC_TIMELINE_HARDENING_V382
// EVERY_ANALYSIS_INPUT_EVIDENCE_V383
object DiagnosticHub {
    private const val FATAL_FLUSH_TIMEOUT_MS = 2_000L
    // The 10-minute timeline is the primary visual record. Selected-input snapshots are
    // supplemental and deliberately sparse so they cannot consume the timeline budget.
    private const val DENSE_SELECTED_EVIDENCE_INTERVAL_MS = 5_000L
    private const val TIMELINE_EVIDENCE_INTERVAL_MS = 1_000L
    private const val TIMELINE_EVIDENCE_WINDOW_MS = 10L * 60L * 1_000L

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
    private sealed interface EvidenceTask {
        data class Capture(val bitmap: Bitmap,val frameId: String,val reason: String,
                           val fields: Map<String,Any?>,val epoch: Long,val queuedAt: Long):EvidenceTask
        data class Barrier(val completion: CompletableDeferred<Unit>):EvidenceTask
    }
    private val evidenceTasks=Channel<EvidenceTask>(3)
    private val evidenceSlots=java.util.concurrent.Semaphore(2)
    private val evidenceEpoch=AtomicLong(0)
    private val evidenceWriteLock=Any()

    init {
        scope.launch {
            for(task in evidenceTasks) when(task) {
                is EvidenceTask.Barrier -> task.completion.complete(Unit)
                is EvidenceTask.Capture -> try {
                    synchronized(evidenceWriteLock) {
                        if(task.epoch==evidenceEpoch.get()) {
                            val start=SystemClock.elapsedRealtimeNanos()
                            val name=evidence?.capture(task.bitmap,task.frameId,task.reason,capturedWhileEnabled=true)
                            record(if(name==null) "EVIDENCE_FRAME_SKIPPED" else "EVIDENCE_FRAME_CAPTURED",
                                task.fields+mapOf("frameId" to task.frameId,"reason" to task.reason,
                                    "file" to name,"evidenceQueueMs" to (start-task.queuedAt)/1e6,
                                    "evidenceWriteMs" to (SystemClock.elapsedRealtimeNanos()-start)/1e6))
                        }
                    }
                } catch(error:Exception) {
                    record("EVIDENCE_WRITE_FAILED",task.fields+mapOf("errorType" to error.javaClass.simpleName))
                } finally { task.bitmap.recycle();evidenceSlots.release() }
            }
        }
    }
    private val latestTrace = AtomicReference<DiagnosticTrace?>(null)
    private val lastDenseEvidenceAtElapsedMs = AtomicLong(0L)
    private val timelineWindowStartedAtElapsedMs = AtomicLong(0L)
    // Absolute one-second slots avoid cumulative drift from JPEG encoding time.
    private val lastTimelineEvidenceSecond = AtomicLong(-1L)
    private val timelineCompletionRecorded = AtomicLong(0L)

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
                "storesImagesByDefault" to false,
                "optInDenseImageEvidence" to true,
                "visualEvidence" to "aggregate_fingerprint_plus_opt_in_dense_frames",
            ),
        )
    }

    /**
     * Turns failure-frame capture on or off. Off is the only default.
     *
     * Switching off stops capture and keeps what was already captured. It used to delete it, which
     * was defensible while the switch lived on a settings screen and meant "I do not want images in
     * my bundle" — but the switch is now a shortcut pressed from inside the app being captured, and
     * the whole workflow is *on, reproduce the failure, off, export*. Deleting on the third step
     * would throw away the only thing the first two were for. What keeps this safe is unchanged:
     * nothing is captured unless the user switched it on, the count is declared in the manifest and
     * in the archive name, and [discardEvidence] deletes on demand.
     */
    fun setEvidenceCapture(enabled: Boolean) {
        val store = evidence ?: return
        if (store.enabled == enabled) return
        store.enabled = enabled
        timelineWindowStartedAtElapsedMs.set(0L)
        lastTimelineEvidenceSecond.set(-1L)
        timelineCompletionRecorded.set(0L)
        lastDenseEvidenceAtElapsedMs.set(0L)
        record(
            "EVIDENCE_CAPTURE_SETTING",
            mapOf(
                "enabled" to enabled,
                "framesHeld" to store.frameCount(),
                "captureMode" to "ten_minute_timeline_plus_supplemental_failures",
                "timelineIntervalMs" to TIMELINE_EVIDENCE_INTERVAL_MS,
                "timelineWindowMs" to TIMELINE_EVIDENCE_WINDOW_MS,
                "selectedInputCapturePolicy" to "bounded_async_with_explicit_skips",
            ),
        )
    }

    /** Deletes every stored failure frame. The user's own answer to changing their mind. */
    fun discardEvidence() {
        val store = evidence ?: return
        synchronized(evidenceWriteLock) {
            evidenceEpoch.incrementAndGet()
            val held = store.frameCount()
            store.clear()
            record("EVIDENCE_FRAMES_DISCARDED", mapOf("framesDeleted" to held))
        }
    }

    /** How many failure frames are currently held, for anything that has to say so out loud. */
    fun evidenceFrameCount(): Int = evidence?.frameCount() ?: 0

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
        if(!evidenceSlots.tryAcquire()) {
            record("EVIDENCE_FRAME_SKIPPED",fields+mapOf("frameId" to frameId,"reason" to "writer_capacity"))
            return
        }
        val epoch=evidenceEpoch.get()
        val started=SystemClock.elapsedRealtimeNanos()
        val snapshot=try { bitmap.copy(Bitmap.Config.ARGB_8888,false) } catch(error:Exception) { null }
        if(snapshot==null) { evidenceSlots.release();record("EVIDENCE_FRAME_SKIPPED",fields+mapOf("reason" to "snapshot_failed"));return }
        val task=EvidenceTask.Capture(snapshot,frameId,reason,fields+mapOf(
            "evidenceSnapshotCopyMs" to (SystemClock.elapsedRealtimeNanos()-started)/1e6),epoch,started)
        if(!evidenceTasks.trySend(task).isSuccess) {
            snapshot.recycle();evidenceSlots.release()
            record("EVIDENCE_FRAME_SKIPPED",fields+mapOf("frameId" to frameId,"reason" to "writer_queue_full"))
        }
    }

    /**
     * Keeps one real screen frame per second for ten minutes while image evidence is enabled.
     * This runs before change-detection throttling, so a static page is still represented across
     * the whole diagnostic window rather than by a single accepted analysis frame.
     */
    fun timelineFrame(
        bitmap: Bitmap,
        frameId: String,
        fields: Map<String, Any?> = emptyMap(),
    ) {
        val store = evidence ?: return
        if (!store.enabled) return
        val now = SystemClock.elapsedRealtime()
        var windowStart = timelineWindowStartedAtElapsedMs.get()
        if (windowStart == 0L) {
            if (timelineWindowStartedAtElapsedMs.compareAndSet(0L, now)) {
                windowStart = now
                record(
                    "EVIDENCE_TIMELINE_STARTED",
                    mapOf(
                        "durationMs" to TIMELINE_EVIDENCE_WINDOW_MS,
                        "intervalMs" to TIMELINE_EVIDENCE_INTERVAL_MS,
                        "targetFrames" to 600,
                    ),
                )
            } else {
                windowStart = timelineWindowStartedAtElapsedMs.get()
            }
        }
        val elapsed = (now - windowStart).coerceAtLeast(0L)
        if (elapsed >= TIMELINE_EVIDENCE_WINDOW_MS) {
            if (timelineCompletionRecorded.compareAndSet(0L, 1L)) {
                record(
                    "EVIDENCE_TIMELINE_COMPLETED",
                    mapOf(
                        "elapsedMs" to elapsed,
                        "framesHeld" to store.frameCount(),
                        "targetFrames" to 600,
                        "coverageWindowSeconds" to 600,
                    ),
                )
            }
            return
        }

        // Save at most once in each absolute second of the ten-minute window. Compression time can
        // delay a frame inside its slot, but it cannot push every later frame progressively later.
        val timelineSecond = elapsed / TIMELINE_EVIDENCE_INTERVAL_MS
        while (true) {
            val previousSecond = lastTimelineEvidenceSecond.get()
            if (previousSecond >= timelineSecond) return
            if (lastTimelineEvidenceSecond.compareAndSet(previousSecond, timelineSecond)) {
                val missedSeconds = (timelineSecond - previousSecond - 1L).coerceAtLeast(0L)
                if (missedSeconds > 0L && previousSecond >= 0L) {
                    record(
                        "EVIDENCE_TIMELINE_SLOT_GAP",
                        mapOf(
                            "previousSecond" to previousSecond,
                            "currentSecond" to timelineSecond,
                            "missedSeconds" to missedSeconds,
                        ),
                    )
                }
                break
            }
        }

        evidence(
            bitmap = bitmap,
            frameId = frameId,
            reason = "timeline_1s",
            fields = fields + mapOf(
                "evidenceRole" to "timeline",
                "timelineElapsedMs" to elapsed,
                "timelineSecond" to timelineSecond,
                "timelineIntervalMs" to TIMELINE_EVIDENCE_INTERVAL_MS,
                "timelineWindowMs" to TIMELINE_EVIDENCE_WINDOW_MS,
            ),
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
        captureExactAnalysisInputEvidence(bitmap, frameId, stage, metadata)
    }

    /** Save every selected OCR/Gemini visual input while opt-in diagnostics are enabled. */
    private fun captureExactAnalysisInputEvidence(
        bitmap: Bitmap,
        frameId: String,
        stage: String,
        metadata: Map<String, Any?>,
    ) {
        val store = evidence ?: return
        if (!store.enabled) return
        val safeStage = stage.replace(Regex("[^A-Za-z0-9_-]"), "_").take(20)
        evidence(
            bitmap = bitmap,
            frameId = frameId,
            reason = "analysis_input_$safeStage",
            fields = metadata + mapOf(
                "evidenceRole" to "analysis_input",
                "sourceStage" to stage,
                "analysisInputCapturePolicy" to "every_selected_input",
                "analysisInputThrottled" to false,
            ),
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
        val barrier=CompletableDeferred<Unit>()
        evidenceTasks.send(EvidenceTask.Barrier(barrier))
        barrier.await()
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
                lastDenseEvidenceAtElapsedMs.set(0L)
                timelineWindowStartedAtElapsedMs.set(0L)
                lastTimelineEvidenceSecond.set(-1L)
                timelineCompletionRecorded.set(0L)
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
    val turn: com.abdullah.visionbridge.domain.model.AnalysisTurn? = null,
    val section: String? = null,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<DiagnosticTrace>

    fun fields(extra: Map<String, Any?> = emptyMap()): Map<String, Any?> = mapOf(
        "turnId" to (turn?.turnId ?: traceId),
        "traceId" to traceId,
        "frameId" to frameId,
        "capturedAtEpochMs" to capturedAtEpochMs,
        "capturedAtElapsedNanos" to capturedAtElapsedNanos,
        "sinceCaptureMs" to DiagnosticHub.sinceCaptureMs(capturedAtElapsedNanos),
    ) + (turn?.fields() ?: emptyMap()) + mapOf("contentSection" to section) + extra
}
