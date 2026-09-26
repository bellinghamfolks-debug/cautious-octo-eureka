package com.abdullah.visionbridge.data.diagnostics

import com.abdullah.visionbridge.data.diagnostics.SessionVerdict.Event
import com.abdullah.visionbridge.data.diagnostics.SessionVerdict.Finding
import com.abdullah.visionbridge.data.diagnostics.SessionVerdict.Severity

/**
 * The live lane's own outcome rules, one per failure of the 2026-09-26 16:33 session.
 *
 * That session had to be diagnosed by reading its timeline line by line: a model struck off after
 * it had answered, a page never spoken because the model's voice spoke first, frames bursting,
 * the first frame five seconds late, and the same page reported twice. Each is now a rule, so the
 * next bundle says on its own whether any of them came back.
 */
object LiveVerdict {

    fun analyse(events: List<Event>): List<Finding> = listOfNotNull(
        modelStruckAfterAnswering(events),
        readingSpokenByModelVoice(events),
        exactTextNeverSpoken(events),
        frameBurst(events),
        pageReadTwice(events),
        firstFrameLate(events),
    )

    /** A model was ruled out, or a turn was called silent, after that turn had been answered. */
    private fun modelStruckAfterAnswering(events: List<Event>): Finding? {
        val answered = mutableSetOf<String>()
        var struck = 0
        val models = mutableSetOf<String>()
        for (event in events) {
            val turnId = event.text("turnId")
            when (event.type) {
                in ANSWER_EVENTS -> if (turnId != null && event.flag("stale") != true) answered += turnId
                "LIVE_TURN_SILENT" -> if (turnId != null && turnId in answered) {
                    struck += 1
                    event.text("model")?.let(models::add)
                }
                "LIVE_MODEL_CANNOT_ANSWER" -> {
                    struck += 1
                    event.text("model")?.let(models::add)
                }
            }
        }
        if (struck == 0) return null
        return Finding(
            code = "MODEL_STRUCK_AFTER_ANSWERING",
            severity = Severity.FATAL,
            headline = "النموذج أجاب ثم عُومل كأنه صامت أو شُطب.",
            measurement = "$struck حالة، على: ${models.joinToString("، ").ifBlank { "غير مسجَّل" }}.",
            evidence = listOf("LIVE_TURN_SILENT", "LIVE_MODEL_CANNOT_ANSWER", "LIVE_TOOL_TEXT_REPORTED"),
        )
    }

    /** The model's own voice was played, or took the page's place, during a reading. */
    private fun readingSpokenByModelVoice(events: List<Event>): Finding? {
        val played = events.count {
            it.type == "LIVE_FIRST_AUDIO_PACKET" &&
                it.text("mode") == READING &&
                it.flag("played") != false
        }
        val displaced = events.count { it.type == "LIVE_TOOL_TEXT_NOT_SPOKEN" }
        if (played + displaced == 0) return null
        return Finding(
            code = "READING_SPOKEN_BY_MODEL_VOICE",
            severity = Severity.FATAL,
            headline = "صوت النموذج قرأ في وضع القراءة بدل النص الحرفي.",
            measurement = "$played دوراً شُغِّل فيه صوت النموذج، و$displaced نصاً حرفياً لم يُنطق بسببه.",
            evidence = listOf("LIVE_FIRST_AUDIO_PACKET", "LIVE_TOOL_TEXT_NOT_SPOKEN"),
        )
    }

    /** The tool returned a page, and neither a reading nor a deliberate skip followed it. */
    private fun exactTextNeverSpoken(events: List<Event>): Finding? {
        val reported = events.filter {
            it.type == "LIVE_TOOL_TEXT_REPORTED" &&
                (it.number("characters") ?: 0.0) > 0.0 &&
                it.flag("stale") != true
        }
        if (reported.isEmpty()) return null
        val handled = events
            .filter { it.type == "LIVE_READING_ACCEPTED" || it.type == "LIVE_READING_SKIPPED" }
            .mapNotNull { it.text("turnId") }
            .toSet()
        val lost = reported.count { it.text("turnId") !in handled }
        if (lost == 0) return null
        return Finding(
            code = "EXACT_TEXT_NEVER_SPOKEN",
            severity = Severity.FATAL,
            headline = "النص الحرفي وصل من النموذج ولم يُقرأ ولم يُتخطَّ عمداً.",
            measurement = "$lost من ${reported.size} نصاً حرفياً بلا قراءة.",
            evidence = listOf("LIVE_TOOL_TEXT_REPORTED", "LIVE_READING_ACCEPTED", "LIVE_READING_SKIPPED"),
        )
    }

    /** Two frames went out on the socket closer together than the spacing allows. */
    private fun frameBurst(events: List<Event>): Finding? {
        val sent = events.filter { it.type == "LIVE_FRAME_SENT" }
        if (sent.size < 2) return null
        val gaps = sent.zipWithNext { previous, next ->
            next.number("sinceLastSentMs") ?: (next.epochMs - previous.epochMs).toDouble()
        }
        val bursts = gaps.count { it < MIN_FRAME_SPACING_MS }
        if (bursts == 0) return null
        return Finding(
            code = "FRAME_BURST",
            severity = Severity.MAJOR,
            headline = "لقطات أُرسلت متلاحقة فألغت إحداها الأخرى قبل أن تُجاب.",
            measurement = "$bursts فجوة أقل من ${MIN_FRAME_SPACING_MS.toInt()} ms، أقصرها " +
                "${gaps.minOrNull()?.toInt()} ms.",
            evidence = listOf("LIVE_FRAME_SENT"),
        )
    }

    /** The same page was accepted for a full reading twice within the repeat window. */
    private fun pageReadTwice(events: List<Event>): Finding? {
        val lastFullReading = mutableMapOf<String, Long>()
        var repeats = 0
        for (event in events) {
            if (event.type != "LIVE_READING_ACCEPTED" || event.flag("continuation") == true) continue
            val hash = event.text("contentHash") ?: continue
            val previous = lastFullReading[hash]
            if (previous != null && event.epochMs - previous < REPEAT_WINDOW_MS) repeats += 1
            lastFullReading[hash] = event.epochMs
        }
        if (repeats == 0) return null
        return Finding(
            code = "PAGE_READ_TWICE",
            severity = Severity.MAJOR,
            headline = "الصفحة نفسها قُرئت كاملة مرة ثانية.",
            measurement = "$repeats تكراراً خلال ${REPEAT_WINDOW_MS / 1000} ثانية.",
            evidence = listOf("LIVE_READING_ACCEPTED"),
        )
    }

    /** The first frame of the session reached the socket too long after capture started. */
    private fun firstFrameLate(events: List<Event>): Finding? {
        val first = events.firstOrNull { it.type == "LIVE_FRAME_SENT" } ?: return null
        val started = events.firstOrNull { it.type == "PROJECTION_STARTED" }
        val delayMs = first.number("sinceSessionStartMs")
            ?: started?.let { (first.epochMs - it.epochMs).toDouble() }
            ?: return null
        if (delayMs <= FIRST_FRAME_BUDGET_MS) return null
        return Finding(
            code = "FIRST_FRAME_LATE",
            severity = Severity.MINOR,
            headline = "أول لقطة وصلت إلى البث المباشر متأخرة.",
            measurement = "${delayMs.toInt()} ms من بدء المشاركة، والهدف ${FIRST_FRAME_BUDGET_MS.toInt()} ms.",
            evidence = listOf("PROJECTION_STARTED", "LIVE_FRAME_SENT"),
        )
    }

    private const val READING = "TEXT_READING"
    private const val MIN_FRAME_SPACING_MS = 1_000.0
    private const val REPEAT_WINDOW_MS = 120_000L
    private const val FIRST_FRAME_BUDGET_MS = 2_000.0

    private val ANSWER_EVENTS = setOf(
        "LIVE_TOOL_TEXT_REPORTED",
        "LIVE_FIRST_AUDIO_PACKET",
        "LIVE_FIRST_TEXT",
        "LIVE_OUTPUT_TRANSCRIPTION",
    )
}
