package com.abdullah.visionbridge.capture

import android.graphics.Bitmap
import com.abdullah.visionbridge.data.diagnostics.DiagnosticHub
import com.abdullah.visionbridge.data.gemini.GeminiLiveSession
import com.abdullah.visionbridge.domain.model.AnalysisMode
import com.abdullah.visionbridge.domain.repository.ApiKeyStore
import com.abdullah.visionbridge.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.first

/**
 * Strict routing for VisionBridge.
 *
 * - Local text reading is used only when the user explicitly enables PP-OCR.
 * - Every cloud visual task is Gemini Live only.
 * - A missing key, setup failure, socket failure, send failure, timeout, or model rejection is
 *   surfaced as a real Live failure. It NEVER falls back to the legacy Gemini request path.
 *
 * This is intentionally strict so field diagnostics cannot report a successful-looking result that
 * was secretly produced by the old cloud path.
 */
class LiveCloudCoordinator(
    private val settingsRepository: SettingsRepository,
    private val apiKeyStore: ApiKeyStore,
    private val live: GeminiLiveSession,
    private val legacy: FrameAnalysisCoordinator,
) {
    suspend fun process(bitmap: Bitmap) {
        val settings = settingsRepository.settings.first()

        // This is an explicit user-selected offline engine, not a fallback.
        if (settings.mode == AnalysisMode.TEXT_READING && settings.useLocalOcr) {
            DiagnosticHub.record(
                "ANALYSIS_ROUTE_SELECTED",
                mapOf(
                    "route" to "LOCAL_PPOCR_EXPLICIT",
                    "mode" to settings.mode.name,
                    "fallback" to false,
                ),
            )
            legacy.process(bitmap)
            return
        }

        val apiKey = apiKeyStore.get()
        if (apiKey.isNullOrBlank()) {
            DiagnosticHub.record(
                "LIVE_REQUIRED_FAILURE",
                mapOf(
                    "reason" to "api_key_missing",
                    "mode" to settings.mode.name,
                    "fallbackAllowed" to false,
                ),
            )
            live.reportLiveRequiredFailure("مفتاح Gemini غير موجود")
            return
        }

        DiagnosticHub.record(
            "ANALYSIS_ROUTE_SELECTED",
            mapOf(
                "route" to "GEMINI_LIVE_REQUIRED",
                "mode" to settings.mode.name,
                "localOcrEnabled" to settings.useLocalOcr,
                "describeAlongsideText" to settings.describeAlongsideText,
                "fallbackAllowed" to false,
            ),
        )

        val handled = runCatching {
            live.submitFrame(bitmap, settings, apiKey)
        }.onFailure { error ->
            DiagnosticHub.failure(
                "LIVE_FRAME_SUBMIT",
                error,
                mapOf(
                    "mode" to settings.mode.name,
                    "describeAlongsideText" to settings.describeAlongsideText,
                    "fallbackAllowed" to false,
                ),
            )
        }.getOrDefault(false)

        if (!handled) {
            DiagnosticHub.record(
                "LIVE_REQUIRED_FAILURE",
                mapOf(
                    "reason" to "live_submit_or_turn_failed",
                    "mode" to settings.mode.name,
                    "describeAlongsideText" to settings.describeAlongsideText,
                    "fallbackAllowed" to false,
                ),
            )
            live.reportLiveRequiredFailure("تعذر إكمال الدور المباشر")
        }
    }

    fun onVisualTargetChanged(interruptSpeech: Boolean) {
        live.onVisualTargetChanged(interruptSpeech)
        // Kept only for the explicitly selected local PP-OCR lane. It never starts cloud analysis.
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
