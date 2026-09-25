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
 * This is the rule that build 53 got wrong, at the user's expense. Reading was put on the Live
 * socket and asked for TEXT; the setup was accepted and the model then produced nothing at all —
 * three frames sent, no content, no turnComplete, no error, a socket read timing out at 30.6 s,
 * and every reconnect after it closing 1011 "Internal error encountered." The Live model answers
 * in native audio and cannot answer a reading.
 *
 * The rule lives in one object because the two callers must not be able to disagree: the capture
 * loop picks the lane before doing any work, and the transport refuses whatever reaches it anyway.
 * When they were written separately, reading entered the Live lane, was refused inside it, and fell
 * back — at a four-second setup timeout per frame while the socket was unhealthy.
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
    fun `describing a scene goes to Live, where native audio is the point`() {
        assertTrue(LiveTransportRouting.carriedByLive(settings(AnalysisMode.SCENE_DESCRIPTION)))
    }

    /**
     * The regression this file exists for. No combination of the other settings may put a reading
     * back on a socket that answers in synthesised speech.
     */
    @Test
    fun `reading never goes to Live, whatever else is set`() {
        for (forceCellular in listOf(false, true)) {
            for (useLocalOcr in listOf(false, true)) {
                for (profile in CaptureProfile.entries) {
                    val current = settings(
                        AnalysisMode.TEXT_READING, forceCellular, useLocalOcr, profile,
                    )
                    assertFalse(
                        "reading reached Live with cellular=$forceCellular local=$useLocalOcr " +
                            "profile=$profile",
                        LiveTransportRouting.carriedByLive(current),
                    )
                }
            }
        }
    }

    /** Live opens its own socket, which the per-request cellular binding does not cover. */
    @Test
    fun `forcing cellular keeps even a description off Live`() {
        assertFalse(
            LiveTransportRouting.carriedByLive(
                settings(AnalysisMode.SCENE_DESCRIPTION, forceCellular = true),
            ),
        )
    }
}
