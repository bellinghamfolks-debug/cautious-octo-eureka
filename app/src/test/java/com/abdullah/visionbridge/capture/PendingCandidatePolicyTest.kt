package com.abdullah.visionbridge.capture

import org.junit.Assert.*
import org.junit.Test

class PendingCandidatePolicyTest {
    private fun candidate(generation:Long=0,ms:Long=0,sharpness:Double=400.0)=PendingCandidatePolicy.Candidate(
        generation,ms*1_000_000,QualityRetryPolicy.Quality(sharpness,50.0,.2,1.0,300))
    @Test fun keepsTheSharperStableImageWithinTheBoundedWindow() {
        assertFalse(PendingCandidatePolicy.choose(candidate(),candidate(ms=200,sharpness=20.0)).replace)
        assertTrue(PendingCandidatePolicy.choose(candidate(),candidate(ms=800,sharpness=20.0)).replace)
    }
    @Test fun freshTargetAlwaysSupersedesEvenASharperOlderTarget() {
        assertTrue(PendingCandidatePolicy.choose(candidate(),candidate(1,20,20.0)).replace)
        assertFalse(PendingCandidatePolicy.choose(candidate(1),candidate(0,200)).replace)
    }
    @Test fun newerSharperCandidateWinsButAnOutOfOrderFrameCannotReplaceIt() {
        assertTrue(PendingCandidatePolicy.choose(candidate(sharpness=20.0),candidate(ms=200)).replace)
        assertFalse(PendingCandidatePolicy.choose(candidate(ms=200),candidate()).replace)
    }
}
