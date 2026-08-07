package com.abdullah.visionbridge.accessibility

import com.abdullah.visionbridge.domain.model.AnalysisMode

/**
 * What one press of the accessibility button does, and what the user is told afterwards.
 *
 * The button cycles the thing the user actually changes during a session — describe, read, stop —
 * because that is the choice that has to be made while standing in front of something, and it was
 * the one choice that required leaving the app being captured to make. Screen capture mirrors the
 * display, so opening VisionBridge to change the mode replaces the very view being analysed.
 *
 * Three states, in the order someone reaches for them:
 *
 * 1. **Describe** — what is in front of me.
 * 2. **Read** — read the text on it.
 * 3. **Stopped** — quiet now.
 *
 * A fourth press starts again at describe.
 *
 * "Stopped" suspends analysis and speech and keeps the screen-share permission. That is not a
 * softening of the requirement, it is the only version of it that can be undone: MediaProjection
 * consent can only be granted from an activity, so tearing the projection down would mean the next
 * press had to open the app — leaving the target app to get back to it, which is the problem this
 * button exists to remove. Stopped is silent and analyses nothing; the difference is invisible from
 * outside and entirely reversible from inside.
 *
 * Kept pure because the announcement *is* the interface here. Someone who cannot see the button has
 * only the sentence to tell them where the press landed.
 */
object ShortcutCycle {

    enum class State { DESCRIBE, READ, STOPPED }

    data class Step(
        val state: State,
        val announcement: String,
        /** Null while stopped; otherwise the mode to write to settings. */
        val mode: AnalysisMode?,
    ) {
        val analysing: Boolean get() = state != State.STOPPED
    }

    /** The state one press moves to from [current]. */
    fun next(current: State): Step = when (current) {
        State.STOPPED -> describe()
        State.DESCRIBE -> read()
        State.READ -> stopped()
    }

    /** The state a given mode and running flag correspond to, for reading the current position. */
    fun stateOf(mode: AnalysisMode, analysing: Boolean): State = when {
        !analysing -> State.STOPPED
        mode == AnalysisMode.SCENE_DESCRIPTION -> State.DESCRIBE
        else -> State.READ
    }

    private fun describe() = Step(
        state = State.DESCRIBE,
        // Mode first, in one word, because that is the whole question the press asked.
        announcement = "وصف المشهد. سيصف VisionBridge ما أمامك.",
        mode = AnalysisMode.SCENE_DESCRIPTION,
    )

    private fun read() = Step(
        state = State.READ,
        announcement = "قراءة النص. وجّه النظر إلى المكتوب.",
        mode = AnalysisMode.TEXT_READING,
    )

    private fun stopped() = Step(
        state = State.STOPPED,
        announcement = "إيقاف. لا تحليل ولا نطق. اضغط الزر مرة أخرى للعودة إلى وصف المشهد.",
        mode = null,
    )

    /** Spoken when the button is pressed before screen sharing has been allowed at all. */
    const val NO_CAPTURE_ANNOUNCEMENT =
        "مشاركة الشاشة غير مفعّلة. افتح VisionBridge واضغط ابدأ الالتقاط مرة واحدة، " +
            "وبعدها يتحكم الزر بالأوضاع من دون مغادرة التطبيق."

    /** Spoken when the app has not finished starting. */
    const val NOT_READY_ANNOUNCEMENT =
        "VisionBridge لم يكتمل تشغيله بعد. أعد المحاولة بعد لحظة."
}
