package com.abdullah.visionbridge.capture

import android.graphics.Bitmap
import android.os.SystemClock
import com.abdullah.visionbridge.data.diagnostics.DiagnosticHub
import com.abdullah.visionbridge.data.diagnostics.DiagnosticTrace
import com.abdullah.visionbridge.data.gemini.OcrTrustRejectedException
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.EmptyCoroutineContext

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
        val trace: DiagnosticTrace? = null,
    )

    private val mutex = Mutex()
    private val cloudScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cloudQueueLock = Any()
    private val visualGeneration = AtomicLong(0L)
    private val lastTrustFeedbackGeneration = AtomicLong(Long.MIN_VALUE)
    private val evidenceFilter = SameFrameOcrEvidenceFilter()

    private var lastCloudOcrAt = 0L
    private var lastSceneAt = 0L
    private var lastCloudSnapshotAt = 0L
    private var cloudJob: Job? = null
    private var pendingCloudFrame: PendingCloudFrame? = null

    suspend fun process(bitmap: Bitmap) = mutex.withLock {
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
                    "localOcrEnabled" to settings.localOcrEnabled,
                ),
            ),
        )
        runtime.processing(true)
        try {
            when (settings.mode) {
                AnalysisMode.TEXT_READING -> {
                    if (settings.captureProfile == CaptureProfile.FAST_TEXT) {
                        processFastText(bitmap, settings, generationAtCapture, trace)
                    } else {
                        processStableText(bitmap, settings, generationAtCapture, trace)
                    }
                }
                AnalysisMode.SCENE_DESCRIPTION -> queueDynamicScene(bitmap, settings, generationAtCapture, trace)
            }
        } catch (error: OcrTrustRejectedException) {
            DiagnosticHub.record(
                "OCR_TRUST_REJECTED",
                trace.fieldsOrEmpty(mapOf("message" to error.spokenMessage, "visualGeneration" to generationAtCapture)),
            )
            notifyTrustRejection(error, settings, generationAtCapture, trace)
        } catch (error: Throwable) {
            if (error !is CancellationException) {
                DiagnosticHub.failure("COORDINATOR_PROCESS", error, trace.fieldsOrEmpty())
                runtime.error(error.userMessage())
            } else {
                DiagnosticHub.record("COORDINATOR_CANCELLED", trace.fieldsOrEmpty(mapOf("reason" to error.message)))
            }
        } finally {
            DiagnosticHub.record(
                "COORDINATOR_PROCESS_RETURNED",
                trace.fieldsOrEmpty(
                    mapOf("durationMs" to (SystemClock.elapsedRealtimeNanos() - processStarted) / 1_000_000.0),
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
                    "durationMs" to (SystemClock.elapsedRealtimeNanos() - keyStarted) / 1_000_000.0,
                ),
            ),
        )
        val localText = recognizeLocal(bitmap, settings, generationAtCapture, key == null, trace)
        if (key == null) {
            DiagnosticHub.record("CLOUD_SKIPPED", trace.fieldsOrEmpty(mapOf("reason" to "api_key_missing")))
            return
        }

        queueCloudFrame(
            frame = PendingCloudFrame(
                bitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false),
                settings = settings,
                visualGeneration = generationAtCapture,
                apiKey = key,
                mode = AnalysisMode.TEXT_READING,
                localEvidence = localText,
                trace = trace,
            ),
            now = System.currentTimeMillis(),
            minimumLaunchIntervalMs = if (localText.isBlank()) 350L else 500L,
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
        DiagnosticHub.record("API_KEY_LOOKUP_COMPLETED", trace.fieldsOrEmpty(mapOf("hasApiKey" to (key != null))))
        if (key == null) {
            recognizeLocal(bitmap, settings, generationAtCapture, true, trace)
            DiagnosticHub.record("CLOUD_SKIPPED", trace.fieldsOrEmpty(mapOf("reason" to "api_key_missing")))
            return
        }

        if (settings.trustGateEnabled) {
            val localText = recognizeLocal(bitmap, settings, generationAtCapture, false, trace)
            queueFastText(bitmap, settings, generationAtCapture, key, localText, trace)
        } else {
            DiagnosticHub.record(
                "FAST_TEXT_PARALLEL_PATH",
                trace.fieldsOrEmpty(mapOf("cloudQueuedBeforeLocalOcr" to true)),
            )
            queueFastText(bitmap, settings, generationAtCapture, key, "", trace)
            recognizeLocal(bitmap, settings, generationAtCapture, false, trace)
        }
    }

    private fun queueFastText(
        bitmap: Bitmap,
        settings: AppSettings,
        generationAtCapture: Long,
        key: String,
        localEvidence: String,
        trace: DiagnosticTrace?,
    ) {
        queueCloudFrame(
            frame = PendingCloudFrame(
                bitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false),
                settings = settings,
                visualGeneration = generationAtCapture,
                apiKey = key,
                mode = AnalysisMode.TEXT_READING,
                localEvidence = localEvidence,
                trace = trace,
            ),
            now = System.currentTimeMillis(),
            minimumLaunchIntervalMs = if (localEvidence.isBlank()) 250L else 420L,
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
            ?: throw IllegalStateException("أدخل مفتاح Gemini أولاً لاستخدام وصف المشهد")
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
                trace = trace,
            ),
            now = System.currentTimeMillis(),
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
            if (cloudJob?.isActive == true) {
                if (now - lastCloudSnapshotAt < pendingSnapshotIntervalMs) {
                    DiagnosticHub.record(
                        "CLOUD_FRAME_DROPPED",
                        frame.trace.fieldsOrEmpty(
                            mapOf(
                                "reason" to "pending_snapshot_interval",
                                "pendingSnapshotIntervalMs" to pendingSnapshotIntervalMs,
                                "mode" to frame.mode.name,
                            ),
                        ),
                    )
                    frame.bitmap.recycle()
                    return
                }
                lastCloudSnapshotAt = now
                pendingCloudFrame?.let { old ->
                    DiagnosticHub.record(
                        "CLOUD_FRAME_DROPPED",
                        old.trace.fieldsOrEmpty(
                            mapOf(
                                "reason" to "replaced_by_newer_cloud_frame",
                                "replacementFrameId" to frame.trace?.frameId,
                            ),
                        ),
                    )
                    old.bitmap.recycle()
                }
                pendingCloudFrame = frame
                DiagnosticHub.record("CLOUD_FRAME_QUEUED_AS_LATEST", frame.trace.fieldsOrEmpty(mapOf("mode" to frame.mode.name)))
                return
            }

            val lastLaunch = if (frame.mode == AnalysisMode.TEXT_READING) lastCloudOcrAt else lastSceneAt
            if (now - lastLaunch < minimumLaunchIntervalMs) {
                DiagnosticHub.record(
                    "CLOUD_FRAME_DROPPED",
                    frame.trace.fieldsOrEmpty(
                        mapOf(
                            "reason" to "minimum_cloud_launch_interval",
                            "minimumLaunchIntervalMs" to minimumLaunchIntervalMs,
                            "elapsedSinceLastLaunchMs" to (now - lastLaunch),
                            "mode" to frame.mode.name,
                        ),
                    ),
                )
                frame.bitmap.recycle()
                return
            }
            if (frame.mode == AnalysisMode.TEXT_READING) lastCloudOcrAt = now else lastSceneAt = now
            lastCloudSnapshotAt = now
            DiagnosticHub.record("CLOUD_FRAME_LAUNCHING", frame.trace.fieldsOrEmpty(mapOf("mode" to frame.mode.name)))
            launchCloud(frame)
        }
    }

    private fun launchCloud(frame: PendingCloudFrame) {
        val launched = cloudScope.launch {
            val thisJob = coroutineContext[Job]
            val cloudStarted = SystemClock.elapsedRealtimeNanos()
            try {
                val result = withContext(frame.trace ?: EmptyCoroutineContext) {
                    streamAnalysis(
                        bitmap = frame.bitmap,
                        mode = frame.mode,
                        settings = frame.settings,
                        apiKey = frame.apiKey,
                        generationAtCapture = frame.visualGeneration,
                        localEvidence = frame.localEvidence,
                        trace = frame.trace,
                    )
                }
                DiagnosticHub.record(
                    "CLOUD_ANALYSIS_COMPLETED",
                    frame.trace.fieldsOrEmpty(
                        mapOf(
                            "durationMs" to (SystemClock.elapsedRealtimeNanos() - cloudStarted) / 1_000_000.0,
                            "text" to result.text,
                            "language" to result.language,
                            "urgent" to result.urgent,
                            "source" to result.source.name,
                        ),
                    ),
                )
                publishIfCurrent(result, frame.settings, frame.visualGeneration, frame.trace)
            } catch (error: OcrTrustRejectedException) {
                DiagnosticHub.record(
                    "OCR_TRUST_REJECTED",
                    frame.trace.fieldsOrEmpty(mapOf("message" to error.spokenMessage)),
                )
                notifyTrustRejection(error, frame.settings, frame.visualGeneration, frame.trace)
            } catch (error: Throwable) {
                if (error !is CancellationException) {
                    DiagnosticHub.failure("CLOUD_ANALYSIS", error, frame.trace.fieldsOrEmpty())
                    runtime.error(error.userMessage())
                } else {
                    DiagnosticHub.record(
                        "CLOUD_ANALYSIS_CANCELLED",
                        frame.trace.fieldsOrEmpty(mapOf("reason" to error.message)),
                    )
                }
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
                        DiagnosticHub.record("CLOUD_PENDING_FRAME_PROMOTED", next.trace.fieldsOrEmpty(mapOf("mode" to next.mode.name)))
                        launchCloud(next)
                    }
                } else {
                    next?.let { dropped ->
                        DiagnosticHub.record(
                            "CLOUD_FRAME_DROPPED",
                            dropped.trace.fieldsOrEmpty(mapOf("reason" to "stale_visual_generation")),
                        )
                        dropped.bitmap.recycle()
                    }
                    synchronized(cloudQueueLock) {
                        if (cloudJob === thisJob) cloudJob = null
                    }
                }
            }
        }
        cloudJob = launched
    }

    private suspend fun recognizeLocal(
        bitmap: Bitmap,
        settings: AppSettings,
        generationAtCapture: Long,
        speakLocally: Boolean,
        trace: DiagnosticTrace?,
    ): String {
        if (!settings.localOcrEnabled) {
            DiagnosticHub.record("LOCAL_OCR_SKIPPED", trace.fieldsOrEmpty(mapOf("reason" to "disabled")))
            return ""
        }
        val started = SystemClock.elapsedRealtimeNanos()
        DiagnosticHub.record("LOCAL_OCR_STARTED", trace.fieldsOrEmpty())
        val outcome = runCatching { localTextRecognizer.recognize(bitmap) }
        val localText = outcome.getOrElse { error ->
            DiagnosticHub.failure("LOCAL_OCR", error, trace.fieldsOrEmpty())
            ""
        }
        DiagnosticHub.record(
            "LOCAL_OCR_COMPLETED",
            trace.fieldsOrEmpty(
                mapOf(
                    "durationMs" to (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0,
                    "text" to localText,
                    "textLength" to localText.length,
                    "blank" to localText.isBlank(),
                    "speakLocally" to speakLocally,
                ),
            ),
        )
        if (localText.isNotBlank() && generationAtCapture == visualGeneration.get()) {
            val result = AnalysisResult(
                text = localText,
                source = AnalysisSource.LOCAL_OCR,
                language = if (localText.any { it in '\u0600'..'\u06FF' }) "mixed" else "en",
            )
            runtime.result(result)
            DiagnosticHub.record(
                "TEXT_DISPLAYED",
                trace.fieldsOrEmpty(
                    mapOf(
                        "text" to localText,
                        "source" to AnalysisSource.LOCAL_OCR.name,
                        "language" to result.language,
                    ),
                ),
            )
            if (speakLocally && settings.speechEnabled) {
                DiagnosticHub.record(
                    "TTS_REQUESTED",
                    trace.fieldsOrEmpty(
                        mapOf(
                            "text" to localText,
                            "source" to "LOCAL_OCR",
                            "rate" to settings.speechRate,
                            "interruptPrevious" to false,
                        ),
                    ),
                )
                tts.speak(localText, false, settings.speechRate, false)
            }
        } else if (localText.isNotBlank()) {
            DiagnosticHub.record(
                "LOCAL_OCR_RESULT_SUPPRESSED",
                trace.fieldsOrEmpty(
                    mapOf(
                        "reason" to "stale_visual_generation",
                        "text" to localText,
                        "capturedGeneration" to generationAtCapture,
                        "currentGeneration" to visualGeneration.get(),
                    ),
                ),
            )
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
                    "localEvidence" to localEvidence,
                    "localEvidenceLength" to localEvidence.length,
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
            onSpeechChunk = { streamedText, urgent ->
                ensureTargetIsAllowed(settings, generationAtCapture, trace)
                val safeText = if (mode == AnalysisMode.TEXT_READING && settings.trustGateEnabled) {
                    evidenceFilter.filter(streamedText, localEvidence)
                } else {
                    streamedText
                }
                DiagnosticHub.record(
                    "MODEL_TEXT_CHUNK_EMITTED",
                    trace.fieldsOrEmpty(
                        mapOf(
                            "rawText" to streamedText,
                            "text" to safeText,
                            "urgent" to urgent,
                            "removedByEvidenceFilter" to (streamedText.isNotBlank() && safeText.isBlank()),
                        ),
                    ),
                )
                if (settings.speechEnabled && safeText.isNotBlank()) {
                    DiagnosticHub.record(
                        "TTS_REQUESTED",
                        trace.fieldsOrEmpty(
                            mapOf(
                                "text" to safeText,
                                "source" to "GEMINI_STREAM",
                                "rate" to settings.speechRate,
                                "urgent" to urgent,
                                "interruptPrevious" to (urgent && firstSpeechBlock),
                            ),
                        ),
                    )
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

        if (mode != AnalysisMode.TEXT_READING || !settings.trustGateEnabled) return rawResult
        val safeFullText = evidenceFilter.filter(rawResult.text, localEvidence)
        DiagnosticHub.record(
            "EVIDENCE_FILTER_COMPLETED",
            trace.fieldsOrEmpty(
                mapOf(
                    "rawText" to rawResult.text,
                    "text" to safeFullText,
                    "localEvidence" to localEvidence,
                    "accepted" to safeFullText.isNotBlank(),
                ),
            ),
        )
        if (safeFullText.isBlank()) {
            throw OcrTrustRejectedException("النص غير واضح بعد التحقق. قرّب الصورة أو ثبّت النظرة.")
        }
        return rawResult.copy(text = safeFullText)
    }

    private suspend fun notifyTrustRejection(
        error: OcrTrustRejectedException,
        settings: AppSettings,
        generationAtCapture: Long,
        trace: DiagnosticTrace?,
    ) {
        if (!settings.trustGateEnabled) return
        if (settings.interruptSpeechOnVisualChange && generationAtCapture != visualGeneration.get()) {
            DiagnosticHub.record(
                "TRUST_FEEDBACK_SUPPRESSED",
                trace.fieldsOrEmpty(mapOf("reason" to "stale_visual_generation", "message" to error.spokenMessage)),
            )
            return
        }
        if (lastTrustFeedbackGeneration.getAndSet(generationAtCapture) == generationAtCapture) {
            DiagnosticHub.record(
                "TRUST_FEEDBACK_SUPPRESSED",
                trace.fieldsOrEmpty(mapOf("reason" to "already_spoken_for_generation", "message" to error.spokenMessage)),
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

    private fun ensureTargetIsAllowed(
        settings: AppSettings,
        generationAtCapture: Long,
        trace: DiagnosticTrace?,
    ) {
        if (settings.interruptSpeechOnVisualChange && generationAtCapture != visualGeneration.get()) {
            DiagnosticHub.record(
                "STREAM_CHUNK_REJECTED",
                trace.fieldsOrEmpty(
                    mapOf(
                        "reason" to "visual_target_changed",
                        "capturedGeneration" to generationAtCapture,
                        "currentGeneration" to visualGeneration.get(),
                    ),
                ),
            )
            throw CancellationException("تغيّر الهدف البصري قبل اكتمال بث Gemini")
        }
    }

    private fun publishIfCurrent(
        result: AnalysisResult,
        settings: AppSettings,
        generationAtCapture: Long,
        trace: DiagnosticTrace?,
    ) {
        if (!settings.interruptSpeechOnVisualChange || generationAtCapture == visualGeneration.get()) {
            runtime.result(result)
            DiagnosticHub.record(
                "TEXT_DISPLAYED",
                trace.fieldsOrEmpty(
                    mapOf(
                        "text" to result.text,
                        "source" to result.source.name,
                        "language" to result.language,
                        "urgent" to result.urgent,
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

    fun onVisualTargetChanged(interruptSpeech: Boolean) {
        val newGeneration = visualGeneration.incrementAndGet()
        DiagnosticHub.record(
            "VISUAL_TARGET_CHANGED",
            mapOf("newGeneration" to newGeneration, "interruptSpeech" to interruptSpeech),
        )
        lastCloudOcrAt = 0L
        lastSceneAt = 0L
        lastCloudSnapshotAt = 0L
        if (!interruptSpeech) return

        tts.onVisualTargetChanged(true)
        synchronized(cloudQueueLock) {
            pendingCloudFrame?.let { pending ->
                DiagnosticHub.record(
                    "CLOUD_FRAME_DROPPED",
                    pending.trace.fieldsOrEmpty(mapOf("reason" to "visual_target_changed")),
                )
                pending.bitmap.recycle()
            }
            pendingCloudFrame = null
            cloudJob?.cancel(CancellationException("visual_target_changed"))
            cloudJob = null
        }
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
        synchronized(cloudQueueLock) {
            cloudJob?.cancel(CancellationException("coordinator_reset"))
            cloudJob = null
            pendingCloudFrame?.let { pending ->
                DiagnosticHub.record(
                    "CLOUD_FRAME_DROPPED",
                    pending.trace.fieldsOrEmpty(mapOf("reason" to "coordinator_reset")),
                )
                pending.bitmap.recycle()
            }
            pendingCloudFrame = null
        }
        tts.stop()
        tts.resetHistory()
    }

    private fun DiagnosticTrace?.fieldsOrEmpty(extra: Map<String, Any?> = emptyMap()): Map<String, Any?> =
        this?.fields(extra) ?: extra

    private fun Throwable.userMessage(): String =
        message?.takeIf { it.isNotBlank() } ?: "تعذر تحليل إطار الشاشة"

    private companion object {
        const val STABLE_PENDING_SNAPSHOT_INTERVAL_MS = 240L
        const val FAST_PENDING_SNAPSHOT_INTERVAL_MS = 160L
        const val SCENE_PENDING_SNAPSHOT_INTERVAL_MS = 140L
        const val BRIEF_SCENE_INTERVAL_MS = 300L
        const val COMPREHENSIVE_SCENE_INTERVAL_MS = 500L
    }
}
