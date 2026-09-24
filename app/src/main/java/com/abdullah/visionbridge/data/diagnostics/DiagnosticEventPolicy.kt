package com.abdullah.visionbridge.data.diagnostics

/** Timing configuration, time windows and ages are not execution durations. */
object DiagnosticEventPolicy {
    fun latencyFields(type:String):List<String> = when(type) {
        "FRAME_STAGE","PPOCR_LOAD_COMPLETED","PPOCR_DETECTION_COMPLETED","PPOCR_RECOGNITION_COMPLETED",
        "LOCAL_GROUNDING_COMPLETED","LOCAL_OCR_COMPLETED","MLKIT_PROCESS_COMPLETED" -> listOf("durationMs")
        "ANALYSIS_DISPATCH_COMPLETED" -> listOf("dispatchDurationMs")
        "SMART_TARGET_POLICY_DECISION","VISUAL_TARGET_DECISION" -> listOf("trackingMs")
        "FRAME_REQUEST_SENT","LIVE_FRAME_SENT" -> listOf("encodeTotalMs","base64Ms","payloadMs")
        "EVIDENCE_FRAME_CAPTURED" -> listOf("evidenceWriteMs")
        else -> emptyList()
    }
    fun expectedReplacement(reason:String)=reason in setOf("newer_candidate","better_quality_candidate",
        "pending_target_obsolete","older_generation_candidate","obsolete_at_promotion")
    fun expectedCancellation(type:String, fields:Map<String,Any?>)=
        type=="TURN_CANCELLED_OBSOLETE" || type=="OPTIONAL_GROUNDING_CANCELLED" ||
            (type=="ANALYSIS_DISPATCH_CANCELLED" && fields["expected"]==true)
}
