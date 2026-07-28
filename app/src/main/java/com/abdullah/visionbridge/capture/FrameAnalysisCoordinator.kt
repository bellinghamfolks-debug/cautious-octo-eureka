package com.abdullah.visionbridge.capture

import android.graphics.Bitmap
import com.abdullah.visionbridge.data.ocr.LocalTextRecognizer
import com.abdullah.visionbridge.data.speech.BilingualTtsEngine
import com.abdullah.visionbridge.data.speech.SpeechTextTools
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
            runtime.error(error.userMessage())
        } finally {
            runtime.processing(false)
        }
    }

    private suspend fun processStableText(
        bitmap: Bitmap,
        settings: AppSettings,
        generationAtCapture: Long,
    ) {
        val localText = recognizeLocal(bitmap, settings, generationAtCapture)
        val now = System.currentTimeMillis()
        val interval = if (localText.isBlank()) 1_200L else settings.cloudOcrIntervalMs
        if (now - lastCloudOcrAt < interval) return
        lastCloudOcrAt = now

        val key = apiKeyStore.get() ?: return
        val result = analyzeFrame(
            bitmap = bitmap,
            mode = AnalysisMode.TEXT_READING,
            model = settings.model,
            apiKey = key,
            forceCellular = settings.forceCellular,
            sceneDescriptionStyle = settings.sceneDescriptionStyle,
        )
        if (generationAtCapture != visualGeneration.get()) return
        publishCloudText(result, localText, settings)
    }

    /**
     * Local OCR completes immediately and releases the capture pipeline. Gemini runs on a copied
     * snapshot in a separate bounded latest-frame queue, so cloud latency cannot make fast text
     * disappear before the next on-device recognition pass.
     */
    private suspend fun processFastText(
        bitmap: Bitmap,
        settings: AppSettings,
        generationAtCapture: Long,
    ) {
        val localText = recognizeLocal(bitmap, settings, generationAtCapture)
        val key = apiKeyStore.get() ?: return
        val now = System.currentTimeMillis()
        val desiredInterval = if (localText.isBlank()) 650L else 1_200L

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
                ),
                key,
            )
        }
    }

    private fun launchFastCloud(frame: PendingCloudFrame, apiKey: String) {
        fastCloudJob = cloudScope.launch {
            try {
                val result = analyzeFrame(
                    bitmap = frame.bitmap,
                    mode = AnalysisMode.TEXT_READING,
                    model = frame.settings.model,
                    apiKey = apiKey,
                    forceCellular = frame.settings.forceCellular,
                    sceneDescriptionStyle = frame.settings.sceneDescriptionStyle,
                )
                if (frame.visualGeneration == visualGeneration.get()) {
                    publishCloudText(result, frame.localText, frame.settings)
                }
            } catch (error: Throwable) {
                if (error !is CancellationException) runtime.error(error.userMessage())
            } finally {
                frame.bitmap.recycle()
                val next = synchronized(cloudQueueLock) {
                    pendingFastCloudFrame.also { pendingFastCloudFrame = null }
                }
                if (next != null) {
                    val nextKey = apiKeyStore.get()
                    if (nextKey != null) {
                        synchronized(cloudQueueLock) {
                            lastCloudOcrAt = System.currentTimeMillis()
                            launchFastCloud(next, nextKey)
                        }
                    } else {
                        next.bitmap.recycle()
                        synchronized(cloudQueueLock) { fastCloudJob = null }
                    }
                } else {
                    synchronized(cloudQueueLock) { fastCloudJob = null }
                }
            }
        }
    }

    private suspend fun recognizeLocal(
        bitmap: Bitmap,
        settings: AppSettings,
        generationAtCapture: Long,
    ): String {
        if (!settings.localOcrEnabled) return ""
        val localText = runCatching { localTextRecognizer.recognize(bitmap) }.getOrDefault("")
        if (localText.isNotBlank() && generationAtCapture == visualGeneration.get()) {
            publish(
                AnalysisResult(
                    text = localText,
                    source = AnalysisSource.LOCAL_OCR,
                    language = if (localText.any { it in '\u0600'..'\u06FF' }) "mixed" else "en",
                ),
                settings,
            )
        }
        return localText
    }

    private suspend fun publishCloudText(
        result: AnalysisResult,
        localText: String,
        settings: AppSettings,
    ) {
        if (result.text.isBlank()) return
        val speechText = SpeechTextTools.cloudDeltaAgainstLocal(result.text, localText)
        publish(result, settings, speechText)
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
        val result = analyzeFrame(
            bitmap = bitmap,
            mode = AnalysisMode.SCENE_DESCRIPTION,
            model = settings.model,
            apiKey = key,
            forceCellular = settings.forceCellular,
            sceneDescriptionStyle = settings.sceneDescriptionStyle,
        )
        if (generationAtCapture == visualGeneration.get() && result.text.isNotBlank()) {
            publish(result, settings)
        }
    }

    private suspend fun publish(
        result: AnalysisResult,
        settings: AppSettings,
        speechText: String = result.text,
    ) {
        runtime.result(result)
        if (settings.speechEnabled && speechText.isNotBlank()) {
            tts.speak(
                text = speechText,
                urgent = result.urgent,
                rate = settings.speechRate,
                interruptPrevious = false,
            )
        }
    }

    fun onVisualTargetChanged(interruptSpeech: Boolean) {
        visualGeneration.incrementAndGet()
        if (interruptSpeech) tts.onVisualTargetChanged(true)
        synchronized(cloudQueueLock) {
            pendingFastCloudFrame?.bitmap?.recycle()
            pendingFastCloudFrame = null
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

    private fun Throwable.userMessage(): String = when (this) {
        is CancellationException -> throw this
        else -> message?.takeIf { it.isNotBlank() } ?: "تعذر تحليل إطار الشاشة"
    }

    private companion object {
        const val FAST_PENDING_SNAPSHOT_INTERVAL_MS = 450L
    }
}
