package com.abdullah.visionbridge.data.diagnostics

/** Verifiable identity/accounting rules. Missing old identities are uncertainty, never fabricated
 * proof that a model response belonged to the temporally nearest image. Does not inspect pixels. */
object FrameIntegrityVerdict {
    private val identity=listOf("turnId","traceId","frameId","visualGeneration","mode","model",
        "transportSessionId","imageHash","promptVersion")
    private val outputs=setOf("RUNTIME_RESULT","TEXT_DISPLAYED","TTS_UTTERANCE_STARTED")
    fun analyse(events:List<SessionVerdict.Event>):List<SessionVerdict.Finding> {
        val submitted=mutableMapOf<String,SessionVerdict.Event>()
        // Streaming can accept several increasing prefixes for one image. A speech delta may
        // belong to any accepted revision, but never to an invented or not-yet-accepted one.
        val acceptedRevisions=mutableMapOf<String,MutableSet<String>>()
        val digest=Regex("[0-9a-f]{64}")
        var missingRevision=0;var unacceptedRevision=0
        var active:String?=null;var generation:Long?=null
        var missing=0;var stale=0;var mismatch=0;var queueLate=0;var engineLate=0
        for(e in events) {
            if(e.type=="VISUAL_GENERATION_CHANGED") {
                generation=e.number("visualGeneration")?.toLong();active=null
            }
            if(e.type=="TURN_ACTIVATED") { active=e.text("turnId");generation=e.number("visualGeneration")?.toLong() }
            if(e.type in setOf("FRAME_REQUEST_SENT","LOCAL_FRAME_BOUND")) e.text("turnId")?.let { submitted[it]=e }
            if(e.type !in outputs) continue
            // Operational notices are not frame content. Legacy frame-tagged output still needs
            // its missing identity reported rather than assumed valid.
            if(e.text("turnId")==null && e.text("frameId")==null)continue
            if(identity.any { e.text(it).isNullOrBlank() }) { missing++;continue }
            val id=e.text("turnId")!!;val owner=submitted[id]
            if(owner==null)missing++
            else if(identity.any { owner.text(it)!=e.text(it) })mismatch++
            if((active!=null && active!=id) || (generation!=null && generation!=e.number("visualGeneration")?.toLong()))stale++
            val revision=e.text("acceptedContentHash")
            if(revision==null || !digest.matches(revision)) missingRevision++
            else if(e.type=="RUNTIME_RESULT") {
                if(owner!=null && identity.all { owner.text(it)==e.text(it) } &&
                    (active==null || active==id) &&
                    (generation==null || generation==e.number("visualGeneration")?.toLong()))
                    acceptedRevisions.getOrPut(id) { mutableSetOf() }.add(revision)
            } else if(revision !in acceptedRevisions[id].orEmpty()) unacceptedRevision++
            if(e.type=="TTS_UTTERANCE_STARTED") {
                if((e.number("queueAgeMs") ?: e.number("queueWaitMs") ?: 0.0)>1000)queueLate++
                if((e.number("engineQueueAgeMs") ?: 0.0)>=1000)engineLate++
            }
        }
        // Also count old unbound TTS records, without pretending to know their frame association.
        queueLate=maxOf(queueLate,events.count { it.type=="TTS_UTTERANCE_STARTED" &&
            (it.number("queueAgeMs") ?: it.number("queueWaitMs") ?: 0.0)>1000 })
        val selected=events.count { it.type=="FRAME_SELECTED_FOR_ANALYSIS" }
        val sent=events.count { it.type in setOf("FRAME_REQUEST_SENT","LIVE_FRAME_SENT","LOCAL_FRAME_BOUND") }
        val justified=events.count { it.type=="FRAME_SKIPPED" && (it.text("reason")?.let { r ->
            r.contains("duplicate") || r.contains("quality") || r.contains("stability") || r.contains("obsolete")
        }==true) }
        val speechBlocks=events.count { it.type=="LIVE_LOCAL_SPEECH_BACKPRESSURE" }
        val viewport=events.filter { it.type in setOf("VIEWPORT_RESOLVED","ESIGHT_VIEWPORT_FALLBACK_AUTO","VIEWPORT_UNRESOLVED") }
        val fallback=viewport.count { it.type!="VIEWPORT_RESOLVED" || it.text("strategy")?.contains("UNRESOLVED")==true }
        val opticalConflicts=events.count { it.type=="TEXT_GROUNDING_DECISION" &&
            it.text("reason")=="no_text_conflicts_with_optical_evidence" }
        val result=mutableListOf<SessionVerdict.Finding>()
        fun add(code:String,severity:SessionVerdict.Severity,headline:String,measurement:String,vararg evidence:String) {
            result+=SessionVerdict.Finding(code,severity,headline,measurement,evidence.toList())
        }
        val timedOutWhileUnverified=events.filter { it.text("turnId")!=null }
            .groupBy { Triple(it.text("sessionId"),it.text("processId"),it.text("turnId")) }
            .values.count { turnEvents ->
                val sentIndex=turnEvents.indexOfFirst { it.type=="FRAME_REQUEST_SENT" && it.text("mode")=="TEXT_READING" }
                val chunkIndex=turnEvents.indexOfFirst { it.type=="FIRST_CHUNK" }
                val timeoutIndex=turnEvents.indexOfFirst { it.type=="CLOUD_ANALYSIS_BUDGET_EXCEEDED" }
                sentIndex>=0 && chunkIndex>sentIndex && timeoutIndex>chunkIndex &&
                    turnEvents.take(timeoutIndex).none { it.type=="LOCAL_GROUNDING_COMPLETED" || it.type=="RUNTIME_RESULT" }
            }
        val completedButUnpublished=events.filter { it.text("turnId")!=null }
            .groupBy { Triple(it.text("sessionId"),it.text("processId"),it.text("turnId")) }
            .values.count { turnEvents ->
                val complete=turnEvents.indexOfFirst { it.type=="TURN_COMPLETE" }
                val ended=turnEvents.indexOfFirst { it.type in setOf("TURN_CANCELLED_OBSOLETE","ANALYSIS_LANE_RELEASED","FRAME_ANALYSIS_FAILURE") }
                complete>=0 && ended>complete &&
                    turnEvents.any { it.type=="OUTPUT_VERIFICATION_WAIT_STARTED" } &&
                    turnEvents.take(ended+1).none { it.type=="RUNTIME_RESULT" || it.type=="LOCAL_GROUNDING_COMPLETED" }
            }
        if(completedButUnpublished>0)add("COMPLETED_RESPONSE_HELD_IN_VERIFICATION",SessionVerdict.Severity.MAJOR,
            "اكتمل رد Gemini وانتهى الدور دون نشر نتيجة أو اكتمال التحقق البصري المنتظر.",
            "count=$completedButUnpublished؛ افحص تبعية النشر للتحقق المحلي؛ اكتمال الشبكة وحده ليس نجاحًا.",
            "TURN_COMPLETE","OUTPUT_VERIFICATION_WAIT_STARTED","LOCAL_GROUNDING_COMPLETED","RUNTIME_RESULT","TURN_CANCELLED_OBSOLETE")
        if(timedOutWhileUnverified>0)add("RESPONSE_TIMED_OUT_BEFORE_VERIFICATION",SessionVerdict.Severity.MAJOR,
            "وصل رد Gemini ثم انتهت مهلة الطلب قبل تسجيل اكتمال التحقق البصري أو نتيجة للمستخدم.",
            "count=$timedOutWhileUnverified؛ راجع انتظار OCR داخل مسار الاستقبال؛ الأحداث وحدها لا تثبت السبب.",
            "FRAME_REQUEST_SENT","FIRST_CHUNK","CLOUD_ANALYSIS_BUDGET_EXCEEDED","LOCAL_GROUNDING_COMPLETED","RUNTIME_RESULT")
        if(stale>0)add("STALE_VISUAL_OUTPUT",SessionVerdict.Severity.FATAL,"وصلت نتيجة من دور أو جيل قديم إلى العرض أو النطق.","count=$stale",*outputs.toTypedArray())
        if(mismatch>0)add("FRAME_RESULT_IDENTITY_MISMATCH",SessionVerdict.Severity.FATAL,"هوية النتيجة لا تطابق الصورة المرسلة.","count=$mismatch",*outputs.toTypedArray())
        if(missing>0)add("UNPROVABLE_FRAME_RESULT_IDENTITY",SessionVerdict.Severity.MAJOR,"بعض النتائج لا تحمل هوية كاملة تثبت الصورة التي تخصها.","count=$missing",*outputs.toTypedArray())
        if(missingRevision>0)add("UNPROVABLE_ACCEPTED_CONTENT",SessionVerdict.Severity.MAJOR,"بصمة النص المقبول مفقودة أو غير صالحة؛ لا يمكن إثبات تطابق العرض والنطق.","count=$missingRevision",*outputs.toTypedArray())
        if(unacceptedRevision>0)add("UNACCEPTED_CONTENT_OUTPUT",SessionVerdict.Severity.FATAL,"عُرض أو نُطق نص لا يعود إلى نسخة مقبولة من نتيجة هذا الدور.","count=$unacceptedRevision",*outputs.toTypedArray())
        if(queueLate>0)add("EXCESSIVE_TTS_QUEUE_AGE",SessionVerdict.Severity.MAJOR,"بدأت مقاطع بعد انتظار طويل؛ يلزم فصل استكمال القراءة عن تأخر أول كلام.","over1000ms=$queueLate engineStartDeadlineViolations=$engineLate","TTS_UTTERANCE_STARTED")
        if(selected>=20 && (speechBlocks>0 || sent.toDouble()/(selected-justified).coerceAtLeast(1)<.25))
            add("EXCESSIVE_FRAME_SUPPRESSION",SessionVerdict.Severity.MAJOR,"عدد الإرسالات منخفض مقارنة بالإطارات المختارة؛ راجع أسباب المنع.","selected=$selected submitted=$sent explainedSkips=$justified speechBackpressure=$speechBlocks","FRAME_SELECTED_FOR_ANALYSIS","LIVE_LOCAL_SPEECH_BACKPRESSURE","FRAME_SKIPPED")
        if(viewport.size>=10 && fallback.toDouble()/viewport.size>=.8)
            add("PERSISTENT_VIEWPORT_FALLBACK",SessionVerdict.Severity.MAJOR,"لم تثبت منطقة بث الكاميرا في معظم الإطارات.","unresolved=$fallback total=${viewport.size}","VIEWPORT_UNRESOLVED","ESIGHT_VIEWPORT_FALLBACK_AUTO")
        if(opticalConflicts>0)add("NO_TEXT_WITH_OPTICAL_EVIDENCE",SessionVerdict.Severity.MINOR,"رفضت البوابة NO_TEXT لوجود دليل نصي محلي؛ هذه الحالات تحتاج إعادة محاولة.","rejectedConflicts=$opticalConflicts","TEXT_GROUNDING_DECISION","LOCAL_GROUNDING_COMPLETED")
        return result
    }
}
