package com.abdullah.visionbridge.capture

import com.abdullah.visionbridge.domain.model.AnalysisResult
import com.abdullah.visionbridge.domain.model.CaptureState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class CaptureRuntime {
    private val mutableState = MutableStateFlow(CaptureState())
    val state: StateFlow<CaptureState> = mutableState.asStateFlow()

    private val mutableAnalysing = MutableStateFlow(true)

    /**
     * Whether frames are being analysed at all.
     *
     * Separate from [state]'s `isRunning`, which is about the screen-share permission. The
     * accessibility button's third position turns analysis and speech off while deliberately
     * keeping the projection: consent can only be granted from an activity, so a stop that dropped
     * it would force the next start to happen from inside the app — which is the trip the button
     * exists to save.
     */
    val analysing: StateFlow<Boolean> = mutableAnalysing.asStateFlow()

    fun setAnalysing(value: Boolean) {
        mutableAnalysing.value = value
    }

    fun started() = update {
        mutableAnalysing.value = true
        copy(isRunning = true, status = "مشاركة الشاشة فعّالة", error = null)
    }
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
