package com.abdullah.visionbridge.capture

import com.abdullah.visionbridge.domain.model.AnalysisTurn

/**
 * One authority for committing visual output. Validation and publication share the same lock:
 * checking a generation then asynchronously publishing outside this lock is not safe.
 * Actions must be short/non-suspending and must not acquire a competing output lock.
 */
class TurnGate {
    private var generation = 0L
    private var active: AnalysisTurn? = null
    private val seenTurns = mutableSetOf<String>()

    @Synchronized fun generation(): Long = generation

    @Synchronized fun invalidate(clearOutput: () -> Unit = {}): Long {
        generation++
        seenTurns.clear()
        active = null
        clearOutput()
        return generation
    }

    @Synchronized fun withinGeneration(expected: Long, action: () -> Unit): Boolean {
        if (generation != expected) return false
        action()
        return true
    }

    @Synchronized fun activate(turn: AnalysisTurn, clearOutput: () -> Unit = {}): Boolean {
        if (turn.visualGeneration != generation || turn.imageHash != null || !seenTurns.add(turn.turnId)) return false
        active = turn
        clearOutput()
        return true
    }

    @Synchronized fun bindSubmission(turn: AnalysisTurn): Boolean {
        val owner = active ?: return false
        if (owner.imageHash != null || !owner.sameCapture(turn) || turn.imageHash == null) return false
        active = turn
        return true
    }

    @Synchronized fun rejection(turn: AnalysisTurn): String? = when {
        turn.visualGeneration != generation -> "obsolete_generation"
        active == null -> "no_active_turn"
        active!!.turnId != turn.turnId -> "superseded_turn"
        active!!.imageHash == null || turn.imageHash == null -> "unbound_image"
        active != turn -> "identity_mismatch"
        else -> null
    }

    @Synchronized fun commit(turn: AnalysisTurn, publish: () -> Unit): Boolean {
        if (rejection(turn) != null) return false
        publish()
        return true
    }
}
