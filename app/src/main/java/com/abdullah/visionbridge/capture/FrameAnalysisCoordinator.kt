package com.abdullah.visionbridge.capture

import android.graphics.Bitmap
import com.abdullah.visionbridge.data.ocr.LocalTextRecognizer
import com.abdullah.visionbridge.data.ocr.SameFrameOcrEvidenceFilter
import com.abdullah.visionbridge.data.speech.BilingualTtsEngine
import com.abdullah.visionbridge.domain.model.AnalysisMode
import com.abdullah.visionbridge.domain.model.AnalysisResult
import com.abdullah.visionbridge.domain.model.AnalysisSource
import com.abdullah.visionbridge.domain.model.AppSettings
import com.abdullah.visionbridge.domain.model.CaptureProfile
import com.abdullah.visionbridge.domain.model.SceneDescriptionStyle
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
        val visualGeneration: Long,
        val apiKey: String,
        val mode: AnalysisMode,
        val localEvidence: String = "",
    )

    private val mutex = Mutex()
    private val cloudScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cloudQueueLock = Any()
    private val visualGeneration = AtomicLong(0L)
    private val evidenceFilter = SameFrameOcrEvidenceFilter()

    private var lastCloudOcrAt = 0L
    private var lastSceneAt = 0L
    private var lastCloudSnapshotAt = 0L
    private var cloudJob: Job? = null
    private var pendingCloudFrame: PendingCloudFrame? = null

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
                AnalysisMode.SCENE_DESCRIPTION -> queueDynamicScene(bitmap, settings, generationAtCapture)
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
        val localText = recognizeLocal(
            bitmap = bitmap,
            settings = settings,
            generationAtCapture = generationAtCapture,
            speakLocally = key == null,
        )
        if (key == null) return

        val now = System.currentTimeMillis()
        val interval = if (localText.isBlank()) 700L else settings.cloudOcrIntervalMs
        if (now - lastCloudOcrAt < interval) return
        lastCloudOcrAt = now

        val result = streamAnalysis(
            bitmap = bitmap,
            mode = AnalysisMode.TEXT_READING,
            settings = settings,
            apiKey = key,
            generationAtCapture = generationAtCapture,
            localEvidence = localText,
        )
        publishIfCurrent(result, settings, generationAtCapture)
    }

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
        val desiredInterval = if (localText.isBlank()) 450L else 750L
        queueCloudFrame(
            frame = PendingCloudFrame(
                bitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false),
                settings = settings,
                visualGeneration = generationAtCapture,
                apiKey = key,
                mode = AnalysisMode.TEXT_READING,
                localEvidence = localText,
            ),
            now = now,
            minimumLaunchIntervalMs = desiredInterval,
            pendingSnapshotIntervalMs = FAST_PENDING_SNAPSHOT_INTERVAL_MS,
        )
    }

    private suspend fun queueDynamicScene(
        bitmap: Bitmap,
        settings: AppSettings,
        generationAtCapture: Long,
    ) {
        val key = apiKeyStore.get()
            ?: throw IllegalStateException("أدخل مفتاح Gemini أولاً لاستخدام وصف المشهد")
        val now = System.currentTimeMillis()
        val minimumInterval = when (settings.sceneDescriptionStyle) {
            SceneDescriptionStyle.BRIEF -> BRIEF_SCENE_INTERVAL_MS
            SceneDescriptionStyle.COMPREHENSIVE -> COMPREHENSIVE_SCENE_INTERVAL_MS
        }
        queueCloudFrame(
            frame = PendingCloudFrame(
                bitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false),
                settings = settings,
                visualGeneration = generationAtCapture,
                apiKey = key,
                mode = AnalysisMode.SCENE_DESCRIPTION,
            ),
            now = now,
            minimumLaunchIntervalMs = minimumInterval,
            pendingSnapshotIntervalMs = SCENE_PENDING_SNAPSHOT_INTERVAL_MS,
        )
    }

    private fun queueCloudFrame(
        frame: PendingCloudFrame,
        now: Long,
        minimumLaunchIntervalMs: Long,
        pendingSnapshotIntervalMs: Long,
    ) {
        synchronized(cloudQueueLock) {
            val running = cloudJob?.isActive == true
            if (running) {
                if (now - lastCloudSnapshotAt < pendingSnapshotIntervalMs) {
                    frame.bitmap.recycle()
                    return
                }
                lastCloudSnapshotAt = now
                pendingCloudFrame?.bitmap?.recycle()
                pendingCloudFrame = frame
                return
            }

            val lastLaunch = if (frame.mode == AnalysisMode.TEXT_READING) lastCloudOcrAt else lastSceneAt
            if (now - lastLaunch < minimumLaunchIntervalMs) {
                frame.bitmap.recycle()
                return
            }
            if (frame.mode == AnalysisMode.TEXT_READING) lastCloudOcrAt = now else lastSceneAt = now
            lastCloudSnapshotAt = now
            launchCloud(frame)
        }
    }

    private fun launchCloud(frame: PendingCloudFrame) {
        cloudJob = cloudScope.launch {
            try {
                val result = streamAnalysis(
                    bitmap = frame.bitmap,
                    mode = frame.mode,
                    settings = frame.settings,
                    apiKey = frame.apiKey,
                    generationAtCapture = frame.visualGeneration,
                    localEvidence = frame.localEvidence,
                )
                publishIfCurrent(result, frame.settings, frame.visualGeneration)
            } catch (error: Throwable) {
                if (error !is CancellationException) runtime.error(error.userMessage())
            } finally {
                frame.bitmap.recycle()
                val next = synchronized(cloudQueueLock) {
                    pendingCloudFrame.also { pendingCloudFrame = null }
                }
                val mayContinue = next != null && (
                    !next.settings.interruptSpeechOnVisualChange ||
                        next.visualGeneration == visualGeneration.get()
                    )
                if (mayContinue) {
                    synchronized(cloudQueueLock) {
                        val now = System.currentTimeMillis()
                        if (next!!.mode == AnalysisMode.TEXT_READING) lastCloudOcrAt = now else lastSceneAt = now
                        launchCloud(next)
                    }
                } else {
                    next?.bitmap?.recycle()
                    synchronized(cloudQueueLock) { cloudJob = null }
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

    private suspend fun streamAnalysis(
        bitmap: Bitmap,
        mode: AnalysisMode,
        settings: AppSettings,
        apiKey: String,
        generationAtCapture: Long,
        localEvidence: String,
    ): AnalysisResult {
        var firstSpeechBlock = true
        val rawResult = analyzeFrame(
            bitmap = bitmap,
            mode = mode,
            model = settings.model,
            apiKey = apiKey,
            forceCellular = settings.forceCellular,
            sceneDescriptionStyle = settings.sceneDescriptionStyle,
            onSpeechChunk = { streamedText, urgent ->
                ensureTargetIsAllowed(settings, generationAtCapture)
                val safeText = if (mode == AnalysisMode.TEXT_READING) {
                    evidenceFilter.filter(streamedText, localEvidence)
                } else {
                    streamedText
                }
                if (settings.speechEnabled && safeText.isNotBlank()) {
                    tts.speak(
                        text = safeText,
                        urgent = urgent,
                        rate = settings.speechRate,
                        interruptPrevious = urgent && firstSpeechBlock,
                    )
                    firstSpeechBlock = false
                }
            },
        )

        if (mode != AnalysisMode.TEXT_READING) return rawResult
        val safeFullText = evidenceFilter.filter(rawResult.text, localEvidence)
        if (safeFullText.isBlank()) {
            throw IllegalStateException("لم يبق نص موثوق بعد التحقق من الإطار نفسه")
        }
        return rawResult.copy(text = safeFullText)
    }

    private fun ensureTargetIsAllowed(settings: AppSettings, generationAtCapture: Long) {
        if (
            settings.interruptSpeechOnVisualChange &&
            generationAtCapture != visualGeneration.get()
        ) {
            throw CancellationException("تغيّر الهدف البصري قبل اكتمال بث Gemini")
        }
    }

    private fun publishIfCurrent(
        result: AnalysisResult,
        settings: AppSettings,
        generationAtCapture: Long,
    ) {
        if (
            !settings.interruptSpeechOnVisualChange ||
            generationAtCapture == visualGeneration.get()
        ) {
            runtime.result(result)
        }
    }

    fun onVisualTargetChanged(interruptSpeech: Boolean) {
        visualGeneration.incrementAndGet()
        lastCloudOcrAt = 0L
        lastSceneAt = 0L
        lastCloudSnapshotAt = 0L
        if (!interruptSpeech) return

        tts.onVisualTargetChanged(true)
        synchronized(cloudQueueLock) {
            pendingCloudFrame?.bitmap?.recycle()
            pendingCloudFrame = null
            cloudJob?.cancel()
            cloudJob = null
        }
    }

    fun stopSpeech() = tts.stop()

    fun reset() {
        visualGeneration.incrementAndGet()
        lastCloudOcrAt = 0L
        lastSceneAt = 0L
        lastCloudSnapshotAt = 0L
        synchronized(cloudQueueLock) {
            cloudJob?.cancel()
            cloudJob = null
            pendingCloudFrame?.bitmap?.recycle()
            pendingCloudFrame = null
        }
        tts.stop()
        tts.resetHistory()
    }

    private fun Throwable.userMessage(): String =
        message?.takeIf { it.isNotBlank() } ?: "تعذر تحليل إطار الشاشة"

    private companion object {
        const val FAST_PENDING_SNAPSHOT_INTERVAL_MS = 280L
        const val SCENE_PENDING_SNAPSHOT_INTERVAL_MS = 220L
        const val BRIEF_SCENE_INTERVAL_MS = 500L
        const val COMPREHENSIVE_SCENE_INTERVAL_MS = 750L
    }
}
