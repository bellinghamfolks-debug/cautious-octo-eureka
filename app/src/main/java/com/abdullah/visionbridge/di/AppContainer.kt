package com.abdullah.visionbridge.di

import android.content.Context
import com.abdullah.visionbridge.capture.CaptureRuntime
import com.abdullah.visionbridge.capture.FrameAnalysisCoordinator
import com.abdullah.visionbridge.data.diagnostics.DiagnosticHub
import com.abdullah.visionbridge.data.diagnostics.DiagnosticRecorder
import com.abdullah.visionbridge.data.gemini.GeminiVisionRepository
import com.abdullah.visionbridge.data.network.CellularNetworkManager
import com.abdullah.visionbridge.data.ocr.LocalTextRecognizer
import com.abdullah.visionbridge.data.security.AndroidKeystoreApiKeyStore
import com.abdullah.visionbridge.data.settings.SettingsRepositoryImpl
import com.abdullah.visionbridge.data.speech.BilingualTtsEngine
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
    private val localOcr = LocalTextRecognizer()
    private val tts = BilingualTtsEngine(appContext)

    val coordinator = FrameAnalysisCoordinator(
        settingsRepository = settingsRepository,
        apiKeyStore = apiKeyStore,
        localTextRecognizer = localOcr,
        analyzeFrame = AnalyzeFrameUseCase(visionRepository),
        tts = tts,
        runtime = runtime,
    )
}
