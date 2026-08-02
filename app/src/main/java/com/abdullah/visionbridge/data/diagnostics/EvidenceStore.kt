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
class EvidenceStore(val directory: File) {

    private val written = AtomicInteger(0)
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
        if (written.get() >= MAX_FRAMES || bytes.get() >= MAX_TOTAL_BYTES) {
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
    )

    fun clear() {
        runCatching { directory.listFiles()?.forEach { it.delete() } }
        written.set(0)
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
        const val MAX_FRAMES = 40
        const val MAX_TOTAL_BYTES = 24L * 1024 * 1024
        const val MAX_EDGE = 1600
        const val JPEG_QUALITY = 78
        val REASON_UNSAFE = Regex("[^A-Za-z0-9_-]")
    }
}
