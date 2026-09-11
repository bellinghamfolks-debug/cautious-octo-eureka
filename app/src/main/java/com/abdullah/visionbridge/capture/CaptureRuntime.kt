package com.abdullah.visionbridge.capture

import com.abdullah.visionbridge.domain.model.AnalysisResult
import com.abdullah.visionbridge.domain.model.CaptureState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class CaptureRuntime {
    val turnGate = TurnGate()
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
    fun result(value: AnalysisResult): Boolean {
        val turn = value.turn ?: run {
            com.abdullah.visionbridge.data.diagnostics.DiagnosticHub.record("RESULT_DROPPED",mapOf("reason" to "missing_turn_identity"))
            return false
        }
        val accepted = turnGate.commit(turn) {
            update { copy(lastResult=value, status="اكتمل التحليل", error=null) }
            com.abdullah.visionbridge.data.diagnostics.DiagnosticHub.record("RUNTIME_RESULT",turn.fields()+mapOf("acceptedAtElapsedNanos" to android.os.SystemClock.elapsedRealtimeNanos()))
        }
        if (!accepted) com.abdullah.visionbridge.data.diagnostics.DiagnosticHub.record(
            "RESULT_DROPPED",turn.fields()+mapOf("reason" to turnGate.rejection(turn)))
        return accepted
    }
    fun clearVisualResult() = update { copy(lastResult=null, isProcessing=false) }
    fun displayed(value: AnalysisResult) {
        value.turn?.let { turnGate.commit(it) {
            com.abdullah.visionbridge.data.diagnostics.DiagnosticHub.record("TEXT_DISPLAYED",it.fields()+mapOf("displayedAtElapsedNanos" to android.os.SystemClock.elapsedRealtimeNanos()))
        } }
    }
    fun notice(message: String) = update {
        copy(status = message, error = null, isProcessing = false)
    }
    fun error(message: String) = update { copy(error = message, status = "تعذر إكمال التحليل", isProcessing = false) }
    fun stopped(reason: String = "متوقف") = update {
        copy(isRunning = false, isProcessing = false, status = reason)
    }

    private inline fun update(block: CaptureState.() -> CaptureState) {
        mutableState.update { it.block() }
    }
}
