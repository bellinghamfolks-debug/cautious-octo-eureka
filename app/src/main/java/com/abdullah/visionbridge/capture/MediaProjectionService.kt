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
            ACTION_STOP -> {
                releaseProjection(stopProjection = true, reason = "user_stopped_capture")
                container.runtime.stopped()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        releaseProjection(stopProjection = true, reason = "service_destroyed")
        settingsJob?.cancel()
        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null
        DiagnosticHub.record("CAPTURE_SERVICE_DESTROYED")
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startProjection(resultCode: Int, resultData: Intent) {
        if (mediaProjection != null) {
            DiagnosticHub.record("PROJECTION_START_IGNORED", mapOf("reason" to "already_running"))
            return
        }
        runCatching {
            runBlocking(Dispatchers.IO) {
                container.diagnostics.startSession(settingsMap(activeSettings))
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
            traceId = "T-$frameId-${acquiredAtElapsedNanos}",
            frameId = frameId,
            capturedAtEpochMs = acquiredAtEpochMs,
            capturedAtElapsedNanos = acquiredAtElapsedNanos,
        )

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
            recordDroppedFrame(bitmap, trace, "capture_interval_throttle", mapOf(
                "minimumIntervalMs" to minimumInterval,
                "actualIntervalMs" to intervalSincePrevious,
            ))
            bitmap.recycle()
            return
        }

        val detectorStarted = SystemClock.elapsedRealtimeNanos()
        val shouldAnalyze = when {
            settings.mode == AnalysisMode.SCENE_DESCRIPTION -> frameChangeDetector.shouldProcessFast(
                bitmap = bitmap,
                minimumMeanDifference = SCENE_MIN_MEAN_DIFFERENCE,
                minimumChangedRatio = SCENE_MIN_CHANGED_RATIO,
            )
            settings.captureProfile == CaptureProfile.STABLE -> frameChangeDetector.shouldProcessStable(
                bitmap = bitmap,
                minimumMeanDifference = STABLE_MIN_MEAN_DIFFERENCE,
                minimumChangedRatio = STABLE_MIN_CHANGED_RATIO,
                stableForMs = STABLE_FRAME_DURATION_MS,
                now = now,
            )
            else -> frameChangeDetector.shouldProcessFast(
                bitmap = bitmap,
                minimumMeanDifference = FAST_MIN_MEAN_DIFFERENCE,
                minimumChangedRatio = FAST_MIN_CHANGED_RATIO,
            )
        }
        val detectorMs = (SystemClock.elapsedRealtimeNanos() - detectorStarted) / 1_000_000.0

        DiagnosticHub.record(
            "FRAME_CHANGE_DECISION",
            trace.fields(
                mapOf(
                    "shouldAnalyze" to shouldAnalyze,
                    "detectorMs" to detectorMs,
                    "mode" to settings.mode.name,
                    "captureProfile" to settings.captureProfile.name,
                    "stableForMs" to if (settings.captureProfile == CaptureProfile.STABLE) STABLE_FRAME_DURATION_MS else 0L,
                ),
            ),
        )

        if (!shouldAnalyze) {
            recordDroppedFrame(bitmap, trace, "change_detector_rejected", mapOf("detectorMs" to detectorMs))
            bitmap.recycle()
            return
        }
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
        val visualTargetChanged = targetChangeDetector.shouldProcessFast(
            bitmap = bitmap,
            minimumMeanDifference = targetMean,
            minimumChangedRatio = targetRatio,
        )
        DiagnosticHub.record(
            "VISUAL_TARGET_DECISION",
            trace.fields(
                mapOf(
                    "targetChanged" to visualTargetChanged,
                    "minimumMeanDifference" to targetMean,
                    "minimumChangedRatio" to targetRatio,
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
                ),
            ),
        )
        DiagnosticHub.record("FRAME_SELECTED_FOR_ANALYSIS", trace.fields())
        submitLatestFrame(PendingFrame(bitmap, trace))
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
                        mapOf("dispatchDurationMs" to (SystemClock.elapsedRealtimeNanos() - dispatchStarted) / 1_000_000.0),
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

    private fun resizeCapture(width: Int, height: Int) {
        val display = virtualDisplay ?: return
        val oldReader = imageReader
        val newReader = createImageReader(width, height)
        imageReader = newReader
        display.resize(width, height, resources.displayMetrics.densityDpi)
        display.setSurface(newReader.surface)
        oldReader?.setOnImageAvailableListener(null, null)
        oldReader?.close()
        resetFrameState()
        DiagnosticHub.record("CAPTURE_RESIZE_APPLIED", mapOf("width" to width, "height" to height))
    }

    private fun releaseProjection(stopProjection: Boolean, reason: String) {
        val hadProjection = mediaProjection != null || virtualDisplay != null || imageReader != null
        frameJob?.cancel()
        frameJob = null
        synchronized(frameQueueLock) {
            pendingFrame?.let { pending ->
                DiagnosticHub.record("FRAME_DROPPED", pending.trace.fields(mapOf("reason" to "projection_released")))
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
                runBlocking(Dispatchers.IO) { container.diagnostics.endSession(reason) }
            }
        }
    }

    private fun resetFrameState() {
        lastFrameAt = 0L
        lastPreviewAt = 0L
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
        "localOcrEnabled" to settings.localOcrEnabled,
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
        val stopIntent = PendingIntent.getService(
            this,
            2,
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

        fun startIntent(context: Context, resultCode: Int, resultData: Intent) =
            Intent(context, MediaProjectionService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
            }

        fun stopIntent(context: Context) = Intent(context, MediaProjectionService::class.java).apply {
            action = ACTION_STOP
        }
    }
}
