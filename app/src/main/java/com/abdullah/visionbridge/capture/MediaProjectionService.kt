package com.abdullah.visionbridge.capture

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.abdullah.visionbridge.R
import com.abdullah.visionbridge.VisionBridgeApp
import com.abdullah.visionbridge.accessibility.EvidenceShortcut
import com.abdullah.visionbridge.data.diagnostics.DiagnosticHub
import com.abdullah.visionbridge.data.diagnostics.DiagnosticTrace
import com.abdullah.visionbridge.domain.model.AnalysisMode
import com.abdullah.visionbridge.domain.model.AppSettings
import com.abdullah.visionbridge.domain.model.CaptureProfile
import com.abdullah.visionbridge.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class MediaProjectionService : Service() {
    private data class PendingFrame(
        val bitmap: Bitmap,
        val trace: DiagnosticTrace,
    )

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val processing = AtomicBoolean(false)
    private val frameSequence = AtomicLong(0L)
    private val frameChangeDetector = FrameChangeDetector()

    /**
     * One tracker per mode, because a scene and a page tolerate very different amounts of residual
     * change. Switching mode leaves the other tracker without a reference, which is the right
     * answer: the first frame after a mode change genuinely is a new target.
     */
    private val textTargetTracker = VisualTargetTracker(
        maximumDissimilarity = TEXT_TARGET_DISSIMILARITY,
        maximumChromaDifference = TEXT_TARGET_CHROMA,
    )
    private val sceneTargetTracker = VisualTargetTracker(
        maximumDissimilarity = SCENE_TARGET_DISSIMILARITY,
        maximumChromaDifference = SCENE_TARGET_CHROMA,
    )
    private val frameQueueLock = Any()

    private val container by lazy { (application as VisionBridgeApp).container }
    private val projectionManager by lazy { getSystemService(MediaProjectionManager::class.java) }
    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }

    @Volatile
    private var activeSettings = AppSettings()

    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var frameJob: Job? = null
    private var settingsJob: Job? = null
    private var pendingFrame: PendingFrame? = null
    private var lastFrameAt = 0L
    private var lastPreviewAt = 0L
    private var diagnosticSessionOpen = false
    private var unavailableFeedSince = 0L
    private var lastUnavailableFeedNoticeAt = 0L
    private var appliedCaptureWidth = 0
    private var appliedCaptureHeight = 0
    private var lastResizeAtElapsedMs = 0L
    private var lastSurfaceRecoveryAtElapsedMs = 0L

    /** Rebuilding the notification before it exists is a no-op that logs a warning; this avoids it. */
    private var foregroundStarted = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            DiagnosticHub.record("PROJECTION_SYSTEM_STOPPED")
            releaseProjection(stopProjection = false, reason = "system_stopped_projection")
            // Said out loud, and said after the release so the stop-speech inside it cannot cut the
            // notice off. A device session shows the projection dying and then 216 seconds of
            // silence: the message went to a screen, and the person holding the glasses had no way
            // to learn that the app had stopped working. It is spoken on the engine's own scope,
            // which outlives this service.
            container.tts.speakUrgentNotice(PROJECTION_STOPPED_MESSAGE)
            container.runtime.stopped(PROJECTION_STOPPED_MESSAGE)
            stopSelf()
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            DiagnosticHub.record(
                "CAPTURE_CONTENT_RESIZED",
                mapOf("width" to width, "height" to height, "sdk" to Build.VERSION.SDK_INT),
            )
            if (Build.VERSION.SDK_INT >= 34 && width > 0 && height > 0) {
                captureHandler?.post { resizeCapture(width, height) }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        captureThread = HandlerThread("vision-capture").also { it.start() }
        captureHandler = Handler(captureThread!!.looper)
        settingsJob = serviceScope.launch {
            container.settingsRepository.settings.collectLatest { newSettings ->
                if (newSettings != activeSettings) {
                    DiagnosticHub.record("SETTINGS_CHANGED", settingsMap(newSettings))
                }
                DiagnosticHub.setEvidenceCapture(newSettings.captureFailureEvidence)
                val evidenceChanged =
                    newSettings.captureFailureEvidence != activeSettings.captureFailureEvidence
                activeSettings = newSettings
                // The notification action carries the state in its label, and that label is the
                // only readout a user gets who reaches the switch through the shade rather than
                // through the accessibility button.
                if (evidenceChanged && foregroundStarted) startAsForeground()
            }
        }
        DiagnosticHub.record("CAPTURE_SERVICE_CREATED")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        DiagnosticHub.record(
            "CAPTURE_SERVICE_COMMAND",
            mapOf("action" to intent?.action, "flags" to flags, "startId" to startId),
        )
        when (intent?.action) {
            ACTION_START -> {
                startAsForeground()
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData = intent.parcelableIntentExtra(EXTRA_RESULT_DATA)
                if (resultCode == Activity.RESULT_OK && resultData != null) {
                    startProjection(resultCode, resultData)
                } else {
                    DiagnosticHub.record("PROJECTION_PERMISSION_REJECTED", mapOf("resultCode" to resultCode))
                    container.runtime.error("لم تمنح إذن مشاركة الشاشة")
                    stopSelf()
                }
            }
            ACTION_MARK_PROBLEM -> {
                DiagnosticHub.markProblemAsync("تم تحديد لحظة المشكلة من إشعار VisionBridge")
                DiagnosticHub.record("PROBLEM_MARKED_FROM_NOTIFICATION")
                container.runtime.notice("تم تسجيل لحظة المشكلة في ملف التشخيص")
            }
            ACTION_TOGGLE_EVIDENCE -> toggleEvidenceCapture()
            ACTION_STOP -> {
                releaseProjection(stopProjection = true, reason = "user_stopped_capture")
                container.runtime.stopped()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * The on-device model is the largest allocation this process ever makes. Holding
     * it after capture ends is what gets a foreground service killed, so it is
     * unloaded on the way out rather than left for the garbage collector.
     */
    override fun onDestroy() {
        releaseProjection(stopProjection = true, reason = "service_destroyed")
        settingsJob?.cancel()
        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null
        // Bounded, because releasing now waits for any read in flight and this runs on the main
        // thread: an unbounded wait here would be an ANR. If the reader is still busy the sessions
        // are left loaded — they are reused if capture restarts, and reclaimed with the process
        // otherwise, so nothing leaks by giving up.
        runBlocking {
            withTimeoutOrNull(RELEASE_ON_DESTROY_TIMEOUT_MS) {
                container.localOcrEngine.release("capture_service_destroyed")
            } ?: DiagnosticHub.record(
                "PPOCR_RELEASE_ON_DESTROY_TIMED_OUT",
                mapOf("timeoutMs" to RELEASE_ON_DESTROY_TIMEOUT_MS),
            )
        }
        DiagnosticHub.record("CAPTURE_SERVICE_DESTROYED")
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level < TRIM_MEMORY_RUNNING_LOW) return
        DiagnosticHub.record(
            "PPOCR_MEMORY_PRESSURE",
            mapOf("trimLevel" to level, "engineLoaded" to container.localOcrEngine.isLoaded),
        )
        // Release the sessions rather than be killed mid-sentence. The next frame reloads them,
        // which for four small models is a fraction of a second.
        serviceScope.launch { container.localOcrEngine.release("system_memory_pressure") }
    }

    private fun startProjection(resultCode: Int, resultData: Intent) {
        if (mediaProjection != null) {
            DiagnosticHub.record("PROJECTION_START_IGNORED", mapOf("reason" to "already_running"))
            return
        }
        runCatching {
            runBlocking(Dispatchers.IO) {
                DiagnosticHub.startSession(settingsMap(activeSettings))
            }
            diagnosticSessionOpen = true

            val projection = projectionManager.getMediaProjection(resultCode, resultData)
                ?: error("تعذر إنشاء جلسة MediaProjection")
            mediaProjection = projection
            projection.registerCallback(projectionCallback, captureHandler)

            val (width, height, density) = initialCaptureSize()
            val reader = createImageReader(width, height)
            imageReader = reader
            virtualDisplay = projection.createVirtualDisplay(
                "VisionBridgeCapture",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                captureHandler,
            ) ?: error("تعذر إنشاء سطح MediaProjection")

            appliedCaptureWidth = width
            appliedCaptureHeight = height
            resetFrameState()
            container.coordinator.reset()
            container.runtime.started()
            DiagnosticHub.record(
                "PROJECTION_STARTED",
                settingsMap(activeSettings) + mapOf(
                    "width" to width,
                    "height" to height,
                    "densityDpi" to density,
                    "imageReaderMaxImages" to 3,
                ),
            )
        }.onFailure { error ->
            DiagnosticHub.failure("START_PROJECTION", error, settingsMap(activeSettings))
            container.runtime.error(error.message ?: "تعذر بدء مشاركة الشاشة")
            releaseProjection(stopProjection = true, reason = "projection_start_failed")
            stopSelf()
        }
    }

    private fun createImageReader(width: Int, height: Int): ImageReader =
        ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3).apply {
            setOnImageAvailableListener({ reader -> consumeLatestFrame(reader) }, captureHandler)
        }

    private fun consumeLatestFrame(reader: ImageReader) {
        val acquiredAtEpochMs = System.currentTimeMillis()
        val acquiredAtElapsedNanos = SystemClock.elapsedRealtimeNanos()
        val frameId = String.format(Locale.US, "F%09d", frameSequence.incrementAndGet())
        val trace = DiagnosticTrace(
            traceId = "T-$frameId-$acquiredAtElapsedNanos",
            frameId = frameId,
            capturedAtEpochMs = acquiredAtEpochMs,
            capturedAtElapsedNanos = acquiredAtElapsedNanos,
        )
        DiagnosticHub.observeTrace(trace)

        val image = runCatching { reader.acquireLatestImage() }.getOrElse { error ->
            DiagnosticHub.failure("ACQUIRE_LATEST_IMAGE", error, trace.fields())
            return
        } ?: return

        val conversionStarted = SystemClock.elapsedRealtimeNanos()
        val bitmap = try {
            ImageFrameConverter.toBitmap(image)
        } catch (error: Throwable) {
            image.close()
            DiagnosticHub.failure("IMAGE_TO_BITMAP", error, trace.fields())
            container.runtime.error(error.message ?: "تعذر تجهيز لقطة الشاشة للتحليل")
            return
        }
        image.close()
        val conversionMs = (SystemClock.elapsedRealtimeNanos() - conversionStarted) / 1_000_000.0
        val settings = activeSettings
        val now = System.currentTimeMillis()

        DiagnosticHub.record(
            "FRAME_ACQUIRED",
            trace.fields(
                mapOf(
                    "width" to bitmap.width,
                    "height" to bitmap.height,
                    "allocationBytes" to bitmap.allocationByteCount,
                    "conversionMs" to conversionMs,
                    "mode" to settings.mode.name,
                    "captureProfile" to settings.captureProfile.name,
                ),
            ),
        )

        val minimumInterval = when {
            settings.mode == AnalysisMode.SCENE_DESCRIPTION -> SCENE_FRAME_INTERVAL_MS
            settings.captureProfile == CaptureProfile.FAST_TEXT -> FAST_FRAME_INTERVAL_MS
            else -> STABLE_FRAME_INTERVAL_MS
        }
        val intervalSincePrevious = if (lastFrameAt == 0L) Long.MAX_VALUE else now - lastFrameAt
        if (intervalSincePrevious < minimumInterval) {
            recordDroppedFrame(
                bitmap,
                trace,
                "capture_interval_throttle",
                mapOf(
                    "minimumIntervalMs" to minimumInterval,
                    "actualIntervalMs" to intervalSincePrevious,
                ),
            )
            bitmap.recycle()
            return
        }

        val detectorStarted = SystemClock.elapsedRealtimeNanos()
        val changeDecision = when {
            settings.mode == AnalysisMode.SCENE_DESCRIPTION -> frameChangeDetector.evaluateFast(
                bitmap = bitmap,
                minimumMeanDifference = SCENE_MIN_MEAN_DIFFERENCE,
                minimumChangedRatio = SCENE_MIN_CHANGED_RATIO,
            )
            settings.captureProfile == CaptureProfile.STABLE -> frameChangeDetector.evaluateStable(
                bitmap = bitmap,
                minimumMeanDifference = STABLE_MIN_MEAN_DIFFERENCE,
                minimumChangedRatio = STABLE_MIN_CHANGED_RATIO,
                stableForMs = STABLE_FRAME_DURATION_MS,
                now = now,
            )
            else -> frameChangeDetector.evaluateFast(
                bitmap = bitmap,
                minimumMeanDifference = FAST_MIN_MEAN_DIFFERENCE,
                minimumChangedRatio = FAST_MIN_CHANGED_RATIO,
            )
        }
        val detectorMs = (SystemClock.elapsedRealtimeNanos() - detectorStarted) / 1_000_000.0
        val changeThresholds = when {
            settings.mode == AnalysisMode.SCENE_DESCRIPTION -> Pair(
                SCENE_MIN_MEAN_DIFFERENCE,
                SCENE_MIN_CHANGED_RATIO,
            )
            settings.captureProfile == CaptureProfile.STABLE -> Pair(
                STABLE_MIN_MEAN_DIFFERENCE,
                STABLE_MIN_CHANGED_RATIO,
            )
            else -> Pair(FAST_MIN_MEAN_DIFFERENCE, FAST_MIN_CHANGED_RATIO)
        }
        val changeFields = mapOf(
            "shouldAnalyze" to changeDecision.accepted,
            "decisionReason" to changeDecision.reason,
            "meanAbsoluteDifference" to changeDecision.meanAbsoluteDifference,
            "changedPixelRatio" to changeDecision.changedPixelRatio,
            "minimumMeanDifference" to changeThresholds.first,
            "minimumChangedRatio" to changeThresholds.second,
            "candidateMeanAbsoluteDifference" to changeDecision.candidateMeanAbsoluteDifference,
            "candidateChangedPixelRatio" to changeDecision.candidateChangedPixelRatio,
            "candidateStableElapsedMs" to changeDecision.candidateStableElapsedMs,
            "requiredStableMs" to if (settings.captureProfile == CaptureProfile.STABLE) STABLE_FRAME_DURATION_MS else 0L,
            "thresholdLogic" to if (settings.captureProfile == CaptureProfile.STABLE) "AND" else "OR",
            "detectorMs" to detectorMs,
            "mode" to settings.mode.name,
            "captureProfile" to settings.captureProfile.name,
        )

        DiagnosticHub.record("FRAME_CHANGE_DECISION", trace.fields(changeFields))
        observeVisualFeedHealth(changeDecision, now, trace)

        if (!changeDecision.accepted) {
            // A frame the app calls unusable is exactly the frame worth keeping: "the mirror is
            // black" and "the room is dark" produce the same measurements and need opposite
            // repairs, and only the picture separates them.
            if (changeDecision.reason.startsWith("quality_")) {
                DiagnosticHub.evidence(
                    bitmap = bitmap,
                    frameId = trace.frameId,
                    reason = changeDecision.reason,
                    fields = mapOf(
                        "captureWidth" to appliedCaptureWidth,
                        "captureHeight" to appliedCaptureHeight,
                        "sinceLastResizeMs" to
                            if (lastResizeAtElapsedMs > 0L) {
                                SystemClock.elapsedRealtime() - lastResizeAtElapsedMs
                            } else {
                                null
                            },
                    ),
                )
            }
            recordDroppedFrame(bitmap, trace, "change_detector_rejected", changeFields)
            bitmap.recycle()
            return
        }
        unavailableFeedSince = 0L
        lastFrameAt = now

        val scene = settings.mode == AnalysisMode.SCENE_DESCRIPTION
        val tracker = if (scene) sceneTargetTracker else textTargetTracker
        val trackingStarted = SystemClock.elapsedRealtimeNanos()
        val targetDecision = tracker.evaluate(BitmapFrames.trackedFrame(bitmap))
        val trackingMs = (SystemClock.elapsedRealtimeNanos() - trackingStarted) / 1_000_000.0
        val visualTargetChanged = targetDecision.targetChanged
        DiagnosticHub.record(
            "VISUAL_TARGET_DECISION",
            trace.fields(
                mapOf(
                    "targetChanged" to visualTargetChanged,
                    "decisionReason" to targetDecision.reason,
                    "targetTrackId" to targetDecision.trackId,
                    // What is left after the frames have been registered onto each other, measured
                    // structurally rather than as a pixel difference. The unaligned figure is kept
                    // beside it because it is what the previous build decided on, so a bundle from
                    // the field shows directly how often the two disagree.
                    "structuralDissimilarity" to targetDecision.dissimilarity,
                    "unalignedDissimilarity" to targetDecision.unalignedDissimilarity,
                    "chromaDifference" to targetDecision.chromaDifference,
                    "alignmentCoverage" to targetDecision.coverage,
                    "registrationMethod" to targetDecision.method,
                    "motionTranslationX" to targetDecision.translationX,
                    "motionTranslationY" to targetDecision.translationY,
                    "motionScale" to targetDecision.scale,
                    "motionRotationDegrees" to targetDecision.rotationDegrees,
                    "motionProjective" to targetDecision.projective,
                    "featureInlierRatio" to targetDecision.featureInlierRatio,
                    "consecutiveCandidateFrames" to targetDecision.consecutiveCandidateFrames,
                    "maximumDissimilarity" to
                        if (scene) SCENE_TARGET_DISSIMILARITY else TEXT_TARGET_DISSIMILARITY,
                    "trackingMs" to trackingMs,
                ),
            ),
        )
        if (visualTargetChanged) {
            container.coordinator.onVisualTargetChanged(settings.interruptSpeechOnVisualChange)
        }

        DiagnosticHub.frame(
            bitmap = bitmap,
            frameId = frameId,
            stage = "selected_input",
            metadata = trace.fields(
                mapOf(
                    "mode" to settings.mode.name,
                    "captureProfile" to settings.captureProfile.name,
                    "targetChanged" to visualTargetChanged,
                    "frameMeanAbsoluteDifference" to changeDecision.meanAbsoluteDifference,
                    "frameChangedPixelRatio" to changeDecision.changedPixelRatio,
                ),
            ),
        )
        DiagnosticHub.record("FRAME_SELECTED_FOR_ANALYSIS", trace.fields())
        submitLatestFrame(PendingFrame(bitmap, trace))
    }

    private fun observeVisualFeedHealth(
        decision: FrameChangeDetector.Decision,
        now: Long,
        trace: DiagnosticTrace,
    ) {
        val unavailable = decision.reason == "quality_almost_black" ||
            decision.reason == "quality_blank_low_contrast"
        if (!unavailable) {
            if (!decision.reason.startsWith("quality_")) unavailableFeedSince = 0L
            return
        }

        if (unavailableFeedSince == 0L) unavailableFeedSince = now
        val unavailableForMs = now - unavailableFeedSince
        if (
            unavailableForMs < UNAVAILABLE_FEED_NOTICE_AFTER_MS ||
            now - lastUnavailableFeedNoticeAt < UNAVAILABLE_FEED_NOTICE_REPEAT_MS
        ) return

        lastUnavailableFeedNoticeAt = now
        // Recorded together because they are what separates the possible causes. Diagnostics from a
        // real session show the black period beginning within 0.3 s of a rotation and ending within
        // 0.4 s of the next one, twice, with every black frame in landscape and none in portrait —
        // a pattern no screen timeout produces. The display state and the capture geometry are kept
        // so the next bundle can tell a blank mirror from a genuinely dark scene without guessing.
        val interactive = runCatching {
            getSystemService(PowerManager::class.java)?.isInteractive
        }.getOrNull()
        val elapsed = SystemClock.elapsedRealtime()
        DiagnosticHub.record(
            "VISUAL_FEED_UNAVAILABLE_NOTICE_TRIGGERED",
            trace.fields(
                mapOf(
                    "decisionReason" to decision.reason,
                    "unavailableForMs" to unavailableForMs,
                    "displayInteractive" to interactive,
                    "captureWidth" to appliedCaptureWidth,
                    "captureHeight" to appliedCaptureHeight,
                    "landscapeCapture" to (appliedCaptureWidth > appliedCaptureHeight),
                    "sinceLastResizeMs" to
                        if (lastResizeAtElapsedMs > 0L) elapsed - lastResizeAtElapsedMs else null,
                ),
            ),
        )

        // Try to repair the capture before blaming anything the user can act on. A blank mirror and
        // a dark room look identical in pixels, but only one of them is fixable from here.
        val mayRecover = interactive != false &&
            elapsed - lastSurfaceRecoveryAtElapsedMs >= SURFACE_RECOVERY_COOLDOWN_MS
        if (mayRecover) {
            lastSurfaceRecoveryAtElapsedMs = elapsed
            captureHandler?.post { refreshCaptureSurfaceAfterBlackFeed() }
        }

        val message = when {
            interactive == false -> SCREEN_OFF_MESSAGE
            mayRecover -> VISUAL_FEED_RECOVERING_MESSAGE
            else -> VISUAL_FEED_UNAVAILABLE_MESSAGE
        }
        DiagnosticHub.record(
            "SYSTEM_NOTICE_PUBLISHED",
            mapOf("code" to "VISUAL_FEED_UNAVAILABLE", "text" to message),
        )
        container.runtime.notice(message)
        serviceScope.launch { container.coordinator.speakNotice(message) }
    }

    private fun recordDroppedFrame(
        bitmap: Bitmap,
        trace: DiagnosticTrace,
        reason: String,
        extra: Map<String, Any?> = emptyMap(),
    ) {
        DiagnosticHub.record("FRAME_DROPPED", trace.fields(mapOf("reason" to reason) + extra))
        val now = System.currentTimeMillis()
        if (now - lastPreviewAt >= DROPPED_PREVIEW_INTERVAL_MS) {
            lastPreviewAt = now
            DiagnosticHub.preview(bitmap, trace.frameId, reason, trace.fields(extra))
        }
    }

    private fun submitLatestFrame(frame: PendingFrame) {
        val launchNow = synchronized(frameQueueLock) {
            if (processing.compareAndSet(false, true)) {
                DiagnosticHub.record("FRAME_QUEUE_BYPASSED", frame.trace.fields())
                true
            } else {
                pendingFrame?.let { old ->
                    DiagnosticHub.record(
                        "FRAME_DROPPED",
                        old.trace.fields(
                            mapOf(
                                "reason" to "replaced_in_latest_frame_queue",
                                "replacementFrameId" to frame.trace.frameId,
                            ),
                        ),
                    )
                    old.bitmap.recycle()
                }
                pendingFrame = frame
                DiagnosticHub.record("FRAME_QUEUED_AS_LATEST", frame.trace.fields())
                false
            }
        }
        if (launchNow) launchFrame(frame)
    }

    private fun launchFrame(frame: PendingFrame) {
        frameJob = serviceScope.launch {
            val dispatchStarted = SystemClock.elapsedRealtimeNanos()
            DiagnosticHub.record("ANALYSIS_DISPATCH_STARTED", frame.trace.fields())
            try {
                withContext(frame.trace) {
                    container.coordinator.process(frame.bitmap)
                }
                DiagnosticHub.record(
                    "ANALYSIS_DISPATCH_COMPLETED",
                    frame.trace.fields(
                        mapOf(
                            "dispatchDurationMs" to
                                (SystemClock.elapsedRealtimeNanos() - dispatchStarted) / 1_000_000.0,
                        ),
                    ),
                )
            } catch (error: Throwable) {
                DiagnosticHub.failure("ANALYSIS_DISPATCH", error, frame.trace.fields())
                throw error
            } finally {
                frame.bitmap.recycle()
                val next = synchronized(frameQueueLock) {
                    pendingFrame.also { pendingFrame = null }
                }
                if (next != null) {
                    launchFrame(next)
                } else {
                    processing.set(false)
                    val raced = synchronized(frameQueueLock) {
                        pendingFrame?.also {
                            pendingFrame = null
                            processing.set(true)
                        }
                    }
                    if (raced != null) launchFrame(raced)
                }
            }
        }
    }

    /**
     * Re-points the capture at a new content size.
     *
     * The system reports the same size two and three times in a row when the shared app rotates,
     * and each report used to tear down the ImageReader and re-attach the virtual display surface.
     * A device log shows three of these inside 200 ms, immediately followed by sixty seconds of
     * pure black frames. Repeating the work cannot help and can only race the capture thread, so a
     * report that does not actually change the size is now ignored.
     */
    private fun resizeCapture(width: Int, height: Int) {
        val display = virtualDisplay ?: return
        if (width == appliedCaptureWidth && height == appliedCaptureHeight) {
            DiagnosticHub.record(
                "CAPTURE_RESIZE_SKIPPED",
                mapOf("reason" to "size_unchanged", "width" to width, "height" to height),
            )
            return
        }
        val newReader = createImageReader(width, height)
        // Attach before publishing, so a failure here leaves the working reader in place.
        display.resize(width, height, resources.displayMetrics.densityDpi)
        display.setSurface(newReader.surface)
        val oldReader = imageReader
        imageReader = newReader
        oldReader?.setOnImageAvailableListener(null, null)
        oldReader?.close()
        appliedCaptureWidth = width
        appliedCaptureHeight = height
        lastResizeAtElapsedMs = SystemClock.elapsedRealtime()
        resetFrameState()
        DiagnosticHub.record("CAPTURE_RESIZE_APPLIED", mapOf("width" to width, "height" to height))
    }

    /**
     * Re-points the capture at a fresh surface when the mirror has been black for too long.
     *
     * Whatever leaves the mirror blank — a surface that did not survive a rotation, or a
     * single-app capture whose window stopped rendering — sitting black for a minute is never the
     * right outcome for someone waiting to be told what is in front of them.
     *
     * This used to ask the projection for a second virtual display, and Android 14 answers that
     * with `SecurityException: Don't take multiple captures by invoking
     * MediaProjection#createVirtualDisplay multiple times on the same instance` and then stops the
     * projection outright. Three device sessions attempted it, all three died within 3 ms, and one
     * of them left the user with 216 seconds of nothing. One consent yields one virtual display for
     * its whole life; a new surface goes on the display we already hold.
     */
    private fun refreshCaptureSurfaceAfterBlackFeed() {
        val display = virtualDisplay ?: return
        val width = appliedCaptureWidth
        val height = appliedCaptureHeight
        if (width <= 0 || height <= 0) return

        DiagnosticHub.record(
            "CAPTURE_SURFACE_RECOVERY_STARTED",
            mapOf("width" to width, "height" to height),
        )
        // Built on the side and swapped in only once the display has accepted it. The previous
        // version reassigned the field first, so a failure left the capture pointing at a reader
        // with no producer — a repair that made things worse than not trying.
        val replacement = runCatching { createImageReader(width, height) }.getOrElse { error ->
            DiagnosticHub.failure("CAPTURE_SURFACE_RECOVERY", error)
            return
        }
        val attached = runCatching { display.setSurface(replacement.surface) }
        if (attached.isFailure) {
            runCatching { replacement.setOnImageAvailableListener(null, null) }
            runCatching { replacement.close() }
            DiagnosticHub.failure(
                "CAPTURE_SURFACE_RECOVERY",
                attached.exceptionOrNull() ?: IllegalStateException("setSurface failed"),
            )
            return
        }

        val oldReader = imageReader
        imageReader = replacement
        runCatching { oldReader?.setOnImageAvailableListener(null, null) }
        runCatching { oldReader?.close() }
        resetFrameState()
        DiagnosticHub.record(
            "CAPTURE_SURFACE_RECOVERY_COMPLETED",
            mapOf("width" to width, "height" to height),
        )
    }

    private fun releaseProjection(stopProjection: Boolean, reason: String) {
        val hadProjection = mediaProjection != null || virtualDisplay != null || imageReader != null
        frameJob?.cancel()
        frameJob = null
        synchronized(frameQueueLock) {
            pendingFrame?.let { pending ->
                DiagnosticHub.record(
                    "FRAME_DROPPED",
                    pending.trace.fields(mapOf("reason" to "projection_released")),
                )
                pending.bitmap.recycle()
            }
            pendingFrame = null
            processing.set(false)
        }
        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.close()
        imageReader = null
        virtualDisplay?.release()
        virtualDisplay = null
        mediaProjection?.let { projection ->
            runCatching { projection.unregisterCallback(projectionCallback) }
            if (stopProjection) runCatching { projection.stop() }
        }
        mediaProjection = null
        resetFrameState()
        container.coordinator.stopSpeech()

        if (hadProjection) DiagnosticHub.record("PROJECTION_RELEASED", mapOf("reason" to reason))
        if (diagnosticSessionOpen) {
            diagnosticSessionOpen = false
            runCatching {
                runBlocking(Dispatchers.IO) { DiagnosticHub.endSession(reason) }
            }
        }
    }

    private fun resetFrameState() {
        lastFrameAt = 0L
        lastPreviewAt = 0L
        unavailableFeedSince = 0L
        lastUnavailableFeedNoticeAt = 0L
        frameChangeDetector.reset()
        textTargetTracker.reset()
        sceneTargetTracker.reset()
    }

    private fun initialCaptureSize(): Triple<Int, Int, Int> {
        val density = resources.displayMetrics.densityDpi
        val windowManager = getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= 30) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            Triple(bounds.width().coerceAtLeast(1), bounds.height().coerceAtLeast(1), density)
        } else {
            @Suppress("DEPRECATION")
            val metrics = resources.displayMetrics
            Triple(metrics.widthPixels.coerceAtLeast(1), metrics.heightPixels.coerceAtLeast(1), density)
        }
    }

    private fun settingsMap(settings: AppSettings): Map<String, Any?> = mapOf(
        "mode" to settings.mode.name,
        "model" to settings.model,
        "forceCellular" to settings.forceCellular,
        "speechEnabled" to settings.speechEnabled,
        "useLocalOcr" to settings.useLocalOcr,
        "trustGateEnabled" to settings.trustGateEnabled,
        "captureProfile" to settings.captureProfile.name,
        "interruptSpeechOnVisualChange" to settings.interruptSpeechOnVisualChange,
        "sceneDescriptionStyle" to settings.sceneDescriptionStyle.name,
        "captureFailureEvidence" to settings.captureFailureEvidence,
        "speechRate" to settings.speechRate,
    )

    private fun startAsForeground() {
        val openIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val problemIntent = PendingIntent.getService(
            this,
            2,
            markProblemIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            3,
            stopIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // The same switch as the accessibility button, on a surface that needs no setup. The shade
        // is reachable from inside eSight's shared view too, and it is already a place TalkBack
        // users navigate confidently.
        val evidenceIntent = PendingIntent.getService(
            this,
            4,
            toggleEvidenceIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val evidenceLabel = if (activeSettings.captureFailureEvidence) {
            R.string.notification_evidence_off
        } else {
            R.string.notification_evidence_on
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, getString(evidenceLabel), evidenceIntent)
            .addAction(0, getString(R.string.notification_mark_problem), problemIntent)
            .addAction(0, getString(R.string.notification_stop), stopIntent)
            .build()

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0,
        )
        foregroundStarted = true
    }

    /**
     * Flips failure-frame capture from the notification, with the same wording and the same spoken
     * confirmation as the accessibility button, because they are the same action.
     */
    private fun toggleEvidenceCapture() {
        val action = EvidenceShortcut.press(
            currentlyEnabled = activeSettings.captureFailureEvidence,
            framesAlreadyHeld = container.diagnostics.evidenceStore.frameCount(),
            captureRunning = container.runtime.state.value.isRunning,
        )
        DiagnosticHub.setEvidenceCapture(action.enable)
        if (action.markProblem) {
            DiagnosticHub.markProblemAsync("شُغّل حفظ اللقطات من إشعار VisionBridge")
        }
        DiagnosticHub.record(
            "EVIDENCE_SHORTCUT_PRESSED",
            mapOf(
                "source" to "notification",
                "applied" to true,
                "enabled" to action.enable,
                "captureRunning" to container.runtime.state.value.isRunning,
                "framesHeld" to container.diagnostics.evidenceStore.frameCount(),
            ),
        )
        container.tts.speakUrgentNotice(action.announcement)
        container.runtime.notice(action.announcement)
        serviceScope.launch {
            container.settingsRepository.setCaptureFailureEvidence(action.enable)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notification_channel_description)
                setSound(null, null)
                enableVibration(false)
            }
        )
    }

    @Suppress("DEPRECATION")
    private fun Intent.parcelableIntentExtra(name: String): Intent? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(name, Intent::class.java)
        else getParcelableExtra(name)

    companion object {
        /** Longest the main thread may wait for the reader before the service gives up on it. */
        private const val RELEASE_ON_DESTROY_TIMEOUT_MS = 1_500L

        private const val CHANNEL_ID = "screen_capture_analysis"
        private const val NOTIFICATION_ID = 4101
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val ACTION_START = "com.abdullah.visionbridge.START_CAPTURE"
        private const val ACTION_MARK_PROBLEM = "com.abdullah.visionbridge.MARK_DIAGNOSTIC_PROBLEM"
        private const val ACTION_TOGGLE_EVIDENCE = "com.abdullah.visionbridge.TOGGLE_EVIDENCE_CAPTURE"
        private const val ACTION_STOP = "com.abdullah.visionbridge.STOP_CAPTURE"

        private const val STABLE_FRAME_INTERVAL_MS = 400L
        private const val STABLE_FRAME_DURATION_MS = 900L
        private const val STABLE_MIN_MEAN_DIFFERENCE = 7.5
        private const val STABLE_MIN_CHANGED_RATIO = 0.06

        private const val FAST_FRAME_INTERVAL_MS = 160L
        private const val FAST_MIN_MEAN_DIFFERENCE = 2.2
        private const val FAST_MIN_CHANGED_RATIO = 0.018

        private const val SCENE_FRAME_INTERVAL_MS = 180L
        private const val SCENE_MIN_MEAN_DIFFERENCE = 2.4
        private const val SCENE_MIN_CHANGED_RATIO = 0.018

        /**
         * How much structure may remain unexplained after registration before the subject counts as
         * replaced. Measured as (1 − SSIM) / 2, so it is a fraction of content rather than a pixel
         * count: on constructed scenes the same page after motion lands under 0.10, a lighting
         * change under 0.15, and an entirely different page above 0.30.
         *
         * Text reading is the more forgiving of the two, because abandoning a page the user is
         * halfway through hearing is the expensive mistake. A scene description is a live statement
         * about what is in front of someone, so it is held to a tighter bound.
         *
         * Both sit inside a measured gap. Over 29 views of one page — translated, rotated, zoomed,
         * relit and noised — the worst score was 0.226; over 13 genuinely different subjects the
         * best was 0.299. The gap is only 0.073 wide, which is why the consensus rule matters as
         * much as the threshold: a single frame that lands on the wrong side of it changes nothing.
         */
        private const val TEXT_TARGET_DISSIMILARITY = 0.26
        private const val SCENE_TARGET_DISSIMILARITY = 0.24

        /**
         * Colour difference that counts as a new subject on its own, averaged over the two chroma
         * channels. Two labels can share a layout and differ only in hue, which the grayscale
         * signature this replaces could not see at all.
         */
        private const val TEXT_TARGET_CHROMA = 26.0
        private const val SCENE_TARGET_CHROMA = 18.0
        private const val DROPPED_PREVIEW_INTERVAL_MS = 1_000L

        private const val UNAVAILABLE_FEED_NOTICE_AFTER_MS = 1_500L
        private const val UNAVAILABLE_FEED_NOTICE_REPEAT_MS = 30_000L
        private const val VISUAL_FEED_UNAVAILABLE_MESSAGE =
            "لا تصل صورة قابلة للتحليل. تأكد من أن شاشة الهاتف مضاءة وأن بث eSight ظاهر عليها. " +
                "داخل تطبيق eSight، فعّل Share Your View."

        /**
         * Screen capture mirrors the phone display, so a display that has gone to sleep is captured
         * as a black frame. This is the common case in scene mode, where the user is looking through
         * the glasses and not touching the phone, and the screen times out.
         */
        /** Cooldown between capture-surface rebuilds, so a genuinely dark room is not thrashed. */
        private const val SURFACE_RECOVERY_COOLDOWN_MS = 20_000L

        private const val VISUAL_FEED_RECOVERING_MESSAGE =
            "الصورة الواردة سوداء. يعيد VisionBridge تهيئة مشاركة الشاشة الآن. إن استمرت المشكلة، أوقف Share Your View ثم فعّله من جديد."

        private const val PROJECTION_STOPPED_MESSAGE =
            "أوقف Android مشاركة الشاشة، لذلك توقفت القراءة. افتح VisionBridge واضغط ابدأ الالتقاط للسماح بها من جديد."

        private const val SCREEN_OFF_MESSAGE =
            "شاشة الهاتف مطفأة، لذلك توقف وصول الصورة. شغّل الشاشة، ويفضل زيادة مهلة القفل التلقائي من إعدادات الهاتف."

        fun startIntent(context: Context, resultCode: Int, resultData: Intent) =
            Intent(context, MediaProjectionService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
            }

        fun markProblemIntent(context: Context) = Intent(context, MediaProjectionService::class.java).apply {
            action = ACTION_MARK_PROBLEM
        }

        fun toggleEvidenceIntent(context: Context) =
            Intent(context, MediaProjectionService::class.java).apply {
                action = ACTION_TOGGLE_EVIDENCE
            }

        fun stopIntent(context: Context) = Intent(context, MediaProjectionService::class.java).apply {
            action = ACTION_STOP
        }
    }
}
