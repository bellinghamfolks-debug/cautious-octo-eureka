package com.abdullah.visionbridge.di

import android.content.Context
import com.abdullah.visionbridge.capture.CaptureRuntime
import com.abdullah.visionbridge.capture.FrameAnalysisCoordinator
import com.abdullah.visionbridge.data.diagnostics.DiagnosticHub
import com.abdullah.visionbridge.data.diagnostics.DiagnosticRecorder
import com.abdullah.visionbridge.data.gemini.GeminiVisionRepository
import com.abdullah.visionbridge.data.localvlm.LocalVlmEngine
import com.abdullah.visionbridge.data.localvlm.LocalVlmModelStore
import com.abdullah.visionbridge.data.localvlm.LocalVlmVisionRepository
import com.abdullah.visionbridge.data.network.CellularNetworkManager
import com.abdullah.visionbridge.data.ocr.InstantLocalOcrBridge
import com.abdullah.visionbridge.data.ocr.LocalTextRecognizer
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

    /** On-device engine. Nothing is loaded until the user turns it on and a frame arrives. */
    val localVlmModelStore = LocalVlmModelStore(appContext)
    val localVlmEngine = LocalVlmEngine(appContext, localVlmModelStore)

    /**
     * Single entry point for both Read and Describe. The coordinator never learns
     * which engine ran, so the reading, speech and diagnostics layers are shared.
     */
    private val visionRepository: VisionAiRepository = RoutingVisionRepository(
        cloud = cloudVisionRepository,
        local = LocalVlmVisionRepository(localVlmEngine),
        localEngine = localVlmEngine,
        modelStore = localVlmModelStore,
        settingsRepository = settingsRepository,
    )
    private val tts = BilingualTtsEngine(appContext)

    init {
        InstantLocalOcrBridge.initialize(
            settingsRepository = settingsRepository,
            runtime = runtime,
            tts = tts,
        )
    }

    private val localOcr = LocalTextRecognizer(appContext)

    val coordinator = FrameAnalysisCoordinator(
        settingsRepository = settingsRepository,
        apiKeyStore = apiKeyStore,
        localTextRecognizer = localOcr,
        analyzeFrame = AnalyzeFrameUseCase(visionRepository),
        tts = tts,
        runtime = runtime,
    )
}
