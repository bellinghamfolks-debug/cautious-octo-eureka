package com.abdullah.visionbridge.capture

import android.graphics.Bitmap
import com.abdullah.visionbridge.data.ocr.LocalTextRecognizer
import com.abdullah.visionbridge.data.speech.BilingualTtsEngine
import com.abdullah.visionbridge.domain.model.AnalysisMode
import com.abdullah.visionbridge.domain.model.AnalysisResult
import com.abdullah.visionbridge.domain.model.AnalysisSource
import com.abdullah.visionbridge.domain.model.AppSettings
import com.abdullah.visionbridge.domain.model.CaptureProfile
import com.abdullah.visionbridge.domain.repository.ApiKeyStore
import com.abdullah.visionbridge.domain.repository.SettingsRepository
import com.abdullah.visionbridge.domain.usecase.AnalyzeFrameUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

class FrameAnalysisCoordinator(
    private val settingsRepository: SettingsRepository,
    private val apiKeyStore: ApiKeyStore,
    private val localTextRecognizer: LocalTextRecognizer,
    private val analyzeFrame: AnalyzeFrameUseCase,
    private val tts: BilingualTtsEngine,
    private val runtime: CaptureRuntime,
) {
    private data class PendingCloudFrame(
        val bitmap: Bitmap,
        val settings: AppSettings,
        val localText: String,
        val visualGeneration: Long,
        val apiKey: String,
    )

    private val mutex = Mutex()
    private val cloudScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cloudQueueLock = Any()
    private val visualGeneration = AtomicLong(0L)

    private var lastCloudOcrAt = 0L
    private var lastSceneAt = 0L
    private var lastFastCloudSnapshotAt = 0L
    private var fastCloudJob: Job? = null
    private var pendingFastCloudFrame: PendingCloudFrame? = null

    suspend fun process(bitmap: Bitmap) = mutex.withLock {
        val settings = settingsRepository.settings.first()
        val generationAtCapture = visualGeneration.get()
        runtime.processing(true)
        try {
            when (settings.mode) {
                AnalysisMode.TEXT_READING -> {
                    if (settings.captureProfile == CaptureProfile.FAST_TEXT) {
                        processFastText(bitmap, settings, generationAtCapture)
                    } else {
                        processStableText(bitmap, settings, generationAtCapture)
                    }
                }
                AnalysisMode.SCENE_DESCRIPTION -> processScene(bitmap, settings, generationAtCapture)
            }
        } catch (error: Throwable) {
            if (error !is CancellationException) runtime.error(error.userMessage())
        } finally {
            runtime.processing(false)
        }
    }

    private suspend fun processStableText(
        bitmap: Bitmap,
        settings: AppSettings,
        generationAtCapture: Long,
    ) {
        val key = apiKeyStore.get()
        // When Gemini is available, local OCR remains a quick visual preview only. Speaking its
        // English result first would destroy the true mixed Arabic/English visual order.
        val localText = recognizeLocal(
            bitmap = bitmap,
            settings = settings,
            generationAtCapture = generationAtCapture,
            speakLocally = key == null,
        )
        if (key == null) return

        val now = System.currentTimeMillis()
        val interval = if (localText.isBlank()) 900L else settings.cloudOcrIntervalMs
        if (now - lastCloudOcrAt < interval) return
        lastCloudOcrAt = now

        val result = streamAnalysis(
            bitmap = bitmap,
            mode = AnalysisMode.TEXT_READING,
            settings = settings,
            apiKey = key,
            generationAtCapture = generationAtCapture,
        )
        if (generationAtCapture == visualGeneration.get()) runtime.result(result)
    }

    /**
     * Local OCR completes immediately and releases the capture pipeline. Gemini streams from a
     * copied snapshot in a separate latest-frame queue, so cloud latency cannot block detection of
     * fast subtitles, tickers, or scrolling text.
     */
    private suspend fun processFastText(
        bitmap: Bitmap,
        settings: AppSettings,
        generationAtCapture: Long,
    ) {
        val key = apiKeyStore.get()
        val localText = recognizeLocal(
            bitmap = bitmap,
            settings = settings,
            generationAtCapture = generationAtCapture,
            speakLocally = key == null,
        )
        if (key == null) return

        val now = System.currentTimeMillis()
        val desiredInterval = if (localText.isBlank()) 550L else 900L
        synchronized(cloudQueueLock) {
            val running = fastCloudJob?.isActive == true
            if (running) {
                if (now - lastFastCloudSnapshotAt < FAST_PENDING_SNAPSHOT_INTERVAL_MS) return
                lastFastCloudSnapshotAt = now
                pendingFastCloudFrame?.bitmap?.recycle()
                pendingFastCloudFrame = PendingCloudFrame(
                    bitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false),
                    settings = settings,
                    localText = localText,
                    visualGeneration = generationAtCapture,
                    apiKey = key,
                )
                return
            }
            if (now - lastCloudOcrAt < desiredInterval) return
            lastCloudOcrAt = now
            lastFastCloudSnapshotAt = now
            launchFastCloud(
                PendingCloudFrame(
                    bitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false),
                    settings = settings,
                    localText = localText,
                    visualGeneration = generationAtCapture,
                    apiKey = key,
                )
            )
        }
    }

    private fun launchFastCloud(frame: PendingCloudFrame) {
        fastCloudJob = cloudScope.launch {
            try {
                val result = streamAnalysis(
                    bitmap = frame.bitmap,
                    mode = AnalysisMode.TEXT_READING,
                    settings = frame.settings,
                    apiKey = frame.apiKey,
                    generationAtCapture = frame.visualGeneration,
                )
                if (frame.visualGeneration == visualGeneration.get()) runtime.result(result)
            } catch (error: Throwable) {
                if (error !is CancellationException) runtime.error(error.userMessage())
            } finally {
                frame.bitmap.recycle()
                val next = synchronized(cloudQueueLock) {
                    pendingFastCloudFrame.also { pendingFastCloudFrame = null }
                }
                if (next != null && next.visualGeneration == visualGeneration.get()) {
                    synchronized(cloudQueueLock) {
                        lastCloudOcrAt = System.currentTimeMillis()
                        launchFastCloud(next)
                    }
                } else {
                    next?.bitmap?.recycle()
                    synchronized(cloudQueueLock) { fastCloudJob = null }
                }
            }
        }
    }

    private suspend fun recognizeLocal(
        bitmap: Bitmap,
        settings: AppSettings,
        generationAtCapture: Long,
        speakLocally: Boolean,
    ): String {
        if (!settings.localOcrEnabled) return ""
        val localText = runCatching { localTextRecognizer.recognize(bitmap) }.getOrDefault("")
        if (localText.isNotBlank() && generationAtCapture == visualGeneration.get()) {
            val result = AnalysisResult(
                text = localText,
                source = AnalysisSource.LOCAL_OCR,
                language = if (localText.any { it in '\u0600'..'\u06FF' }) "mixed" else "en",
            )
            runtime.result(result)
            if (speakLocally && settings.speechEnabled) {
                tts.speak(
                    text = localText,
                    urgent = false,
                    rate = settings.speechRate,
                    interruptPrevious = false,
                )
            }
        }
        return localText
    }

    private suspend fun processScene(
        bitmap: Bitmap,
        settings: AppSettings,
        generationAtCapture: Long,
    ) {
        val now = System.currentTimeMillis()
        if (now - lastSceneAt < settings.sceneIntervalMs) return
        lastSceneAt = now
        val key = apiKeyStore.get()
            ?: throw IllegalStateException("أدخل مفتاح Gemini أولاً لاستخدام وصف المشهد")
        val result = streamAnalysis(
            bitmap = bitmap,
            mode = AnalysisMode.SCENE_DESCRIPTION,
            settings = settings,
            apiKey = key,
            generationAtCapture = generationAtCapture,
        )
        if (generationAtCapture == visualGeneration.get()) runtime.result(result)
    }

    private suspend fun streamAnalysis(
        bitmap: Bitmap,
        mode: AnalysisMode,
        settings: AppSettings,
        apiKey: String,
        generationAtCapture: Long,
    ): AnalysisResult = analyzeFrame(
        bitmap = bitmap,
        mode = mode,
        model = settings.model,
        apiKey = apiKey,
        forceCellular = settings.forceCellular,
        sceneDescriptionStyle = settings.sceneDescriptionStyle,
        onSpeechChunk = { text, urgent ->
            if (generationAtCapture != visualGeneration.get()) {
                throw CancellationException("تغيّر الهدف البصري قبل اكتمال بث Gemini")
            }
            if (settings.speechEnabled && text.isNotBlank()) {
                tts.speak(
                    text = text,
                    urgent = urgent,
                    rate = settings.speechRate,
                    interruptPrevious = false,
                )
            }
        },
    )

    fun onVisualTargetChanged(interruptSpeech: Boolean) {
        visualGeneration.incrementAndGet()
        if (interruptSpeech) tts.onVisualTargetChanged(true)
        synchronized(cloudQueueLock) {
            pendingFastCloudFrame?.bitmap?.recycle()
            pendingFastCloudFrame = null
            fastCloudJob?.cancel()
            fastCloudJob = null
        }
    }

    fun stopSpeech() = tts.stop()

    fun reset() {
        visualGeneration.incrementAndGet()
        lastCloudOcrAt = 0L
        lastSceneAt = 0L
        lastFastCloudSnapshotAt = 0L
        synchronized(cloudQueueLock) {
            fastCloudJob?.cancel()
            fastCloudJob = null
            pendingFastCloudFrame?.bitmap?.recycle()
            pendingFastCloudFrame = null
        }
        tts.stop()
        tts.resetHistory()
    }

    private fun Throwable.userMessage(): String =
        message?.takeIf { it.isNotBlank() } ?: "تعذر تحليل إطار الشاشة"

    private companion object {
        const val FAST_PENDING_SNAPSHOT_INTERVAL_MS = 350L
    }
}
