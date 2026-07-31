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
    private val targetChangeDetector = FrameChangeDetector()
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

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            DiagnosticHub.record("PROJECTION_SYSTEM_STOPPED")
            releaseProjection(stopProjection = false, reason = "system_stopped_projection")
            container.runtime.stopped("أوقف النظام مشاركة الشاشة")
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
                activeSettings = newSettings
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
                    container.runtime.error("لم تُمنح صلاحية مشاركة الشاشة")
                    stopSelf()
                }
            }
            ACTION_MARK_PROBLEM -> {
                DiagnosticHub.markProblemAsync("تم تعليم المشكلة مباشرة من إشعار الالتقاط")
                DiagnosticHub.record("PROBLEM_MARKED_FROM_NOTIFICATION")
                container.runtime.notice("تم تعليم لحظة المشكلة وربطها بأقرب إطار")
            }
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
        runBlocking { container.localOcrEngine.release("capture_service_destroyed") }
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
            ) ?: error("تعذر إنشاء شاشة افتراضية للالتقاط")

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
            container.runtime.error(error.message ?: "فشل بدء التقاط الشاشة")
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
            container.runtime.error(error.message ?: "تعذر تحويل إطار الشاشة")
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
            recordDroppedFrame(bitmap, trace, "change_detector_rejected", changeFields)
            bitmap.recycle()
            return
        }
        unavailableFeedSince = 0L
        lastFrameAt = now

        val targetMean = if (settings.mode == AnalysisMode.SCENE_DESCRIPTION) {
            SCENE_TARGET_CHANGE_MEAN_DIFFERENCE
        } else {
            TEXT_TARGET_CHANGE_MEAN_DIFFERENCE
        }
        val targetRatio = if (settings.mode == AnalysisMode.SCENE_DESCRIPTION) {
            SCENE_TARGET_CHANGE_RATIO
        } else {
            TEXT_TARGET_CHANGE_RATIO
        }
        val targetDecision = targetChangeDetector.evaluateFast(
            bitmap = bitmap,
            minimumMeanDifference = targetMean,
            minimumChangedRatio = targetRatio,
        )
        val visualTargetChanged = targetDecision.accepted
        DiagnosticHub.record(
            "VISUAL_TARGET_DECISION",
            trace.fields(
                mapOf(
                    "targetChanged" to visualTargetChanged,
                    "decisionReason" to targetDecision.reason,
                    "meanAbsoluteDifference" to targetDecision.meanAbsoluteDifference,
                    "changedPixelRatio" to targetDecision.changedPixelRatio,
                    "minimumMeanDifference" to targetMean,
                    "minimumChangedPixelRatio" to targetRatio,
                    "thresholdLogic" to "OR",
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
            captureHandler?.post { recreateVirtualDisplayAfterBlackFeed() }
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
        val oldReader = imageReader
        val newReader = createImageReader(width, height)
        imageReader = newReader
        display.resize(width, height, resources.displayMetrics.densityDpi)
        display.setSurface(newReader.surface)
        oldReader?.setOnImageAvailableListener(null, null)
        oldReader?.close()
        appliedCaptureWidth = width
        appliedCaptureHeight = height
        lastResizeAtElapsedMs = SystemClock.elapsedRealtime()
        resetFrameState()
        DiagnosticHub.record("CAPTURE_RESIZE_APPLIED", mapOf("width" to width, "height" to height))
    }

    /**
     * Rebuilds the virtual display when the capture has been black for too long.
     *
     * Whatever leaves the mirror blank — a surface that did not survive a rotation, or a
     * single-app capture whose window stopped rendering — sitting black for a minute is never the
     * right outcome for someone waiting to be told what is in front of them. Diagnostics show the
     * black period starting within 0.3 s of a rotation and ending within 0.4 s of the next one, so
     * a fresh surface at the current size is the natural thing to try, and it costs one frame.
     */
    private fun recreateVirtualDisplayAfterBlackFeed() {
        val projection = mediaProjection ?: return
        val width = appliedCaptureWidth
        val height = appliedCaptureHeight
        if (width <= 0 || height <= 0) return

        DiagnosticHub.record(
            "CAPTURE_SURFACE_RECOVERY_STARTED",
            mapOf("width" to width, "height" to height),
        )
        runCatching {
            val oldReader = imageReader
            val oldDisplay = virtualDisplay
            val reader = createImageReader(width, height)
            imageReader = reader
            virtualDisplay = projection.createVirtualDisplay(
                "VisionBridgeCapture",
                width,
                height,
                resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                captureHandler,
            ) ?: error("تعذر إعادة إنشاء شاشة الالتقاط")
            oldDisplay?.release()
            oldReader?.setOnImageAvailableListener(null, null)
            oldReader?.close()
            resetFrameState()
            DiagnosticHub.record("CAPTURE_SURFACE_RECOVERY_COMPLETED", mapOf("width" to width, "height" to height))
        }.onFailure { error ->
            DiagnosticHub.failure("CAPTURE_SURFACE_RECOVERY", error)
        }
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
        targetChangeDetector.reset()
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
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, getString(R.string.notification_mark_problem), problemIntent)
            .addAction(0, getString(R.string.notification_stop), stopIntent)
            .build()

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0,
        )
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
        private const val CHANNEL_ID = "screen_capture_analysis"
        private const val NOTIFICATION_ID = 4101
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val ACTION_START = "com.abdullah.visionbridge.START_CAPTURE"
        private const val ACTION_MARK_PROBLEM = "com.abdullah.visionbridge.MARK_DIAGNOSTIC_PROBLEM"
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

        private const val TEXT_TARGET_CHANGE_MEAN_DIFFERENCE = 19.0
        private const val TEXT_TARGET_CHANGE_RATIO = 0.32
        private const val SCENE_TARGET_CHANGE_MEAN_DIFFERENCE = 8.0
        private const val SCENE_TARGET_CHANGE_RATIO = 0.12
        private const val DROPPED_PREVIEW_INTERVAL_MS = 1_000L

        private const val UNAVAILABLE_FEED_NOTICE_AFTER_MS = 1_500L
        private const val UNAVAILABLE_FEED_NOTICE_REPEAT_MS = 30_000L
        private const val VISUAL_FEED_UNAVAILABLE_MESSAGE =
            "لا تصل صورة قابلة للتحليل. تأكد أن شاشة الهاتف مضاءة، وأن منظر النظارة ظاهر عليها. " +
                "إن كنت في تطبيق إي سايت فاضغط شير يور فيو."

        /**
         * Screen capture mirrors the phone display, so a display that has gone to sleep is captured
         * as a black frame. This is the common case in scene mode, where the user is looking through
         * the glasses and not touching the phone, and the screen times out.
         */
        /** Cooldown between capture-surface rebuilds, so a genuinely dark room is not thrashed. */
        private const val SURFACE_RECOVERY_COOLDOWN_MS = 20_000L

        private const val VISUAL_FEED_RECOVERING_MESSAGE =
            "الصورة الواردة سوداء. أعيد تجهيز الالتقاط الآن. إن استمرت، أعد الضغط على شير يور فيو."

        private const val SCREEN_OFF_MESSAGE =
            "شاشة الهاتف مطفأة، ولذلك لا تصل صورة. أضئ الشاشة، ويفضل إطالة مهلة إطفاء الشاشة من إعدادات الهاتف."

        fun startIntent(context: Context, resultCode: Int, resultData: Intent) =
            Intent(context, MediaProjectionService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
            }

        fun markProblemIntent(context: Context) = Intent(context, MediaProjectionService::class.java).apply {
            action = ACTION_MARK_PROBLEM
        }

        fun stopIntent(context: Context) = Intent(context, MediaProjectionService::class.java).apply {
            action = ACTION_STOP
        }
    }
}
