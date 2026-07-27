package com.abdullah.visionbridge.capture

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.abdullah.visionbridge.R
import com.abdullah.visionbridge.VisionBridgeApp
import com.abdullah.visionbridge.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class MediaProjectionService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val processing = AtomicBoolean(false)
    private val changeDetector = FrameChangeDetector()

    private val container by lazy { (application as VisionBridgeApp).container }
    private val projectionManager by lazy { getSystemService(MediaProjectionManager::class.java) }
    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }

    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var frameJob: Job? = null
    private var lastFrameAt = 0L

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            releaseProjection(stopProjection = false)
            container.runtime.stopped("أوقف النظام مشاركة الشاشة")
            stopSelf()
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startAsForeground()
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData = intent.parcelableIntentExtra(EXTRA_RESULT_DATA)
                if (resultCode == Activity.RESULT_OK && resultData != null) {
                    startProjection(resultCode, resultData)
                } else {
                    container.runtime.error("لم تُمنح صلاحية مشاركة الشاشة")
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                releaseProjection(stopProjection = true)
                container.runtime.stopped()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        releaseProjection(stopProjection = true)
        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startProjection(resultCode: Int, resultData: Intent) {
        if (mediaProjection != null) return
        runCatching {
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

            changeDetector.reset()
            container.coordinator.reset()
            container.runtime.started()
        }.onFailure {
            container.runtime.error(it.message ?: "فشل بدء التقاط الشاشة")
            releaseProjection(stopProjection = true)
            stopSelf()
        }
    }

    private fun createImageReader(width: Int, height: Int): ImageReader =
        ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2).apply {
            setOnImageAvailableListener({ reader -> consumeLatestFrame(reader) }, captureHandler)
        }

    private fun consumeLatestFrame(reader: ImageReader) {
        val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return
        val bitmap = try {
            ImageFrameConverter.toBitmap(image)
        } catch (error: Throwable) {
            image.close()
            container.runtime.error(error.message ?: "تعذر تحويل إطار الشاشة")
            return
        }
        image.close()

        val now = System.currentTimeMillis()
        val tooSoon = now - lastFrameAt < MIN_FRAME_INTERVAL_MS
        val changed = !tooSoon && changeDetector.shouldProcess(
            bitmap = bitmap,
            minimumDifference = MIN_FRAME_DIFFERENCE,
            forceAfterMs = FORCE_FRAME_AFTER_MS,
            now = now,
        )
        if (!changed || !processing.compareAndSet(false, true)) {
            bitmap.recycle()
            return
        }
        lastFrameAt = now

        frameJob = serviceScope.launch {
            try {
                container.coordinator.process(bitmap)
            } finally {
                bitmap.recycle()
                processing.set(false)
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
        changeDetector.reset()
    }

    private fun releaseProjection(stopProjection: Boolean) {
        frameJob?.cancel()
        frameJob = null
        processing.set(false)
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
        changeDetector.reset()
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
        private const val MIN_FRAME_INTERVAL_MS = 650L
        private const val FORCE_FRAME_AFTER_MS = 3_500L
        private const val MIN_FRAME_DIFFERENCE = 4.0

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
