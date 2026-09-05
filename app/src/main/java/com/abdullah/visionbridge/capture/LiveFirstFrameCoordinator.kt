package com.abdullah.visionbridge.capture

import android.graphics.Bitmap
import com.abdullah.visionbridge.data.diagnostics.DiagnosticHub
import com.abdullah.visionbridge.data.gemini.GeminiLiveSession
import com.abdullah.visionbridge.domain.repository.ApiKeyStore
import com.abdullah.visionbridge.domain.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * 1.14 routing boundary: both text reading and scene description try the persistent Live lane first.
 *
 * The legacy 1.13 coordinator remains intact as an automatic safety net. Local Arabic OCR is never
 * used by the Live lane. If Live cannot establish or send a frame, the exact same bitmap is handed to
 * the mature SSE/ML Kit fallback rather than surfacing a dead end to the user.
 */
class LiveFirstFrameCoordinator(
    private val settingsRepository: SettingsRepository,
    private val apiKeyStore: ApiKeyStore,
    private val liveSession: GeminiLiveSession,
    private val legacy: FrameAnalysisCoordinator,
    private val runtime: CaptureRuntime,
) {
    suspend fun process(bitmap: Bitmap) {
        val settings = settingsRepository.settings.first()
        runtime.processing(true)
        try {
            val apiKey = apiKeyStore.get()
            val liveHandled = if (apiKey.isNullOrBlank()) {
                false
            } else {
                runCatching { liveSession.submitFrame(bitmap, settings, apiKey) }
                    .onFailure { error ->
                        if (error !is CancellationException) {
                            DiagnosticHub.failure(
                                "LIVE_PRIMARY_LANE",
                                error,
                                mapOf("mode" to settings.mode.name),
                            )
                        }
                    }
                    .getOrDefault(false)
            }

            if (liveHandled) {
                DiagnosticHub.record(
                    "ANALYSIS_ROUTED_TO_LIVE",
                    mapOf(
                        "mode" to settings.mode.name,
                        "localArabicOcrUsed" to false,
                    ),
                )
                return
            }

            DiagnosticHub.record(
                "ANALYSIS_ROUTED_TO_LEGACY_FALLBACK",
                mapOf(
                    "mode" to settings.mode.name,
                    "reason" to if (apiKey.isNullOrBlank()) "api_key_missing" else "live_unavailable",
                ),
            )
            legacy.process(bitmap)
        } finally {
            runtime.processing(false)
        }
    }

    fun onVisualTargetChanged(interruptSpeech: Boolean) {
        liveSession.onVisualTargetChanged(interruptSpeech)
        legacy.onVisualTargetChanged(interruptSpeech)
    }

    fun stopSpeech() {
        liveSession.stop()
        legacy.stopSpeech()
    }

    fun reset() {
        liveSession.reset()
        legacy.reset()
    }
}
