package com.abdullah.visionbridge.di

import android.content.Context
import com.abdullah.visionbridge.capture.CaptureRuntime
import com.abdullah.visionbridge.capture.FrameAnalysisCoordinator
import com.abdullah.visionbridge.capture.LiveFirstFrameCoordinator
import com.abdullah.visionbridge.data.diagnostics.DiagnosticHub
import com.abdullah.visionbridge.data.diagnostics.DiagnosticRecorder
import com.abdullah.visionbridge.data.gemini.GeminiLiveSession
import com.abdullah.visionbridge.data.gemini.GeminiVisionRepository
import com.abdullah.visionbridge.data.network.CellularNetworkManager
import com.abdullah.visionbridge.data.ocr.InstantLocalOcrBridge
import com.abdullah.visionbridge.data.ocr.LocalTextRecognizer
import com.abdullah.visionbridge.data.security.AndroidKeystoreApiKeyStore
import com.abdullah.visionbridge.data.settings.SettingsRepositoryImpl
import com.abdullah.visionbridge.data.speech.BilingualTtsEngine
import com.abdullah.visionbridge.data.speech.LivePcmAudioPlayer
import com.abdullah.visionbridge.domain.repository.ApiKeyStore
import com.abdullah.visionbridge.domain.repository.SettingsRepository
import com.abdullah.visionbridge.domain.usecase.AnalyzeFrameUseCase

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val runtime = CaptureRuntime()
    val diagnostics = DiagnosticRecorder(appContext).also(DiagnosticHub::initialize)
    val settingsRepository: SettingsRepository = SettingsRepositoryImpl(appContext)
    val apiKeyStore: ApiKeyStore = AndroidKeystoreApiKeyStore(appContext)
    private val networkManager = CellularNetworkManager(appContext)
    private val visionRepository = GeminiVisionRepository(networkManager)
    private val tts = BilingualTtsEngine(appContext)
    private val liveAudio = LivePcmAudioPlayer()
    private val liveSession = GeminiLiveSession(
        runtime = runtime,
        audioPlayer = liveAudio,
    )

    init {
        InstantLocalOcrBridge.initialize(
            settingsRepository = settingsRepository,
            runtime = runtime,
            tts = tts,
        )
    }

    private val localOcr = LocalTextRecognizer(appContext)

    private val legacyCoordinator = FrameAnalysisCoordinator(
        settingsRepository = settingsRepository,
        apiKeyStore = apiKeyStore,
        localTextRecognizer = localOcr,
        analyzeFrame = AnalyzeFrameUseCase(visionRepository),
        tts = tts,
        runtime = runtime,
    )

    val coordinator = LiveFirstFrameCoordinator(
        settingsRepository = settingsRepository,
        apiKeyStore = apiKeyStore,
        liveSession = liveSession,
        legacy = legacyCoordinator,
        runtime = runtime,
    )
}
