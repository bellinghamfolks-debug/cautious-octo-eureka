package com.abdullah.visionbridge.capture

import android.graphics.Bitmap
import android.os.SystemClock
import com.abdullah.visionbridge.data.diagnostics.DiagnosticHub
import com.abdullah.visionbridge.data.diagnostics.DiagnosticTrace
import com.abdullah.visionbridge.data.gemini.OcrTrustRejectedException
import com.abdullah.visionbridge.data.gemini.StreamingSpeechBuffer
import com.abdullah.visionbridge.data.speech.BilingualTtsEngine
import com.abdullah.visionbridge.data.speech.ReadingLedger
import com.abdullah.visionbridge.domain.model.AnalysisMode
import com.abdullah.visionbridge.domain.model.AnalysisResult
import com.abdullah.visionbridge.domain.model.AppSettings
import com.abdullah.visionbridge.domain.model.CaptureProfile
import com.abdullah.visionbridge.domain.model.SceneDescriptionStyle
import com.abdullah.visionbridge.domain.repository.ApiKeyStore
import com.abdullah.visionbridge.domain.repository.SettingsRepository
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
 * engine serves it. A visual target change marks an active result stale but never cancels a healthy
 * request, so repeated target changes cannot starve the analysis by cancelling every request before
 * it produces anything.
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

    /** Visual generation that the reading currently being spoken belongs to. */
    @Volatile
    private var activeReadingGeneration = Long.MIN_VALUE

    /** Visual generation of the last completed text analysis, and when it completed. */
    @Volatile
    private var lastTextAnalysisGeneration = Long.MIN_VALUE

    @Volatile
    private var lastTextAnalysisAtElapsedMs = 0L

    private var lastCloudOcrAt = 0L
    private var lastSceneAt = 0L
    private var lastCloudSnapshotAt = 0L
    private var cloudJob: Job? = null
    private var delayedLaunchJob: Job? = null
    private var activeCloudMode: AnalysisMode? = null
    private var activeCloudFrameId: String? = null
    private var activeCloudStartedAtElapsedMs = 0L
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
    }

    suspend fun process(bitmap: Bitmap) = processMutex.withLock {
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
            throw IllegalStateException("أدخل مفتاح Gemini أولاً لاستخدام وصف المشهد")
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
                }
                publishIfCurrent(result, frame.settings, frame.visualGeneration, frame.trace)
                if (frame.mode == AnalysisMode.TEXT_READING) {
                    speakDocument(result, frame.settings, frame.visualGeneration, frame.trace)
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
                runtime.notice("تأخر التحليل؛ انتقلت تلقائياً إلى أحدث صورة")
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
        val sinceLastAnalysisMs = SystemClock.elapsedRealtime() - lastTextAnalysisAtElapsedMs
        if (
            generationAtCapture == lastTextAnalysisGeneration &&
            lastTextAnalysisAtElapsedMs > 0L &&
            sinceLastAnalysisMs < STATIC_TEXT_REANALYSIS_INTERVAL_MS
        ) {
            DiagnosticHub.record(
                "TEXT_READING_DEFERRED",
                trace.fieldsOrEmpty(
                    mapOf(
                        "reason" to "static_target_reanalysis_interval",
                        "visualGeneration" to generationAtCapture,
                        "sinceLastAnalysisMs" to sinceLastAnalysisMs,
                        "intervalMs" to STATIC_TEXT_REANALYSIS_INTERVAL_MS,
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
    private suspend fun speakDocument(
        result: AnalysisResult,
        settings: AppSettings,
        generationAtCapture: Long,
        trace: DiagnosticTrace?,
    ) {
        if (!settings.speechEnabled || result.text.isBlank()) return

        when (val decision = readingLedger.evaluate(result.text)) {
            is ReadingLedger.Decision.Skip -> DiagnosticHub.record(
                "DOCUMENT_READING_SKIPPED",
                trace.fieldsOrEmpty(
                    mapOf(
                        "reason" to decision.reason,
                        "text" to result.text,
                        "visualGeneration" to generationAtCapture,
                    ),
                ),
            )

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
                if (blocks.isEmpty()) return

                activeReadingGeneration = generationAtCapture
                val readingId = tts.beginReading(
                    interruptPrevious = settings.interruptSpeechOnVisualChange && !decision.continuation,
                )
                blocks.forEach { block ->
                    tts.speakReadingBlock(readingId, block, settings.speechRate)
                }
                tts.finishReading(readingId)
                readingLedger.recordSpoken(decision.document)
            }
        }
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
                DiagnosticHub.record(
                    "CLOUD_ACTIVE_REQUEST_MARKED_STALE",
                    mapOf(
                        "activeCloudMode" to activeCloudMode?.name,
                        "activeFrameId" to activeCloudFrameId,
                        "newGeneration" to newGeneration,
                        "requestCancelled" to false,
                    ),
                )
            }
        }
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
        readingLedger.reset()
        synchronized(cloudQueueLock) {
            delayedLaunchJob?.cancel()
            delayedLaunchJob = null
            cloudJob?.cancel(CancellationException("coordinator_reset"))
            cloudJob = null
            activeCloudMode = null
            activeCloudFrameId = null
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
        message?.takeIf { it.isNotBlank() } ?: "تعذر تحليل إطار الشاشة"

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

        const val CLOUD_REQUEST_HARD_TIMEOUT_MS = 42_000L
        const val CLOUD_WATCHDOG_TIMEOUT_MS = 48_000L
        const val HEALTH_CHECK_INTERVAL_MS = 5_000L
        const val HEALTH_SNAPSHOT_INTERVAL_MS = 30_000L
        const val SPEECH_INTERRUPT_COOLDOWN_MS = 1_200L

    }
}
