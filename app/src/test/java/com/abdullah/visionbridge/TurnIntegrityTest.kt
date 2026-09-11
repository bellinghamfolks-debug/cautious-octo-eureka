package com.abdullah.visionbridge

import com.abdullah.visionbridge.capture.TurnGate
import com.abdullah.visionbridge.domain.model.AnalysisMode
import com.abdullah.visionbridge.domain.model.AnalysisTurn
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TurnIntegrityTest {
    private fun capture(id: String, generation: Long = 0) = AnalysisTurn(
        id, "trace-$id", "frame-$id", generation, AnalysisMode.TEXT_READING,
        1, 100, "test-model", "test-prompt", "session-$id",
    )
    private fun bind(turn: AnalysisTurn) = turn.copy(submittedAtNanos = 200, imageHash = "a".repeat(64))

    @Test fun noOldChunkRuntimeUiOrSpeechAfterNextTurn() {
        val gate = TurnGate()
        val old = capture("N")
        assertTrue(gate.activate(old)); assertTrue(gate.bindSubmission(bind(old)))
        val generation = gate.invalidate()
        val next = capture("N+1", generation)
        assertTrue(gate.activate(next)); assertTrue(gate.bindSubmission(bind(next)))
        for (boundary in listOf("chunk", "audio", "runtime", "UI", "TTS", "SCENE_TAIL")) {
            assertFalse(boundary, gate.commit(bind(old)) { fail("Old $boundary escaped") })
        }
        assertTrue(gate.commit(bind(next)) {})
    }

    @Test fun newRequestWithinSameTargetStillOwnsItsResponse() {
        val gate = TurnGate(); val first = capture("N"); val next = capture("N+1")
        gate.activate(first); gate.bindSubmission(bind(first))
        gate.activate(next); gate.bindSubmission(bind(next))
        assertFalse(gate.commit(bind(first)) { fail() })
        assertFalse(gate.activate(first))
        assertTrue(gate.commit(bind(next)) {})
    }

    @Test fun cannotRebindHashOrAcceptUnsubmittedIdentity() {
        val gate = TurnGate(); val turn = capture("N")
        gate.activate(turn)
        assertFalse(gate.commit(turn) { fail() })
        assertTrue(gate.bindSubmission(bind(turn)))
        assertFalse(gate.bindSubmission(bind(turn).copy(imageHash = "b".repeat(64))))
        for (bad in listOf(bind(turn).copy(frameId="other"), bind(turn).copy(traceId="other"),
            bind(turn).copy(sessionId="other"), bind(turn).copy(model="other"),
            bind(turn).copy(imageHash="b".repeat(64)), bind(turn).copy(mode=AnalysisMode.SCENE_DESCRIPTION))) {
            assertFalse(gate.commit(bad) { fail() })
        }
    }

    @Test fun delayedCallbackCannotPublishAfterCancellation() {
        val gate = TurnGate(); val turn = capture("N")
        gate.activate(turn); gate.bindSubmission(bind(turn))
        val release = CountDownLatch(1); val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<Boolean> {
                check(release.await(2, TimeUnit.SECONDS))
                gate.commit(bind(turn)) { fail("cancelled callback escaped") }
            }
            gate.invalidate(); release.countDown()
            assertFalse(result.get(2, TimeUnit.SECONDS))
        } finally { executor.shutdownNow() }
    }
}
