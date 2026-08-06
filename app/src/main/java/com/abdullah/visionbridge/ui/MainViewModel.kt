package com.abdullah.visionbridge.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.abdullah.visionbridge.VisionBridgeApp
import com.abdullah.visionbridge.data.diagnostics.DiagnosticHub
import com.abdullah.visionbridge.domain.model.AnalysisMode
import com.abdullah.visionbridge.domain.model.AppSettings
import com.abdullah.visionbridge.domain.model.CaptureProfile
import com.abdullah.visionbridge.domain.model.LocalReadingQuality
import com.abdullah.visionbridge.domain.model.CaptureState
import com.abdullah.visionbridge.domain.model.SceneDescriptionStyle
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

    init {
        refreshKeyState()
    }

    fun setCaptureFailureEvidence(enabled: Boolean) = viewModelScope.launch {
        container.settingsRepository.setCaptureFailureEvidence(enabled)
    }

    fun setUseLocalOcr(enabled: Boolean) = viewModelScope.launch {
        container.settingsRepository.setUseLocalOcr(enabled)
        if (!enabled) container.localOcrEngine.release("user_disabled_local_reader")
        message.value = if (enabled) {
            "تم تفعيل PP-OCRv5. ستتم قراءة النص على الجهاز، بينما يبقى وصف المشهد عبر Gemini."
        } else {
            "تم إيقاف PP-OCRv5. ستتم قراءة النص عبر Gemini."
        }
    }

    fun setLocalReadingQuality(quality: LocalReadingQuality) = viewModelScope.launch {
        container.settingsRepository.setLocalReadingQuality(quality)
        message.value = when (quality) {
            LocalReadingQuality.AUTO ->
                "جودة OCR على الجهاز: تلقائي. يقيس التطبيق حجم النص ويختار الدقة في كل لقطة."
            LocalReadingQuality.FAST -> "جودة OCR على الجهاز: سريع"
            LocalReadingQuality.BALANCED -> "جودة OCR على الجهاز: متوازن"
            LocalReadingQuality.MAXIMUM -> "جودة OCR على الجهاز: أعلى دقة. قد تستغرق القراءة وقتًا أطول."
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
    }

    fun setModel(model: String) = viewModelScope.launch {
        container.settingsRepository.setModel(model)
    }

    fun setForceCellular(enabled: Boolean) = viewModelScope.launch {
        container.settingsRepository.setForceCellular(enabled)
    }

    fun setSpeechEnabled(enabled: Boolean) = viewModelScope.launch {
        container.settingsRepository.setSpeechEnabled(enabled)
    }

    fun setTrustGateEnabled(enabled: Boolean) = viewModelScope.launch {
        container.settingsRepository.setTrustGateEnabled(enabled)
    }

    fun setCaptureProfile(profile: CaptureProfile) = viewModelScope.launch {
        container.settingsRepository.setCaptureProfile(profile)
    }

    fun setInterruptSpeechOnVisualChange(enabled: Boolean) = viewModelScope.launch {
        container.settingsRepository.setInterruptSpeechOnVisualChange(enabled)
    }

    fun setSceneDescriptionStyle(style: SceneDescriptionStyle) = viewModelScope.launch {
        container.settingsRepository.setSceneDescriptionStyle(style)
    }

    fun setSpeechRate(rate: Float) = viewModelScope.launch {
        container.settingsRepository.setSpeechRate(rate)
    }

    /** Optional label only. The recorder already captures the complete timeline automatically. */
    fun markDiagnosticProblem(note: String) = viewModelScope.launch {
        runCatching {
            DiagnosticHub.markProblem(note)
            DiagnosticHub.storageStatus()
        }.onSuccess { status ->
            val megabytes = status.totalBytes.toDouble() / (1024.0 * 1024.0)
            message.value =
                "تم تحديد لحظة المشكلة. يحتوي السجل على ${status.sessionCount} جلسة من دون صور، بحجم ${String.format(Locale.US, "%.1f", megabytes)} MB"
        }.onFailure { message.value = it.message ?: "تعذر تحديد لحظة المشكلة" }
    }

    fun exportDiagnostics(onReady: (File) -> Unit) = viewModelScope.launch {
        message.value = "جارٍ تجهيز ملف التشخيص من دون صور"
        runCatching {
            DiagnosticHub.record(
                "AUTOMATIC_DIAGNOSTIC_EXPORT_REQUESTED",
                mapOf(
                    "manualProblemMarkerRequired" to false,
                    "includesImages" to false,
                ),
            )
            DiagnosticHub.export()
        }.onSuccess { file ->
            val megabytes = file.length().toDouble() / (1024.0 * 1024.0)
            message.value =
                "تم تجهيز ملف التشخيص من دون صور: ${String.format(Locale.US, "%.1f", megabytes)} MB"
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
