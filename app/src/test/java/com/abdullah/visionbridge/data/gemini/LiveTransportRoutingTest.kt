package com.abdullah.visionbridge.data.gemini

import com.abdullah.visionbridge.domain.model.AnalysisMode
import com.abdullah.visionbridge.domain.model.AppSettings
import com.abdullah.visionbridge.domain.model.CaptureProfile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which lane a frame takes.
 *
 * One object answers it because two callers ask: the capture loop picks a lane before doing any
 * work, and the transport refuses whatever reaches it anyway. When those disagreed, a frame entered
 * the Live lane, was refused inside it, and fell back — at a four-second setup timeout per frame.
 *
 * Both modes stream over Live. Whether a *reading* can be streamed is decided by the model, at
 * connection time, by [LiveModelDirectory] and the transport's first-token deadline — never by
 * quietly routing reading somewhere slower.
 */
class LiveTransportRoutingTest {

    private fun settings(
        mode: AnalysisMode,
        forceCellular: Boolean = false,
        useLocalOcr: Boolean = false,
        captureProfile: CaptureProfile = CaptureProfile.STABLE,
    ) = AppSettings(
        mode = mode,
        forceCellular = forceCellular,
        useLocalOcr = useLocalOcr,
        captureProfile = captureProfile,
    )

    @Test
    fun `describing a scene streams over Live`() {
        assertTrue(LiveTransportRouting.carriedByLive(settings(AnalysisMode.SCENE_DESCRIPTION)))
    }

    /**
     * The requirement this file defends: reading is streamed too. Build 54 sent it down the SSE
     * lane to get correct text, which cost the live response the user asked for; correctness now
     * comes from choosing the right model instead of from leaving the socket.
     */
    @Test
    fun `reading streams over Live at every capture profile`() {
        for (profile in CaptureProfile.entries) {
            assertTrue(
                "reading left the live lane at profile $profile",
                LiveTransportRouting.carriedByLive(
                    settings(AnalysisMode.TEXT_READING, captureProfile = profile),
                ),
            )
        }
    }

    /** Choosing the on-device reader is a choice about where screen content goes. */
    @Test
    fun `the on-device reader keeps reading off the network`() {
        assertFalse(
            LiveTransportRouting.carriedByLive(
                settings(AnalysisMode.TEXT_READING, useLocalOcr = true),
            ),
        )
        // Describing has no on-device engine, so that setting cannot divert it.
        assertTrue(
            LiveTransportRouting.carriedByLive(
                settings(AnalysisMode.SCENE_DESCRIPTION, useLocalOcr = true),
            ),
        )
    }

    /** Live holds one socket open; the per-request cellular binding does not cover it. */
    @Test
    fun `forcing cellular takes every mode off Live`() {
        for (mode in AnalysisMode.entries) {
            assertFalse(
                "$mode stayed on Live while pinned to cellular",
                LiveTransportRouting.carriedByLive(settings(mode, forceCellular = true)),
            )
        }
    }
}
