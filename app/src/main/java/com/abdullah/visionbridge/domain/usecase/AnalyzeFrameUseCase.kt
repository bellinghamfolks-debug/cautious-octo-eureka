package com.abdullah.visionbridge.domain.usecase

import android.graphics.Bitmap
import android.os.SystemClock
import com.abdullah.visionbridge.data.diagnostics.DiagnosticsHub
import com.abdullah.visionbridge.domain.model.AnalysisMode
import com.abdullah.visionbridge.domain.model.AnalysisResult
import com.abdullah.visionbridge.domain.model.CaptureProfile
import com.abdullah.visionbridge.domain.model.SceneDescriptionStyle
import com.abdullah.visionbridge.domain.repository.VisionAiRepository

class AnalyzeFrameUseCase(private val repository: VisionAiRepository) {
    suspend operator fun invoke(
        bitmap: Bitmap,
        mode: AnalysisMode,
        model: String,
        apiKey: String,
        forceCellular: Boolean,
        sceneDescriptionStyle: SceneDescriptionStyle,
        captureProfile: CaptureProfile,
        trustGateEnabled: Boolean,
        onSpeechChunk: suspend (text: String, urgent: Boolean) -> Unit,
    ): AnalysisResult {
        val started = SystemClock.elapsedRealtime()
        var firstChunkAt: Long? = null
        DiagnosticsHub.stage("GEMINI_ANALYSIS_START", mapOf(
            "mode" to mode.name,
            "model" to model,
            "forceCellular" to forceCellular,
            "sceneStyle" to sceneDescriptionStyle.name,
            "captureProfile" to captureProfile.name,
            "trustGateEnabled" to trustGateEnabled,
            "width" to bitmap.width,
            "height" to bitmap.height,
            "apiKeyPresent" to apiKey.isNotBlank(),
        ))
        return try {
            val result = repository.analyzeStreaming(
                bitmap = bitmap,
                mode = mode,
                model = model,
                apiKey = apiKey,
                forceCellular = forceCellular,
                sceneDescriptionStyle = sceneDescriptionStyle,
                captureProfile = captureProfile,
                trustGateEnabled = trustGateEnabled,
                onSpeechChunk = { text, urgent ->
                    val now = SystemClock.elapsedRealtime()
                    if (firstChunkAt == null) {
                        firstChunkAt = now
                        DiagnosticsHub.stage("GEMINI_FIRST_SPEECH_CHUNK", mapOf(
                            "latencyMs" to (now - started),
                            "textLength" to text.length,
                            "text" to text,
                            "urgent" to urgent,
                        ))
                    } else {
                        DiagnosticsHub.stage("GEMINI_SPEECH_CHUNK", mapOf(
                            "elapsedMs" to (now - started),
                            "textLength" to text.length,
                            "text" to text,
                            "urgent" to urgent,
                        ))
                    }
                    onSpeechChunk(text, urgent)
                },
            )
            DiagnosticsHub.stage("GEMINI_ANALYSIS_COMPLETE", mapOf(
                "durationMs" to (SystemClock.elapsedRealtime() - started),
                "firstChunkLatencyMs" to firstChunkAt?.minus(started),
                "resultText" to result.text,
                "resultLength" to result.text.length,
                "language" to result.language,
                "urgent" to result.urgent,
                "source" to result.source.name,
            ))
            result
        } catch (error: Throwable) {
            DiagnosticsHub.failure(
                "GEMINI_ANALYSIS",
                error,
                mapOf(
                    "durationMs" to (SystemClock.elapsedRealtime() - started),
                    "firstChunkLatencyMs" to firstChunkAt?.minus(started),
                    "mode" to mode.name,
                    "model" to model,
                ),
            )
            throw error
        }
    }
}
