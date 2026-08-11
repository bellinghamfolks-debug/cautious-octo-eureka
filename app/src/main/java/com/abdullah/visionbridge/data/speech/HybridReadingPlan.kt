package com.abdullah.visionbridge.data.speech

/**
 * Decides what to say when one analysis has produced both a transcription and a description.
 *
 * ## Why this is a plan and not a concatenation
 *
 * The obvious implementation of "text and description together" is to run both analyses and speak
 * both answers. That is the version worth refusing. It doubles the upload and the wait — on the
 * measured numbers, 3.8 s for a description plus 5.2 s for a reading — and it hands the user two
 * accounts of the same object back to back, one of which they did not need. Someone holding a
 * perfume bottle up to their glasses is asking what it says. Someone standing in a doorway is
 * asking what is in front of them. Answering both questions every time is not more helpful; it is
 * the same feature with a delay and a preamble in front of it.
 *
 * So the pipeline asks **one** question and gets one answer in two parts, and this decides how much
 * of that answer is worth the user's time. Four rules, each one the answer to "what would make this
 * annoying?":
 *
 * 1. **Text leads.** Always. A description spoken before a label is an obstacle between the user
 *    and the thing they pointed at.
 * 2. **The description is a tail, not a second answer.** It is spoken only if the user is still
 *    looking at the same subject when the transcription finishes. If they have moved on, the tail
 *    is silently dropped — they are already asking about something else.
 * 3. **Nothing is repeated.** A page already read does not get read again, and a subject already
 *    described does not get described again. Holding a bottle steady for ten seconds should produce
 *    one answer, not four.
 * 4. **A description without text stands alone.** When there is nothing written, the tail *is* the
 *    answer, and it is spoken whether or not the subject has changed — the same as ordinary scene
 *    description, because that is what it has become.
 *
 * Pure, so the whole policy can be argued with on a table of cases rather than on a device.
 */
object HybridReadingPlan {

    /**
     * One thing to say. [isDescription] separates the two so the caller can give them different
     * speech treatment — a transcription is a document, a description is a remark.
     */
    data class Utterance(val text: String, val isDescription: Boolean)

    /**
     * [reason] names the rule that produced this plan, so a diagnostic bundle shows why the user
     * heard what they heard rather than only what was available to hear.
     */
    data class Plan(val utterances: List<Utterance>, val reason: String) {
        val speaks: Boolean get() = utterances.isNotEmpty()
    }

    /**
     * @param text the transcription, empty when the frame carried no readable text.
     * @param description the scene tail, empty when the model offered none.
     * @param textAlreadyRead this exact transcription has already been delivered to the user.
     * @param subjectAlreadyDescribed the current subject has already had its description spoken.
     * @param subjectUnchanged the user is still looking at what they were looking at when the
     *   analysis began. False means they have moved on and the tail has expired.
     */
    fun plan(
        text: String,
        description: String,
        textAlreadyRead: Boolean,
        subjectAlreadyDescribed: Boolean,
        subjectUnchanged: Boolean,
    ): Plan {
        val page = text.trim()
        val scene = description.trim()

        if (page.isEmpty() && scene.isEmpty()) return Plan(emptyList(), "nothing_recognised")

        // No text at all: this frame was never a reading, and the description is the whole answer.
        // It is offered on the same terms an ordinary scene description would be, including when
        // the user has turned away — the coordinator decides staleness for scenes, not this.
        if (page.isEmpty()) {
            if (subjectAlreadyDescribed) return Plan(emptyList(), "subject_already_described")
            return Plan(listOf(Utterance(scene, isDescription = true)), "description_only")
        }

        val spoken = mutableListOf<Utterance>()
        if (!textAlreadyRead) spoken += Utterance(page, isDescription = false)

        // The tail. It costs nothing extra to have produced, but it costs the user time to hear, so
        // it is spent only when it is still about what they are looking at and has not been said.
        val tailWorthSpeaking = scene.isNotEmpty() && subjectUnchanged && !subjectAlreadyDescribed
        if (tailWorthSpeaking) spoken += Utterance(scene, isDescription = true)

        val reason = when {
            spoken.isEmpty() -> "nothing_new_to_say"
            spoken.size == 2 -> "text_then_description"
            spoken.single().isDescription -> "text_already_read_description_new"
            scene.isEmpty() -> "text_only_no_description_offered"
            !subjectUnchanged -> "text_only_description_expired"
            else -> "text_only_description_already_said"
        }
        return Plan(spoken, reason)
    }
}
