package com.abdullah.visionbridge.accessibility

import com.abdullah.visionbridge.domain.model.AnalysisMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The button's whole interface is three sentences, so the sentences are what get tested.
 *
 * Someone who cannot see the button has nothing to check a press against except what comes back,
 * which makes the wording load-bearing in a way UI text usually is not: an announcement that does
 * not name the new mode first is a defect of the same order as the press not registering.
 */
class ShortcutCycleTest {

    @Test
    fun `the cycle runs describe then read then stop`() {
        val first = ShortcutCycle.next(ShortcutCycle.State.STOPPED)
        assertEquals(ShortcutCycle.State.DESCRIBE, first.state)
        assertEquals(AnalysisMode.SCENE_DESCRIPTION, first.mode)

        val second = ShortcutCycle.next(first.state)
        assertEquals(ShortcutCycle.State.READ, second.state)
        assertEquals(AnalysisMode.TEXT_READING, second.mode)

        val third = ShortcutCycle.next(second.state)
        assertEquals(ShortcutCycle.State.STOPPED, third.state)
        assertNull("stopped writes no mode", third.mode)
    }

    @Test
    fun `a fourth press starts the cycle again`() {
        var state = ShortcutCycle.State.STOPPED
        repeat(3) { state = ShortcutCycle.next(state).state }
        assertEquals(ShortcutCycle.State.DESCRIBE, ShortcutCycle.next(state).state)
    }

    /** Three presses from anywhere return to where they started, or the button drifts unseen. */
    @Test
    fun `three presses are an identity from every state`() {
        for (start in ShortcutCycle.State.entries) {
            var state = start
            repeat(3) { state = ShortcutCycle.next(state).state }
            assertEquals("from $start", start, state)
        }
    }

    @Test
    fun `only the stopped step suspends analysis`() {
        assertTrue(ShortcutCycle.next(ShortcutCycle.State.STOPPED).analysing)
        assertTrue(ShortcutCycle.next(ShortcutCycle.State.DESCRIBE).analysing)
        assertFalse(ShortcutCycle.next(ShortcutCycle.State.READ).analysing)
    }

    /** The mode is the answer to the press, so it comes first, not after an explanation. */
    @Test
    fun `each announcement names its mode first`() {
        assertTrue(ShortcutCycle.next(ShortcutCycle.State.STOPPED).announcement.startsWith("وصف المشهد"))
        assertTrue(ShortcutCycle.next(ShortcutCycle.State.DESCRIBE).announcement.startsWith("قراءة النص"))
        assertTrue(ShortcutCycle.next(ShortcutCycle.State.READ).announcement.startsWith("إيقاف"))
    }

    /** Stopping without saying how to undo it strands someone who cannot see the button. */
    @Test
    fun `stopping says how to come back`() {
        val stopped = ShortcutCycle.next(ShortcutCycle.State.READ)
        assertTrue(stopped.announcement.contains("مرة أخرى"))
    }

    @Test
    fun `the current position is read from mode and running state`() {
        assertEquals(
            ShortcutCycle.State.DESCRIBE,
            ShortcutCycle.stateOf(AnalysisMode.SCENE_DESCRIPTION, analysing = true),
        )
        assertEquals(
            ShortcutCycle.State.READ,
            ShortcutCycle.stateOf(AnalysisMode.TEXT_READING, analysing = true),
        )
        assertEquals(
            ShortcutCycle.State.STOPPED,
            ShortcutCycle.stateOf(AnalysisMode.TEXT_READING, analysing = false),
        )
    }

    /**
     * Pressing before screen sharing exists cannot cycle anything, and the button would simply feel
     * broken. Naming the single press that is still needed is the difference.
     */
    @Test
    fun `the no-capture message names the one thing left to do`() {
        assertTrue(ShortcutCycle.NO_CAPTURE_ANNOUNCEMENT.contains("ابدأ الالتقاط"))
    }
}
