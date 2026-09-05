package com.abdullah.visionbridge.capture

import android.graphics.Bitmap
import com.abdullah.visionbridge.data.diagnostics.DiagnosticHub
import com.abdullah.visionbridge.data.gemini.GeminiLiveSceneSession
import com.abdullah.visionbridge.domain.model.AnalysisMode
import com.abdullah.visionbridge.domain.repository.ApiKeyStore
import com.abdullah.visionbridge.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.first

/**
 * Preserves the complete 3.1.1 coordinator for reading and fallback, while routing scene description
 * through Gemini Live first. This is intentionally a wrapper rather than a rewrite: PP-OCR, document
 * speech policy, reading ledger, diagnostics and all 13-August freshness fixes remain unchanged.
 */
class LiveSceneCoordinator(
    private val settingsRepository: SettingsRepository,
    private val apiKeyStore: ApiKeyStore,
    private val liveScene: GeminiLiveSceneSession,
    private val legacy: FrameAnalysisCoordinator,
) {
    suspend fun process(bitmap: Bitmap) {
        val settings = settingsRepository.settings.first()
        if (settings.mode != AnalysisMode.SCENE_DESCRIPTION) {
            legacy.process(bitmap)
            return
        }

        val apiKey = apiKeyStore.get()
        if (apiKey.isNullOrBlank()) {
            DiagnosticHub.record("LIVE_SCENE_FALLBACK", mapOf("reason" to "api_key_missing"))
            legacy.process(bitmap)
            return
        }

        val handled = runCatching {
            liveScene.submitScene(bitmap, settings, apiKey)
        }.onFailure { error ->
            DiagnosticHub.failure("LIVE_SCENE_SUBMIT", error)
        }.getOrDefault(false)

        if (!handled) {
            DiagnosticHub.record("LIVE_SCENE_LEGACY_STARTED")
            legacy.process(bitmap)
        }
    }

    fun onVisualTargetChanged(interruptSpeech: Boolean) {
        liveScene.onVisualTargetChanged(interruptSpeech)
        legacy.onVisualTargetChanged(interruptSpeech)
    }

    suspend fun speakNotice(message: String) = legacy.speakNotice(message)

    fun stopSpeech() {
        liveScene.stop()
        legacy.stopSpeech()
    }

    fun reset() {
        liveScene.reset()
        legacy.reset()
    }
}
