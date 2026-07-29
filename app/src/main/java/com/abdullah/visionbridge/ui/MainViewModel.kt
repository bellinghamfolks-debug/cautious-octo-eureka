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
import com.abdullah.visionbridge.domain.model.SceneDescriptionStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

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

    fun setLocalOcrEnabled(enabled: Boolean) = viewModelScope.launch {
        container.settingsRepository.setLocalOcrEnabled(enabled)
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

    fun markDiagnosticProblem(note: String) = viewModelScope.launch {
        runCatching {
            DiagnosticHub.markProblem(note)
            DiagnosticHub.storageStatus()
        }.onSuccess { status ->
            message.value =
                "تم تعليم لحظة المشكلة وربطها بأقرب إطار. محفوظ ${status.imageCount} صورة تشخيصية في ${status.sessionCount} جلسة"
        }.onFailure { message.value = it.message ?: "تعذر تعليم لحظة المشكلة" }
    }

    fun exportDiagnostics(onReady: (File) -> Unit) = viewModelScope.launch {
        message.value = "يجري إكمال الكتابات وتجهيز ملف التشخيص الكامل مع الصور"
        runCatching { DiagnosticHub.export() }
            .onSuccess { file ->
                message.value = "تم تجهيز ملف التشخيص الكامل"
                onReady(file)
            }
            .onFailure { message.value = it.message ?: "تعذر إنشاء ملف التشخيص" }
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
