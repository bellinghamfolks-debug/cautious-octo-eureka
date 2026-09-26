package com.abdullah.visionbridge.data.gemini

import com.abdullah.visionbridge.domain.model.AnalysisMode

/**
 * Every decision the live transport makes about a turn, in one place and free of Android.
 *
 * Each rule here replaces one that failed on a real device, and the 2026-09-26 16:33 session is the
 * reference for most of them. That session proved the hard part works — `report_visible_text`
 * returned a whole page, 747 characters over 35 lines, on a live audio session — and then showed the
 * app undoing its own result six different ways: striking off the model that had just answered,
 * touring four models that never answer on this device, letting a 23-character spoken summary stop
 * the 747-character page from being read, bursting three frames in half a second, starting five
 * seconds late, and reading the same page twice.
 *
 * None of those were Gemini's. They were decisions, and decisions belong somewhere they can be
 * tested against the timeline that exposed them without a device, a socket or a build.
 */
object LiveTurnPolicy {

    // region which model

    /**
     * The only two models ever measured answering on this account, in the order they are used.
     *
     * The catalogue has nine Live models and every other one has been tried on this device. The
     * native-audio family refuses `ar-XA`, then answers a turn with silence; transcribe, translate,
     * robotics and extended-thinking refuse or ignore a reading outright. Discovering that again at
     * the start of every session cost 48 seconds of the 16:33 session alone. The list is a
     * measurement, so it is written down rather than rediscovered.
     */
    const val PRIMARY_MODEL = "gemini-3.8-live"
    const val FALLBACK_MODEL = "gemini-3.1-flash-live-preview"
    val PINNED_MODELS = listOf(PRIMARY_MODEL, FALLBACK_MODEL)

    /** Consecutive connection failures on one model before the other is tried. */
    const val CONNECT_FAILURES_BEFORE_SWITCH = 3

    /** How long the fallback is used before the primary is given another chance. */
    const val RETURN_TO_PRIMARY_AFTER_MS = 60_000L

    /**
     * The model the next connection should use.
     *
     * A model is left only after it has failed to connect repeatedly — never because one turn was
     * slow or quiet, which says nothing about whether it can answer. And the fallback is a detour,
     * not a destination: after a minute the primary is tried again, so a passing outage does not
     * leave a session on the second-best model for good.
     */
    fun nextModel(
        current: String,
        consecutiveConnectFailures: Int,
        onCurrentSinceMs: Long,
        nowMs: Long,
    ): String {
        val model = current.takeIf { it in PINNED_MODELS } ?: PRIMARY_MODEL
        if (consecutiveConnectFailures >= CONNECT_FAILURES_BEFORE_SWITCH) return otherThan(model)
        if (model != PRIMARY_MODEL && nowMs - onCurrentSinceMs >= RETURN_TO_PRIMARY_AFTER_MS) {
            return PRIMARY_MODEL
        }
        return model
    }

    private fun otherThan(model: String) = if (model == PRIMARY_MODEL) FALLBACK_MODEL else PRIMARY_MODEL

    /**
     * How long after a failed handshake the next one may start.
     *
     * Short, because there are only two models and each is allowed three failures: at the fifteen
     * seconds this used to be, one bad minute of network put every frame of the next forty-five
     * seconds on the per-frame fallback before the second model was even tried.
     */
    const val SETUP_RETRY_INTERVAL_MS = 3_000L

    // endregion

    // region what counts as an answer

    /**
     * Any sign the model responded to this turn.
     *
     * A function call is an answer. At 8.56 s in the 16:33 session gemini-3.8-live called
     * `report_visible_text` with an empty list of lines — it looked and found nothing legible — and
     * 0.6 s later the watchdog, which counted only audio and transcript, declared the turn silent and
     * struck off the best model on the account. Everything after that was the app touring models
     * that could not replace it.
     */
    fun answered(audio: Boolean, transcript: Boolean, toolCall: Boolean): Boolean =
        audio || transcript || toolCall

    /** How long a turn may go without any sign of an answer before the lane is freed. */
    const val FIRST_ANSWER_DEADLINE_MS = 6_000L

    /**
     * Quiet turns in a row on one socket before it is reopened, to the same model.
     *
     * One. In the 17:28 session (build 66) a socket went quiet for three turns — eighteen seconds —
     * and the moment it was reopened the model delivered a 938-character page 82 ms later: the
     * answer had been stuck, not absent. Every other quiet turn in that session was the app
     * discarding an answer it had received, which is fixed separately, so a quiet turn that is
     * left is a stuck socket, and reopening it costs about a second.
     */
    const val SILENT_TURNS_BEFORE_RECONNECT = 1

    enum class AfterSilence {
        /** Stop waiting for this turn and let the newest frame go out. */
        FREE_LANE,

        /** The socket has gone quiet; reopen it, to the same model. */
        RECONNECT_SAME_MODEL,
    }

    /**
     * What a turn that missed its deadline means.
     *
     * Never that the model cannot answer. A quiet turn is a slow turn, and a run of them is a quiet
     * socket; neither is evidence about the model, and striking one off for it is exactly what cost
     * the 16:33 session its best model at 9.19 s.
     */
    fun afterSilentTurn(consecutiveSilentTurns: Int): AfterSilence =
        if (consecutiveSilentTurns >= SILENT_TURNS_BEFORE_RECONNECT) {
            AfterSilence.RECONNECT_SAME_MODEL
        } else {
            AfterSilence.FREE_LANE
        }

    // endregion

    // region who speaks

    /**
     * Whether the model's own voice reaches the user in [mode].
     *
     * Describing: yes — native speech is what makes a description live, and its sentence is the
     * model's own, so nothing is lost by hearing it.
     *
     * Reading: never. At 79.59 s the model's voice read a 23-character summary; at 79.64 s the
     * function call delivered the page itself, 747 characters over 35 lines; and because the voice
     * had started first, the page was published but never spoken. The rule that let the first voice
     * win contradicted the instruction that asked the model to speak a short line first. In reading
     * the exact text is the product, so the app speaks it and the model's audio is not played.
     */
    fun playsModelAudio(mode: AnalysisMode): Boolean = mode == AnalysisMode.SCENE_DESCRIPTION

    /** Whether the session is set up with the reading tool. */
    fun declaresReadingTool(mode: AnalysisMode): Boolean = mode == AnalysisMode.TEXT_READING

    /**
     * Whether the transcript of the model's speech should be read out when the turn ends.
     *
     * Only in reading, only when the function never delivered text, and only when there is a
     * transcript to read. It is the one fallback that stops a reading turn from ending in silence,
     * and it is spoken by the app for the same reason the tool's text is.
     */
    fun speaksTranscriptAtTurnEnd(
        mode: AnalysisMode,
        toolTextArrived: Boolean,
        transcriptNonBlank: Boolean,
    ): Boolean = mode == AnalysisMode.TEXT_READING && !toolTextArrived && transcriptNonBlank

    /**
     * Whether an answer still counts after the view moved on while it was being generated.
     *
     * In a reading, yes. A page says what it said when the frame was taken, and in the 17:28
     * session a small head movement arrived during nearly every long answer: the answer was
     * thrown away as stale, the next frame was deferred waiting for it, and the user waited two
     * to six seconds for a page that had already arrived. Whether the view really changed is
     * decided by the next answer — a different page replaces this one, the same page is skipped.
     *
     * In a description, no: a room is only true about the moment it was seen.
     */
    fun keepsAnswerAfterViewMoved(mode: AnalysisMode): Boolean = mode == AnalysisMode.TEXT_READING

    /**
     * Whether a target change should cut the speech that is playing.
     *
     * Not in a reading. The tracker sees the camera, not the page: in the 17:28 session it
     * declared an immediate change every few seconds while the user held one chemistry summary,
     * and the page was cut and restarted from its first line four times without a line of it
     * finishing. What replaces a reading is a different page, and that is known only when the next
     * answer arrives — [com.abdullah.visionbridge.data.speech.LiveReadingSpeaker] cuts it then.
     */
    fun cutsSpeechOnTargetChange(mode: AnalysisMode, immediateAndAllowed: Boolean): Boolean =
        immediateAndAllowed && mode != AnalysisMode.TEXT_READING

    // endregion

    // region frames

    /** Frames on the socket are at least this far apart. */
    const val MIN_FRAME_SPACING_MS = 1_000L

    /**
     * Whether a frame may be sent now, given when the last one went out.
     *
     * Measured from the last frame *sent*, not the last one reserved. The 16:33 session reserved
     * frames a second apart while the first handshake was still running, then sent all three as soon
     * as it finished — at 5.63, 5.91 and 6.18 s — each one superseding the last before it could be
     * answered.
     */
    fun frameMaySend(nowMs: Long, lastSentAtMs: Long?): Boolean =
        lastSentAtMs == null || nowMs - lastSentAtMs >= MIN_FRAME_SPACING_MS

    /**
     * Whether a frame is still the newest one waiting to be sent.
     *
     * Every frame takes a ticket before it queues for the socket. When its turn comes, a frame whose
     * ticket is no longer the latest is older than something already waiting behind it, and is
     * dropped rather than sent to be immediately superseded.
     */
    fun isNewest(ticket: Long, latestTicket: Long): Boolean = ticket == latestTicket

    /**
     * Whether a target change should act before the first frame of a session has been sent.
     *
     * It should not. The tracker has no settled reference yet, and in the 16:33 session it declared
     * three strong changes in the first half second, before any socket existed — interrupting
     * nothing but the start-up announcement.
     */
    fun honoursTargetChange(firstFrameSent: Boolean): Boolean = firstFrameSent

    /** How long a view that came back with no legible text waits before it is looked at again. */
    const val EMPTY_READING_RETRY_MS = 3_000L

    /**
     * Whether the view already answered may be sent again, before the tracker has seen it change.
     *
     * A scene is sampled again as soon as its answer has finished: a room keeps changing whether or
     * not the tracker notices. A page is not — re-sending it only interrupts its own reading — unless
     * the last look at it found nothing to read. Held glasses take a moment to focus, and a page
     * judged illegible while blurred would otherwise never be looked at again until the user moved.
     */
    fun resendsSameView(
        mode: AnalysisMode,
        inFlight: Boolean,
        lastAnswerHadText: Boolean,
        sinceLastSentMs: Long,
    ): Boolean = when {
        inFlight -> false
        mode == AnalysisMode.SCENE_DESCRIPTION -> true
        else -> !lastAnswerHadText && sinceLastSentMs >= EMPTY_READING_RETRY_MS
    }

    // endregion
}
