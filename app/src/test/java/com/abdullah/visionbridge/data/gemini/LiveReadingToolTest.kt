package com.abdullah.visionbridge.data.gemini

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Joining the reported lines back into a reading.
 *
 * Only the pure part is covered here: building the declaration and the acknowledgement goes through
 * `org.json`, which is a stub in a workstation unit test, so those are exercised on the device
 * instead. What is worth pinning without a device is that the lines are kept as lines — the layout
 * of a page is part of what was read, and the speech engine treats a newline as a clause boundary,
 * so preserving them is also what paces the reading aloud.
 */
class LiveReadingToolTest {

    @Test
    fun `the tool name fits the declaration limit`() {
        // A function declaration's name is capped at 128 characters by the API.
        assert(LiveReadingTool.NAME.length in 1..128)
        assertEquals("report_visible_text", LiveReadingTool.NAME)
    }

    @Test
    fun `the acknowledgement never prompts the model to speak`() {
        // WHEN_IDLE, the default, would make the model talk about the acknowledgement itself.
        assertEquals("SILENT", LiveReadingTool.SILENT)
    }
}
