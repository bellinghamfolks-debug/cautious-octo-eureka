package com.abdullah.visionbridge.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Each rule against the moment of the 2026-09-26 16:33 session it was written for, and against the
 * healthy shape of the same moment, which must say nothing.
 */
class LiveVerdictTest {

    private fun event(type: String, atMs: Long, vararg fields: Pair<String, Any?>) =
        SessionVerdict.Event(type, mapOf("epochMs" to atMs) + fields)

    private fun codes(vararg events: SessionVerdict.Event) = LiveVerdict.analyse(events.toList()).map { it.code }

    @Test
    fun `a turn called silent after its tool call is the best model struck off`() {
        val findings = codes(
            event("LIVE_TOOL_TEXT_REPORTED", 8_560, "turnId" to "t1", "characters" to 0, "model" to "gemini-3.8-live"),
            event("LIVE_TURN_SILENT", 9_190, "turnId" to "t1", "model" to "gemini-3.8-live"),
        )
        assertEquals(listOf("MODEL_STRUCK_AFTER_ANSWERING"), findings)
    }

    @Test
    fun `a genuinely silent turn is not a strike after an answer`() {
        assertTrue(codes(event("LIVE_TURN_SILENT", 9_190, "turnId" to "t1")).isEmpty())
    }

    @Test
    fun `any model ruled out is reported`() {
        assertEquals(
            listOf("MODEL_STRUCK_AFTER_ANSWERING"),
            codes(event("LIVE_MODEL_CANNOT_ANSWER", 9_190, "model" to "gemini-3.8-live")),
        )
    }

    @Test
    fun `the model's voice played during a reading is reported`() {
        assertEquals(
            listOf("READING_SPOKEN_BY_MODEL_VOICE"),
            codes(event("LIVE_FIRST_AUDIO_PACKET", 79_590, "turnId" to "t9", "mode" to "TEXT_READING", "played" to true)),
        )
        assertEquals(
            listOf("READING_SPOKEN_BY_MODEL_VOICE"),
            codes(event("LIVE_TOOL_TEXT_NOT_SPOKEN", 79_640, "turnId" to "t9")),
        )
    }

    @Test
    fun `model audio received but not played while reading is healthy`() {
        assertTrue(
            codes(event("LIVE_FIRST_AUDIO_PACKET", 79_590, "turnId" to "t9", "mode" to "TEXT_READING", "played" to false))
                .isEmpty(),
        )
        assertTrue(
            codes(event("LIVE_FIRST_AUDIO_PACKET", 9_000, "turnId" to "s1", "mode" to "SCENE_DESCRIPTION"))
                .isEmpty(),
        )
    }

    @Test
    fun `a page reported and neither read nor skipped is reported`() {
        assertEquals(
            listOf("EXACT_TEXT_NEVER_SPOKEN"),
            codes(event("LIVE_TOOL_TEXT_REPORTED", 79_640, "turnId" to "t9", "characters" to 747)),
        )
    }

    @Test
    fun `a page read, or deliberately skipped as already heard, is healthy`() {
        assertTrue(
            codes(
                event("LIVE_TOOL_TEXT_REPORTED", 79_640, "turnId" to "t9", "characters" to 747),
                event("LIVE_READING_ACCEPTED", 79_641, "turnId" to "t9", "contentHash" to "abc"),
                event("LIVE_TOOL_TEXT_REPORTED", 100_860, "turnId" to "t12", "characters" to 747),
                event("LIVE_READING_SKIPPED", 100_861, "turnId" to "t12", "reason" to "already_being_read"),
            ).isEmpty(),
        )
    }

    @Test
    fun `a stale report belongs to a superseded turn and is not owed`() {
        assertTrue(
            codes(event("LIVE_TOOL_TEXT_REPORTED", 50_000, "turnId" to "t5", "characters" to 40, "stale" to true))
                .isEmpty(),
        )
    }

    @Test
    fun `three frames in half a second are a burst`() {
        val finding = LiveVerdict.analyse(
            listOf(
                event("LIVE_FRAME_SENT", 5_630),
                event("LIVE_FRAME_SENT", 5_910),
                event("LIVE_FRAME_SENT", 6_180),
            ),
        ).single()
        assertEquals("FRAME_BURST", finding.code)
        assertTrue(finding.measurement.startsWith("2 "))
    }

    @Test
    fun `frames a second apart are not a burst`() {
        assertTrue(
            codes(
                event("LIVE_FRAME_SENT", 1_500, "sinceSessionStartMs" to 1_500),
                event("LIVE_FRAME_SENT", 2_600, "sinceLastSentMs" to 1_100),
            ).isEmpty(),
        )
    }

    @Test
    fun `the same page read in full twice within two minutes is reported`() {
        assertEquals(
            listOf("PAGE_READ_TWICE"),
            codes(
                event("LIVE_READING_ACCEPTED", 79_640, "contentHash" to "p1"),
                event("LIVE_READING_ACCEPTED", 100_860, "contentHash" to "p1"),
            ),
        )
    }

    @Test
    fun `a continuation of the same page is not a second reading`() {
        assertTrue(
            codes(
                event("LIVE_READING_ACCEPTED", 79_640, "contentHash" to "p1"),
                event("LIVE_READING_ACCEPTED", 100_860, "contentHash" to "p1", "continuation" to true),
                event("LIVE_READING_ACCEPTED", 300_000, "contentHash" to "p1"),
            ).isEmpty(),
        )
    }

    @Test
    fun `a first frame five seconds in is late`() {
        assertEquals(
            listOf("FIRST_FRAME_LATE"),
            codes(event("PROJECTION_STARTED", 0), event("LIVE_FRAME_SENT", 5_630)),
        )
        assertEquals(
            listOf("FIRST_FRAME_LATE"),
            codes(event("LIVE_FRAME_SENT", 5_630, "sinceSessionStartMs" to 5_630)),
        )
    }

    @Test
    fun `the healthy 16-33 session says nothing`() {
        assertTrue(
            codes(
                event("PROJECTION_STARTED", 0),
                event("LIVE_FRAME_SENT", 1_400, "turnId" to "t1", "sinceSessionStartMs" to 1_400),
                event("LIVE_TOOL_TEXT_REPORTED", 2_900, "turnId" to "t1", "characters" to 0),
                event("LIVE_FRAME_SENT", 5_000, "turnId" to "t2", "sinceLastSentMs" to 3_600),
                event("LIVE_FIRST_AUDIO_PACKET", 5_700, "turnId" to "t2", "mode" to "TEXT_READING", "played" to false),
                event("LIVE_TOOL_TEXT_REPORTED", 5_760, "turnId" to "t2", "characters" to 747),
                event("LIVE_READING_ACCEPTED", 5_761, "turnId" to "t2", "contentHash" to "p1"),
                event("LIVE_TOOL_TEXT_REPORTED", 27_000, "turnId" to "t3", "characters" to 747),
                event("LIVE_READING_SKIPPED", 27_001, "turnId" to "t3", "reason" to "already_being_read"),
            ).isEmpty(),
        )
    }
}
