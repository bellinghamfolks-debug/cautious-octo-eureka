package com.abdullah.visionbridge.capture

import kotlinx.coroutines.*

/** One best-effort verifier per session, never a child that the cloud turn must join.
 * Native inference may take time to observe cancellation: keep its slot until it really exits.
 */
class OptionalGroundingLane(
    dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
    private val deadlineMs: Long = 1_200,
    private val maxTimeouts: Int = 2,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var job: Job? = null
    private var timeouts = 0
    private var epoch = 0L

    @Synchronized fun submit(onEvent: (String) -> Unit, verify: suspend () -> Unit): Boolean {
        if (timeouts >= maxTimeouts) { onEvent("circuit_open"); return false }
        if (job?.isCompleted == false) { onEvent("busy"); return false }
        val owner = epoch
        job = scope.launch(start = CoroutineStart.LAZY) {
            onEvent("started")
            try {
                val completed = withTimeoutOrNull(deadlineMs) { verify(); true } == true
                if (completed) onEvent("completed") else {
                    synchronized(this@OptionalGroundingLane) { if(owner==epoch) timeouts++ }
                    onEvent("timeout")
                }
            } catch (e: CancellationException) {
                onEvent("cancelled"); throw e
            } catch (_: Exception) {
                onEvent("unavailable") // Advisory failure never escapes to the cloud owner.
            }
        }.also { it.start() }
        return true
    }

    @Synchronized fun cancel() { job?.cancel() }
    @Synchronized fun resetSession() { job?.cancel(); epoch++; timeouts=0 }
}
