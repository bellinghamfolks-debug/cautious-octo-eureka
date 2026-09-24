package com.abdullah.visionbridge.capture

import com.abdullah.visionbridge.domain.model.AnalysisResult
import com.abdullah.visionbridge.domain.model.CaptureState
import com.abdullah.visionbridge.data.diagnostics.DiagnosticTrace
import com.abdullah.visionbridge.data.diagnostics.FrameStages
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class CaptureRuntime {
    val turnGate = TurnGate()
    private val mutableState = MutableStateFlow(CaptureState())
    private data class DisplayRevision(val result: AnalysisResult, val acceptedAt: Long)
    private val awaitingDisplay = AtomicReference<DisplayRevision?>(null)
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
        val runtimeStartedAt = SystemClock.elapsedRealtimeNanos()
        val turn = value.turn ?: run {
            com.abdullah.visionbridge.data.diagnostics.DiagnosticHub.record("RESULT_DROPPED",mapOf("reason" to "missing_turn_identity"))
            return false
        }
        val accepted = turnGate.commit(turn) {
            update { copy(lastResult=value, status="اكتمل التحليل", error=null) }
            val acceptedAt = SystemClock.elapsedRealtimeNanos()
            awaitingDisplay.set(DisplayRevision(value, acceptedAt))
            com.abdullah.visionbridge.data.diagnostics.DiagnosticHub.record("RUNTIME_RESULT",turn.fields()+mapOf("acceptedAtElapsedNanos" to acceptedAt,"acceptedContentHash" to value.contentHash,"readText" to value.text,"sceneTail" to value.sceneTail))
            com.abdullah.visionbridge.data.diagnostics.DiagnosticHub.record("OUTPUT_COMMITTED",turn.fields()+mapOf("acceptedAtElapsedNanos" to acceptedAt,"acceptedContentHash" to value.contentHash))
            FrameStages.record(value.trace(), "runtimeAcceptance", runtimeStartedAt, acceptedAt)
        }
        if (!accepted) com.abdullah.visionbridge.data.diagnostics.DiagnosticHub.record(
            "RESULT_DROPPED",turn.fields()+mapOf("reason" to turnGate.rejection(turn)))
        return accepted
    }
    fun clearVisualResult() {
        awaitingDisplay.set(null)
        update { copy(lastResult=null, isProcessing=false) }
    }
    fun displayed(value: AnalysisResult) {
        value.turn?.let { turnGate.commit(it) {
            val revision = awaitingDisplay.get()
            // A cancelled Compose effect must not acknowledge an older streaming prefix.
            if(revision?.result == value && mutableState.value.lastResult == value &&
                awaitingDisplay.compareAndSet(revision, null)) {
                val displayedAt = SystemClock.elapsedRealtimeNanos()
                com.abdullah.visionbridge.data.diagnostics.DiagnosticHub.record("TEXT_DISPLAYED",it.fields()+mapOf("displayedAtElapsedNanos" to displayedAt,"acceptedContentHash" to value.contentHash))
                FrameStages.record(value.trace(), "uiRender", revision.acceptedAt, displayedAt)
            }
        } }
    }
    private fun AnalysisResult.trace(): DiagnosticTrace {
        val owner = requireNotNull(turn)
        return DiagnosticTrace(owner.traceId, owner.frameId, owner.capturedAt,
            owner.capturedAtNanos, owner, acceptedContentHash=contentHash)
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
