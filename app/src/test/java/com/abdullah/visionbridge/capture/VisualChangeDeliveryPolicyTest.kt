package com.abdullah.visionbridge.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualChangeDeliveryPolicyTest {
    @Test
    fun `current generation is always deliverable`() {
        assertTrue(VisualChangeDeliveryPolicy.mayDeliver(7L, 7L, true))
        assertTrue(VisualChangeDeliveryPolicy.mayDeliver(7L, 7L, false))
    }

    @Test
    fun `older generation completes when interruption is disabled`() {
        assertTrue(VisualChangeDeliveryPolicy.mayDeliver(7L, 8L, false))
        assertFalse(VisualChangeDeliveryPolicy.shouldCancelActiveRequest(false))
    }

    @Test
    fun `older generation is rejected when interruption is enabled`() {
        assertFalse(VisualChangeDeliveryPolicy.mayDeliver(7L, 8L, true))
        assertTrue(VisualChangeDeliveryPolicy.shouldCancelActiveRequest(true))
    }
}
