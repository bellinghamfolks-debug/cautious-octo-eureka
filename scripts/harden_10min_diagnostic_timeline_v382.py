#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
HUB = ROOT / "app/src/main/java/com/abdullah/visionbridge/data/diagnostics/DiagnosticHub.kt"
STORE = ROOT / "app/src/main/java/com/abdullah/visionbridge/data/diagnostics/EvidenceStore.kt"

MARKER = "TEN_MINUTE_DIAGNOSTIC_TIMELINE_HARDENING_V382"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    if old not in text:
        raise SystemExit(f"3.8.2 timeline hardening failed: {label} anchor not found")
    return text.replace(old, new, 1)


def patch_hub() -> None:
    text = HUB.read_text()
    if MARKER in text:
        return

    text = replace_once(
        text,
        "// TEN_MINUTE_DIAGNOSTIC_TIMELINE_V382\nobject DiagnosticHub {\n",
        "// TEN_MINUTE_DIAGNOSTIC_TIMELINE_V382\n"
        f"// {MARKER}\n"
        "object DiagnosticHub {\n",
        "hub marker",
    )

    text = replace_once(
        text,
        "    private val lastTimelineEvidenceAtElapsedMs = AtomicLong(0L)\n",
        "    // Absolute one-second slots avoid cumulative drift from JPEG encoding time.\n"
        "    private val lastTimelineEvidenceSecond = AtomicLong(-1L)\n",
        "timeline slot clock",
    )

    text = text.replace(
        "lastTimelineEvidenceAtElapsedMs.set(0L)",
        "lastTimelineEvidenceSecond.set(-1L)",
    )

    old_gate = '''        val elapsed = (now - windowStart).coerceAtLeast(0L)
        if (elapsed > TIMELINE_EVIDENCE_WINDOW_MS) {
            if (timelineCompletionRecorded.compareAndSet(0L, 1L)) {
                record(
                    "EVIDENCE_TIMELINE_COMPLETED",
                    mapOf(
                        "elapsedMs" to elapsed,
                        "framesHeld" to store.frameCount(),
                        "targetFrames" to 600,
                    ),
                )
            }
            return
        }

        while (true) {
            val previous = lastTimelineEvidenceAtElapsedMs.get()
            if (previous > 0L && now - previous < TIMELINE_EVIDENCE_INTERVAL_MS) return
            if (lastTimelineEvidenceAtElapsedMs.compareAndSet(previous, now)) break
        }

        evidence(
            bitmap = bitmap,
            frameId = frameId,
            reason = "timeline_1s",
            fields = fields + mapOf(
                "evidenceRole" to "timeline",
                "timelineElapsedMs" to elapsed,
                "timelineSecond" to (elapsed / 1_000L),
                "timelineIntervalMs" to TIMELINE_EVIDENCE_INTERVAL_MS,
                "timelineWindowMs" to TIMELINE_EVIDENCE_WINDOW_MS,
            ),
        )
'''
    new_gate = '''        val elapsed = (now - windowStart).coerceAtLeast(0L)
        if (elapsed >= TIMELINE_EVIDENCE_WINDOW_MS) {
            if (timelineCompletionRecorded.compareAndSet(0L, 1L)) {
                record(
                    "EVIDENCE_TIMELINE_COMPLETED",
                    mapOf(
                        "elapsedMs" to elapsed,
                        "framesHeld" to store.frameCount(),
                        "targetFrames" to 600,
                        "coverageWindowSeconds" to 600,
                    ),
                )
            }
            return
        }

        // Save at most once in each absolute second of the ten-minute window. Compression time can
        // delay a frame inside its slot, but it cannot push every later frame progressively later.
        val timelineSecond = elapsed / TIMELINE_EVIDENCE_INTERVAL_MS
        while (true) {
            val previousSecond = lastTimelineEvidenceSecond.get()
            if (previousSecond >= timelineSecond) return
            if (lastTimelineEvidenceSecond.compareAndSet(previousSecond, timelineSecond)) {
                val missedSeconds = (timelineSecond - previousSecond - 1L).coerceAtLeast(0L)
                if (missedSeconds > 0L && previousSecond >= 0L) {
                    record(
                        "EVIDENCE_TIMELINE_SLOT_GAP",
                        mapOf(
                            "previousSecond" to previousSecond,
                            "currentSecond" to timelineSecond,
                            "missedSeconds" to missedSeconds,
                        ),
                    )
                }
                break
            }
        }

        evidence(
            bitmap = bitmap,
            frameId = frameId,
            reason = "timeline_1s",
            fields = fields + mapOf(
                "evidenceRole" to "timeline",
                "timelineElapsedMs" to elapsed,
                "timelineSecond" to timelineSecond,
                "timelineIntervalMs" to TIMELINE_EVIDENCE_INTERVAL_MS,
                "timelineWindowMs" to TIMELINE_EVIDENCE_WINDOW_MS,
            ),
        )
'''
    text = replace_once(text, old_gate, new_gate, "absolute timeline slots")
    HUB.write_text(text)


def patch_store() -> None:
    text = STORE.read_text()
    if MARKER in text:
        return

    text = replace_once(
        text,
        "// TEN_MINUTE_DIAGNOSTIC_TIMELINE_V382\nclass EvidenceStore(val directory: File) {\n",
        "// TEN_MINUTE_DIAGNOSTIC_TIMELINE_V382\n"
        f"// {MARKER}\n"
        "class EvidenceStore(val directory: File) {\n",
        "store marker",
    )

    text = replace_once(
        text,
        '''    private val supplementalWritten = AtomicInteger(0)
    private val skipped = AtomicInteger(0)
    private val bytes = AtomicLong(0L)
''',
        '''    private val supplementalWritten = AtomicInteger(0)
    private val supplementalBytes = AtomicLong(0L)
    private val skipped = AtomicInteger(0)
    private val bytes = AtomicLong(0L)
''',
        "supplemental byte counter",
    )

    text = replace_once(
        text,
        '''        if (!timelineFrame && supplementalWritten.get() >= MAX_SUPPLEMENTAL_FRAMES) {
            skipped.incrementAndGet()
            return null
        }
        if (written.get() >= MAX_FRAMES || bytes.get() >= MAX_TOTAL_BYTES) {
''',
        '''        if (
            !timelineFrame &&
            (supplementalWritten.get() >= MAX_SUPPLEMENTAL_FRAMES ||
                supplementalBytes.get() >= MAX_SUPPLEMENTAL_BYTES)
        ) {
            skipped.incrementAndGet()
            return null
        }
        if (written.get() >= MAX_FRAMES || bytes.get() >= MAX_TOTAL_BYTES) {
''',
        "supplemental frame and byte gate",
    )

    text = replace_once(
        text,
        '''            written.incrementAndGet()
            if (!timelineFrame) supplementalWritten.incrementAndGet()
            bytes.addAndGet(file.length())
            name
''',
        '''            written.incrementAndGet()
            if (!timelineFrame) {
                supplementalWritten.incrementAndGet()
                supplementalBytes.addAndGet(file.length())
            }
            bytes.addAndGet(file.length())
            name
''',
        "supplemental byte accounting",
    )

    text = replace_once(
        text,
        '''        "evidenceSupplementalFrameCount" to supplementalWritten.get(),
        "evidenceSupplementalFrameLimit" to MAX_SUPPLEMENTAL_FRAMES,
        "evidenceLongEdgeLimitPx" to MAX_EDGE,
''',
        '''        "evidenceSupplementalFrameCount" to supplementalWritten.get(),
        "evidenceSupplementalFrameLimit" to MAX_SUPPLEMENTAL_FRAMES,
        "evidenceSupplementalBytes" to supplementalBytes.get(),
        "evidenceSupplementalByteLimit" to MAX_SUPPLEMENTAL_BYTES,
        "evidenceLongEdgeLimitPx" to MAX_EDGE,
''',
        "supplemental byte manifest",
    )

    text = replace_once(
        text,
        '''        written.set(0)
        supplementalWritten.set(0)
        skipped.set(0)
''',
        '''        written.set(0)
        supplementalWritten.set(0)
        supplementalBytes.set(0L)
        skipped.set(0)
''',
        "supplemental byte reset",
    )

    text = replace_once(
        text,
        '''        const val MAX_FRAMES = 820
        const val MAX_SUPPLEMENTAL_FRAMES = 200
        const val MAX_TOTAL_BYTES = 512L * 1024 * 1024
''',
        '''        const val MAX_FRAMES = 820
        const val MAX_SUPPLEMENTAL_FRAMES = 200
        // Supplemental evidence gets its own byte ceiling so even unusually noisy JPEGs cannot
        // consume the space intended for the 600 timeline frames.
        const val MAX_SUPPLEMENTAL_BYTES = 96L * 1024 * 1024
        const val MAX_TOTAL_BYTES = 512L * 1024 * 1024
''',
        "supplemental byte limit",
    )

    STORE.write_text(text)


patch_hub()
patch_store()
print("Hardened VisionBridge 3.8.2 ten-minute diagnostic timeline against drift and supplemental budget exhaustion")
