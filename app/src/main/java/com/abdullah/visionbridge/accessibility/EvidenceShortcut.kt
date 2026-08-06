package com.abdullah.visionbridge.accessibility

/**
 * What pressing the shortcut does, and what the user is told afterwards.
 *
 * The decision is separated from the Android service that hosts it for one reason: a blind user
 * pressing a button they cannot see has nothing to check the result against except the sentence
 * that comes back, so that sentence is the feature. Keeping it pure means the wording, the count it
 * quotes and the state it lands on can be verified without a device.
 *
 * The shortcut exists because the setting it drives is only useful at a moment that has already
 * arrived. Failure-frame capture answers one question — was the text never detected, or detected
 * and thrown away — and that question is asked while the user is standing in front of the thing
 * that will not read. Reaching the switch meant leaving eSight's shared view, which ends the
 * capture, which loses the moment.
 */
object EvidenceShortcut {

    /**
     * [announcement] is spoken, not shown. It names the new state first, because that is the part
     * the user pressed the button to learn, and only then explains the consequence.
     */
    data class Action(
        val enable: Boolean,
        val announcement: String,
        /** Turning capture on is itself a statement that something is wrong right now. */
        val markProblem: Boolean,
    )

    /**
     * @param currentlyEnabled the setting as it stands.
     * @param framesAlreadyHeld how many failure frames are already stored, quoted when switching
     *   off so the user knows whether the moment was actually caught.
     * @param captureRunning whether screen capture is live. Off, nothing can be captured yet, and
     *   saying so is the difference between a switch that seems broken and one that is waiting.
     */
    fun press(
        currentlyEnabled: Boolean,
        framesAlreadyHeld: Int,
        captureRunning: Boolean,
    ): Action {
        if (currentlyEnabled) {
            return Action(
                enable = false,
                announcement = buildString {
                    append("أُوقف حفظ لقطات التشخيص.")
                    if (framesAlreadyHeld > 0) {
                        append(" محفوظ $framesAlreadyHeld ")
                        append(if (framesAlreadyHeld == 1) "لقطة" else "لقطة")
                        append(" وستُرسل داخل ملف التشخيص.")
                    } else {
                        append(" لم تُحفظ أي لقطة، لأن القراءة لم تُخفق أثناء التشغيل.")
                    }
                },
                markProblem = false,
            )
        }
        return Action(
            enable = true,
            announcement = buildString {
                append("شُغّل حفظ لقطات التشخيص.")
                append(" ستُحفظ صورة الشاشة عند كل إخفاق قراءة، بحد أقصى $FRAME_LIMIT لقطة.")
                if (!captureRunning) {
                    append(" مشاركة الشاشة متوقفة الآن، لذلك لن تُحفظ لقطة حتى تبدأ الالتقاط.")
                } else {
                    append(" أعِد المحاولة على النص الذي لا يُقرأ، ثم اضغط الزر مرة أخرى لإيقاف الحفظ.")
                }
            },
            markProblem = true,
        )
    }

    /** Spoken when the shortcut fires and the app has not finished starting. */
    const val NOT_READY_ANNOUNCEMENT =
        "VisionBridge لم يكتمل تشغيله بعد، لذلك لم يتغير حفظ لقطات التشخيص. أعد المحاولة بعد لحظة."

    /** Mirrors [com.abdullah.visionbridge.data.diagnostics.EvidenceStore]'s own ceiling. */
    const val FRAME_LIMIT = 40
}
