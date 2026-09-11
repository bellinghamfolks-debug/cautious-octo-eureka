package com.abdullah.visionbridge.data.diagnostics

import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Keeps the actual frame behind a failure, when the user has asked for it.
 *
 * The bundle otherwise carries no pixels at all, and that limit has a cost: given a page that was
 * not read, there is no way to tell "the text was never detected" from "it was detected and thrown
 * away", and those two need opposite repairs. Guessing between them is what turns one diagnosis
 * into many attempts.
 *
 * The rules this operates under are the ones that make it safe to offer:
 *
 * - **Off unless switched on.** No frame is ever written by default.
 * - **Failures only.** Not a recording — a handful of moments where something demonstrably went
 *   wrong, each one named.
 * - **Bounded, hard.** A ceiling on the count and on total bytes, enforced here rather than
 *   promised in a comment. Past it, capture stops and the bundle says how many were skipped.
 * - **Declared.** The count is in the manifest and in the archive's own readme, so a bundle that
 *   contains screen images can never look like one that does not.
 */
// DENSE_DIAGNOSTIC_EVIDENCE_V381
// TEN_MINUTE_DIAGNOSTIC_TIMELINE_V382
// TEN_MINUTE_DIAGNOSTIC_TIMELINE_HARDENING_V382
// EVERY_ANALYSIS_INPUT_EVIDENCE_V383
class EvidenceStore(val directory: File) {

    private val written = AtomicInteger(0)
    private val supplementalWritten = AtomicInteger(0)
    private val supplementalBytes = AtomicLong(0L)
    private val analysisInputWritten = AtomicInteger(0)
    private val analysisInputBytes = AtomicLong(0L)
    private val analysisInputSkipped = AtomicInteger(0)
    private val skipped = AtomicInteger(0)
    private val bytes = AtomicLong(0L)

    @Volatile
    var enabled: Boolean = false

    /**
     * Writes [bitmap] as evidence for [reason], if capture is on and the budget allows.
     *
     * Returns the file name recorded, or null when nothing was written — which is the normal case
     * and never an error.
     */
    fun capture(bitmap: Bitmap, frameId: String, reason: String): String? {
        if (!enabled) return null
        val timelineFrame = reason == "timeline_1s"
        val analysisInputFrame = reason.startsWith("analysis_input_")
        val supplementalFrame = !timelineFrame && !analysisInputFrame
        if (analysisInputFrame && (analysisInputWritten.get() >= MAX_ANALYSIS_INPUT_FRAMES || analysisInputBytes.get() >= MAX_ANALYSIS_INPUT_BYTES)) {
            analysisInputSkipped.incrementAndGet()
            skipped.incrementAndGet()
            return null
        }
        if (supplementalFrame && (supplementalWritten.get() >= MAX_SUPPLEMENTAL_FRAMES || supplementalBytes.get() >= MAX_SUPPLEMENTAL_BYTES)) {
            skipped.incrementAndGet()
            return null
        }
        if (written.get() >= MAX_FRAMES || bytes.get() >= MAX_TOTAL_BYTES) {
            if (analysisInputFrame) analysisInputSkipped.incrementAndGet()
            skipped.incrementAndGet()
            return null
        }
        return runCatching {
            directory.mkdirs()
            // The reason is part of the name so the file answers "why is this here" on its own.
            val safeReason = reason.replace(REASON_UNSAFE, "_").take(40)
            val name = "$frameId-$safeReason.jpg"
            val file = File(directory, name)
            FileOutputStream(file).use { output ->
                val scaled = scaleForEvidence(bitmap)
                try {
                    check(scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                        "could not encode evidence frame"
                    }
                } finally {
                    if (scaled !== bitmap) scaled.recycle()
                }
            }
            written.incrementAndGet()
            when {
                analysisInputFrame -> { analysisInputWritten.incrementAndGet(); analysisInputBytes.addAndGet(file.length()) }
                supplementalFrame -> { supplementalWritten.incrementAndGet(); supplementalBytes.addAndGet(file.length()) }
            }
            bytes.addAndGet(file.length())
            name
        }.getOrElse {
            skipped.incrementAndGet()
            null
        }
    }

    fun frameCount(): Int = written.get()

    /** What the manifest must say, so a bundle with images cannot be mistaken for one without. */
    fun manifest(): Map<String, Any?> = mapOf(
        "evidenceCaptureEnabled" to enabled,
        "evidenceFrameCount" to written.get(),
        "evidenceFramesSkipped" to skipped.get(),
        "evidenceBytes" to bytes.get(),
        "evidenceFrameLimit" to MAX_FRAMES,
        "evidenceByteLimit" to MAX_TOTAL_BYTES,
        "evidenceCaptureMode" to "ten_minute_timeline_plus_supplemental_failures",
        "evidenceTimelineIntervalMs" to 1_000L,
        "evidenceTimelineWindowMs" to 600_000L,
        "evidenceTimelineTargetFrames" to 600,
        "evidenceAnalysisInputCapturePolicy" to "every_selected_input",
        "evidenceAnalysisInputFrameCount" to analysisInputWritten.get(),
        "evidenceAnalysisInputFrameLimit" to MAX_ANALYSIS_INPUT_FRAMES,
        "evidenceAnalysisInputFramesSkipped" to analysisInputSkipped.get(),
        "evidenceAnalysisInputBytes" to analysisInputBytes.get(),
        "evidenceAnalysisInputByteLimit" to MAX_ANALYSIS_INPUT_BYTES,
        "evidenceSupplementalFrameCount" to supplementalWritten.get(),
        "evidenceSupplementalFrameLimit" to MAX_SUPPLEMENTAL_FRAMES,
        "evidenceSupplementalBytes" to supplementalBytes.get(),
        "evidenceSupplementalByteLimit" to MAX_SUPPLEMENTAL_BYTES,
        "evidenceLongEdgeLimitPx" to MAX_EDGE,
        "evidenceJpegQuality" to JPEG_QUALITY,
    )

    fun clear() {
        runCatching { directory.listFiles()?.forEach { it.delete() } }
        written.set(0)
        supplementalWritten.set(0)
        supplementalBytes.set(0L)
        analysisInputWritten.set(0)
        analysisInputBytes.set(0L)
        analysisInputSkipped.set(0)
        skipped.set(0)
        bytes.set(0L)
    }

    /**
     * Full frames, but not full resolution: 1600 px on the long edge keeps small print legible
     * while a 2712-wide capture would treble the size for detail nothing needs.
     */
    private fun scaleForEvidence(bitmap: Bitmap): Bitmap {
        val longEdge = maxOf(bitmap.width, bitmap.height)
        if (longEdge <= MAX_EDGE) return bitmap
        val factor = MAX_EDGE.toDouble() / longEdge
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * factor).toInt().coerceAtLeast(1),
            (bitmap.height * factor).toInt().coerceAtLeast(1),
            true,
        )
    }

    private companion object {
        /** Enough moments to see a pattern, few enough that a bundle stays sendable. */
        // 600 timeline frames cover ten minutes at one frame per second. The separate
        // supplemental cap leaves room for failures and selected analysis inputs without allowing
        // them to exhaust the timeline allocation early.
        const val MAX_ANALYSIS_INPUT_FRAMES = 2_400
        const val MAX_ANALYSIS_INPUT_BYTES = 768L * 1024 * 1024
        const val MAX_SUPPLEMENTAL_FRAMES = 200
        const val MAX_SUPPLEMENTAL_BYTES = 96L * 1024 * 1024
        const val MAX_FRAMES = 3_200
        const val MAX_TOTAL_BYTES = 1_280L * 1024 * 1024
        const val MAX_EDGE = 1600
        const val JPEG_QUALITY = 82
        val REASON_UNSAFE = Regex("[^A-Za-z0-9_-]")
    }
}
