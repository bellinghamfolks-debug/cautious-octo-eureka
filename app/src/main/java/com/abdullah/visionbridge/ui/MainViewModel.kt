package com.abdullah.visionbridge.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.abdullah.visionbridge.VisionBridgeApp
import com.abdullah.visionbridge.data.diagnostics.DiagnosticHub
import com.abdullah.visionbridge.data.paddleocr.PaddleOcrModelStore
import com.abdullah.visionbridge.domain.model.AnalysisMode
import com.abdullah.visionbridge.domain.model.AppSettings
import com.abdullah.visionbridge.domain.model.CaptureProfile
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

    private val localModel = MutableStateFlow(LocalModelUiState())

    val uiState: StateFlow<MainUiState> = combine(
        container.settingsRepository.settings,
        container.runtime.state,
        keyState,
        message,
        localModel,
    ) { settings, capture, hasKey, transientMessage, model ->
        MainUiState(settings, capture, hasKey, transientMessage, model)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        MainUiState(),
    )

    init {
        refreshKeyState()
        refreshLocalModelState()
    }

    fun setUseLocalOcr(enabled: Boolean) = viewModelScope.launch {
        container.settingsRepository.setUseLocalOcr(enabled)
        if (!enabled) container.localOcrEngine.release("user_disabled_local_reader")
        refreshLocalModelState()
        val missing = container.localOcrModelStore.missing()
        message.value = when {
            !enabled -> "عادت قراءة النص إلى Gemini السحابي."
            missing.isEmpty() -> "تم تفعيل القراءة المحلية. قراءة النص تعمل على الجهاز، ووصف المشهد يبقى سحابياً."
            else -> "فعّلت القراءة المحلية، لكن ينقص: " + missing.joinToString("، ") { it.label }
        }
    }

    fun installLocalModelArtifact(artifact: PaddleOcrModelStore.Artifact, source: Uri) =
        viewModelScope.launch {
            message.value = "يجري نسخ ${artifact.label}"
            container.localOcrModelStore.install(artifact, source)
                .onSuccess { file ->
                    refreshLocalModelState()
                    val kilobytes = file.length() / 1024
                    message.value = "تم تثبيت ${artifact.label}، الحجم $kilobytes كيلوبايت"
                }
                .onFailure { message.value = it.message ?: "تعذر تثبيت الملف" }
        }

    fun deleteLocalModel() = viewModelScope.launch {
        container.localOcrEngine.release("model_deleted")
        container.localOcrModelStore.deleteAll()
        refreshLocalModelState()
        message.value = "تم حذف ملفات القارئ المحلي"
    }

    private fun refreshLocalModelState() {
        val store = container.localOcrModelStore
        localModel.value = LocalModelUiState(
            installed = PaddleOcrModelStore.Artifact.entries.filter(store::isInstalled),
            missing = store.missing(),
            totalKilobytes = PaddleOcrModelStore.Artifact.entries
                .sumOf { store.installedBytes(it) } / 1024,
        )
    }

    fun saveApiKey(value: String) = viewModelScope.launch {
        runCatching { container.apiKeyStore.save(value) }
            .onSuccess {
                keyState.value = true
                message.value = "تم حفظ المفتاح مشفراً داخل الجهاز"
            }
            .onFailure { message.value = it.message ?: "تعذر حفظ المفتاح" }
    }

    fun deleteApiKey() = viewModelScope.launch {
        container.apiKeyStore.clear()
        keyState.value = false
        message.value = "تم حذف المفتاح"
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
                "أضيفت العلامة الاختيارية. التشخيص التلقائي كان يعمل مسبقاً، ومحفوظ ${status.sessionCount} جلسة بلا صور بحجم ${String.format(Locale.US, "%.1f", megabytes)} ميجابايت"
        }.onFailure { message.value = it.message ?: "تعذر إضافة العلامة الاختيارية" }
    }

    fun exportDiagnostics(onReady: (File) -> Unit) = viewModelScope.launch {
        message.value = "يجري ضغط سجل التشخيص التلقائي الشامل بلا صور"
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
                "تم تجهيز التشخيص التلقائي الشامل بلا صور: ${String.format(Locale.US, "%.1f", megabytes)} ميجابايت"
            onReady(file)
        }.onFailure { message.value = it.message ?: "تعذر إنشاء حزمة التشخيص التلقائي" }
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
    val localModel: LocalModelUiState = LocalModelUiState(),
)

/** Installation state of the two on-device model files. */
data class LocalModelUiState(
    val installed: List<PaddleOcrModelStore.Artifact> = emptyList(),
    val missing: List<PaddleOcrModelStore.Artifact> = PaddleOcrModelStore.Artifact.entries.toList(),
    val totalKilobytes: Long = 0,
) {
    val isReady: Boolean get() = missing.isEmpty()
}
