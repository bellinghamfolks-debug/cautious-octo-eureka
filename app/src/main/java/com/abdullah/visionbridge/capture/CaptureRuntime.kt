package com.abdullah.visionbridge.capture

import com.abdullah.visionbridge.domain.model.AnalysisResult
import com.abdullah.visionbridge.domain.model.CaptureState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class CaptureRuntime {
    private val mutableState = MutableStateFlow(CaptureState())
    val state: StateFlow<CaptureState> = mutableState.asStateFlow()

    fun started() = update { copy(isRunning = true, status = "مشاركة الشاشة فعّالة", error = null) }
    fun processing(active: Boolean) = update { copy(isProcessing = active) }
    fun result(value: AnalysisResult) = update {
        copy(lastResult = value, status = if (value.urgent) "تنبيه عاجل" else "اكتمل التحليل", error = null)
    }
    fun notice(message: String) = update {
        copy(status = message, error = null, isProcessing = false)
    }
    fun error(message: String) = update { copy(error = message, status = "تعذر إكمال التحليل", isProcessing = false) }
    fun stopped(reason: String = "متوقف") = update {
        copy(isRunning = false, isProcessing = false, status = reason)
    }

    private inline fun update(block: CaptureState.() -> CaptureState) {
        mutableState.value = mutableState.value.block()
    }
}
