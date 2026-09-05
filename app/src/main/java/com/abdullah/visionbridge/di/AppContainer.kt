package com.abdullah.visionbridge.di

import android.content.Context
import com.abdullah.visionbridge.capture.CaptureRuntime
import com.abdullah.visionbridge.capture.FrameAnalysisCoordinator
import com.abdullah.visionbridge.capture.LiveCloudCoordinator
import com.abdullah.visionbridge.data.diagnostics.DiagnosticHub
import com.abdullah.visionbridge.data.diagnostics.DiagnosticRecorder
import com.abdullah.visionbridge.data.gemini.GeminiLiveSession
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

    /** Proven 3.1.1 on-device Arabic/English PP-OCR reader. Kept unchanged. */
    val localOcrEngine = PaddleOcrEngine(appContext)

    private val visionRepository: VisionAiRepository = RoutingVisionRepository(
        cloud = cloudVisionRepository,
        local = PaddleOcrVisionRepository(localOcrEngine, settingsRepository),
        localEngine = localOcrEngine,
        settingsRepository = settingsRepository,
    )

    /** Original TTS remains for local reading, notices and legacy cloud fallback. */
    val tts = BilingualTtsEngine(appContext)

    /** Exact 13-August pipeline retained for local PP-OCR and Live failure fallback. */
    private val legacyCoordinator = FrameAnalysisCoordinator(
        settingsRepository = settingsRepository,
        apiKeyStore = apiKeyStore,
        analyzeFrame = AnalyzeFrameUseCase(visionRepository),
        tts = tts,
        runtime = runtime,
    )

    private val liveAudioPlayer = LivePcmAudioPlayer()
    private val liveSession = GeminiLiveSession(
        runtime = runtime,
        audioPlayer = liveAudioPlayer,
    )

    /**
     * Local text -> PP-OCR 3.1.1.
     * Cloud text -> Gemini Live.
     * Scene description -> Gemini Live.
     * Live failure -> original 3.1.1 cloud path.
     */
    val coordinator = LiveCloudCoordinator(
        settingsRepository = settingsRepository,
        apiKeyStore = apiKeyStore,
        live = liveSession,
        legacy = legacyCoordinator,
    )
}
