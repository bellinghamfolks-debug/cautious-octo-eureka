package com.abdullah.visionbridge.capture

import android.graphics.Bitmap
import com.abdullah.visionbridge.data.diagnostics.DiagnosticHub
import com.abdullah.visionbridge.data.gemini.GeminiLiveSession
import com.abdullah.visionbridge.domain.model.AnalysisMode
import com.abdullah.visionbridge.domain.repository.ApiKeyStore
import com.abdullah.visionbridge.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.first

/**
 * Cloud/live switchboard layered over the proven 3.1.1 coordinator.
 *
 * - Local text reading: untouched PP-OCR/ONNX through the legacy coordinator.
 * - Cloud text reading: Gemini Live WebSocket first.
 * - Scene description: Gemini Live WebSocket first.
 * - Any Live connection/setup/send failure: exact 3.1.1 cloud path as fallback.
 */
class LiveCloudCoordinator(
    private val settingsRepository: SettingsRepository,
    private val apiKeyStore: ApiKeyStore,
    private val live: GeminiLiveSession,
    private val legacy: FrameAnalysisCoordinator,
) {
    suspend fun process(bitmap: Bitmap) {
        val settings = settingsRepository.settings.first()

        // The user's local Arabic/English reader is a separate, proven lane and remains untouched.
        if (settings.mode == AnalysisMode.TEXT_READING && settings.useLocalOcr) {
            DiagnosticHub.record(
                "ANALYSIS_ROUTE_SELECTED",
                mapOf("route" to "LOCAL_PPOCR", "mode" to settings.mode.name),
            )
            legacy.process(bitmap)
            return
        }

        // Everything else is a cloud visual task and therefore Live-first.
        val apiKey = apiKeyStore.get()
        if (apiKey.isNullOrBlank()) {
            DiagnosticHub.record(
                "LIVE_FRAME_FALLBACK",
                mapOf("reason" to "api_key_missing", "mode" to settings.mode.name),
            )
            legacy.process(bitmap)
            return
        }

        DiagnosticHub.record(
            "ANALYSIS_ROUTE_SELECTED",
            mapOf(
                "route" to "GEMINI_LIVE",
                "mode" to settings.mode.name,
                "localOcrEnabled" to settings.useLocalOcr,
            ),
        )

        val handled = runCatching {
            live.submitFrame(bitmap, settings, apiKey)
        }.onFailure { error ->
            DiagnosticHub.failure(
                "LIVE_FRAME_SUBMIT",
                error,
                mapOf("mode" to settings.mode.name),
            )
        }.getOrDefault(false)

        if (!handled) {
            DiagnosticHub.record(
                "LIVE_LEGACY_FALLBACK_STARTED",
                mapOf("mode" to settings.mode.name),
            )
            legacy.process(bitmap)
        }
    }

    fun onVisualTargetChanged(interruptSpeech: Boolean) {
        live.onVisualTargetChanged(interruptSpeech)
        legacy.onVisualTargetChanged(interruptSpeech)
    }

    suspend fun speakNotice(message: String) = legacy.speakNotice(message)

    fun stopSpeech() {
        live.stop()
        legacy.stopSpeech()
    }

    fun reset() {
        live.reset()
        legacy.reset()
    }
}
