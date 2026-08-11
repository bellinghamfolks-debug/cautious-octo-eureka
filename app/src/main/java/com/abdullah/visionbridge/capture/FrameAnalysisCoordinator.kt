package com.abdullah.visionbridge.capture

import android.graphics.Bitmap
import android.os.SystemClock
import com.abdullah.visionbridge.data.diagnostics.DiagnosticHub
import com.abdullah.visionbridge.data.diagnostics.DiagnosticTrace
import com.abdullah.visionbridge.data.gemini.OcrTrustRejectedException
import com.abdullah.visionbridge.data.gemini.StreamingSpeechBuffer
import com.abdullah.visionbridge.data.speech.BilingualTtsEngine
import com.abdullah.visionbridge.data.speech.DocumentSpeechPolicy
import com.abdullah.visionbridge.data.speech.ReadingDeliveryTracker
import com.abdullah.visionbridge.data.speech.ReadingLedger
import com.abdullah.visionbridge.domain.model.AnalysisMode
import com.abdullah.visionbridge.domain.model.AnalysisResult
import com.abdullah.visionbridge.domain.model.AppSettings
import com.abdullah.visionbridge.domain.model.CaptureProfile
import com.abdullah.visionbridge.domain.model.SceneDescriptionStyle
import com.abdullah.visionbridge.domain.repository.ApiKeyStore
import com.abdullah.visionbridge.domain.repository.SettingsRepository
import com.abdullah.visionbridge.data.speech.HybridReadingPlan
import com.abdullah.visionbridge.domain.usecase.AnalyzeFrameUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Coordinates the single analysis lane, result freshness and speech.
 *
 * The lane deliberately owns at most one active request and one latest pending frame, whichever
 * engine serves it. A visual target change now cancels the active request as well as dropping the
 * pending frame, because a target change means the subject really was replaced: the request in
 * flight is for something the user has walked away from, and letting it finish only delays the one
 * they are waiting for. That was not safe while target changes fired every 343 ms on a motionless
 * bottle — 460 requests were marked stale across one diagnostic bundle — and it is safe now that
 * [VisualTargetTracker] separates a subject that moved from a subject that changed. A scene
 * description the user has asked not to interrupt is the one request allowed to outlive its target.
 */
class FrameAnalysisCoordinator(
    private val settingsRepository: SettingsRepository,
    private val apiKeyStore: ApiKeyStore,
    private val analyzeFrame: AnalyzeFrameUseCase,
    private val tts: BilingualTtsEngine,
    private val runtime: CaptureRuntime,
) {
    private data class PendingCloudFrame(
        val bitmap: Bitmap,
        val settings: AppSettings,
        val visualGeneration: Long,
        val apiKey: String,
        val mode: AnalysisMode,
        val trace: DiagnosticTrace? = null,
        val queuedAtElapsedMs: Long = SystemClock.elapsedRealtime(),
    )

    private data class FinishedLane(
        val ownedByFinishingJob: Boolean,
        val next: PendingCloudFrame?,
    )

    private val processMutex = Mutex()
    private val cloudScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cloudQueueLock = Any()
    private val visualGeneration = AtomicLong(0L)
    private val lastTrustFeedbackGeneration = AtomicLong(Long.MIN_VALUE)
    private val readingLedger = ReadingLedger()
    private val deliveryTracker = ReadingDeliveryTracker()

    /** Visual generation that the reading currently being spoken belongs to. */
    @Volatile
    private var activeReadingGeneration = Long.MIN_VALUE

    /** The subject whose surroundings have already been described, so they are not described twice. */
    private var lastDescribedGeneration = Long.MIN_VALUE

    /** Visual generation of the last completed text analysis, and when it completed. */
    @Volatile
    private var lastTextAnalysisGeneration = Long.MIN_VALUE

    @Volatile
    private var lastTextAnalysisAtElapsedMs = 0L

    /** True when the previous pass over the current target produced nothing worth speaking. */
    @Volatile
    private var lastTextAnalysisYieldedNothing = false

    private var lastCloudOcrAt = 0L
    private var lastSceneAt = 0L
    private var lastCloudSnapshotAt = 0L
    private var cloudJob: Job? = null
    private var delayedLaunchJob: Job? = null
    private var activeCloudMode: AnalysisMode? = null
    private var activeCloudFrameId: String? = null
    private var activeCloudStartedAtElapsedMs = 0L

    /**
     * True when the active request's result would still be publishable after the target changes.
     * Only a scene description the user has asked not to interrupt qualifies.
     */
    private var activeCloudMayOutliveTarget = false
    private var lastCloudProgressAtElapsedMs = 0L
    private var lastCloudCompletedAtElapsedMs = 0L
    private var cloudFailureStreak = 0
    private var pendingCloudFrame: PendingCloudFrame? = null
    private var lastHealthSnapshotAtElapsedMs = 0L
    private var lastSpeechInterruptAtElapsedMs = 0L

    init {
        cloudScope.launch {
            while (isActive) {
                delay(HEALTH_CHECK_INTERVAL_MS)
                runHealthCheck()
            }
        }
        // The ledger is written from here and nowhere else. A page counts as read when the speech
        // engine says the user heard it, never when the coordinator finishes queueing it.
        tts.onBlockDelivered { readingId, blockIndex, outcome ->
            commitDelivery(deliveryTracker.record(readingId, blockIndex, outcome))
        }
    }

    /** Writes the heard part of a settled reading to the ledger and leaves the rest owed. */
    private fun commitDelivery(delivery: ReadingDeliveryTracker.Delivery?) {
        if (delivery == null) return
        if (delivery.deliveredText.isNotBlank()) {
            readingLedger.recordDelivered(delivery.alreadyHeard, delivery.deliveredText)
        }
        DiagnosticHub.record(
            "DOCUMENT_READING_DELIVERED",
            mapOf(
                "deliveredText" to delivery.deliveredText,
                "deliveredCharacters" to delivery.deliveredText.length,
                "owedText" to delivery.owedText,
                "owedCharacters" to delivery.owedText.length,
                "complete" to delivery.complete,
                "outcomes" to delivery.outcomes.map { it.name },
            ),
        )
    }

    suspend fun process(bitmap: Bitmap) = processMutex.withLock {
        // Every arriving frame enforces the deadline of whatever is still in flight. The health
        // loop alone was not enough: after a capture died, a 48,000 ms watchdog first ran at
        // 221,584 ms, because the `delay` driving it had stopped along with everything else. A
        // frame is proof that the app is running, which makes it the one moment a stalled request
        // is guaranteed to be noticed.
        runHealthCheck()
        val trace = currentCoroutineContext()[DiagnosticTrace]
        val settings = settingsRepository.settings.first()
        val generationAtCapture = visualGeneration.get()
        val processStarted = SystemClock.elapsedRealtimeNanos()
        DiagnosticHub.record(
            "COORDINATOR_PROCESS_STARTED",
            trace.fieldsOrEmpty(
                mapOf(
                    "visualGeneration" to generationAtCapture,
                    "mode" to settings.mode.name,
                    "captureProfile" to settings.captureProfile.name,
                    "trustGateEnabled" to settings.trustGateEnabled,
                    "useLocalOcr" to settings.useLocalOcr,
                ),
            ),
        )
        runtime.processing(true)
        try {
            when (settings.mode) {
                AnalysisMode.TEXT_READING -> {
                    if (shouldDeferStaticTextReading(generationAtCapture, trace)) return@withLock
                    if (settings.captureProfile == CaptureProfile.FAST_TEXT) {
                        processFastText(bitmap, settings, generationAtCapture, trace)
                    } else {
                        processStableText(bitmap, settings, generationAtCapture, trace)
                    }
                }

                AnalysisMode.SCENE_DESCRIPTION -> queueDynamicScene(
                    bitmap,
                    settings,
                    generationAtCapture,
                    trace,
                )
            }
        } catch (error: OcrTrustRejectedException) {
            DiagnosticHub.record(
                "OCR_TRUST_REJECTED",
                trace.fieldsOrEmpty(
                    mapOf(
                        "message" to error.spokenMessage,
                        "visualGeneration" to generationAtCapture,
                    ),
                ),
            )
            notifyTrustRejection(error, settings, generationAtCapture, trace)
        } catch (error: Throwable) {
            if (error !is CancellationException) {
                DiagnosticHub.failure("COORDINATOR_PROCESS", error, trace.fieldsOrEmpty())
                runtime.error(error.userMessage())
            } else {
                DiagnosticHub.record(
                    "COORDINATOR_CANCELLED",
                    trace.fieldsOrEmpty(mapOf("reason" to error.message)),
                )
            }
        } finally {
            DiagnosticHub.record(
                "COORDINATOR_PROCESS_RETURNED",
                trace.fieldsOrEmpty(
                    mapOf(
                        "durationMs" to
                            (SystemClock.elapsedRealtimeNanos() - processStarted) / 1_000_000.0,
                    ),
                ),
            )
            runtime.processing(false)
        }
    }

    private suspend fun processStableText(
        bitmap: Bitmap,
        settings: AppSettings,
        generationAtCapture: Long,
        trace: DiagnosticTrace?,
    ) {
        val keyStarted = SystemClock.elapsedRealtimeNanos()
        val key = apiKeyStore.get()
        DiagnosticHub.record(
            "API_KEY_LOOKUP_COMPLETED",
            trace.fieldsOrEmpty(
                mapOf(
                    "hasApiKey" to (key != null),
                    "durationMs" to
                        (SystemClock.elapsedRealtimeNanos() - keyStarted) / 1_000_000.0,
                ),
            ),
        )
        if (key == null && !settings.useLocalOcr) {
            DiagnosticHub.record(
                "CLOUD_SKIPPED",
                trace.fieldsOrEmpty(mapOf("reason" to "api_key_missing")),
            )
            return
        }

        queueCloudFrame(
            frame = PendingCloudFrame(
                bitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false),
                settings = settings,
                visualGeneration = generationAtCapture,
                apiKey = key.orEmpty(),
                mode = AnalysisMode.TEXT_READING,
                trace = trace,
            ),
            now = System.currentTimeMillis(),
            minimumLaunchIntervalMs = STABLE_LAUNCH_INTERVAL_MS,
            pendingSnapshotIntervalMs = STABLE_PENDING_SNAPSHOT_INTERVAL_MS,
        )
    }

    private suspend fun processFastText(
        bitmap: Bitmap,
        settings: AppSettings,
        generationAtCapture: Long,
        trace: DiagnosticTrace?,
    ) {
        val key = apiKeyStore.get()
        DiagnosticHub.record(
            "API_KEY_LOOKUP_COMPLETED",
            trace.fieldsOrEmpty(mapOf("hasApiKey" to (key != null))),
        )
        if (key == null && !settings.useLocalOcr) {
            DiagnosticHub.record(
                "CLOUD_SKIPPED",
                trace.fieldsOrEmpty(mapOf("reason" to "api_key_missing")),
            )
            return
        }
        queueFastText(bitmap, settings, generationAtCapture, key.orEmpty(), trace)
    }

    private fun queueFastText(
        bitmap: Bitmap,
        settings: AppSettings,
        generationAtCapture: Long,
        key: String,
        trace: DiagnosticTrace?,
    ) {
        queueCloudFrame(
            frame = PendingCloudFrame(
                bitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false),
                settings = settings,
                visualGeneration = generationAtCapture,
                apiKey = key,
                mode = AnalysisMode.TEXT_READING,
                trace = trace,
            ),
            now = System.currentTimeMillis(),
            minimumLaunchIntervalMs = FAST_LAUNCH_INTERVAL_MS,
            pendingSnapshotIntervalMs = FAST_PENDING_SNAPSHOT_INTERVAL_MS,
        )
    }

    private suspend fun queueDynamicScene(
        bitmap: Bitmap,
        settings: AppSettings,
        generationAtCapture: Long,
        trace: DiagnosticTrace?,
    ) {
        val key = apiKeyStore.get()
        // Describing has no on-device implementation, so it needs the key whatever the local
        // reading switch says. Consulting that switch here let a user with local reading on and no
        // key through the guard, and the request then failed against Gemini with an HTTP error
        // instead of the one sentence that would have told them what to do.
        if (key == null) {
            throw IllegalStateException("أضف Gemini API Key من الإعدادات لاستخدام وصف المشهد")
        }
        val minimumInterval = when (settings.sceneDescriptionStyle) {
            SceneDescriptionStyle.BRIEF -> BRIEF_SCENE_INTERVAL_MS
            SceneDescriptionStyle.COMPREHENSIVE -> COMPREHENSIVE_SCENE_INTERVAL_MS
        }
        queueCloudFrame(
            frame = PendingCloudFrame(
                bitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false),
                settings = settings,
                visualGeneration = generationAtCapture,
                apiKey = key.orEmpty(),
                mode = AnalysisMode.SCENE_DESCRIPTION,
                trace = trace,
            ),
            now = System.currentTimeMillis(),
            minimumLaunchIntervalMs = minimumInterval,
            pendingSnapshotIntervalMs = SCENE_PENDING_SNAPSHOT_INTERVAL_MS,
        )
    }

    /**
     * One active request plus one latest pending frame. Incoming frames can replace only the pending
     * slot; they never cancel the active network request. This is the backpressure boundary that
     * prevents request storms and the two-minute silent failure seen in diagnostics.
     */
    private fun queueCloudFrame(
        frame: PendingCloudFrame,
        now: Long,
        minimumLaunchIntervalMs: Long,
        pendingSnapshotIntervalMs: Long,
    ) {
        var launchNow: PendingCloudFrame? = null
        var delayedByMs = 0L

        synchronized(cloudQueueLock) {
            val active = cloudJob?.isCompleted == false
            if (active) {
                val sameGeneration = pendingCloudFrame?.visualGeneration == frame.visualGeneration
                if (sameGeneration && now - lastCloudSnapshotAt < pendingSnapshotIntervalMs) {
                    DiagnosticHub.record(
                        "CLOUD_FRAME_DROPPED",
                        frame.trace.fieldsOrEmpty(
                            mapOf(
                                "reason" to "pending_snapshot_interval",
                                "pendingSnapshotIntervalMs" to pendingSnapshotIntervalMs,
                                "mode" to frame.mode.name,
                                "activeFrameId" to activeCloudFrameId,
                            ),
                        ),
                    )
                    frame.bitmap.recycle()
                    return
                }
                lastCloudSnapshotAt = now

                replacePendingLocked(frame, "replaced_by_newer_cloud_frame")
                DiagnosticHub.record(
                    "CLOUD_FRAME_QUEUED_AS_LATEST",
                    frame.trace.fieldsOrEmpty(
                        mapOf(
                            "mode" to frame.mode.name,
                            "activeFrameId" to activeCloudFrameId,
                        ),
                    ),
                )
                return
            }

            val lastLaunch = if (frame.mode == AnalysisMode.TEXT_READING) lastCloudOcrAt else lastSceneAt
            val elapsed = now - lastLaunch
            if (elapsed < minimumLaunchIntervalMs) {
                delayedByMs = minimumLaunchIntervalMs - elapsed
                replacePendingLocked(frame, "replaced_while_waiting_for_launch_interval")
                DiagnosticHub.record(
                    "CLOUD_FRAME_DELAYED",
                    frame.trace.fieldsOrEmpty(
                        mapOf(
                            "reason" to "minimum_cloud_launch_interval",
                            "delayMs" to delayedByMs,
                            "mode" to frame.mode.name,
                        ),
                    ),
                )
            } else {
                launchNow = frame
            }
        }

        if (launchNow != null) {
            launchCloud(launchNow!!)
        } else if (delayedByMs > 0L) {
            schedulePendingPromotion(delayedByMs)
        }
    }

    private fun replacePendingLocked(frame: PendingCloudFrame, oldReason: String) {
        pendingCloudFrame?.let { old ->
            DiagnosticHub.record(
                "CLOUD_FRAME_DROPPED",
                old.trace.fieldsOrEmpty(
                    mapOf(
                        "reason" to oldReason,
                        "replacementFrameId" to frame.trace?.frameId,
                    ),
                ),
            )
            old.bitmap.recycle()
        }
        pendingCloudFrame = frame
    }

    private fun schedulePendingPromotion(delayMs: Long) {
        synchronized(cloudQueueLock) {
            if (delayedLaunchJob?.isActive == true) return
            delayedLaunchJob = cloudScope.launch {
                delay(delayMs.coerceAtLeast(1L))
                promotePendingIfIdle("minimum_interval_elapsed")
            }
        }
    }

    private fun promotePendingIfIdle(reason: String) {
        val next = synchronized(cloudQueueLock) {
            if (cloudJob?.isCompleted == false) return
            delayedLaunchJob = null
            pendingCloudFrame.also { pendingCloudFrame = null }
        } ?: return

        DiagnosticHub.record(
            "CLOUD_PENDING_FRAME_PROMOTED",
            next.trace.fieldsOrEmpty(
                mapOf(
                    "mode" to next.mode.name,
                    "reason" to reason,
                    "queuedForMs" to (SystemClock.elapsedRealtime() - next.queuedAtElapsedMs),
                ),
            ),
        )
        launchCloud(next)
    }

    private fun launchCloud(frame: PendingCloudFrame) {
        val launched = cloudScope.launch(start = CoroutineStart.LAZY) {
            val thisJob = currentCoroutineContext()[Job]
            val cloudStarted = SystemClock.elapsedRealtimeNanos()
            try {
                val result = withContext(frame.trace ?: EmptyCoroutineContext) {
                    withTimeout(CLOUD_REQUEST_HARD_TIMEOUT_MS) {
                        streamAnalysis(
                            bitmap = frame.bitmap,
                            mode = frame.mode,
                            settings = frame.settings,
                            apiKey = frame.apiKey,
                            generationAtCapture = frame.visualGeneration,
                            trace = frame.trace,
                        )
                    }
                }
                synchronized(cloudQueueLock) {
                    cloudFailureStreak = 0
                    lastCloudCompletedAtElapsedMs = SystemClock.elapsedRealtime()
                }
                DiagnosticHub.record(
                    "CLOUD_ANALYSIS_COMPLETED",
                    frame.trace.fieldsOrEmpty(
                        mapOf(
                            "durationMs" to
                                (SystemClock.elapsedRealtimeNanos() - cloudStarted) / 1_000_000.0,
                            "text" to result.text,
                            "language" to result.language,
                            "urgent" to result.urgent,
                            "source" to result.source.name,
                        ),
                    ),
                )
                if (frame.mode == AnalysisMode.TEXT_READING) {
                    lastTextAnalysisGeneration = frame.visualGeneration
                    lastTextAnalysisAtElapsedMs = SystemClock.elapsedRealtime()
                    // Whether this pass found anything decides how soon the same motionless
                    // subject may be tried again. Nothing found is a reason to look again sooner,
                    // not a reason to stop looking.
                    lastTextAnalysisYieldedNothing =
                        !DocumentSpeechPolicy.isSpeakable(result.text) ||
                            DocumentSpeechPolicy.readableLines(result.text).isEmpty()
                }
                publishIfCurrent(result, frame.settings, frame.visualGeneration, frame.trace)
                if (frame.mode == AnalysisMode.TEXT_READING) {
                    val spokeNewText =
                        speakDocument(result, frame.settings, frame.visualGeneration, frame.trace)
                    speakSceneTail(
                        result = result,
                        settings = frame.settings,
                        generationAtCapture = frame.visualGeneration,
                        spokeNewText = spokeNewText,
                        trace = frame.trace,
                    )
                }
            } catch (error: OcrTrustRejectedException) {
                DiagnosticHub.record(
                    "OCR_TRUST_REJECTED",
                    frame.trace.fieldsOrEmpty(mapOf("message" to error.spokenMessage)),
                )
                notifyTrustRejection(error, frame.settings, frame.visualGeneration, frame.trace)
            } catch (timeout: TimeoutCancellationException) {
                synchronized(cloudQueueLock) {
                    cloudFailureStreak++
                    resetLaunchIntervalLocked(frame.mode)
                }
                DiagnosticHub.record(
                    "CLOUD_ANALYSIS_TIMED_OUT",
                    frame.trace.fieldsOrEmpty(
                        mapOf(
                            "timeoutMs" to CLOUD_REQUEST_HARD_TIMEOUT_MS,
                            "failureStreak" to cloudFailureStreak,
                        ),
                    ),
                )
                runtime.notice("استغرق التحليل وقتًا أطول من المعتاد، لذلك انتقل VisionBridge إلى أحدث لقطة")
            } catch (error: Throwable) {
                if (error !is CancellationException) {
                    synchronized(cloudQueueLock) {
                        cloudFailureStreak++
                        resetLaunchIntervalLocked(frame.mode)
                    }
                    DiagnosticHub.failure(
                        "CLOUD_ANALYSIS",
                        error,
                        frame.trace.fieldsOrEmpty(mapOf("failureStreak" to cloudFailureStreak)),
                    )
                    runtime.error(error.userMessage())
                } else {
                    DiagnosticHub.record(
                        "CLOUD_ANALYSIS_CANCELLED",
                        frame.trace.fieldsOrEmpty(mapOf("reason" to error.message)),
                    )
                }
            } finally {
                frame.bitmap.recycle()
                val lane = synchronized(cloudQueueLock) {
                    val ownsLane = cloudJob === thisJob
                    val next = if (ownsLane) {
                        cloudJob = null
                        activeCloudMode = null
                        activeCloudFrameId = null
                        activeCloudMayOutliveTarget = false
                        activeCloudStartedAtElapsedMs = 0L
                        lastCloudProgressAtElapsedMs = 0L
                        pendingCloudFrame.also { pendingCloudFrame = null }
                    } else {
                        null
                    }
                    FinishedLane(ownsLane, next)
                }

                if (lane.ownedByFinishingJob) {
                    val next = lane.next
                    if (next != null && pendingFrameStillUseful(next)) {
                        DiagnosticHub.record(
                            "CLOUD_PENDING_FRAME_PROMOTED",
                            next.trace.fieldsOrEmpty(
                                mapOf(
                                    "mode" to next.mode.name,
                                    "reason" to "active_request_finished",
                                    "queuedForMs" to
                                        (SystemClock.elapsedRealtime() - next.queuedAtElapsedMs),
                                ),
                            ),
                        )
                        launchCloud(next)
                    } else {
                        next?.let { stale ->
                            DiagnosticHub.record(
                                "CLOUD_FRAME_DROPPED",
                                stale.trace.fieldsOrEmpty(mapOf("reason" to "stale_visual_generation")),
                            )
                            stale.bitmap.recycle()
                        }
                    }
                }
            }
        }

        synchronized(cloudQueueLock) {
            delayedLaunchJob?.cancel()
            delayedLaunchJob = null
            cloudJob = launched
            activeCloudMode = frame.mode
            activeCloudFrameId = frame.trace?.frameId
            activeCloudMayOutliveTarget = frame.mode == AnalysisMode.SCENE_DESCRIPTION &&
                !frame.settings.interruptSpeechOnVisualChange
            activeCloudStartedAtElapsedMs = SystemClock.elapsedRealtime()
            lastCloudProgressAtElapsedMs = activeCloudStartedAtElapsedMs
            val now = System.currentTimeMillis()
            if (frame.mode == AnalysisMode.TEXT_READING) lastCloudOcrAt = now else lastSceneAt = now
            lastCloudSnapshotAt = now
        }
        DiagnosticHub.record(
            "CLOUD_FRAME_LAUNCHING",
            frame.trace.fieldsOrEmpty(
                mapOf(
                    "mode" to frame.mode.name,
                    "visualGeneration" to frame.visualGeneration,
                    "pendingQueueCapacity" to 1,
                ),
            ),
        )
        launched.start()
    }

    private fun pendingFrameStillUseful(frame: PendingCloudFrame): Boolean =
        frame.mode == AnalysisMode.SCENE_DESCRIPTION && !frame.settings.interruptSpeechOnVisualChange ||
            frame.visualGeneration == visualGeneration.get()

    private fun resetLaunchIntervalLocked(mode: AnalysisMode) {
        if (mode == AnalysisMode.TEXT_READING) lastCloudOcrAt = 0L else lastSceneAt = 0L
    }

    private suspend fun streamAnalysis(
        bitmap: Bitmap,
        mode: AnalysisMode,
        settings: AppSettings,
        apiKey: String,
        generationAtCapture: Long,
        trace: DiagnosticTrace?,
    ): AnalysisResult {
        var firstSpeechBlock = true
        DiagnosticHub.record(
            "GEMINI_ANALYSIS_REQUESTED",
            trace.fieldsOrEmpty(
                mapOf(
                    "mode" to mode.name,
                    "model" to settings.model,
                    "forceCellular" to settings.forceCellular,
                    "sceneDescriptionStyle" to settings.sceneDescriptionStyle.name,
                    "captureProfile" to settings.captureProfile.name,
                    "trustGateEnabled" to settings.trustGateEnabled,
                    "bitmapWidth" to bitmap.width,
                    "bitmapHeight" to bitmap.height,
                ),
            ),
        )
        val rawResult = analyzeFrame(
            bitmap = bitmap,
            mode = mode,
            model = settings.model,
            apiKey = apiKey,
            forceCellular = settings.forceCellular,
            sceneDescriptionStyle = settings.sceneDescriptionStyle,
            captureProfile = settings.captureProfile,
            trustGateEnabled = settings.trustGateEnabled,
            describeAlongsideText = settings.describeAlongsideText,
            onSpeechChunk = speechChunk@{ streamedText, urgent ->
                synchronized(cloudQueueLock) {
                    lastCloudProgressAtElapsedMs = SystemClock.elapsedRealtime()
                }
                // Text reading speaks whole pages once the stream is complete, so streamed blocks
                // only keep the watchdog fed here. Speaking them as they arrived is what allowed a
                // page to be started, cut off, and restarted from the top on the next frame.
                if (mode == AnalysisMode.TEXT_READING) return@speechChunk
                if (!targetMayPublish(settings, generationAtCapture)) {
                    DiagnosticHub.record(
                        "STREAM_CHUNK_REJECTED",
                        trace.fieldsOrEmpty(
                            mapOf(
                                "reason" to "visual_target_changed",
                                "capturedGeneration" to generationAtCapture,
                                "currentGeneration" to visualGeneration.get(),
                                "mode" to settings.mode.name,
                                "networkRequestContinues" to true,
                            ),
                        ),
                    )
                    return@speechChunk
                }

                DiagnosticHub.record(
                    "MODEL_TEXT_CHUNK_EMITTED",
                    trace.fieldsOrEmpty(
                        mapOf(
                            "text" to streamedText,
                            "urgent" to urgent,
                        ),
                    ),
                )
                if (settings.speechEnabled && streamedText.isNotBlank()) {
                    DiagnosticHub.record(
                        "TTS_REQUESTED",
                        trace.fieldsOrEmpty(
                            mapOf(
                                "text" to streamedText,
                                "source" to "GEMINI_STREAM",
                                "rate" to settings.speechRate,
                                "urgent" to urgent,
                                "interruptPrevious" to (urgent && firstSpeechBlock),
                            ),
                        ),
                    )
                    tts.speak(
                        text = streamedText,
                        urgent = urgent,
                        rate = settings.speechRate,
                        interruptPrevious = urgent && firstSpeechBlock,
                    )
                    firstSpeechBlock = false
                }
            },
        )

        DiagnosticHub.record(
            "MODEL_FINAL_TEXT_AVAILABLE",
            trace.fieldsOrEmpty(
                mapOf(
                    "text" to rawResult.text,
                    "source" to rawResult.source.name,
                    "language" to rawResult.language,
                    "urgent" to rawResult.urgent,
                ),
            ),
        )

        // A reading that came back empty, with the frame that produced it still in hand. This is
        // the one moment that separates "the text was never detected" from "it was detected and
        // discarded" — the two explanations for a label that is not read, which need opposite
        // repairs and cannot be told apart from events alone.
        if (mode == AnalysisMode.TEXT_READING && rawResult.text.isBlank() && trace != null) {
            DiagnosticHub.evidence(
                bitmap = bitmap,
                frameId = trace.frameId,
                reason = "recognition_returned_nothing",
                fields = mapOf(
                    "source" to rawResult.source.name,
                    "bitmapWidth" to bitmap.width,
                    "bitmapHeight" to bitmap.height,
                ),
            )
        }

        return rawResult
    }

    private suspend fun notifyTrustRejection(
        error: OcrTrustRejectedException,
        settings: AppSettings,
        generationAtCapture: Long,
        trace: DiagnosticTrace?,
    ) {
        if (!settings.trustGateEnabled) return
        if (!targetMayPublish(settings, generationAtCapture)) {
            DiagnosticHub.record(
                "TRUST_FEEDBACK_SUPPRESSED",
                trace.fieldsOrEmpty(
                    mapOf(
                        "reason" to "stale_visual_generation",
                        "message" to error.spokenMessage,
                    ),
                ),
            )
            return
        }
        if (lastTrustFeedbackGeneration.getAndSet(generationAtCapture) == generationAtCapture) {
            DiagnosticHub.record(
                "TRUST_FEEDBACK_SUPPRESSED",
                trace.fieldsOrEmpty(
                    mapOf(
                        "reason" to "already_spoken_for_generation",
                        "message" to error.spokenMessage,
                    ),
                ),
            )
            return
        }

        runtime.notice(error.spokenMessage)
        DiagnosticHub.record(
            "TRUST_FEEDBACK_DISPLAYED",
            trace.fieldsOrEmpty(mapOf("text" to error.spokenMessage)),
        )
        if (settings.speechEnabled) {
            DiagnosticHub.record(
                "TTS_REQUESTED",
                trace.fieldsOrEmpty(
                    mapOf(
                        "text" to error.spokenMessage,
                        "source" to "TRUST_FEEDBACK",
                        "rate" to settings.speechRate,
                        "interruptPrevious" to settings.interruptSpeechOnVisualChange,
                    ),
                ),
            )
            tts.speakFeedback(
                text = error.spokenMessage,
                rate = settings.speechRate,
                interruptPrevious = settings.interruptSpeechOnVisualChange,
            )
        }
    }

    /**
     * Refuses to re-read a page the user is already hearing, or has just heard.
     *
     * The capture layer deliberately re-offers a motionless screen on a timer so that one truncated
     * response can never permanently silence the rest of a page. Without this gate that safety net
     * became the defect: diagnostics show a single static screen analyzed twenty-five times in one
     * hundred and forty-five seconds, each pass racing the previous one into the same speech queue.
     * Completeness is now owned by [ReadingLedger], which speaks the missing tail of a page instead,
     * so re-analysis while nothing has visually changed has nothing left to contribute.
     */
    private fun shouldDeferStaticTextReading(
        generationAtCapture: Long,
        trace: DiagnosticTrace?,
    ): Boolean {
        val targetChanged = generationAtCapture != activeReadingGeneration
        if (tts.isReadingInProgress() && !targetChanged) {
            DiagnosticHub.record(
                "TEXT_READING_DEFERRED",
                trace.fieldsOrEmpty(
                    mapOf(
                        "reason" to "reading_in_progress_for_same_visual_target",
                        "visualGeneration" to generationAtCapture,
                    ),
                ),
            )
            return true
        }

        // Second gate for the speech-disabled case, where no reading is ever "in progress". A
        // motionless screen still must not be sent to the model twice per five seconds.
        //
        // Unless the last pass over this same target produced nothing. Holding an object still in
        // front of the glasses is precisely how someone asks to have it read, and a bundle from the
        // field shows what the unconditional version of this gate does with that: 788 deferrals
        // under this rule in one session, while the user held a jar of cream in view for ten
        // seconds and heard silence. A target that has been read has nothing left to contribute; a
        // target that yielded nothing has everything left to contribute, and the two were being
        // treated as the same thing. Retry it on the shorter interval instead.
        val sinceLastAnalysisMs = SystemClock.elapsedRealtime() - lastTextAnalysisAtElapsedMs
        val interval = if (lastTextAnalysisYieldedNothing) {
            EMPTY_TEXT_RETRY_INTERVAL_MS
        } else {
            STATIC_TEXT_REANALYSIS_INTERVAL_MS
        }
        if (
            generationAtCapture == lastTextAnalysisGeneration &&
            lastTextAnalysisAtElapsedMs > 0L &&
            sinceLastAnalysisMs < interval
        ) {
            DiagnosticHub.record(
                "TEXT_READING_DEFERRED",
                trace.fieldsOrEmpty(
                    mapOf(
                        "reason" to "static_target_reanalysis_interval",
                        "visualGeneration" to generationAtCapture,
                        "sinceLastAnalysisMs" to sinceLastAnalysisMs,
                        "intervalMs" to interval,
                        "previousPassWasEmpty" to lastTextAnalysisYieldedNothing,
                    ),
                ),
            )
            return true
        }
        return false
    }

    /**
     * Speaks a recognized page exactly once, in visual order, and only the part the user has not
     * heard yet. Text reading intentionally decides after the stream completes rather than speaking
     * partial blocks as they arrive: a page is a document, and half of one read out of order is
     * worse than the same page read a second later in full.
     */
    /** @return true when new text was queued for speech; false when there was nothing new to say. */
    private suspend fun speakDocument(
        result: AnalysisResult,
        settings: AppSettings,
        generationAtCapture: Long,
        trace: DiagnosticTrace?,
    ): Boolean {
        if (!settings.speechEnabled || result.text.isBlank()) return false

        when (val decision = readingLedger.evaluate(result.text)) {
            is ReadingLedger.Decision.Skip -> {
                DiagnosticHub.record(
                    "DOCUMENT_READING_SKIPPED",
                    trace.fieldsOrEmpty(
                        mapOf(
                            "reason" to decision.reason,
                            "text" to result.text,
                            "visualGeneration" to generationAtCapture,
                        ),
                    ),
                )
                return false
            }

            is ReadingLedger.Decision.Speak -> {
                val blocks = documentBlocks(decision.text)
                DiagnosticHub.record(
                    "DOCUMENT_READING_ACCEPTED",
                    trace.fieldsOrEmpty(
                        mapOf(
                            "continuation" to decision.continuation,
                            "text" to decision.text,
                            "characters" to decision.text.length,
                            "blockCount" to blocks.size,
                            "visualGeneration" to generationAtCapture,
                        ),
                    ),
                )
                if (blocks.isEmpty()) return false

                activeReadingGeneration = generationAtCapture
                val readingId = tts.beginReading(
                    interruptPrevious = settings.interruptSpeechOnVisualChange && !decision.continuation,
                )
                // Opened before the first block is queued so no outcome can arrive unaccounted for.
                deliveryTracker.open(readingId, decision.alreadyHeard, blocks)
                blocks.forEachIndexed { index, block ->
                    tts.speakReadingBlock(readingId, index, block, settings.speechRate)
                }
                tts.finishReading(readingId)
                // Nothing is written to the ledger here. What the user heard is known only once the
                // engine reports it, and on a device where the target changes every few hundred
                // milliseconds the two answers differ by most of a page.
                return true
            }
        }
    }

    /**
     * Speaks the one-sentence description that came back with a reading, when it is still worth
     * hearing.
     *
     * The sentence cost nothing extra to produce — it rode along on the request that read the page
     * — but it costs the user seconds to listen to, and those are the seconds in which they are
     * already moving on to the next thing. [HybridReadingPlan] holds the rules; this supplies the
     * three facts only the coordinator knows and carries out the answer.
     */
    private suspend fun speakSceneTail(
        result: AnalysisResult,
        settings: AppSettings,
        generationAtCapture: Long,
        spokeNewText: Boolean,
        trace: DiagnosticTrace?,
    ) {
        if (result.sceneTail.isBlank()) return

        val plan = HybridReadingPlan.plan(
            text = result.text,
            description = result.sceneTail,
            textAlreadyRead = !spokeNewText,
            subjectAlreadyDescribed = lastDescribedGeneration == generationAtCapture,
            subjectUnchanged = generationAtCapture == visualGeneration.get(),
        )
        val tail = plan.utterances.lastOrNull()?.takeIf { it.isDescription }

        DiagnosticHub.record(
            "SCENE_TAIL_PLANNED",
            trace.fieldsOrEmpty(
                mapOf(
                    "reason" to plan.reason,
                    "spoken" to (tail != null),
                    "text" to result.sceneTail,
                    "characters" to result.sceneTail.length,
                    "visualGeneration" to generationAtCapture,
                    "currentGeneration" to visualGeneration.get(),
                ),
            ),
        )
        if (tail == null) return

        lastDescribedGeneration = generationAtCapture
        if (!settings.speechEnabled) return
        DiagnosticHub.record(
            "TTS_REQUESTED",
            trace.fieldsOrEmpty(
                mapOf(
                    "text" to tail.text,
                    "source" to "SCENE_TAIL",
                    "rate" to settings.speechRate,
                    // Never interrupts: the reading it follows is the thing the user asked for.
                    "interruptPrevious" to false,
                ),
            ),
        )
        tts.speakFeedback(text = tail.text, rate = settings.speechRate, interruptPrevious = false)
    }

    /** Splits an accepted page into ordered speech blocks with document-sized phrasing. */
    private fun documentBlocks(text: String): List<String> {
        val buffer = StreamingSpeechBuffer(StreamingSpeechBuffer.Profile.DOCUMENT)
        return buffer.append(text, urgent = false) + buffer.finish()
    }

    private fun targetMayPublish(settings: AppSettings, generationAtCapture: Long): Boolean {
        val targetCurrent = generationAtCapture == visualGeneration.get()
        val mayPublishStaleScene =
            settings.mode == AnalysisMode.SCENE_DESCRIPTION && !settings.interruptSpeechOnVisualChange
        return targetCurrent || mayPublishStaleScene
    }

    private fun publishIfCurrent(
        result: AnalysisResult,
        settings: AppSettings,
        generationAtCapture: Long,
        trace: DiagnosticTrace?,
    ) {
        if (result.text.isBlank()) {
            DiagnosticHub.record(
                "FINAL_RESULT_SUPPRESSED",
                trace.fieldsOrEmpty(mapOf("reason" to "no_text_recognized")),
            )
            return
        }

        // A scene description is a live safety statement, so a stale one is withheld. Recognized
        // text is not: it is still the correct transcription of the page the user asked about, and
        // withholding it left the screen blank after the work had already been paid for.
        val mayPublish = settings.mode == AnalysisMode.TEXT_READING ||
            targetMayPublish(settings, generationAtCapture)
        if (mayPublish) {
            runtime.result(result)
            DiagnosticHub.record(
                "TEXT_DISPLAYED",
                trace.fieldsOrEmpty(
                    mapOf(
                        "text" to result.text,
                        "source" to result.source.name,
                        "language" to result.language,
                        "urgent" to result.urgent,
                        "staleVisualGeneration" to !targetMayPublish(settings, generationAtCapture),
                    ),
                ),
            )
        } else {
            DiagnosticHub.record(
                "FINAL_RESULT_SUPPRESSED",
                trace.fieldsOrEmpty(
                    mapOf(
                        "reason" to "stale_visual_generation",
                        "text" to result.text,
                        "capturedGeneration" to generationAtCapture,
                        "currentGeneration" to visualGeneration.get(),
                    ),
                ),
            )
        }
    }

    /**
     * A visual change invalidates output freshness and the old pending snapshot. It does not cancel
     * the active cloud request; that request finishes silently, releases its resources and immediately
     * promotes the newest pending frame. Only reset and the hard watchdog may cancel an active job.
     */
    fun onVisualTargetChanged(interruptSpeech: Boolean) {
        val newGeneration = visualGeneration.incrementAndGet()
        val now = SystemClock.elapsedRealtime()
        DiagnosticHub.record(
            "VISUAL_TARGET_CHANGED",
            mapOf(
                "newGeneration" to newGeneration,
                "interruptSpeech" to interruptSpeech,
                "activeCloudFrameId" to activeCloudFrameId,
                "activeRequestWillContinue" to true,
            ),
        )
        lastCloudOcrAt = 0L
        lastSceneAt = 0L
        lastCloudSnapshotAt = 0L
        // The page that was being read is no longer in front of the user, so the next recognition
        // is allowed to open a new reading instead of being treated as a re-read of this one.
        activeReadingGeneration = Long.MIN_VALUE

        if (interruptSpeech && now - lastSpeechInterruptAtElapsedMs >= SPEECH_INTERRUPT_COOLDOWN_MS) {
            lastSpeechInterruptAtElapsedMs = now
            tts.onVisualTargetChanged(true)
        }

        var abandoned: Job? = null
        synchronized(cloudQueueLock) {
            pendingCloudFrame?.let { pending ->
                DiagnosticHub.record(
                    "CLOUD_FRAME_DROPPED",
                    pending.trace.fieldsOrEmpty(
                        mapOf(
                            "reason" to "visual_target_changed_pending_only",
                            "activeCloudMode" to activeCloudMode?.name,
                        ),
                    ),
                )
                pending.bitmap.recycle()
            }
            pendingCloudFrame = null
            if (cloudJob?.isCompleted == false) {
                // A request for a subject the user has walked away from is work nobody will hear.
                // It used to be left running on the reasoning that target changes were often
                // spurious and cancelling everything would starve the analysis — and they were:
                // 460 requests were marked stale across one bundle, 431 of them in a single
                // session where the "changes" were one bottle being held still. Now that a target
                // change means the subject really was replaced, holding the lane, the cellular
                // lease and the radio for the old one is simply a delay before the new one starts.
                val mayOutlive = activeCloudMayOutliveTarget
                if (!mayOutlive) abandoned = cloudJob
                DiagnosticHub.record(
                    "CLOUD_ACTIVE_REQUEST_MARKED_STALE",
                    mapOf(
                        "activeCloudMode" to activeCloudMode?.name,
                        "activeFrameId" to activeCloudFrameId,
                        "newGeneration" to newGeneration,
                        "requestCancelled" to !mayOutlive,
                    ),
                )
            }
        }
        abandoned?.cancel(CancellationException("visual_target_changed"))
    }

    private fun runHealthCheck() {
        val now = SystemClock.elapsedRealtime()
        var timedOutJob: Job? = null
        var orphanedPending = false
        val snapshot = synchronized(cloudQueueLock) {
            val job = cloudJob
            val active = job?.isCompleted == false
            val activeAgeMs = if (active && activeCloudStartedAtElapsedMs > 0L) {
                now - activeCloudStartedAtElapsedMs
            } else {
                0L
            }
            val progressAgeMs = if (active && lastCloudProgressAtElapsedMs > 0L) {
                now - lastCloudProgressAtElapsedMs
            } else {
                0L
            }
            if (active && activeAgeMs >= CLOUD_WATCHDOG_TIMEOUT_MS) {
                timedOutJob = job
            }
            orphanedPending = !active && pendingCloudFrame != null
            mapOf(
                "active" to active,
                "activeFrameId" to activeCloudFrameId,
                "activeMode" to activeCloudMode?.name,
                "activeAgeMs" to activeAgeMs,
                "progressAgeMs" to progressAgeMs,
                "pending" to (pendingCloudFrame != null),
                "pendingFrameId" to pendingCloudFrame?.trace?.frameId,
                "failureStreak" to cloudFailureStreak,
                "lastCompletionAgeMs" to if (lastCloudCompletedAtElapsedMs > 0L) {
                    now - lastCloudCompletedAtElapsedMs
                } else {
                    null
                },
            )
        }

        if (now - lastHealthSnapshotAtElapsedMs >= HEALTH_SNAPSHOT_INTERVAL_MS) {
            lastHealthSnapshotAtElapsedMs = now
            DiagnosticHub.record("CLOUD_HEALTH_SNAPSHOT", snapshot)
        }

        timedOutJob?.let { job ->
            DiagnosticHub.record(
                "CLOUD_WATCHDOG_RECOVERY",
                snapshot + mapOf(
                    "action" to "cancel_hard_timeout_and_promote_latest",
                    "timeoutMs" to CLOUD_WATCHDOG_TIMEOUT_MS,
                ),
            )
            job.cancel(CancellationException("cloud_watchdog_hard_timeout"))
        }
        if (orphanedPending) promotePendingIfIdle("watchdog_orphan_recovery")
    }

    /** Speaks an operational notice that is not recognized content, such as a blank capture. */
    suspend fun speakNotice(message: String) {
        val settings = settingsRepository.settings.first()
        if (!settings.speechEnabled) return
        tts.speakFeedback(text = message, rate = settings.speechRate, interruptPrevious = true)
    }

    fun stopSpeech() {
        DiagnosticHub.record("TTS_STOP_REQUESTED", mapOf("reason" to "capture_stopped"))
        tts.stop()
    }

    fun reset() {
        val generation = visualGeneration.incrementAndGet()
        DiagnosticHub.record("COORDINATOR_RESET", mapOf("newGeneration" to generation))
        lastTrustFeedbackGeneration.set(Long.MIN_VALUE)
        lastCloudOcrAt = 0L
        lastSceneAt = 0L
        lastCloudSnapshotAt = 0L
        lastSpeechInterruptAtElapsedMs = 0L
        activeReadingGeneration = Long.MIN_VALUE
        lastTextAnalysisGeneration = Long.MIN_VALUE
        lastTextAnalysisAtElapsedMs = 0L
        lastTextAnalysisYieldedNothing = false
        readingLedger.reset()
        deliveryTracker.reset()
        synchronized(cloudQueueLock) {
            delayedLaunchJob?.cancel()
            delayedLaunchJob = null
            cloudJob?.cancel(CancellationException("coordinator_reset"))
            cloudJob = null
            activeCloudMode = null
            activeCloudFrameId = null
            activeCloudMayOutliveTarget = false
            activeCloudStartedAtElapsedMs = 0L
            lastCloudProgressAtElapsedMs = 0L
            pendingCloudFrame?.let { pending ->
                DiagnosticHub.record(
                    "CLOUD_FRAME_DROPPED",
                    pending.trace.fieldsOrEmpty(mapOf("reason" to "coordinator_reset")),
                )
                pending.bitmap.recycle()
            }
            pendingCloudFrame = null
            cloudFailureStreak = 0
        }
        tts.stop()
        tts.resetHistory()
    }

    private fun DiagnosticTrace?.fieldsOrEmpty(
        extra: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> = this?.fields(extra) ?: extra

    private fun Throwable.userMessage(): String =
        message?.takeIf { it.isNotBlank() } ?: "تعذر تحليل أحدث لقطة"

    private companion object {
        const val STABLE_PENDING_SNAPSHOT_INTERVAL_MS = 1_000L
        const val FAST_PENDING_SNAPSHOT_INTERVAL_MS = 650L
        const val SCENE_PENDING_SNAPSHOT_INTERVAL_MS = 500L
        const val BRIEF_SCENE_INTERVAL_MS = 650L
        const val COMPREHENSIVE_SCENE_INTERVAL_MS = 900L
        const val STABLE_LAUNCH_INTERVAL_MS = 850L
        const val FAST_LAUNCH_INTERVAL_MS = 650L

        /**
         * Floor between two analyses of a visually identical page. It only applies while nothing has
         * changed on screen, so a genuine page turn is still read immediately.
         */
        const val STATIC_TEXT_REANALYSIS_INTERVAL_MS = 15_000L

        /**
         * How soon a motionless subject that yielded nothing is tried again. Short enough that
         * holding a jar of cream up feels like it is being looked at, long enough that a genuinely
         * blank wall is not analysed continuously.
         */
        const val EMPTY_TEXT_RETRY_INTERVAL_MS = 1_800L

        /**
         * Bounds for one request, ordered so each is the backstop for the one before it.
         *
         * The analysis budget inside [com.abdullah.visionbridge.data.network.CellularNetworkManager]
         * is 24 s and should always be what fires. These sit just above it, close enough that a
         * request which slips past the budget is caught in seconds rather than minutes. They used
         * to be 42 s and 48 s, which on a device that stopped scheduling meant a request was
         * finally noticed at 221.6 s — the watchdog cannot be the thing that saves you if it is
         * asleep too, so the frame path now checks these as well.
         */
        const val CLOUD_REQUEST_HARD_TIMEOUT_MS = 27_000L
        const val CLOUD_WATCHDOG_TIMEOUT_MS = 30_000L
        const val HEALTH_CHECK_INTERVAL_MS = 5_000L
        const val HEALTH_SNAPSHOT_INTERVAL_MS = 30_000L
        const val SPEECH_INTERRUPT_COOLDOWN_MS = 1_200L

    }
}
