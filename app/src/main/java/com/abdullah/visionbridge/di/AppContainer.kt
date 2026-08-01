package com.abdullah.visionbridge.di

import android.content.Context
import com.abdullah.visionbridge.capture.CaptureRuntime
import com.abdullah.visionbridge.capture.FrameAnalysisCoordinator
import com.abdullah.visionbridge.data.diagnostics.DiagnosticHub
import com.abdullah.visionbridge.data.diagnostics.DiagnosticRecorder
import com.abdullah.visionbridge.data.gemini.GeminiVisionRepository
import com.abdullah.visionbridge.data.paddleocr.PaddleOcrEngine
import com.abdullah.visionbridge.data.paddleocr.PaddleOcrVisionRepository
import com.abdullah.visionbridge.data.network.CellularNetworkManager
import com.abdullah.visionbridge.data.security.AndroidKeystoreApiKeyStore
import com.abdullah.visionbridge.data.settings.SettingsRepositoryImpl
import com.abdullah.visionbridge.data.speech.BilingualTtsEngine
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
     * On-device reader. The models are packaged in the APK; nothing is loaded into memory until
     * the user turns local reading on and a frame arrives.
     */
    val localOcrEngine = PaddleOcrEngine(appContext)

    /**
     * Single entry point for both Read and Describe. The coordinator never learns
     * which engine ran, so the reading, speech and diagnostics layers are shared.
     */
    private val visionRepository: VisionAiRepository = RoutingVisionRepository(
        cloud = cloudVisionRepository,
        local = PaddleOcrVisionRepository(localOcrEngine, settingsRepository),
        localEngine = localOcrEngine,
        settingsRepository = settingsRepository,
    )
    private val tts = BilingualTtsEngine(appContext)

    val coordinator = FrameAnalysisCoordinator(
        settingsRepository = settingsRepository,
        apiKeyStore = apiKeyStore,
        analyzeFrame = AnalyzeFrameUseCase(visionRepository),
        tts = tts,
        runtime = runtime,
    )
}
