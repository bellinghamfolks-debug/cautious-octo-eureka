package com.abdullah.visionbridge.data.diagnostics

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * The arithmetic behind compressed video evidence, kept free of Android so it can be tested on a
 * workstation.
 *
 * Why video at all: ten minutes of evidence used to be thousands of separate JPEGs, 1600 px and
 * quality 82, about 120 KB each. Measured on a real session from the Xiaomi 14T (411 analysis
 * inputs in two and a half minutes), that is roughly 290 MB for ten minutes. Consecutive frames of
 * a screen are mostly the same picture, and a JPEG pays for every one of them in full; a video
 * codec pays once and then only for what changed. On the same frames, at the same measured
 * quality, H.265 was 14–19% of the JPEG size.
 *
 * The rest of the saving is the agreed trade: the frames only have to show what was on screen,
 * not show it sharply. 960 px on the long edge at about 300 kbit/s kept the page layout, the
 * on-screen controls and large print readable in the measured session, and puts ten minutes near
 * 22 MB.
 */
object EvidenceVideoPlan {
    /** Long edge of the encoded picture. Enough to see what was on screen, not to read fine print. */
    const val LONG_EDGE_PX = 960

    /** Hardware encoders want dimensions on a 16-pixel grid. */
    const val ALIGNMENT_PX = 16

    /** Every byte of visual evidence together, all streams, for one capture window. */
    const val EVIDENCE_BYTE_CAP = 24_000_000L

    /** The whole shareable bundle: evidence, events, summaries. */
    const val EXPORT_BYTE_LIMIT = 30_000_000L

    /** What the encoders are paced to cover. */
    const val WINDOW_MS = 10L * 60L * 1_000L

    /**
     * A segment is only playable once it is finished, so a process killed mid-segment loses the
     * unfinished part. Thirty seconds bounds that loss without filling the bundle with files.
     */
    const val SEGMENT_MS = 30_000L

    /** A keyframe at least this often, so any moment can be decoded without the whole segment. */
    const val KEYFRAME_INTERVAL_S = 10

    /** A frame whose shape differs from the segment's by more than this starts a new segment. */
    const val ASPECT_TOLERANCE = 0.12

    /** Room kept back for the frames already inside the encoder when the cap is reached. */
    const val RESERVE_BYTES = 600_000L

    /** When the window is nearly over, pace the rest of the budget over at least this long. */
    const val MIN_HORIZON_MS = 60_000L

    const val MIN_TOTAL_BITRATE = 64_000
    const val MAX_TOTAL_BITRATE = 640_000

    /** The timeline is one frame a second; the inputs are several. It gets the smaller share. */
    const val TIMELINE_SHARE = 0.2

    enum class Stream(val fileStem: String, val nominalFrameRate: Int) {
        /** One frame per second of the ten-minute window, before any throttling. */
        TIMELINE("timeline", 1),

        /** Every frame that went into reading or description, plus named failure frames. */
        INPUTS("inputs", 4),
        ;

        companion object {
            fun forReason(reason: String): Stream = if (reason == "timeline_1s") TIMELINE else INPUTS
        }
    }

    data class Canvas(val width: Int, val height: Int)

    /** Where a source picture lands inside a canvas: scaled to fit, centred, never stretched. */
    data class Placement(val left: Int, val top: Int, val width: Int, val height: Int)

    /** The encoded size for a source of this shape: the long edge at [longEdge], on the grid. */
    fun canvasFor(sourceWidth: Int, sourceHeight: Int, longEdge: Int = LONG_EDGE_PX, alignment: Int = ALIGNMENT_PX): Canvas {
        require(sourceWidth > 0 && sourceHeight > 0) { "empty source" }
        val long = maxOf(sourceWidth, sourceHeight)
        val scale = minOf(1.0, longEdge.toDouble() / long)
        fun snap(value: Double): Int =
            maxOf(alignment * 4, (value / alignment).roundToInt() * alignment)
        return Canvas(snap(sourceWidth * scale), snap(sourceHeight * scale))
    }

    /** Whether a source can go into [canvas] without a new segment. */
    fun fits(canvas: Canvas, sourceWidth: Int, sourceHeight: Int): Boolean {
        val canvasAspect = canvas.width.toDouble() / canvas.height
        val sourceAspect = sourceWidth.toDouble() / sourceHeight
        return abs(ln(sourceAspect / canvasAspect)) <= ln(1.0 + ASPECT_TOLERANCE)
    }

    fun placement(canvas: Canvas, sourceWidth: Int, sourceHeight: Int): Placement {
        val scale = minOf(canvas.width.toDouble() / sourceWidth, canvas.height.toDouble() / sourceHeight)
        val width = (sourceWidth * scale).roundToInt().coerceIn(1, canvas.width)
        val height = (sourceHeight * scale).roundToInt().coerceIn(1, canvas.height)
        return Placement((canvas.width - width) / 2, (canvas.height - height) / 2, width, height)
    }

    /**
     * The bitrate, all streams together, that spends what is left of the budget evenly over what
     * is left of the window.
     *
     * This is the feedback that keeps a real encoder inside the cap. Hardware encoders miss the
     * rate they are asked for, some by a lot; an encoder that spends twice what it was given
     * leaves less budget, so the next request is lower. Even a steady two-fold overshoot then
     * only shapes the spending — remaining bytes fall as (1 − t/T)² — and still lasts the whole
     * window instead of running out early.
     */
    fun totalBitrate(bytesUsed: Long, elapsedMs: Long, byteCap: Long = EVIDENCE_BYTE_CAP): Int {
        val remainingBytes = (byteCap - RESERVE_BYTES - bytesUsed).coerceAtLeast(0L)
        val remainingMs = (WINDOW_MS - elapsedMs).coerceAtLeast(MIN_HORIZON_MS)
        val bitsPerSecond = remainingBytes * 8L * 1_000L / remainingMs
        return bitsPerSecond.coerceIn(MIN_TOTAL_BITRATE.toLong(), MAX_TOTAL_BITRATE.toLong()).toInt()
    }

    /**
     * Learns how far the encoder misses the rate it is given, and asks for less (or more) to
     * land on the target. [totalBitrate] alone keeps an overspending encoder inside the cap, but
     * against a persistent overshoot it does so by starving the end of the window; correcting the
     * request keeps the window whole. Observations are only taken while frames are actually
     * arriving — an idle interval says nothing about the encoder.
     */
    class RateCorrection {
        var ratio = 1.0
            private set

        fun observe(requestedBits: Double, actualBits: Double) {
            if (requestedBits <= 0.0) return
            val observed = (actualBits / requestedBits).coerceIn(MIN_RATIO, MAX_RATIO)
            ratio = ratio * (1.0 - LEARNING_RATE) + observed * LEARNING_RATE
        }

        fun request(target: Int): Int =
            (target / ratio).roundToInt().coerceIn(MIN_TOTAL_BITRATE / 4, MAX_TOTAL_BITRATE)

        private companion object {
            const val MIN_RATIO = 0.5
            const val MAX_RATIO = 4.0
            const val LEARNING_RATE = 0.5
        }
    }

    fun streamBitrate(stream: Stream, total: Int): Int = when (stream) {
        Stream.TIMELINE -> (total * TIMELINE_SHARE).roundToInt()
        Stream.INPUTS -> (total * (1.0 - TIMELINE_SHARE)).roundToInt()
    }

    /** Whether another frame may be encoded at all. Past this, capture stops and says so. */
    fun hasRoom(bytesUsed: Long, byteCap: Long = EVIDENCE_BYTE_CAP): Boolean = bytesUsed < byteCap - RESERVE_BYTES

    /**
     * How an event points at one frame inside a video: the segment's path plus a W3C media
     * fragment, `inputs-0003.mp4#t=12.345`. Players that understand fragments open at that
     * moment; for everything else the per-session `frames.jsonl` maps the same reference to the
     * frame's index, reason and source size.
     */
    fun reference(segmentPath: String, presentationUs: Long): String =
        "$segmentPath#t=${presentationUs / 1_000_000}.${(presentationUs / 1_000 % 1_000).toString().padStart(3, '0')}"

    /** The file a reference lives in, with or without a fragment. */
    fun referencedFile(reference: String): String = reference.substringBefore('#')
}
