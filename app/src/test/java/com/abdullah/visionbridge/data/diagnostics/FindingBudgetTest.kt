package com.abdullah.visionbridge.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that turns 2,109 repetitions of one observation back into one observation.
 *
 * The danger in a filter like this is that it quietly removes the line that mattered, so the cases
 * below are as much about what must still get through — a worse instance, a different stage, a
 * critical severity — as about what must not.
 */
class FindingBudgetTest {

    private fun budget() = FindingBudget()

    @Test
    fun `the first few of a kind are always written`() {
        val budget = budget()
        repeat(FindingBudget.WARNING_BURST) { index ->
            assertTrue("instance $index was dropped", budget.admit("SLOW|decode", "warning", 2_100.0))
        }
    }

    @Test
    fun `an identical repeat past the burst is suppressed`() {
        val budget = budget()
        repeat(FindingBudget.WARNING_BURST) { budget.admit("SLOW|decode", "warning", 2_100.0) }
        assertFalse(budget.admit("SLOW|decode", "warning", 2_100.0))
        assertEquals(1, budget.totalSuppressed())
    }

    /** The one thing a filter must never lose: the instance that is worse than anything before it. */
    @Test
    fun `a materially worse instance is written even past the burst`() {
        val budget = budget()
        repeat(FindingBudget.WARNING_BURST) { budget.admit("SLOW|decode", "warning", 2_000.0) }
        assertFalse(budget.admit("SLOW|decode", "warning", 2_200.0))
        assertTrue(budget.admit("SLOW|decode", "warning", 2_000.0 * FindingBudget.ESCALATION))
    }

    /** A new record does not reset the budget; the next one has to beat the new record. */
    @Test
    fun `each escalation raises the bar`() {
        val budget = budget()
        repeat(FindingBudget.WARNING_BURST) { budget.admit("SLOW|decode", "warning", 1_000.0) }
        assertTrue(budget.admit("SLOW|decode", "warning", 1_300.0))
        assertFalse(budget.admit("SLOW|decode", "warning", 1_300.0))
        assertTrue(budget.admit("SLOW|decode", "warning", 1_700.0))
    }

    /** Different stages are different observations, and one must never spend the other's budget. */
    @Test
    fun `keys are independent`() {
        val budget = budget()
        repeat(FindingBudget.WARNING_BURST * 3) { budget.admit("SLOW|decode", "warning", 2_000.0) }
        assertTrue(budget.admit("SLOW|upload", "warning", 2_000.0))
    }

    @Test
    fun `critical findings get a larger burst`() {
        val budget = budget()
        repeat(FindingBudget.WARNING_BURST + 1) { budget.admit("CRASH|tts", "critical", null) }
        assertTrue(budget.admit("CRASH|tts", "critical", null))
    }

    /**
     * A finding with no measurement can never beat a record, so after the burst it is silent. That
     * is deliberate: an unmeasured observation repeated a thousand times adds nothing after the
     * eighth, and pretending otherwise is how the old bundle got its 2,109 lines.
     */
    @Test
    fun `an unmeasured repeat cannot escalate`() {
        val budget = budget()
        repeat(FindingBudget.WARNING_BURST) { budget.admit("DROP|queue_full", "warning", null) }
        repeat(50) { assertFalse(budget.admit("DROP|queue_full", "warning", null)) }
        assertEquals(50, budget.totalSuppressed())
    }

    /** Nothing is dropped silently: what was left out is reported, worst kind first. */
    @Test
    fun `suppressions are reported with their worst value`() {
        val budget = budget()
        repeat(FindingBudget.WARNING_BURST + 5) { budget.admit("SLOW|decode", "warning", 2_000.0) }
        repeat(FindingBudget.WARNING_BURST + 30) { budget.admit("DROP|stale", "warning", null) }
        budget.admit("SLOW|decode", "warning", 2_100.0)

        val suppressions = budget.suppressions()
        assertEquals("DROP|stale", suppressions.first().key)
        val slow = suppressions.first { it.key == "SLOW|decode" }
        assertEquals(2_100.0, slow.worstValue!!, 0.001)
        assertEquals(6, slow.suppressedCount)
    }

    @Test
    fun `a session with no repetition reports nothing`() {
        val budget = budget()
        budget.admit("SLOW|decode", "warning", 2_000.0)
        assertEquals(emptyList<FindingBudget.Suppression>(), budget.suppressions())
        assertEquals(0, budget.totalSuppressed())
    }

    @Test
    fun `a reset starts the next session clean`() {
        val budget = budget()
        repeat(FindingBudget.WARNING_BURST * 2) { budget.admit("SLOW|decode", "warning", 2_000.0) }
        budget.reset()
        assertTrue(budget.admit("SLOW|decode", "warning", 2_000.0))
        assertEquals(0, budget.totalSuppressed())
    }
}
