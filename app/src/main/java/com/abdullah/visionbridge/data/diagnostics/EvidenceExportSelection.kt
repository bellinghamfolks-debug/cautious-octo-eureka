package com.abdullah.visionbridge.data.diagnostics

import java.io.FilterOutputStream
import java.io.OutputStream

/**
 * Which stored evidence goes into a bundle.
 *
 * The capture side already keeps a window inside [EvidenceVideoPlan.EVIDENCE_BYTE_CAP], but
 * evidence can outlive the build that made it — frames from an older version, or from capture
 * switched on again later. The bundle's limit is a promise to the person sending it, so it is
 * enforced again here: the newest media first, up to the cap, and everything left out is named in
 * the bundle rather than silently dropped. The small text indexes always go in.
 */
object EvidenceExportSelection {
    data class Item(val path: String, val bytes: Long, val modifiedAtMs: Long)
    data class Result(val included: List<Item>, val omitted: List<Item>)

    fun isMedia(path: String): Boolean =
        path.endsWith(".mp4", ignoreCase = true) || path.endsWith(".jpg", ignoreCase = true)

    fun select(items: List<Item>, byteCap: Long): Result {
        val (media, text) = items.partition { isMedia(it.path) }
        var budget = byteCap - text.sumOf { it.bytes }
        val included = mutableListOf<Item>()
        val omitted = mutableListOf<Item>()
        media.sortedWith(compareByDescending<Item> { it.modifiedAtMs }.thenByDescending { it.path }).forEach { item ->
            if (item.bytes <= budget) {
                included += item
                budget -= item.bytes
            } else {
                omitted += item
            }
        }
        return Result(text + included.sortedBy { it.path }, omitted.sortedBy { it.path })
    }
}

/** Counts what reaches the file, so the export knows how large the archive already is. */
class CountingOutputStream(output: OutputStream) : FilterOutputStream(output) {
    var count = 0L
        private set

    override fun write(b: Int) {
        out.write(b)
        count++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        out.write(b, off, len)
        count += len
    }
}
