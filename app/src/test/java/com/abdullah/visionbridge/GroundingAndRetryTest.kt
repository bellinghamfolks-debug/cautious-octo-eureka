package com.abdullah.visionbridge

import com.abdullah.visionbridge.capture.QualityRetryPolicy
import com.abdullah.visionbridge.data.vision.TextGroundingGate
import org.junit.Assert.*
import org.junit.Test

class GroundingAndRetryTest {
    private val optical = TextGroundingGate.Evidence("كتاب TEST ١٢٣", .95f, 3)
    @Test fun opticalEvidenceRequiredEvenForCertainModel() {
        assertFalse(TextGroundingGate.evaluate("UNRELATED PRODUCT",100,true,false,optical).accepted)
        assertTrue(TextGroundingGate.evaluate("كتاب TEST 123",95,true,false,optical).accepted)
        assertFalse(TextGroundingGate.evaluate("كتاب TEST 123",100,true,true,optical).accepted)
    }
    @Test fun noTextWithDescriptionIsStillNoTextAndRetryable() {
        val d=TextGroundingGate.evaluate("NO_TEXT\nSCENE|A box",100,true,false,optical)
        assertFalse(d.accepted);assertTrue(d.retry)
        assertEquals("no_text_conflicts_with_optical_evidence",d.reason)
    }
    @Test fun repeatedDigitsCannotBeInvented() {
        assertFalse(TextGroundingGate.evaluate("123123",100,true,false,optical).accepted)
        assertFalse(TextGroundingGate.evaluate("123 123",100,true,false,optical).accepted)
    }
    @Test fun stableRetriesAfterNegativeAndAcceptsBetterCandidate() {
        val policy=QualityRetryPolicy()
        val q=QualityRetryPolicy.Quality(20.0,25.0,.1,.8,200)
        assertTrue(policy.consider(q,0,true).submit);policy.submitted(q,0);policy.result(false)
        assertTrue(policy.consider(q.copy(sharpness=200.0),300,true).submit)
        assertTrue(policy.consider(q,1000,true).submit)
        policy.result(true)
        assertFalse(policy.consider(q,5000,true).submit)
        policy.reset();assertTrue(policy.consider(q,5001,true).submit)
    }
    @Test fun fastDoesNotWaitStableBudgetAndBlurDoesNotLatch() {
        val policy=QualityRetryPolicy();val q=QualityRetryPolicy.Quality(20.0,25.0,.1,.8,0)
        assertFalse(policy.consider(q,0,true).submit)
        assertTrue(policy.consider(q,0,false).submit)
        assertFalse(policy.consider(q.copy(sharpness=1.0),2000,false).submit)
        assertTrue(policy.consider(q.copy(stableForMs=200),2001,true).submit)
    }
}
