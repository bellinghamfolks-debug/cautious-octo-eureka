package com.abdullah.visionbridge.data.diagnostics

import org.junit.Assert.*
import org.junit.Test

class DiagnosticEventPolicyTest {
    @Test fun configuredTimelineWindowIsNotAStageDuration() {
        assertTrue(DiagnosticEventPolicy.latencyFields("EVIDENCE_TIMELINE_STARTED").isEmpty())
        assertEquals(listOf("durationMs"),DiagnosticEventPolicy.latencyFields("PPOCR_DETECTION_COMPLETED"))
    }
    @Test fun replacementAndOwnedCancellationAreNormalControlFlow() {
        assertTrue(DiagnosticEventPolicy.expectedReplacement("newer_candidate"))
        assertTrue(DiagnosticEventPolicy.expectedReplacement("better_quality_candidate"))
        assertTrue(DiagnosticEventPolicy.expectedCancellation("ANALYSIS_DISPATCH_CANCELLED",mapOf("expected" to true)))
        assertFalse(DiagnosticEventPolicy.expectedCancellation("ANALYSIS_DISPATCH_CANCELLED",mapOf("expected" to false)))
    }
    @Test fun archiveAuditCatchesMissingAndOverwrittenEvidence() {
        assertFalse(EvidenceArchiveAudit.check(listOf("F1.jpg"),listOf("F1.jpg","F1.jpg")).valid)
        assertFalse(EvidenceArchiveAudit.check(emptyList(),listOf("s/F1.jpg")).valid)
        assertTrue(EvidenceArchiveAudit.check(listOf("s1/F1.jpg","s2/F1.jpg"),listOf("s1/F1.jpg","s2/F1.jpg")).valid)
    }
}
