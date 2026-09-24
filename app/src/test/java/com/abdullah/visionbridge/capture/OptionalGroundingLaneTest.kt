package com.abdullah.visionbridge.capture

import com.abdullah.visionbridge.domain.model.AppSettings
import com.abdullah.visionbridge.domain.model.AnalysisMode
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OptionalGroundingLaneTest {
    @Test fun disabledTrustStreamsHundredsOfCharactersAndReleasesCloudBeforeThirtySecondVerifier()=runTest {
        val settings=AppSettings(mode=AnalysisMode.TEXT_READING,useLocalOcr=false,trustGateEnabled=false)
        assertFalse(GroundingPolicy.required(settings))
        assertTrue(GroundingPolicy.advisory(settings))
        val guard=OptionalGroundingLane(StandardTestDispatcher(testScheduler))
        val events=mutableListOf<String>()
        val displayed=mutableListOf<String>();val spoken=mutableListOf<String>()
        // A native call can finish after cancellation; this must not extend the cloud lifetime.
        guard.submit(events::add) { withContext(NonCancellable) { delay(30_000) } }
        val cloud=async {
            consumeLatestTurnOutput(produce={emit ->
                delay(20);emit("Current image first line.")
                delay(30);"Current image first line. "+"visible text ".repeat(65)
            },consume={displayed+=it;spoken+=it})
        }
        advanceTimeBy(51);runCurrent()
        assertTrue(cloud.isCompleted)
        assertEquals(2,displayed.size);assertEquals(displayed,spoken)
        assertTrue(displayed.last().length>751)
        assertFalse(guard.submit(events::add) {}) // No pile-up behind uninterruptible inference.
        assertTrue(events.contains("busy"))
        guard.cancel();advanceUntilIdle()
        assertEquals(2,displayed.size)
    }

    @Test fun optionalCancellationCannotRollbackCommittedCloudAndFreshTurnCanRun()=runTest {
        val guard=OptionalGroundingLane(StandardTestDispatcher(testScheduler))
        val events=mutableListOf<String>();val committed=mutableListOf<String>()
        guard.submit(events::add) { delay(30_000) };runCurrent()
        consumeLatestTurnOutput(produce={"N"},consume={committed+=it})
        guard.cancel()
        consumeLatestTurnOutput(produce={"N+1"},consume={committed+=it})
        runCurrent()
        assertEquals(listOf("N","N+1"),committed)
        assertTrue(events.contains("cancelled"))
    }

    @Test fun twoTimeoutsOpenCircuitUntilNewSession()=runTest {
        val guard=OptionalGroundingLane(StandardTestDispatcher(testScheduler),deadlineMs=50)
        val events=mutableListOf<String>()
        repeat(2) { assertTrue(guard.submit(events::add) { delay(30_000) });advanceUntilIdle() }
        assertEquals(2,events.count { it=="timeout" })
        assertFalse(guard.submit(events::add) { error("must not execute") })
        guard.resetSession()
        assertTrue(guard.submit(events::add) {});advanceUntilIdle()
        assertTrue(events.contains("completed"))
    }

    @Test fun strictAndLocalReadingStillRequireEvidence() {
        assertTrue(GroundingPolicy.required(AppSettings(trustGateEnabled=true,useLocalOcr=false)))
        assertTrue(GroundingPolicy.required(AppSettings(trustGateEnabled=false,useLocalOcr=true)))
        assertFalse(GroundingPolicy.advisory(AppSettings(mode=AnalysisMode.SCENE_DESCRIPTION)))
    }
}
