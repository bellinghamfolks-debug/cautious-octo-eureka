package com.abdullah.visionbridge.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The verdict rules, checked against timelines shaped like the ones that produced them.
 *
 * Each case is a defect that was real and was diagnosed by hand from a device bundle. The point of
 * the test is that the same diagnosis now falls out of the data automatically, and — just as
 * importantly — that a healthy session stays silent.
 */
class SessionVerdictTest {

    private var clock = 1_000_000L

    private fun event(
        type: String,
        vararg fields: Pair<String, Any?>,
        advanceMs: Long = 10L,
    ): SessionVerdict.Event {
        clock += advanceMs
        return SessionVerdict.Event(type, mapOf("epochMs" to clock) + fields.toMap())
    }

    private fun codes(events: List<SessionVerdict.Event>): List<String> =
        SessionVerdict.analyse(events).map { it.code }

    // region work discarded before it reaches the user

    /**
     * The text session from 2026-08-10: 42 requests, 15 of them killed in flight when the object
     * moved. That is real waste — an upload and two seconds paid for and nothing heard — and the
     * old rule said nothing about it.
     */
    @Test
    fun `requests killed in flight by a moving subject are named`() {
        val events = buildList {
            repeat(27) { add(event("CLOUD_ANALYSIS_COMPLETED", "text" to "abc")) }
            repeat(15) {
                add(event("CLOUD_ANALYSIS_CANCELLED", "reason" to "visual_target_changed"))
            }
            repeat(17) { add(event("VISUAL_TARGET_CHANGED", advanceMs = 1_800L)) }
        }
        val finding = SessionVerdict.analyse(events)
            .first { it.code == "ANALYSIS_DISCARDED_BEFORE_DELIVERY" }
        assertTrue(finding.measurement.contains("15"))
        assertTrue("the cadence explains it", finding.measurement.contains("17"))
    }

    /**
     * The scene session from the same bundle, which the old rule called FATAL: the subject changed
     * 29 times against a 4.4-second analysis, and every one of the 36 requests was completed and
     * delivered. A scene request outlives the frame that started it, so a fast-moving subject costs
     * nothing and there is nothing to report.
     */
    @Test
    fun `a fast changing scene that discards nothing is not a defect`() {
        val events = buildList {
            repeat(36) { add(event("CLOUD_ANALYSIS_COMPLETED", "text" to "a".repeat(40))) }
            repeat(36) { add(event("TEXT_DISPLAYED", "text" to "a".repeat(40))) }
            repeat(36) { add(event("TTS_UTTERANCE_DONE", "text" to "a".repeat(40))) }
            repeat(29) { add(event("VISUAL_TARGET_CHANGED", advanceMs = 1_800L)) }
            repeat(28) {
                add(event("CLOUD_ACTIVE_REQUEST_MARKED_STALE", "requestCancelled" to false))
            }
        }
        assertEquals(emptyList<String>(), codes(events))
    }

    /** A result withheld because there was no text in it is a correct outcome, not a discard. */
    @Test
    fun `suppressing an empty reading is not counted as waste`() {
        val events = buildList {
            repeat(27) { add(event("CLOUD_ANALYSIS_COMPLETED", "text" to "abc")) }
            repeat(18) { add(event("FINAL_RESULT_SUPPRESSED", "reason" to "no_text_recognized")) }
        }
        assertEquals(emptyList<String>(), codes(events))
    }

    /** One cancelled request in a busy session is a moment, not a pattern. */
    @Test
    fun `a single cancellation is not a verdict`() {
        val events = buildList {
            repeat(30) { add(event("CLOUD_ANALYSIS_COMPLETED", "text" to "abc")) }
            add(event("CLOUD_ANALYSIS_CANCELLED", "reason" to "visual_target_changed"))
        }
        assertEquals(emptyList<String>(), codes(events))
    }

    // endregion

    // region answers cut off by the token ceiling

    /**
     * The 2026-08-10 session in miniature: every response finished on `MAX_TOKENS`, having spent
     * some 343 tokens reasoning and 13 answering against a ceiling of 360. Nothing failed, nothing
     * was cancelled, and the user heard a fluent fragment of a sentence each time.
     */
    @Test
    fun `answers that all end on the token ceiling are named`() {
        val events = buildList {
            repeat(12) {
                add(
                    event(
                        "MODEL_FINISH_REASON",
                        "finishReason" to "MAX_TOKENS",
                        "truncated" to true,
                        "maxOutputTokens" to 360,
                        "reasoningTokens" to 343,
                        "answerTokens" to 13,
                    )
                )
            }
        }
        val finding = SessionVerdict.analyse(events).first { it.code == "MODEL_ANSWERS_TRUNCATED" }
        assertEquals(SessionVerdict.Severity.FATAL, finding.severity)
        assertTrue("the split must be in the measurement", finding.measurement.contains("343"))
        assertTrue(finding.measurement.contains("360"))
    }

    @Test
    fun `answers that finish cleanly say nothing`() {
        val events = buildList {
            repeat(12) {
                add(
                    event(
                        "MODEL_FINISH_REASON",
                        "finishReason" to "STOP",
                        "truncated" to false,
                        "maxOutputTokens" to 1800,
                        "reasoningTokens" to 200,
                        "answerTokens" to 180,
                    )
                )
            }
        }
        assertEquals(emptyList<String>(), codes(events))
    }

    /** One long answer hitting the ceiling is an answer, not a misconfiguration. */
    @Test
    fun `an occasional truncation is not a verdict`() {
        val events = buildList {
            add(
                event(
                    "MODEL_FINISH_REASON",
                    "finishReason" to "MAX_TOKENS",
                    "truncated" to true,
                    "maxOutputTokens" to 4000,
                    "reasoningTokens" to 300,
                    "answerTokens" to 3700,
                )
            )
            repeat(11) {
                add(event("MODEL_FINISH_REASON", "finishReason" to "STOP", "truncated" to false))
            }
        }
        assertEquals(emptyList<String>(), codes(events))
    }

    /** Too few responses to tell a setting from a coincidence. */
    @Test
    fun `a handful of responses is not enough to judge`() {
        val events = buildList {
            repeat(3) {
                add(event("MODEL_FINISH_REASON", "finishReason" to "MAX_TOKENS", "truncated" to true))
            }
        }
        assertEquals(emptyList<String>(), codes(events))
    }

    // endregion

    /** A session where nothing went wrong must produce nothing. Noise is what made the old findings useless. */
    @Test
    fun `a healthy session yields no findings`() {
        val events = buildList {
            repeat(40) { add(event("FRAME_ACQUIRED")) }
            repeat(20) { add(event("COORDINATOR_PROCESS_STARTED")) }
            repeat(10) { add(event("PPOCR_PAGE_READ", "characters" to 60, "sinceCaptureMs" to 800.0)) }
            repeat(12) { add(event("TTS_UTTERANCE_DONE", "text" to "a".repeat(55))) }
        }
        assertEquals(emptyList<String>(), codes(events))
    }

    /**
     * The perfume session: 4,742 characters recognised, 200 heard. The measurement that would have
     * named the complaint on sight.
     */
    @Test
    fun `text recognised but not delivered is named`() {
        val events = buildList {
            repeat(50) { add(event("PPOCR_PAGE_READ", "characters" to 95, "sinceCaptureMs" to 2200.0)) }
            repeat(3) { add(event("TTS_UTTERANCE_DONE", "text" to "BLEU CHANEL")) }
        }
        val finding = SessionVerdict.analyse(events).firstOrNull { it.code == "RECOGNISED_TEXT_NOT_DELIVERED" }
        assertNotNull(finding)
        assertEquals(SessionVerdict.Severity.FATAL, finding!!.severity)
        assertTrue("expected the counts in the measurement", finding.measurement.contains("4750"))
    }

    @Test
    fun `speech cut off repeatedly is named`() {
        val events = buildList {
            repeat(14) { add(event("TTS_UTTERANCE_INTERRUPTED", "text" to "BLEU D CHANEL")) }
            repeat(15) { add(event("TTS_UTTERANCE_DONE", "text" to "ok")) }
            repeat(153) { add(event("TTS_QUEUE_INTERRUPTED")) }
        }
        assertTrue("SPEECH_REPEATEDLY_CUT_OFF" in codes(events))
    }

    /** Interruptions that are rare beside completions are normal use, not a defect. */
    @Test
    fun `occasional interruptions are not a finding`() {
        val events = buildList {
            repeat(2) { add(event("TTS_UTTERANCE_INTERRUPTED", "text" to "x")) }
            repeat(40) { add(event("TTS_UTTERANCE_DONE", "text" to "ok")) }
        }
        assertTrue("SPEECH_REPEATEDLY_CUT_OFF" !in codes(events))
    }

    /**
     * A target that changes fast is only a defect when work is lost to it. Here 60 changes at
     * 343 ms against a 2,231 ms analysis cost nothing, because nothing was cancelled or suppressed
     * — and the verdict must stay quiet about arithmetic alone.
     */
    @Test
    fun `a fast target that costs nothing is not a finding`() {
        val events = buildList {
            repeat(60) { add(event("VISUAL_TARGET_CHANGED", advanceMs = 343L)) }
            repeat(20) { add(event("PPOCR_PAGE_READ", "characters" to 40, "sinceCaptureMs" to 2231.0)) }
            repeat(20) { add(event("TTS_UTTERANCE_DONE", "text" to "a".repeat(40))) }
        }
        assertTrue("ANALYSIS_DISCARDED_BEFORE_DELIVERY" !in codes(events))
    }

    @Test
    fun `a target changing slower than analysis is not a finding`() {
        val events = buildList {
            repeat(20) { add(event("VISUAL_TARGET_CHANGED", advanceMs = 6_000L)) }
            repeat(20) { add(event("PPOCR_PAGE_READ", "characters" to 40, "sinceCaptureMs" to 900.0)) }
        }
        assertTrue("ANALYSIS_DISCARDED_BEFORE_DELIVERY" !in codes(events))
    }

    /** 24,000 ms budget enforced at 221,605. A timer that did not run, not a slow network. */
    @Test
    fun `a request that outlived its deadline is named`() {
        val events = listOf(
            event("CLOUD_ANALYSIS_BUDGET_EXCEEDED", "budgetMs" to 24_000.0, "elapsedMs" to 221_605.0),
        )
        val finding = SessionVerdict.analyse(events).firstOrNull { it.code == "REQUEST_OUTLIVED_ITS_DEADLINE" }
        assertNotNull(finding)
        assertTrue(finding!!.measurement.contains("221605"))
    }

    /** A request that overran slightly really is just a slow network. */
    @Test
    fun `a request that overran slightly is not a finding`() {
        val events = listOf(
            event("CLOUD_ANALYSIS_BUDGET_EXCEEDED", "budgetMs" to 24_000.0, "elapsedMs" to 24_400.0),
        )
        assertTrue("REQUEST_OUTLIVED_ITS_DEADLINE" !in codes(events))
    }

    @Test
    fun `an illegal second virtual display is named as the cause of a dead capture`() {
        val events = listOf(
            event(
                "FAILURE",
                "exception" to "java.lang.SecurityException",
                "message" to "Don't take multiple captures by invoking " +
                    "MediaProjection#createVirtualDisplay multiple times on the same instance.",
            ),
            event("PROJECTION_SYSTEM_STOPPED"),
        )
        val finding = SessionVerdict.analyse(events).firstOrNull { it.code == "CAPTURE_SESSION_KILLED" }
        assertNotNull(finding)
        assertTrue(finding!!.measurement.contains("SecurityException"))
    }

    /** The 216-second hole, and the honesty about what cannot be concluded without a heartbeat. */
    @Test
    fun `a long silence is named, and says whether it can be explained`() {
        val withoutHeartbeat = listOf(event("SESSION_START"), event("SESSION_END", advanceMs = 216_000L))
        val blind = SessionVerdict.analyse(withoutHeartbeat).first { it.code == "APP_STOPPED_RECORDING" }
        assertTrue(blind.measurement.contains("216"))
        assertTrue("must admit it cannot tell", blind.measurement.contains("لا يوجد نبض حياة"))

        val withHeartbeat = listOf(
            event("SESSION_START"),
            event("PROCESS_HEARTBEAT"),
            event("SESSION_END", advanceMs = 216_000L),
        )
        val informed = SessionVerdict.analyse(withHeartbeat).first { it.code == "APP_STOPPED_RECORDING" }
        assertTrue("must use the heartbeat", informed.measurement.contains("نبض الحياة يؤكد"))
    }

    /** A black feed that always follows a resize is a different bug from a dark room. */
    @Test
    fun `a black feed correlated with a resize says so`() {
        val events = listOf(
            event("VISUAL_FEED_UNAVAILABLE_NOTICE_TRIGGERED", "sinceLastResizeMs" to 1592.0, "landscapeCapture" to true),
            event("VISUAL_FEED_UNAVAILABLE_NOTICE_TRIGGERED", "sinceLastResizeMs" to 1719.0, "landscapeCapture" to true),
        )
        val finding = SessionVerdict.analyse(events).first { it.code == "CAPTURE_WENT_BLACK" }
        assertTrue(finding.headline.contains("تغيير لحجم الالتقاط"))
    }

    @Test
    fun `a black feed unrelated to a resize does not blame one`() {
        val events = listOf(
            event("VISUAL_FEED_UNAVAILABLE_NOTICE_TRIGGERED", "sinceLastResizeMs" to 90_000.0),
            event("VISUAL_FEED_UNAVAILABLE_NOTICE_TRIGGERED", "sinceLastResizeMs" to 120_000.0),
        )
        val finding = SessionVerdict.analyse(events).first { it.code == "CAPTURE_WENT_BLACK" }
        assertTrue(!finding.headline.contains("تغيير لحجم الالتقاط"))
    }

    /** The signature of a build that records a page as heard the moment it queues it. */
    @Test
    fun `suppression with no delivery record at all is named`() {
        val events = buildList {
            repeat(86) { add(event("DOCUMENT_READING_SKIPPED", "reason" to "already_read_completely")) }
        }
        assertTrue("SUPPRESSED_WITHOUT_DELIVERY_PROOF" in codes(events))
    }

    /** With delivery records present, the question becomes how much is still owed. */
    @Test
    fun `content still owed is named once delivery is recorded`() {
        val events = buildList {
            repeat(10) { add(event("DOCUMENT_READING_SKIPPED", "reason" to "already_read_completely")) }
            repeat(4) {
                add(event("DOCUMENT_READING_DELIVERED", "owedCharacters" to 30, "complete" to false))
            }
        }
        val codes = codes(events)
        assertTrue("CONTENT_STILL_OWED" in codes)
        assertTrue("SUPPRESSED_WITHOUT_DELIVERY_PROOF" !in codes)
    }

    @Test
    fun `a session that delivers everything it suppresses is silent`() {
        val events = buildList {
            repeat(10) { add(event("DOCUMENT_READING_SKIPPED", "reason" to "already_read_completely")) }
            repeat(4) {
                add(event("DOCUMENT_READING_DELIVERED", "owedCharacters" to 0, "complete" to true))
            }
        }
        val codes = codes(events)
        assertTrue("CONTENT_STILL_OWED" !in codes)
        assertTrue("SUPPRESSED_WITHOUT_DELIVERY_PROOF" !in codes)
    }

    @Test
    fun `cloud requests that mostly fail are named`() {
        val events = buildList {
            repeat(24) { add(event("HTTP_REQUEST_STARTED", "forceCellular" to true)) }
            repeat(3) { add(event("CLOUD_ANALYSIS_COMPLETED", "text" to "x")) }
        }
        val finding = SessionVerdict.analyse(events).firstOrNull { it.code == "CLOUD_REQUESTS_MOSTLY_FAIL" }
        assertNotNull(finding)
        assertTrue("must mention cellular when the session was on it", finding!!.measurement.contains("الخلوية"))
    }

    /** Findings must arrive worst-first; a reader should not have to rank them. */
    @Test
    fun `findings are ranked by severity`() {
        val events = buildList {
            repeat(400) { add(event("FRAME_ACQUIRED")) }
            add(event("COORDINATOR_PROCESS_STARTED"))
            repeat(50) { add(event("PPOCR_PAGE_READ", "characters" to 95, "sinceCaptureMs" to 2200.0)) }
            add(event("PROJECTION_SYSTEM_STOPPED"))
        }
        val severities = SessionVerdict.analyse(events).map { it.severity }
        assertEquals(severities.sortedBy { it.ordinal }, severities)
        assertEquals(SessionVerdict.Severity.FATAL, severities.first())
    }

    /**
     * A number that survived a JSON round trip as text must still be read. A rule that silently
     * fails to fire makes a broken session look clean, which is worse than having no rule.
     */
    @Test
    fun `rules fire when numbers arrive as strings`() {
        val events = buildList {
            repeat(20) {
                add(
                    event(
                        "PPOCR_PAGE_READ",
                        "characters" to "40",
                        "sinceCaptureMs" to "2231.0",
                    ),
                )
            }
            repeat(2) { add(event("TTS_UTTERANCE_DONE", "text" to "a".repeat(40))) }
        }
        assertTrue("RECOGNISED_TEXT_NOT_DELIVERED" in codes(events))
    }

    @Test
    fun `flags stored as text are still read`() {
        val events = listOf(
            event("VISUAL_FEED_UNAVAILABLE_NOTICE_TRIGGERED", "sinceLastResizeMs" to "1592", "landscapeCapture" to "true"),
            event("VISUAL_FEED_UNAVAILABLE_NOTICE_TRIGGERED", "sinceLastResizeMs" to "1719", "landscapeCapture" to "true"),
        )
        val finding = SessionVerdict.analyse(events).first { it.code == "CAPTURE_WENT_BLACK" }
        assertTrue(finding.measurement.contains("2 في الوضع الأفقي"))
    }

    @Test
    fun `an empty timeline is handled`() {
        assertEquals(emptyList<String>(), codes(emptyList()))
    }

    // region what the detector threw away

    private fun barrenDetection(areaWidth: Double, areaHeight: Double, reason: String) = event(
        "PPOCR_DETECTION_COMPLETED",
        "regionsFound" to 4,
        "accepted" to 0,
        "rejectedLowScore" to 3,
        "largestRejectedReason" to reason,
        "largestRejectedWidthFraction" to areaWidth,
        "largestRejectedHeightFraction" to areaHeight,
        "largestRejectedScore" to 0.41,
        "resolutionReason" to "matched_text_height",
    )

    /**
     * The perfume question, answered by arithmetic: a region a fifth of the frame wide was found
     * and discarded, which is not the same defect as never finding it.
     */
    @Test
    fun `a large region found and discarded is named`() {
        val events = (1..14).map { barrenDetection(0.35, 0.12, "mean_probability_below_threshold") }
        val finding = SessionVerdict.analyse(events).firstOrNull { it.code == "DETECTOR_REJECTED_LARGE_REGIONS" }
        assertNotNull(finding)
        assertEquals(SessionVerdict.Severity.MAJOR, finding!!.severity)
        assertTrue(finding.measurement.contains("mean_probability_below_threshold"))
    }

    /** A bottle's edges produce specks. Discarding specks is the stage working, not failing. */
    @Test
    fun `discarding only tiny regions is not a finding`() {
        val events = (1..14).map { barrenDetection(0.02, 0.01, "below_minimum_side") }
        assertTrue("DETECTOR_REJECTED_LARGE_REGIONS" !in codes(events))
    }

    /** Frames that did produce boxes are the normal case and must stay silent. */
    @Test
    fun `frames that accepted boxes are not counted as barren`() {
        val events = (1..20).map {
            event(
                "PPOCR_DETECTION_COMPLETED",
                "regionsFound" to 12,
                "accepted" to 9,
                "resolutionReason" to "held",
            )
        }
        assertEquals(emptyList<String>(), codes(events))
    }

    // endregion

    // region the network the request was actually bound to

    @Test
    fun `binding to an unvalidated network is named`() {
        val events = buildList {
            repeat(6) {
                add(event("CELLULAR_NETWORK_VALIDATION_FALLBACK", "reason" to "timeout"))
                add(
                    event(
                        "CELLULAR_NETWORK_ACQUIRED",
                        "boundValidated" to false,
                        "boundCellular" to true,
                        "defaultValidated" to true,
                        "boundCaptivePortal" to true,
                    ),
                )
            }
        }
        val finding = SessionVerdict.analyse(events).firstOrNull { it.code == "BOUND_NETWORK_NEVER_VALIDATED" }
        assertNotNull(finding)
        assertTrue("the control group belongs in the sentence", finding!!.measurement.contains("6"))
        assertTrue(finding.measurement.contains("بوابة تسجيل دخول"))
    }

    @Test
    fun `a validated cellular binding says nothing`() {
        val events = (1..6).map {
            event("CELLULAR_NETWORK_ACQUIRED", "boundValidated" to true, "defaultValidated" to true)
        }
        assertTrue("BOUND_NETWORK_NEVER_VALIDATED" !in codes(events))
    }

    // endregion

    // region the resolution controller

    @Test
    fun `a controller that never finds text to measure is named`() {
        val events = (1..20).map {
            event("PPOCR_DETECTION_COMPLETED", "accepted" to 0, "resolutionReason" to "nothing_found_bracketing")
        }
        assertTrue("READING_RESOLUTION_NEVER_SETTLED" in codes(events))
    }

    @Test
    fun `a controller that settles says nothing`() {
        val events = (1..20).map {
            event("PPOCR_DETECTION_COMPLETED", "accepted" to 6, "resolutionReason" to "held")
        }
        assertTrue("READING_RESOLUTION_NEVER_SETTLED" !in codes(events))
    }

    // endregion

    // region the pointer at the shortcut

    /** Guidance, and only where it would have changed the answer. */
    @Test
    fun `a session with a real failure and no frames points at the shortcut`() {
        val events = buildList {
            repeat(50) { add(event("PPOCR_PAGE_READ", "characters" to 95)) }
            repeat(3) { add(event("TTS_UTTERANCE_DONE", "text" to "BLEU")) }
            repeat(12) { add(event("PPOCR_DETECTION_COMPLETED", "accepted" to 4, "resolutionReason" to "held")) }
        }
        val found = SessionVerdict.analyse(events)
        assertTrue("NO_FRAME_KEPT_TO_SETTLE_IT" in found.map { it.code })
        assertEquals("guidance belongs last", "NO_FRAME_KEPT_TO_SETTLE_IT", found.last().code)
    }

    /** Nothing went wrong, so there is nothing to advise about. Advice with no defect is noise. */
    @Test
    fun `a healthy session is not told to capture frames`() {
        val events = buildList {
            repeat(10) { add(event("PPOCR_PAGE_READ", "characters" to 60, "sinceCaptureMs" to 800.0)) }
            repeat(12) { add(event("TTS_UTTERANCE_DONE", "text" to "a".repeat(55))) }
            repeat(12) { add(event("PPOCR_DETECTION_COMPLETED", "accepted" to 4, "resolutionReason" to "held")) }
        }
        assertEquals(emptyList<String>(), codes(events))
    }

    /** The frames are already there, so the advice would be wrong as well as unwanted. */
    @Test
    fun `a session that already captured frames is not told to capture frames`() {
        val events = buildList {
            repeat(50) { add(event("PPOCR_PAGE_READ", "characters" to 95)) }
            repeat(3) { add(event("TTS_UTTERANCE_DONE", "text" to "BLEU")) }
            repeat(12) { add(event("PPOCR_DETECTION_COMPLETED", "accepted" to 4, "resolutionReason" to "held")) }
            add(event("EVIDENCE_FRAME_CAPTURED", "reason" to "recognition_returned_nothing"))
        }
        assertTrue("NO_FRAME_KEPT_TO_SETTLE_IT" !in codes(events))
    }

    // endregion
}
