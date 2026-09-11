package com.abdullah.visionbridge.capture

/** Only one pending bitmap. Retain a better stable image briefly, never across target changes. */
object PendingCandidatePolicy {
    data class Candidate(val generation:Long,val capturedAtNanos:Long,val quality:QualityRetryPolicy.Quality)
    data class Decision(val replace:Boolean,val reason:String)
    fun choose(held:Candidate,new:Candidate):Decision {
        if(new.generation!=held.generation) return Decision(new.generation>held.generation,
            if(new.generation>held.generation) "pending_target_obsolete" else "older_generation_candidate")
        if(new.capturedAtNanos<=held.capturedAtNanos) return Decision(false,"out_of_order_candidate")
        val ageMs=(new.capturedAtNanos-held.capturedAtNanos)/1_000_000
        if(ageMs<750 && held.quality.stableForMs>=180 && held.quality.score>new.quality.score+.25)
            return Decision(false,"better_stable_candidate_retained")
        return Decision(true,if(new.quality.score>held.quality.score+.25) "better_quality_candidate" else "newer_candidate")
    }
}
