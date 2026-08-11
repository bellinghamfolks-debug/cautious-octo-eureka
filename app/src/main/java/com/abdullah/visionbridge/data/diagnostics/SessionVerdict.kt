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
            answersTruncated(events),
            textNotDelivered(events),
            speechCutOff(events),
            analysisDiscardedBeforeDelivery(events),
            requestOutlivedDeadline(events),
            appStoppedRunning(events),
            captureWentBlack(events),
            suppressedButNeverSpoken(events),
            cloudNeverSucceeded(events),
            detectedButDiscarded(events),
            detectorRejectedLargeRegions(events),
            boundNetworkNeverValidated(events),
            resolutionNeverSettled(events),
            analysisStarved(events),
        ).sortedBy { it.severity.ordinal }

        // Last, and only when there is something it would have settled: a pointer at the one
        // control that turns the next bundle from an argument into a measurement.
        return findings + listOfNotNull(noPixelsToSettleIt(events, findings))
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

    /**
     * The model's answers are being cut off by the token ceiling rather than finishing.
     *
     * This ranks near the top because of how well it hides. A truncated answer is a real answer as
     * far as every stage after it is concerned: it is spoken, it is recorded as delivered, and no
     * request fails. The user hears a fluent fragment — "تق", "شخص ين" — and reasonably concludes
     * the app saw very little, when in fact the model saw everything and was not allowed to say it.
     *
     * The token split is what turns that into a fix rather than a guess: it says whether the
     * ceiling is too low for the answer, or whether reasoning ate the budget before the answer
     * began.
     */
    private fun answersTruncated(events: List<Event>): Finding? {
        val finishes = events.filter { it.type == "MODEL_FINISH_REASON" }
        if (finishes.size < MIN_RESPONSES_TO_JUDGE) return null
        val truncated = finishes.filter { it.flag("truncated") == true }
        val ratio = truncated.size.toDouble() / finishes.size
        if (ratio < TRUNCATION_FLOOR) return null

        val reasoning = truncated.mapNotNull { it.number("reasoningTokens")?.toInt() }
        val answers = truncated.mapNotNull { it.number("answerTokens")?.toInt() }
        val ceiling = truncated.firstNotNullOfOrNull { it.number("maxOutputTokens")?.toInt() }
        val medianReasoning = reasoning.sorted().getOrNull(reasoning.size / 2)
        val medianAnswer = answers.sorted().getOrNull(answers.size / 2)

        val split = if (medianReasoning != null && medianAnswer != null && ceiling != null) {
            " الوسيط: $medianReasoning رمزاً للتفكير و$medianAnswer فقط للإجابة من سقف $ceiling — " +
                "أي أن التفكير استهلك الميزانية قبل أن تبدأ الإجابة."
        } else {
            ""
        }
        return Finding(
            code = "MODEL_ANSWERS_TRUNCATED",
            severity = Severity.FATAL,
            headline = "إجابات النموذج تُقطع قبل أن تكتمل، لا تأتي قصيرة.",
            measurement = "${truncated.size} إجابة من ${finishes.size} انتهت بسبب نفاد سقف الرموز " +
                "(${percent(ratio)}).$split ما سُمع جزء من جملة، وما بعده لم يُرسل أصلاً.",
            evidence = listOf("MODEL_FINISH_REASON", "MODEL_OUTPUT_TRUNCATED"),
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
     * Analyses thrown away because the subject moved on before they could finish.
     *
     * The rule this replaces compared two medians — the gap between target changes against the time
     * an analysis takes — and called the pipeline structurally broken whenever the first was
     * smaller. That arithmetic is not evidence of anything. On the 2026-08-10 scene session it
     * declared a FATAL over 29 target changes at a median 1866 ms against a 4354 ms analysis, while
     * the same session completed 36 requests, delivered all 36, and discarded exactly none: a
     * scene request is allowed to outlive the frame that started it, so a shorter gap costs
     * nothing. Meanwhile the text session in the same bundle, where 15 of 42 requests really were
     * killed in flight, produced no finding at all. The rule was precisely inverted.
     *
     * What matters is not how often the subject changes — a user walking through a room changes it
     * constantly and correctly — but whether work that had already been paid for was thrown away.
     * So this counts the discard directly, and uses the cadence only to explain it.
     */
    private fun analysisDiscardedBeforeDelivery(events: List<Event>): Finding? {
        val cancelled = events.count {
            it.type == "CLOUD_ANALYSIS_CANCELLED" && it.text("reason") == "visual_target_changed"
        }
        val suppressed = events.count {
            it.type == "FINAL_RESULT_SUPPRESSED" && it.text("reason") == "stale_visual_generation"
        }
        val discarded = cancelled + suppressed
        if (discarded == 0) return null

        val completed = events.count {
            it.type == "CLOUD_ANALYSIS_COMPLETED" || it.type == "PPOCR_PAGE_READ"
        }
        val attempted = completed + cancelled
        if (attempted < MIN_REQUESTS) return null
        val ratio = discarded.toDouble() / attempted
        if (ratio < DISCARD_RATIO) return null

        val changes = events.filter { it.type == "VISUAL_TARGET_CHANGED" }.map { it.epochMs }
        val gaps = changes.zipWithNext { a, b -> (b - a).toDouble() }.sorted()
        val cadence = if (gaps.isEmpty()) {
            ""
        } else {
            " الهدف تغيّر ${changes.size} مرة، الوسيط بينها ${gaps[gaps.size / 2].toInt()} مللي ثانية."
        }
        return Finding(
            code = "ANALYSIS_DISCARDED_BEFORE_DELIVERY",
            severity = if (ratio >= SEVERE_DISCARD_RATIO) Severity.FATAL else Severity.MAJOR,
            headline = "تحليل اكتمل أو كاد، ثم أُلقي قبل أن يصل إلى المستخدم.",
            measurement = "$discarded من $attempted (${percent(ratio)}): $cancelled أُلغيت في " +
                "الطريق و$suppressed نتيجة وصلت متأخرة فطُرحت.$cadence " +
                "هذا رفعٌ ووقتٌ دُفعا ولم يُسمع منهما شيء.",
            evidence = listOf(
                "CLOUD_ANALYSIS_CANCELLED",
                "FINAL_RESULT_SUPPRESSED",
                "VISUAL_TARGET_CHANGED",
            ),
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

    /**
     * The detector found something large and this pipeline threw it away before anyone could read
     * it — which is the difference between "the text was never seen" and "the text was seen and
     * discarded", and those two need opposite repairs.
     *
     * A big prominent word going unread was the complaint that motivated this rule, and the bundle
     * of the day could not answer it: four `continue` statements decided the outcome and counted
     * nothing. Now every rejected region is counted and the largest is described, so the answer is
     * arithmetic rather than a guess.
     */
    private fun detectorRejectedLargeRegions(events: List<Event>): Finding? {
        val frames = events.filter { it.type == "PPOCR_DETECTION_COMPLETED" }
        if (frames.size < MIN_DETECTION_FRAMES) return null

        val barren = frames.filter { frame ->
            (frame.number("accepted")?.toInt() ?: -1) == 0 &&
                (frame.number("regionsFound")?.toInt() ?: 0) > 0
        }
        if (barren.size < frames.size * BARREN_FRAME_RATIO) return null

        val worst = barren.maxByOrNull { frame ->
            val width = frame.number("largestRejectedWidthFraction") ?: 0.0
            val height = frame.number("largestRejectedHeightFraction") ?: 0.0
            width * height
        } ?: return null
        val area = (worst.number("largestRejectedWidthFraction") ?: 0.0) *
            (worst.number("largestRejectedHeightFraction") ?: 0.0)
        if (area < LARGE_REGION_AREA) return null

        val reasons = barren.mapNotNull { it.text("largestRejectedReason") }
        val dominant = reasons.groupingBy { it }.eachCount().maxByOrNull { it.value }
        return Finding(
            code = "DETECTOR_REJECTED_LARGE_REGIONS",
            severity = Severity.MAJOR,
            headline = "الكاشف يجد نصاً كبيراً ثم يرميه قبل القراءة.",
            measurement = "${barren.size} إطاراً من ${frames.size} انتهى بصفر مربع مقبول رغم وجود " +
                "مناطق فوق العتبة. أكبر منطقة مرميّة تشغل ${percent(area)} من الإطار، " +
                "بسبب ${dominant?.key ?: "غير مسجَّل"} (${dominant?.value ?: 0} إطاراً)، " +
                "ودرجتها ${worst.number("largestRejectedScore")?.let { "%.2f".format(it) } ?: "غير مسجَّلة"} " +
                "مقابل عتبة القبول.",
            evidence = listOf("PPOCR_DETECTION_COMPLETED"),
        )
    }

    /**
     * Cellular was forced and the network it bound to was never proven to carry traffic.
     *
     * Five field sessions all ran with cellular forced, so "cellular freezes the app" had no control
     * group and stayed a correlation. What settles it is not another session — it is what the bound
     * network could actually do, recorded at the moment of binding, next to what the default network
     * could do at the same moment.
     */
    private fun boundNetworkNeverValidated(events: List<Event>): Finding? {
        val acquired = events.filter { it.type == "CELLULAR_NETWORK_ACQUIRED" }
        if (acquired.size < MIN_ACQUISITIONS) return null
        val unvalidated = acquired.count { it.flag("boundValidated") == false }
        if (unvalidated < acquired.size * UNVALIDATED_RATIO) return null

        val fallbacks = events.count { it.type == "CELLULAR_NETWORK_VALIDATION_FALLBACK" }
        val defaultWasValidated = acquired.count { it.flag("defaultValidated") == true }
        val captive = acquired.count { it.flag("boundCaptivePortal") == true }
        return Finding(
            code = "BOUND_NETWORK_NEVER_VALIDATED",
            severity = Severity.MAJOR,
            headline = "طلبات Gemini مربوطة بشبكة لم يثبت أنها تنقل بيانات.",
            measurement = "$unvalidated من ${acquired.size} ربطاً على شبكة غير مُتحقَّق منها، " +
                "و$fallbacks مرة سقط الطلب إلى المسار غير المتحقَّق، بينما كانت الشبكة الافتراضية " +
                "متحقَّقة في $defaultWasValidated منها" +
                if (captive > 0) "، و$captive منها خلف بوابة تسجيل دخول." else ".",
            evidence = listOf("CELLULAR_NETWORK_ACQUIRED", "CELLULAR_NETWORK_VALIDATION_FALLBACK"),
        )
    }

    /**
     * The adaptive resolution controller never found text to solve for, so it spent the session
     * bracketing instead of reading. Distinct from "there was no text": the controller says which
     * of the two it believed, and this rule reports that it never stopped believing the first.
     */
    private fun resolutionNeverSettled(events: List<Event>): Finding? {
        val decisions = events
            .filter { it.type == "PPOCR_DETECTION_COMPLETED" }
            .mapNotNull { it.text("resolutionReason") }
        if (decisions.size < MIN_DETECTION_FRAMES) return null
        val searching = decisions.count { it.startsWith("nothing_found") }
        if (searching < decisions.size * SEARCHING_RATIO) return null

        return Finding(
            code = "READING_RESOLUTION_NEVER_SETTLED",
            severity = Severity.MINOR,
            headline = "لم يستقر اختيار دقة القراءة على قياس، فبقي يبحث.",
            measurement = "$searching من ${decisions.size} قراراً بلا قياس لارتفاع النص " +
                "(${percent(searching.toDouble() / decisions.size)}). إما أن الإطارات بلا نص، " +
                "أو أن النص أصغر من أن يُكتشف عند أي درجة على السلّم.",
            evidence = listOf("PPOCR_DETECTION_COMPLETED"),
        )
    }

    /**
     * Not a defect — an instruction. When something went wrong and no frame was kept, the next
     * bundle can settle it outright, and the control that does so is reachable without leaving the
     * app being captured.
     */
    private fun noPixelsToSettleIt(events: List<Event>, found: List<Finding>): Finding? {
        if (found.none { it.severity != Severity.MINOR }) return null
        if (events.any { it.type == "EVIDENCE_FRAME_CAPTURED" }) return null
        if (events.none { it.type == "PPOCR_DETECTION_COMPLETED" || it.type == "CLOUD_ANALYSIS_COMPLETED" }) {
            return null
        }
        return Finding(
            code = "NO_FRAME_KEPT_TO_SETTLE_IT",
            severity = Severity.MINOR,
            headline = "لا توجد لقطة واحدة تُثبت ما الذي كان أمام الكاميرا وقت الخلل.",
            measurement = "لتشخيص أدق في المرة القادمة: أثناء وجودك داخل eSight، اضغط زر إمكانية " +
                "الوصول العائم — أو زر «تشغيل حفظ اللقطات» في إشعار VisionBridge — قبل النص الذي " +
                "لا يُقرأ مباشرة، ثم اضغطه ثانية بعد المحاولة. تُحفظ لحظات الفشل فقط، وسيُنطق الوضع " +
                "بعد كل ضغطة.",
            evidence = listOf("EVIDENCE_SHORTCUT_PRESSED"),
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

    /** Enough responses that a run of truncations is a setting, not a long answer or two. */
    private const val MIN_RESPONSES_TO_JUDGE = 5

    /** Below this a truncation is an unusually long answer; above it, a ceiling in the wrong place. */
    private const val TRUNCATION_FLOOR = 0.25
    private const val MIN_INTERRUPTIONS = 3
    private const val INTERRUPTION_RATIO = 0.5
    /** Below this a discard is one unlucky moment; above it, work is being thrown away in bulk. */
    private const val DISCARD_RATIO = 0.35

    /** At this share the pipeline is spending most of its budget on results nobody hears. */
    private const val SEVERE_DISCARD_RATIO = 0.6
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
    private const val MIN_DETECTION_FRAMES = 10
    private const val BARREN_FRAME_RATIO = 0.3

    /** Two per cent of the frame. A word held up to be read is far bigger than this. */
    private const val LARGE_REGION_AREA = 0.02
    private const val MIN_ACQUISITIONS = 3
    private const val UNVALIDATED_RATIO = 0.5
    private const val SEARCHING_RATIO = 0.6
}
