package com.abdullah.visionbridge.data.network

/**
 * One absolute instant by which a request must be finished, fixed when the request is created.
 *
 * The previous arrangement had three overlapping countdowns — a coroutine `withTimeout` for the
 * analysis budget, a `delay` loop for the watchdog, and OkHttp's own call timeout — and all three
 * are driven by schedulers that only advance while the process is being scheduled. The 2026-08-02
 * bundle shows what happens when it is not: after the capture died, a request configured with a
 * 24,000 ms budget, a 48,000 ms watchdog and a 45,000 ms call timeout ran for 221,605 ms, because
 * every timer that was supposed to stop it had stopped too. A second request reached 92,311 ms the
 * same way.
 *
 * A deadline stated as an instant does not have that failure mode. It can be compared against the
 * clock by anything that is running — the next arriving frame, the next health check, the next
 * resumption of the request itself — and it gives the same answer to all of them.
 *
 * Pure, and driven by an injected clock, so its behaviour across a gap in scheduling can be tested
 * without needing a device to freeze.
 */
class AnalysisDeadline(
    val budgetMs: Long,
    private val clock: () -> Long,
) {
    val startedAtMs: Long = clock()

    /** The instant after which this request is no longer worth waiting for. */
    val expiresAtMs: Long = startedAtMs + budgetMs

    fun elapsedMs(): Long = clock() - startedAtMs

    /** Milliseconds left, never negative. Zero means the deadline has passed. */
    fun remainingMs(): Long = (expiresAtMs - clock()).coerceAtLeast(0L)

    fun hasExpired(): Boolean = clock() >= expiresAtMs

    /**
     * How far past the deadline this request has run, which is the number worth recording: a
     * request that overran by 20 ms is a slow model, and one that overran by 197 seconds is a
     * broken timer.
     */
    fun overrunMs(): Long = (clock() - expiresAtMs).coerceAtLeast(0L)
}
