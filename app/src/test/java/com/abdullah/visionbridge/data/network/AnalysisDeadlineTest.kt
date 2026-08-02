package com.abdullah.visionbridge.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A deadline has to survive the thing that broke every other bound in the app: a process that
 * stops being scheduled and then resumes.
 *
 * From the 2026-08-02 bundle, one request configured with a 24,000 ms budget, a 48,000 ms watchdog
 * and a 45,000 ms OkHttp call timeout ran for 221,605 ms. All three were countdowns driven by
 * schedulers, and all three stopped together.
 */
class AnalysisDeadlineTest {

    private class TestClock(var now: Long = 0L) : () -> Long {
        override fun invoke(): Long = now
        fun advance(ms: Long) { now += ms }
    }

    @Test
    fun `the deadline is an instant fixed when the request starts`() {
        val clock = TestClock(now = 5_000L)
        val deadline = AnalysisDeadline(budgetMs = 24_000L, clock = clock)

        assertEquals(5_000L, deadline.startedAtMs)
        assertEquals(29_000L, deadline.expiresAtMs)
        assertFalse(deadline.hasExpired())
        assertEquals(24_000L, deadline.remainingMs())
    }

    @Test
    fun `a request within budget has not expired`() {
        val clock = TestClock()
        val deadline = AnalysisDeadline(budgetMs = 24_000L, clock = clock)

        clock.advance(23_999L)
        assertFalse(deadline.hasExpired())
        assertEquals(1L, deadline.remainingMs())
        assertEquals(0L, deadline.overrunMs())
    }

    @Test
    fun `a request is expired the instant the budget is reached`() {
        val clock = TestClock()
        val deadline = AnalysisDeadline(budgetMs = 24_000L, clock = clock)

        clock.advance(24_000L)
        assertTrue(deadline.hasExpired())
        assertEquals(0L, deadline.remainingMs())
    }

    /**
     * The device case. Nothing observes the deadline for 197 seconds — no timer fires, no
     * coroutine resumes — and then something finally looks. It must say *expired*, and by how much,
     * rather than starting to count from the moment it woke up.
     */
    @Test
    fun `a gap in scheduling does not extend the deadline`() {
        val clock = TestClock()
        val deadline = AnalysisDeadline(budgetMs = 24_000L, clock = clock)

        // The process stops being scheduled at 8 s and nothing runs again until 221.6 s.
        clock.advance(8_000L)
        assertFalse(deadline.hasExpired())
        clock.advance(213_605L)

        assertTrue("The deadline must still be expired after the gap", deadline.hasExpired())
        assertEquals(0L, deadline.remainingMs())
        assertEquals(221_605L, deadline.elapsedMs())
        assertEquals(197_605L, deadline.overrunMs())
    }

    @Test
    fun `remaining time never goes negative`() {
        val clock = TestClock()
        val deadline = AnalysisDeadline(budgetMs = 1_000L, clock = clock)
        clock.advance(500_000L)
        assertEquals(0L, deadline.remainingMs())
        assertTrue(deadline.overrunMs() > 0L)
    }

    /** Every observer of the same deadline must reach the same conclusion at the same instant. */
    @Test
    fun `all observers agree`() {
        val clock = TestClock()
        val deadline = AnalysisDeadline(budgetMs = 24_000L, clock = clock)
        clock.advance(30_000L)

        val answers = List(4) { deadline.hasExpired() }
        assertTrue(answers.all { it })
        assertEquals(1, answers.toSet().size)
    }
}
