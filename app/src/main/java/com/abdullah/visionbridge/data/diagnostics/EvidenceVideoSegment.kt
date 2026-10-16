package com.abdullah.visionbridge.data.diagnostics

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Bundle
import java.io.File

/**
 * One finished-or-growing MP4 of evidence frames, written by the device's own video encoder.
 *
 * The file is written as `name.mp4.part` and renamed only after the muxer has written its index,
 * so a `.mp4` on disk is always playable and a `.part` is always the remains of a process that
 * died mid-segment.
 *
 * Not thread-safe; [EvidenceStore] calls it under its own lock, from the evidence worker.
 */
internal class EvidenceVideoSegment private constructor(
    val file: File,
    private val partial: File,
    val canvas: EvidenceVideoPlan.Canvas,
    val codecName: String,
    val mime: String,
    private val codec: MediaCodec,
    private val muxer: MediaMuxer,
) {
    private val frame = Bitmap.createBitmap(canvas.width, canvas.height, Bitmap.Config.ARGB_8888)
    private val drawing = Canvas(frame)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pixels = IntArray(canvas.width * canvas.height)
    private val info = MediaCodec.BufferInfo()
    private var track = -1
    private var muxing = false
    private var lastPresentationUs = -1L

    var inputFrames = 0
        private set
    var outputFrames = 0
        private set
    var bytesWritten = 0L
        private set

    /**
     * Encodes [bitmap] at [presentationUs] and returns the time actually used, which is moved
     * forward if needed so every frame in a segment has its own millisecond. Null when the
     * encoder had no room for it; the caller records that frame as skipped.
     */
    fun encode(bitmap: Bitmap, presentationUs: Long): Long? {
        val time = maxOf(presentationUs, lastPresentationUs + 1_000L)
        var index = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
        if (index < 0) {
            drain(endOfStream = false)
            index = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
            if (index < 0) return null
        }
        // Read before the image is taken: asking for the buffer afterwards would invalidate it.
        val size = codec.getInputBuffer(index)?.capacity() ?: (canvas.width * canvas.height * 3 / 2)
        val image = codec.getInputImage(index)
        if (image == null) {
            codec.queueInputBuffer(index, 0, 0, time, 0)
            error("encoder $codecName gave no input image")
        }
        drawing.drawColor(Color.BLACK)
        val place = EvidenceVideoPlan.placement(canvas, bitmap.width, bitmap.height)
        drawing.drawBitmap(
            bitmap,
            null,
            Rect(place.left, place.top, place.left + place.width, place.top + place.height),
            paint,
        )
        frame.getPixels(pixels, 0, canvas.width, 0, 0, canvas.width, canvas.height)
        val planes = image.planes
        Yuv420Writer.write(
            pixels,
            canvas.width,
            canvas.height,
            Yuv420Writer.Plane(planes[0].buffer, planes[0].rowStride, planes[0].pixelStride),
            Yuv420Writer.Plane(planes[1].buffer, planes[1].rowStride, planes[1].pixelStride),
            Yuv420Writer.Plane(planes[2].buffer, planes[2].rowStride, planes[2].pixelStride),
        )
        codec.queueInputBuffer(index, 0, size, time, 0)
        lastPresentationUs = time
        inputFrames++
        drain(endOfStream = false)
        return time
    }

    fun setBitrate(bitsPerSecond: Int) {
        runCatching {
            codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitsPerSecond) })
        }
    }

    /** Flushes the encoder, closes the MP4 and returns it; null if nothing was ever written. */
    fun finish(): File? {
        try {
            val index = codec.dequeueInputBuffer(FINISH_TIMEOUT_US)
            if (index >= 0) {
                codec.queueInputBuffer(index, 0, 0, lastPresentationUs + 1_000L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                drain(endOfStream = true)
            } else {
                drain(endOfStream = false)
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            frame.recycle()
        }
        val written = muxing && outputFrames > 0
        try {
            if (written) muxer.stop()
        } finally {
            runCatching { muxer.release() }
        }
        if (!written) {
            partial.delete()
            return null
        }
        check(partial.renameTo(file)) { "could not finish ${file.name}" }
        return file
    }

    /** Drops the segment without finishing it. */
    fun abandon() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { if (muxing) muxer.stop() }
        runCatching { muxer.release() }
        frame.recycle()
        partial.delete()
    }

    private fun drain(endOfStream: Boolean) {
        val deadline = System.nanoTime() + DRAIN_DEADLINE_NS
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (endOfStream) FINISH_TIMEOUT_US else 0L)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream || System.nanoTime() > deadline) return
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(!muxing) { "encoder format changed twice" }
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxing = true
                }
                index >= 0 -> {
                    val buffer = codec.getOutputBuffer(index)
                    val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (buffer != null && !config && info.size > 0 && muxing) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        muxer.writeSampleData(track, buffer, info)
                        outputFrames++
                        bytesWritten += info.size
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    companion object {
        private const val INPUT_TIMEOUT_US = 50_000L
        private const val FINISH_TIMEOUT_US = 10_000L
        private const val DRAIN_DEADLINE_NS = 2_000_000_000L

        /** Encoders that failed to start on this device; tried again only after a restart. */
        private val refused = mutableSetOf<String>()

        /**
         * Opens a segment with the best encoder this device will actually start: hardware H.265,
         * then hardware H.264, then the software versions of each. Null when none of them will.
         */
        fun open(
            file: File,
            canvas: EvidenceVideoPlan.Canvas,
            stream: EvidenceVideoPlan.Stream,
            bitsPerSecond: Int,
        ): EvidenceVideoSegment? {
            for ((name, mime) in candidates()) {
                if (name in refused) continue
                val partial = File(file.parentFile, file.name + ".part")
                val segment = runCatching { start(file, partial, canvas, stream, bitsPerSecond, name, mime) }
                    .onFailure { partial.delete() }
                    .getOrNull()
                if (segment != null) return segment
                refused += name
            }
            return null
        }

        private fun start(
            file: File,
            partial: File,
            canvas: EvidenceVideoPlan.Canvas,
            stream: EvidenceVideoPlan.Stream,
            bitsPerSecond: Int,
            name: String,
            mime: String,
        ): EvidenceVideoSegment {
            val codec = MediaCodec.createByCodecName(name)
            try {
                val capabilities = codec.codecInfo.getCapabilitiesForType(mime)
                val video = checkNotNull(capabilities.videoCapabilities) { "$name has no video capabilities" }
                check(video.isSizeSupported(canvas.width, canvas.height)) {
                    "$name cannot encode ${canvas.width}x${canvas.height}"
                }
                val format = MediaFormat.createVideoFormat(mime, canvas.width, canvas.height).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                    setInteger(MediaFormat.KEY_BIT_RATE, video.bitrateRange.clamp(bitsPerSecond))
                    setInteger(MediaFormat.KEY_FRAME_RATE, stream.nominalFrameRate)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, EvidenceVideoPlan.KEYFRAME_INTERVAL_S)
                    // Evidence is not live video; let the encoder take its time and spare the battery.
                    setInteger(MediaFormat.KEY_PRIORITY, 1)
                    val encoder = capabilities.encoderCapabilities
                    if (encoder.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)) {
                        setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                    }
                }
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.start()
            } catch (error: Throwable) {
                codec.release()
                throw error
            }
            val muxer = try {
                partial.parentFile?.mkdirs()
                MediaMuxer(partial.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            } catch (error: Throwable) {
                runCatching { codec.stop() }
                codec.release()
                throw error
            }
            return EvidenceVideoSegment(file, partial, canvas, name, mime, codec, muxer)
        }

        private fun candidates(): List<Pair<String, String>> {
            val encoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { it.isEncoder }
            fun matching(mime: String, hardware: Boolean) = encoders
                .filter { info -> info.supportedTypes.any { it.equals(mime, ignoreCase = true) } }
                .filter { isHardware(it) == hardware }
                .map { it.name to mime }
            val hevc = MediaFormat.MIMETYPE_VIDEO_HEVC
            val avc = MediaFormat.MIMETYPE_VIDEO_AVC
            return matching(hevc, true) + matching(avc, true) + matching(hevc, false) + matching(avc, false)
        }

        private fun isHardware(info: MediaCodecInfo): Boolean {
            if (android.os.Build.VERSION.SDK_INT >= 29) return info.isHardwareAccelerated
            val name = info.name.lowercase()
            return !(name.startsWith("omx.google.") || name.startsWith("c2.android.") || name.contains("sw"))
        }
    }
}
