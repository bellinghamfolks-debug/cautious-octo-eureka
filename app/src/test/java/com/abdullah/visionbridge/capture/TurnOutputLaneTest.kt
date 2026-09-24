package com.abdullah.visionbridge.capture

import com.abdullah.visionbridge.domain.model.AnalysisMode
import com.abdullah.visionbridge.domain.model.AnalysisTurn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TurnOutputLaneTest {
    @Test fun slowOpticalVerificationDoesNotConsumeNetworkBudget() = runTest {
        val evidence = CompletableDeferred<Unit>()
        val networkFinished = CompletableDeferred<Unit>()
        val displayed = mutableListOf<String>()
        val task = async {
            consumeLatestTurnOutput(
                produce = { emit ->
                    withTimeout(100) {
                        emit("first line")
                        delay(10)
                        networkFinished.complete(Unit)
                        "first line\nsecond line"
                    }
                },
                consume = { evidence.await(); displayed += it },
            )
        }
        networkFinished.await()
        delay(1_000) // Much longer than the request budget; verifier still owns no network timer.
        assertFalse(task.isCompleted)
        assertTrue(displayed.isEmpty())
        evidence.complete(Unit)
        assertEquals("first line\nsecond line", task.await())
        assertEquals("first line\nsecond line", displayed.last())
    }

    @Test fun slowConsumerHasOnlyOnePendingCumulativeSnapshotAndKeepsFinal() = runTest {
        val evidence = CompletableDeferred<Unit>()
        val consumerStarted = CompletableDeferred<Unit>()
        val displayed = mutableListOf<Int>()
        val task = async {
            consumeLatestTurnOutput(
                produce = { emit ->
                    emit(0)
                    consumerStarted.await()
                    repeat(10_000) { emit(it + 1) }
                    10_001
                },
                consume = { consumerStarted.complete(Unit); evidence.await(); displayed += it },
            )
        }
        runCurrent()
        evidence.complete(Unit)
        task.await()
        assertEquals(listOf(0, 10_001), displayed)
    }

    @Test fun obsoleteTurnCannotPublishAfterAwaitEvenIfEvidenceFinishesLate() = runTest {
        val gate = TurnGate()
        val old = bind(gate, "old")
        val evidence = CompletableDeferred<Unit>()
        val publications = mutableListOf<String>()
        val task = async {
            consumeLatestTurnOutput(
                produce = { emit -> emit(old); old },
                consume = { turn ->
                    evidence.await()
                    gate.commit(turn) { publications += turn.turnId }
                },
            )
        }
        runCurrent()
        gate.invalidate()
        val current = bind(gate, "new")
        gate.commit(current) { publications += current.turnId }
        evidence.complete(Unit)
        task.await()
        assertEquals(listOf("new"), publications)
    }

    @Test fun cancellingTurnCancelsTransportAndVerificationTogether() = runTest {
        val producerStopped = CompletableDeferred<Unit>()
        val verifierStopped = CompletableDeferred<Unit>()
        val task = async {
            consumeLatestTurnOutput<Int>(
                produce = { emit ->
                    try { emit(1); awaitCancellation() }
                    finally { producerStopped.complete(Unit) }
                },
                consume = {
                    try { awaitCancellation() }
                    finally { verifierStopped.complete(Unit) }
                },
            )
        }
        runCurrent()
        task.cancelAndJoin()
        assertTrue(producerStopped.isCompleted)
        assertTrue(verifierStopped.isCompleted)
    }

    @Test fun requestFailureCancelsPendingOutputWithoutPublishingIt() = runTest {
        val evidence = CompletableDeferred<Unit>()
        val displayed = mutableListOf<Int>()
        val failure = runCatching {
            consumeLatestTurnOutput<Int>(
                produce = { emit -> emit(1); delay(10); error("synthetic transport failure") },
                consume = { evidence.await(); displayed += it },
            )
        }
        evidence.complete(Unit)
        runCurrent()
        assertEquals("synthetic transport failure", failure.exceptionOrNull()?.message)
        assertTrue(displayed.isEmpty())
    }

    private fun bind(gate: TurnGate, id: String): AnalysisTurn {
        val capture = AnalysisTurn(id, id, id, gate.generation(), AnalysisMode.TEXT_READING,
            1, 1, "synthetic", "test", "test-session")
        assertTrue(gate.activate(capture))
        return capture.copy(submittedAtNanos = 2, imageHash = "a".repeat(64)).also {
            assertTrue(gate.bindSubmission(it))
        }
    }
}
