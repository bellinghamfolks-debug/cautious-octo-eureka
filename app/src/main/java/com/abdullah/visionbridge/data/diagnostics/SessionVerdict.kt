package com.abdullah.visionbridge.data.diagnostics

import kotlin.math.abs

/**
 * Turns a session's raw timeline into a ranked diagnosis.
 *
 * The bundle already recorded everything. What it never did was *account* for it: a session in
 * which 153 pages were recognised and 200 characters were heard produced 52,097 events, 2,109
 * findings — all of them "a stage was slow" or "a frame was dropped" — and not one line saying the
 * recognised text was not reaching the user. Finding that took an afternoon of arithmetic that the
 * app was in a position to do itself, in a millisecond, at the moment of export.
 *
 * Every rule here is an *outcome* rule. It compares what the pipeline produced against what the
 * user actually received, or a bound against what actually happened, and when the two disagree it
 * says so in one sentence with the numbers attached. The rules are derived from defects that were
 * real, each one traced to a device bundle, so a match is a diagnosis rather than a suspicion.
 *
 * Pure, so it can be run against a recorded bundle offline and checked against a diagnosis reached
 * by hand.
 */
object SessionVerdict {

    enum class Severity { FATAL, MAJOR, MINOR }

    /**
     * [headline] is one Arabic sentence naming the failure. [measurement] carries the numbers that
     * justify it, so the reader can disagree with the conclusion without re-deriving the data.
     */
    data class Finding(
        val code: String,
        val severity: Severity,
        val headline: String,
        val measurement: String,
        val evidence: List<String>,
    )

    /**
     * One recorded event, reduced to what the rules need.
     *
     * The accessors are deliberately forgiving about type. A JSON round trip can hand back the same
     * field as an Int, a Long, a BigDecimal or a String depending on how it was written and which
     * parser read it back — and a rule that silently fails to fire because a number arrived as text
     * is worse than no rule at all, because the bundle then looks clean. This exact case cost a
     * false all-clear on a session whose analysis was six times slower than its target changes.
     */
    class Event(val type: String, val fields: Map<String, Any?>) {
        fun number(key: String): Double? = when (val value = fields[key]) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull()
            is Boolean -> if (value) 1.0 else 0.0
            else -> null
        }

        fun text(key: String): String? = fields[key]?.takeIf { it !is Boolean }?.toString()

        fun flag(key: String): Boolean? = when (val value = fields[key]) {
            is Boolean -> value
            is String -> value.toBooleanStrictOrNull()
            else -> null
        }

        val epochMs: Long get() = number("epochMs")?.toLong() ?: 0L
    }

    /** Ranked most severe first; an empty list means nothing known went wrong. */
    fun analyse(events: List<Event>): List<Finding> {
        val findings = listOfNotNull(
            projectionDied(events),
            textNotDelivered(events),
            speechCutOff(events),
            targetFasterThanAnalysis(events),
            requestOutlivedDeadline(events),
            appStoppedRunning(events),
            captureWentBlack(events),
            suppressedButNeverSpoken(events),
            cloudNeverSucceeded(events),
            detectedButDiscarded(events),
            analysisStarved(events),
        )
        return findings.sortedBy { it.severity.ordinal }
    }

    // region rules

    /**
     * The capture session ended because Android took it away, which on this app has one recurring
     * cause: asking a live projection for a second virtual display.
     */
    private fun projectionDied(events: List<Event>): Finding? {
        val stopped = events.count { it.type == "PROJECTION_SYSTEM_STOPPED" }
        if (stopped == 0) return null
        val illegal = events.count { event ->
            event.type == "FAILURE" &&
                event.text("exception")?.contains("SecurityException") == true &&
                event.text("message")?.contains("createVirtualDisplay") == true
        }
        return Finding(
            code = "CAPTURE_SESSION_KILLED",
            severity = Severity.FATAL,
            headline = "أوقف Android مشاركة الشاشة أثناء الجلسة، فتوقف كل شيء.",
            measurement = if (illegal > 0) {
                "$stopped إيقافاً، و$illegal منها بعد طلب سطح عرض ثانٍ (SecurityException) مباشرة."
            } else {
                "$stopped إيقافاً، بلا استثناء مسجَّل قبله — السبب خارج التطبيق أو غير مُلتقَط."
            },
            evidence = listOf("PROJECTION_SYSTEM_STOPPED", "FAILURE"),
        )
    }

    /**
     * The headline defect: text was recognised correctly and the user never heard it.
     *
     * This is the one measurement that would have named the perfume complaint on sight.
     */
    private fun textNotDelivered(events: List<Event>): Finding? {
        val recognised = events
            .filter { it.type == "PPOCR_PAGE_READ" || it.type == "CLOUD_ANALYSIS_COMPLETED" }
            .sumOf { it.number("characters")?.toInt() ?: it.text("text")?.length ?: 0 }
        if (recognised < MIN_CHARACTERS_TO_JUDGE) return null

        val delivered = events
            .filter { it.type == "TTS_UTTERANCE_DONE" }
            .sumOf { it.text("text")?.length ?: 0 }
        val ratio = delivered.toDouble() / recognised
        if (ratio >= DELIVERY_FLOOR) return null

        return Finding(
            code = "RECOGNISED_TEXT_NOT_DELIVERED",
            severity = Severity.FATAL,
            headline = "النص يُقرأ بنجاح ولا يصل إلى المستخدم.",
            measurement = "$recognised حرفاً تعرَّف عليها المحرك، و$delivered حرفاً فقط اكتمل نطقها " +
                "(${percent(ratio)}). الخلل بعد القراءة، لا فيها.",
            evidence = listOf("PPOCR_PAGE_READ", "CLOUD_ANALYSIS_COMPLETED", "TTS_UTTERANCE_DONE"),
        )
    }

    /** Speech that keeps being cut off mid-word is the usual reason text does not arrive. */
    private fun speechCutOff(events: List<Event>): Finding? {
        val interrupted = events.count { it.type == "TTS_UTTERANCE_INTERRUPTED" }
        val done = events.count { it.type == "TTS_UTTERANCE_DONE" }
        if (interrupted < MIN_INTERRUPTIONS || interrupted < done * INTERRUPTION_RATIO) return null

        val queueInterrupts = events.count { it.type == "TTS_QUEUE_INTERRUPTED" }
        return Finding(
            code = "SPEECH_REPEATEDLY_CUT_OFF",
            severity = Severity.FATAL,
            headline = "النطق يُقطع في منتصف الكلمة مراراً.",
            measurement = "$interrupted نطقاً قُطع مقابل $done اكتمل، و$queueInterrupts إفراغاً " +
                "لطابور النطق.",
            evidence = listOf("TTS_UTTERANCE_INTERRUPTED", "TTS_QUEUE_INTERRUPTED"),
        )
    }

    /**
     * The arithmetic that makes a pipeline structurally unable to finish: the target is declared
     * new more often than a result can be produced, so every result is stale before it exists.
     */
    private fun targetFasterThanAnalysis(events: List<Event>): Finding? {
        val changes = events.filter { it.type == "VISUAL_TARGET_CHANGED" }.map { it.epochMs }
        if (changes.size < MIN_TARGET_CHANGES) return null
        val gaps = changes.zipWithNext { a, b -> (b - a).toDouble() }.sorted()
        if (gaps.isEmpty()) return null
        val medianGap = gaps[gaps.size / 2]

        val latencies = events
            .filter { it.type == "PPOCR_PAGE_READ" || it.type == "CLOUD_ANALYSIS_COMPLETED" }
            .mapNotNull { it.number("sinceCaptureMs") ?: it.number("durationMs") }
            .sorted()
        if (latencies.isEmpty()) return null
        val medianLatency = latencies[latencies.size / 2]
        if (medianGap >= medianLatency) return null

        val over = medianLatency / medianGap
        return Finding(
            code = "TARGET_CHANGES_FASTER_THAN_ANALYSIS",
            severity = Severity.FATAL,
            headline = "الهدف يُعلَن جديداً أسرع مما يستطيع التحليل أن ينتهي.",
            measurement = "${changes.size} تغيّراً، الوسيط بينها ${medianGap.toInt()} مللي ثانية، " +
                "بينما التحليل يحتاج ${medianLatency.toInt()} — أي أن كل نتيجة تُلغى " +
                "${"%.1f".format(over)} مرة قبل أن توجد.",
            evidence = listOf("VISUAL_TARGET_CHANGED", "PPOCR_PAGE_READ"),
        )
    }

    /** A bound that did not bind. The gap between configured and enforced is the whole finding. */
    private fun requestOutlivedDeadline(events: List<Event>): Finding? {
        val worst = events
            .filter { it.type == "CLOUD_ANALYSIS_BUDGET_EXCEEDED" }
            .mapNotNull { event ->
                val budget = event.number("budgetMs") ?: return@mapNotNull null
                val elapsed = event.number("elapsedMs") ?: return@mapNotNull null
                Triple(budget, elapsed, elapsed / budget)
            }
            .maxByOrNull { it.third } ?: return null
        if (worst.third < DEADLINE_OVERRUN_FACTOR) return null

        return Finding(
            code = "REQUEST_OUTLIVED_ITS_DEADLINE",
            severity = Severity.FATAL,
            headline = "طلب سحابي تجاوز مهلته بفارق لا تفسّره بطء الشبكة.",
            measurement = "الميزانية ${worst.first.toInt()} مللي ثانية، والتنفيذ الفعلي " +
                "${worst.second.toInt()} — أي ${"%.0f".format(worst.third)} أضعاف. " +
                "مؤقّت لم يعمل، لا شبكة بطيئة.",
            evidence = listOf("CLOUD_ANALYSIS_BUDGET_EXCEEDED"),
        )
    }

    /**
     * A hole in the timeline: the clock moved and the app recorded nothing.
     *
     * Requires the liveness heartbeat to be conclusive, and says so when it is missing rather than
     * guessing between a frozen process, Doze, and an app that simply had nothing to do.
     */
    private fun appStoppedRunning(events: List<Event>): Finding? {
        if (events.size < 2) return null
        val ordered = events.filter { it.epochMs > 0 }.sortedBy { it.epochMs }
        var worstGapMs = 0L
        var worstAt = 0L
        for (index in 1 until ordered.size) {
            val gap = ordered[index].epochMs - ordered[index - 1].epochMs
            if (gap > worstGapMs) {
                worstGapMs = gap
                worstAt = ordered[index - 1].epochMs
            }
        }
        if (worstGapMs < SILENCE_GAP_MS) return null

        val hadHeartbeat = events.any { it.type == "PROCESS_HEARTBEAT" }
        val missedBeats = if (hadHeartbeat) {
            " نبض الحياة يؤكد أن العملية توقفت عن العمل، لا أنها كانت خاملة."
        } else {
            " لا يوجد نبض حياة في هذه الجلسة، فلا يمكن التمييز بين تجمّد وخمول."
        }
        return Finding(
            code = "APP_STOPPED_RECORDING",
            severity = Severity.MAJOR,
            headline = "توقف التطبيق عن تسجيل أي شيء لفترة طويلة.",
            measurement = "أطول فجوة ${worstGapMs / 1000} ثانية، بدأت عند $worstAt.$missedBeats",
            evidence = listOf("PROCESS_HEARTBEAT"),
        )
    }

    /** A black mirror, and whether it follows a capture resize closely enough to be its cause. */
    private fun captureWentBlack(events: List<Event>): Finding? {
        val black = events.filter { it.type == "VISUAL_FEED_UNAVAILABLE_NOTICE_TRIGGERED" }
        if (black.isEmpty()) return null

        val afterResize = black.count { event ->
            val since = event.number("sinceLastResizeMs")
            since != null && since < RESIZE_CORRELATION_MS
        }
        val landscape = black.count { it.flag("landscapeCapture") == true }
        val correlated = afterResize == black.size && black.size > 1

        return Finding(
            code = "CAPTURE_WENT_BLACK",
            severity = Severity.MAJOR,
            headline = if (correlated) {
                "الصورة الواردة تسودّ بعد كل تغيير لحجم الالتقاط."
            } else {
                "الصورة الواردة سوداء أو بلا تباين."
            },
            measurement = "${black.size} حالة، منها $afterResize خلال " +
                "${RESIZE_CORRELATION_MS / 1000} ثوانٍ من تغيير الحجم، و$landscape في الوضع الأفقي.",
            evidence = listOf("VISUAL_FEED_UNAVAILABLE_NOTICE_TRIGGERED", "CAPTURE_RESIZE_APPLIED"),
        )
    }

    /**
     * The ledger says a page was already read, but nothing was ever delivered for it. That is the
     * signature of recording content as heard at the moment it was queued.
     */
    private fun suppressedButNeverSpoken(events: List<Event>): Finding? {
        val suppressed = events.count { event ->
            event.type == "DOCUMENT_READING_SKIPPED" &&
                event.text("reason") == "already_read_completely"
        }
        if (suppressed < MIN_SUPPRESSIONS) return null

        val deliveries = events.filter { it.type == "DOCUMENT_READING_DELIVERED" }
        val completeDeliveries = deliveries.count { it.flag("complete") == true }
        val owed = deliveries.sumOf { it.number("owedCharacters")?.toInt() ?: 0 }

        // With no delivery record at all, the ledger cannot have been told what was heard.
        if (deliveries.isEmpty()) {
            return Finding(
                code = "SUPPRESSED_WITHOUT_DELIVERY_PROOF",
                severity = Severity.MAJOR,
                headline = "صفحات كُتمت بحجة أنها قُرئت، ولا يوجد ما يثبت أن المستخدم سمعها.",
                measurement = "$suppressed كتماً بسبب already_read_completely، وصفر سجل تسليم. " +
                    "هذا بناء قديم يسجّل الصفحة مقروءة لحظة وضعها في الطابور.",
                evidence = listOf("DOCUMENT_READING_SKIPPED"),
            )
        }
        if (owed == 0) return null
        return Finding(
            code = "CONTENT_STILL_OWED",
            severity = Severity.MAJOR,
            headline = "بقي نص مستحق للمستخدم لم يُنطق.",
            measurement = "$owed حرفاً ديناً، و$completeDeliveries تسليماً كاملاً من " +
                "${deliveries.size}، مع $suppressed كتماً.",
            evidence = listOf("DOCUMENT_READING_DELIVERED", "DOCUMENT_READING_SKIPPED"),
        )
    }

    /** Requests that start and never finish point at the network layer rather than the model. */
    private fun cloudNeverSucceeded(events: List<Event>): Finding? {
        val started = events.count { it.type == "HTTP_REQUEST_STARTED" }
        if (started < MIN_REQUESTS) return null
        val completed = events.count { it.type == "CLOUD_ANALYSIS_COMPLETED" }
        if (completed >= started * CLOUD_SUCCESS_FLOOR) return null

        val cellular = events.any { it.flag("forceCellular") == true }
        return Finding(
            code = "CLOUD_REQUESTS_MOSTLY_FAIL",
            severity = Severity.MAJOR,
            headline = "أغلب الطلبات السحابية تبدأ ولا تنتهي.",
            measurement = "$started طلباً بدأ و$completed اكتمل" +
                if (cellular) "، والجلسة كلها على البيانات الخلوية." else ".",
            evidence = listOf("HTTP_REQUEST_STARTED", "CLOUD_ANALYSIS_COMPLETED"),
        )
    }

    /**
     * Text the detector found and the recogniser threw away. High on its own is not a defect —
     * a bottle's edges produce boxes that are not text — but it is the first thing to look at when
     * a page reads short.
     */
    private fun detectedButDiscarded(events: List<Event>): Finding? {
        val decisions = events.filter { it.type == "PPOCR_LINE_DECISION" }
        if (decisions.size < MIN_LINE_DECISIONS) return null
        val dropped = decisions.count { it.flag("droppedEverything") == true }
        val ratio = dropped.toDouble() / decisions.size
        if (ratio < DISCARD_CEILING) return null

        val bestDropped = decisions
            .filter { it.flag("droppedEverything") == true }
            .mapNotNull { maxOfOrNull(it.number("arabicConfidence"), it.number("latinConfidence")) }
            .maxOrNull()
        return Finding(
            code = "DETECTED_LINES_DISCARDED",
            severity = Severity.MINOR,
            headline = "أكثر من نصف الأسطر المكتشفة تُرمى قبل النطق.",
            measurement = "$dropped من ${decisions.size} (${percent(ratio)})، وأعلى ثقة بين " +
                "المرميّات ${bestDropped?.let { "%.2f".format(it) } ?: "غير مسجَّلة"}. " +
                "إن كانت هذه الثقة عالية فالرمي خاطئ.",
            evidence = listOf("PPOCR_LINE_DECISION"),
        )
    }

    /** Frames arriving far faster than they are analysed, which is throughput, not correctness. */
    private fun analysisStarved(events: List<Event>): Finding? {
        val acquired = events.count { it.type == "FRAME_ACQUIRED" }
        val analysed = events.count { it.type == "COORDINATOR_PROCESS_STARTED" }
        if (acquired < MIN_FRAMES || analysed == 0) return null
        val ratio = analysed.toDouble() / acquired
        if (ratio >= ANALYSIS_FLOOR) return null
        return Finding(
            code = "MOST_FRAMES_NEVER_ANALYSED",
            severity = Severity.MINOR,
            headline = "الغالبية العظمى من الإطارات تُلتقط ولا تُحلَّل.",
            measurement = "$acquired إطاراً وصل، و$analysed حُلِّل (${percent(ratio)}).",
            evidence = listOf("FRAME_ACQUIRED", "COORDINATOR_PROCESS_STARTED"),
        )
    }

    // endregion

    private fun maxOfOrNull(a: Double?, b: Double?): Double? = when {
        a == null -> b
        b == null -> a
        else -> if (abs(a) >= abs(b)) a else b
    }

    private fun percent(ratio: Double): String = "${(ratio * 100).toInt()}٪"

    private const val MIN_CHARACTERS_TO_JUDGE = 200
    private const val DELIVERY_FLOOR = 0.5
    private const val MIN_INTERRUPTIONS = 3
    private const val INTERRUPTION_RATIO = 0.5
    private const val MIN_TARGET_CHANGES = 8
    private const val DEADLINE_OVERRUN_FACTOR = 1.5
    private const val SILENCE_GAP_MS = 60_000L
    private const val RESIZE_CORRELATION_MS = 4_000.0
    private const val MIN_SUPPRESSIONS = 5
    private const val MIN_REQUESTS = 5
    private const val CLOUD_SUCCESS_FLOOR = 0.5
    private const val MIN_LINE_DECISIONS = 50
    private const val DISCARD_CEILING = 0.5
    private const val MIN_FRAMES = 200
    private const val ANALYSIS_FLOOR = 0.05
}
