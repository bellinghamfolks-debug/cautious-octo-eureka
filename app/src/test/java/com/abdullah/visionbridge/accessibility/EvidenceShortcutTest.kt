package com.abdullah.visionbridge.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shortcut's whole user interface is a sentence, so the sentence is what gets tested.
 *
 * A sighted user pressing a switch sees where it landed. The person this is built for hears one
 * announcement and has nothing else to check it against, which makes the wording load-bearing in a
 * way that UI text usually is not: an announcement that omits the new state, or that quietly claims
 * frames were kept when none were, is a defect of the same order as the switch not working.
 */
class EvidenceShortcutTest {

    @Test
    fun `pressing while off turns capture on`() {
        val action = EvidenceShortcut.press(
            currentlyEnabled = false,
            framesAlreadyHeld = 0,
            captureRunning = true,
        )
        assertTrue(action.enable)
        assertTrue("the new state has to be first", action.announcement.startsWith("شُغّل"))
    }

    @Test
    fun `pressing while on turns capture off`() {
        val action = EvidenceShortcut.press(
            currentlyEnabled = true,
            framesAlreadyHeld = 3,
            captureRunning = true,
        )
        assertFalse(action.enable)
        assertTrue(action.announcement.startsWith("أُوقف"))
    }

    /** The count is the only proof the user gets that the moment was actually caught. */
    @Test
    fun `switching off says how many frames were kept`() {
        val action = EvidenceShortcut.press(
            currentlyEnabled = true,
            framesAlreadyHeld = 7,
            captureRunning = true,
        )
        assertTrue("expected the count, got: ${action.announcement}", action.announcement.contains("7"))
    }

    /**
     * Zero frames after a session of trying is a real outcome and a confusing one, so it is stated
     * rather than left to be inferred from a sentence that mentions no number.
     */
    @Test
    fun `switching off with nothing captured says so instead of quoting zero`() {
        val action = EvidenceShortcut.press(
            currentlyEnabled = true,
            framesAlreadyHeld = 0,
            captureRunning = true,
        )
        assertTrue(action.announcement.contains("لم تُحفظ أي لقطة"))
    }

    /**
     * Switching capture on while nothing is being captured is the case that would otherwise look
     * like a broken button: the user presses it, nothing is ever saved, and the announcement gave
     * no reason.
     */
    @Test
    fun `turning on with capture stopped warns that nothing will be saved yet`() {
        val action = EvidenceShortcut.press(
            currentlyEnabled = false,
            framesAlreadyHeld = 0,
            captureRunning = false,
        )
        assertTrue(action.enable)
        assertTrue(action.announcement.contains("مشاركة الشاشة متوقفة"))
    }

    /** Reaching for this button is itself a statement that something is wrong at this instant. */
    @Test
    fun `turning on marks the moment and turning off does not`() {
        assertTrue(EvidenceShortcut.press(false, 0, true).markProblem)
        assertFalse(EvidenceShortcut.press(true, 2, true).markProblem)
    }

    @Test
    fun `the announced ceiling matches the store's own limit`() {
        val action = EvidenceShortcut.press(false, 0, true)
        assertTrue(action.announcement.contains(EvidenceShortcut.FRAME_LIMIT.toString()))
    }

    /** Two presses return to where they started; a toggle that drifts is unusable unseen. */
    @Test
    fun `two presses are an identity`() {
        val first = EvidenceShortcut.press(currentlyEnabled = false, framesAlreadyHeld = 0, captureRunning = true)
        val second = EvidenceShortcut.press(currentlyEnabled = first.enable, framesAlreadyHeld = 1, captureRunning = true)
        assertEquals(false, second.enable)
    }
}
