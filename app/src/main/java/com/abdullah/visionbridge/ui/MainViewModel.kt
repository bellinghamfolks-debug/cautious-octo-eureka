package com.abdullah.visionbridge.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.abdullah.visionbridge.VisionBridgeApp
import com.abdullah.visionbridge.data.diagnostics.DiagnosticHub
import com.abdullah.visionbridge.domain.model.AnalysisMode
import com.abdullah.visionbridge.domain.model.AppSettings
import com.abdullah.visionbridge.domain.model.CaptureProfile
import com.abdullah.visionbridge.domain.model.CaptureState
import com.abdullah.visionbridge.domain.model.LocalReadingQuality
import com.abdullah.visionbridge.domain.model.SceneDescriptionStyle
import com.abdullah.visionbridge.domain.model.ViewportMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as VisionBridgeApp).container
    private val keyState = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)

    val uiState: StateFlow<MainUiState> = combine(
        container.settingsRepository.settings,
        container.runtime.state,
        keyState,
        message,
    ) { settings, capture, hasKey, transientMessage ->
        MainUiState(settings, capture, hasKey, transientMessage)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        MainUiState(),
    )

    init { refreshKeyState() }

    fun setCaptureFailureEvidence(enabled: Boolean) = viewModelScope.launch {
        container.settingsRepository.setCaptureFailureEvidence(enabled)
    }

    fun discardEvidenceFrames() {
        val held = DiagnosticHub.evidenceFrameCount()
        DiagnosticHub.discardEvidence()
        message.value = if (held == 0) {
            "لا توجد لقطات محفوظة."
        } else {
            "حُذفت $held لقطة. ملف التشخيص القادم لن يحتوي أي صورة."
        }
    }

    fun setUseLocalOcr(enabled: Boolean) = viewModelScope.launch {
        container.settingsRepository.setUseLocalOcr(enabled)
        if (!enabled) container.localOcrEngine.release("user_disabled_local_reader")
        message.value = if (enabled) {
            "تم تفعيل PP-OCRv5. ستتم قراءة النص على الجهاز، بينما يبقى وصف المشهد عبر Gemini Live."
        } else {
            "تم إيقاف PP-OCRv5. ستتم قراءة النص عبر Gemini Live فقط."
        }
    }

    fun setDescribeAlongsideText(enabled: Boolean) = viewModelScope.launch {
        container.settingsRepository.setDescribeAlongsideText(enabled)
        message.value = if (enabled) {
            "بعد كل قراءة Live قد تسمع جملة وصفية واحدة إذا كان الوصف مؤكداً."
        } else {
            "سيُقرأ النص وحده دون وصف."
        }
    }

    fun setLocalReadingQuality(quality: LocalReadingQuality) = viewModelScope.launch {
        container.settingsRepository.setLocalReadingQuality(quality)
        message.value = when (quality) {
            LocalReadingQuality.AUTO -> "جودة PP-OCR: تلقائي."
            LocalReadingQuality.FAST -> "جودة PP-OCR: سريع"
            LocalReadingQuality.BALANCED -> "جودة PP-OCR: متوازن"
            LocalReadingQuality.MAXIMUM -> "جودة PP-OCR: أعلى دقة"
        }
    }

    fun setViewportMode(mode: ViewportMode) = viewModelScope.launch {
        container.settingsRepository.setViewportMode(mode)
        message.value = when (mode) {
            ViewportMode.AUTO -> "القص: تلقائي."
            ViewportMode.ESIGHT_FIXED -> "القص: نافذة eSight الثابتة لكل الأوضاع."
            ViewportMode.ESIGHT_TEXT_SAFE -> "القص: eSight نص آمن. القراءة تستخدم نافذة النظارة الثابتة."
        }
    }

    fun saveApiKey(value: String) = viewModelScope.launch {
        runCatching { container.apiKeyStore.save(value) }
            .onSuccess {
                keyState.value = true
                message.value = "تم حفظ Gemini API Key وتشفيره على هذا الجهاز"
            }
            .onFailure { message.value = it.message ?: "تعذر حفظ Gemini API Key" }
    }

    fun deleteApiKey() = viewModelScope.launch {
        container.apiKeyStore.clear()
        keyState.value = false
        message.value = "تم حذف Gemini API Key"
    }

    fun setMode(mode: AnalysisMode) = viewModelScope.launch {
        container.settingsRepository.setMode(mode)
        message.value = when (mode) {
            AnalysisMode.TEXT_READING -> "تم تفعيل قراءة النص"
            AnalysisMode.SCENE_DESCRIPTION -> "تم تفعيل وصف المشهد"
        }
        DiagnosticHub.record("MODE_SELECTED", mapOf("mode" to mode.name))
    }
    fun setModel(model: String) = viewModelScope.launch { container.settingsRepository.setModel(model) }
    fun setForceCellular(enabled: Boolean) = viewModelScope.launch { container.settingsRepository.setForceCellular(enabled) }
    fun setSpeechEnabled(enabled: Boolean) = viewModelScope.launch { container.settingsRepository.setSpeechEnabled(enabled) }
    fun setTrustGateEnabled(enabled: Boolean) = viewModelScope.launch { container.settingsRepository.setTrustGateEnabled(enabled) }
    fun setCaptureProfile(profile: CaptureProfile) = viewModelScope.launch { container.settingsRepository.setCaptureProfile(profile) }
    fun setInterruptSpeechOnVisualChange(enabled: Boolean) = viewModelScope.launch {
        container.settingsRepository.setInterruptSpeechOnVisualChange(enabled)
    }
    fun setSceneDescriptionStyle(style: SceneDescriptionStyle) = viewModelScope.launch {
        container.settingsRepository.setSceneDescriptionStyle(style)
    }
    fun setSpeechRate(rate: Float) = viewModelScope.launch { container.settingsRepository.setSpeechRate(rate) }

    fun markDiagnosticProblem(note: String) = viewModelScope.launch {
        runCatching {
            DiagnosticHub.markProblem(note)
            DiagnosticHub.storageStatus()
        }.onSuccess { status ->
            val megabytes = status.totalBytes.toDouble() / (1024.0 * 1024.0)
            val images = DiagnosticHub.evidenceFrameCount()
            message.value = "تم تحديد لحظة المشكلة. ${status.sessionCount} جلسة، ${String.format(Locale.US, "%.1f", megabytes)} MB، لقطات محفوظة: $images"
        }.onFailure { message.value = it.message ?: "تعذر تحديد لحظة المشكلة" }
    }

    fun exportDiagnostics(onReady: (File) -> Unit) = viewModelScope.launch {
        val evidenceCount = DiagnosticHub.evidenceFrameCount()
        val includesImages = evidenceCount > 0
        message.value = if (includesImages) {
            "جارٍ تجهيز ملف التشخيص ومعه $evidenceCount لقطة محفوظة"
        } else {
            "جارٍ تجهيز ملف التشخيص من دون صور"
        }
        runCatching {
            DiagnosticHub.record(
                "AUTOMATIC_DIAGNOSTIC_EXPORT_REQUESTED",
                mapOf(
                    "manualProblemMarkerRequired" to false,
                    "includesImages" to includesImages,
                    "evidenceFrameCount" to evidenceCount,
                ),
            )
            DiagnosticHub.export()
        }.onSuccess { file ->
            val megabytes = file.length().toDouble() / (1024.0 * 1024.0)
            message.value = if (includesImages) {
                "تم تجهيز ملف التشخيص ومعه $evidenceCount لقطة: ${String.format(Locale.US, "%.1f", megabytes)} MB"
            } else {
                "تم تجهيز ملف التشخيص من دون صور: ${String.format(Locale.US, "%.1f", megabytes)} MB"
            }
            onReady(file)
        }.onFailure { message.value = it.message ?: "تعذر إنشاء ملف التشخيص" }
    }

    fun clearMessage() { message.value = null }

    private fun refreshKeyState() = viewModelScope.launch {
        keyState.value = container.apiKeyStore.hasKey()
    }
}

data class MainUiState(
    val settings: AppSettings = AppSettings(),
    val capture: CaptureState = CaptureState(),
    val hasApiKey: Boolean = false,
    val message: String? = null,
)
