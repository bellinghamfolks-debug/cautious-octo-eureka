package com.abdullah.visionbridge.data.diagnostics

import android.os.SystemClock

/** Explicit intervals, allowing overlap (e.g. optical verification and cloud generation).
 * Sums of overlapping durations must not be presented as end-to-end latency. */
object FrameStages {
    fun record(trace:DiagnosticTrace,stage:String,startedAt:Long,
               endedAt:Long=SystemClock.elapsedRealtimeNanos(),extra:Map<String,Any?> = emptyMap()) {
        DiagnosticHub.record("FRAME_STAGE",trace.fields(mapOf("stage" to stage,
            "stageStartedAtElapsedNanos" to startedAt,"stageEndedAtElapsedNanos" to endedAt,
            "durationMs" to (endedAt-startedAt)/1e6)+extra))
    }
}
