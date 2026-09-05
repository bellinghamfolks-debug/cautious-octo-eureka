package com.abdullah.visionbridge.di

import android.content.Context
import com.abdullah.visionbridge.capture.CaptureRuntime
import com.abdullah.visionbridge.capture.FrameAnalysisCoordinator
import com.abdullah.visionbridge.capture.LiveSceneCoordinator
import com.abdullah.visionbridge.data.diagnostics.DiagnosticHub
import com.abdullah.visionbridge.data.diagnostics.DiagnosticRecorder
import com.abdullah.visionbridge.data.gemini.GeminiLiveSceneSession
import com.abdullah.visionbridge.data.gemini.GeminiVisionRepository
import com.abdullah.visionbridge.data.network.CellularNetworkManager
import com.abdullah.visionbridge.data.paddleocr.PaddleOcrEngine
import com.abdullah.visionbridge.data.paddleocr.PaddleOcrVisionRepository
import com.abdullah.visionbridge.data.security.AndroidKeystoreApiKeyStore
import com.abdullah.visionbridge.data.settings.SettingsRepositoryImpl
import com.abdullah.visionbridge.data.speech.BilingualTtsEngine
import com.abdullah.visionbridge.data.speech.LivePcmAudioPlayer
import com.abdullah.visionbridge.data.vision.RoutingVisionRepository
import com.abdullah.visionbridge.domain.repository.ApiKeyStore
import com.abdullah.visionbridge.domain.repository.SettingsRepository
import com.abdullah.visionbridge.domain.repository.VisionAiRepository
import com.abdullah.visionbridge.domain.usecase.AnalyzeFrameUseCase

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val runtime = CaptureRuntime()
    val diagnostics = DiagnosticRecorder(appContext).also(DiagnosticHub::initialize)
    val settingsRepository: SettingsRepository = SettingsRepositoryImpl(appContext)
    val apiKeyStore: ApiKeyStore = AndroidKeystoreApiKeyStore(appContext)
    private val networkManager = CellularNetworkManager(appContext)
    private val cloudVisionRepository = GeminiVisionRepository(networkManager)

    /**
     * The proven 3.1.1 on-device Arabic/English reader. Its PP-OCR/ONNX path is deliberately left
     * untouched by the Live update; Live is a scene-description transport, not an OCR replacement.
     */
    val localOcrEngine = PaddleOcrEngine(appContext)

    private val visionRepository: VisionAiRepository = RoutingVisionRepository(
        cloud = cloudVisionRepository,
        local = PaddleOcrVisionRepository(localOcrEngine, settingsRepository),
        localEngine = localOcrEngine,
        settingsRepository = settingsRepository,
    )

    /** Long-lived speech engine used by the original 3.1.1 reader and legacy scene fallback. */
    val tts = BilingualTtsEngine(appContext)

    /** Exact 13-August coordinator retained as the reading engine and Live failure fallback. */
    private val legacyCoordinator = FrameAnalysisCoordinator(
        settingsRepository = settingsRepository,
        apiKeyStore = apiKeyStore,
        analyzeFrame = AnalyzeFrameUseCase(visionRepository),
        tts = tts,
        runtime = runtime,
    )

    private val liveAudioPlayer = LivePcmAudioPlayer()
    private val liveSceneSession = GeminiLiveSceneSession(
        runtime = runtime,
        audioPlayer = liveAudioPlayer,
    )

    /**
     * Public capture entry point. Text goes straight to the untouched 3.1.1 coordinator; scene
     * description tries the persistent Live socket first and falls back to the same old coordinator.
     */
    val coordinator = LiveSceneCoordinator(
        settingsRepository = settingsRepository,
        apiKeyStore = apiKeyStore,
        liveScene = liveSceneSession,
        legacy = legacyCoordinator,
    )
}
