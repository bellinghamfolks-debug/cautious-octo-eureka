package com.abdullah.visionbridge.data.speech

import com.abdullah.visionbridge.domain.model.AnalysisTurn

/** First useful speech must start promptly. A delivered predecessor may make its next clause
 * eligible later; this does not erase the original queue age from diagnostics. */
class VisualSpeechTimeline {
    data class Window(val queuedAtNanos: Long, val eligibleAtNanos: Long, val deadlineNanos: Long) {
        fun expired(nowNanos: Long): Boolean = nowNanos >= deadlineNanos
    }

    private var completed: Pair<AnalysisTurn, Long>? = null

    @Synchronized fun window(turn: AnalysisTurn, section: String?, queuedAt: Long): Window {
        val predecessor = completed?.takeIf { it.first == turn }?.second ?: queuedAt
        val eligible = if (section == "SCENE_TAIL") queuedAt else maxOf(queuedAt, predecessor)
        return Window(queuedAt, eligible, eligible + START_BUDGET_NANOS)
    }

    @Synchronized fun delivered(turn: AnalysisTurn, completedAt: Long) { completed = turn to completedAt }
    @Synchronized fun invalidate() { completed = null }

    companion object { const val START_BUDGET_NANOS = 1_000_000_000L }
}
