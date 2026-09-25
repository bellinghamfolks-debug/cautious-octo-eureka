package com.abdullah.visionbridge.di

import android.content.Context
import com.abdullah.visionbridge.capture.CaptureRuntime
import com.abdullah.visionbridge.capture.FrameBoundCoordinator
import com.abdullah.visionbridge.data.diagnostics.DiagnosticHub
import com.abdullah.visionbridge.data.diagnostics.DiagnosticRecorder
import com.abdullah.visionbridge.data.gemini.FrameTurnTransport
import com.abdullah.visionbridge.data.gemini.GeminiLiveTransport
import com.abdullah.visionbridge.data.network.CellularNetworkManager
import com.abdullah.visionbridge.data.paddleocr.PaddleOcrEngine
import com.abdullah.visionbridge.data.security.AndroidKeystoreApiKeyStore
import com.abdullah.visionbridge.data.settings.SettingsRepositoryImpl
import com.abdullah.visionbridge.data.speech.BilingualTtsEngine
import com.abdullah.visionbridge.data.speech.LivePcmAudioPlayer
import com.abdullah.visionbridge.domain.repository.ApiKeyStore
import com.abdullah.visionbridge.domain.repository.SettingsRepository

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val runtime = CaptureRuntime()
    val diagnostics = DiagnosticRecorder(appContext).also(DiagnosticHub::initialize)
    val settingsRepository: SettingsRepository = SettingsRepositoryImpl(appContext)
    val apiKeyStore: ApiKeyStore = AndroidKeystoreApiKeyStore(appContext)
    val localOcrEngine = PaddleOcrEngine(appContext)
    val tts = BilingualTtsEngine(appContext,runtime.turnGate)
    val liveAudio = LivePcmAudioPlayer()
    val liveTransport = GeminiLiveTransport(runtime, apiKeyStore, liveAudio, tts)
    val coordinator = FrameBoundCoordinator(FrameTurnTransport(CellularNetworkManager(appContext)),
        localOcrEngine,apiKeyStore,tts,runtime,PaddleOcrEngine(appContext,advisoryProfile=true))
}
