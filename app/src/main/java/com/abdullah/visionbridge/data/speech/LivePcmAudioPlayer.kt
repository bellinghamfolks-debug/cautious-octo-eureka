package com.abdullah.visionbridge.data.speech

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.abdullah.visionbridge.data.diagnostics.DiagnosticHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/**
 * Low-latency player for Gemini Live native PCM audio.
 *
 * Live scene description values freshness over completeness. Every accepted new visual turn advances
 * an epoch and flushes buffered audio, so a sentence about a previous view cannot sit behind the
 * current scene. Gemini Live audio is mono, signed 16-bit little-endian PCM at 24 kHz.
 */
class LivePcmAudioPlayer {
    private data class Packet(val epoch: Long, val bytes: ByteArray)

    private val epoch = AtomicLong(0L)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val packets = Channel<Packet>(
        capacity = PACKET_QUEUE_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val trackLock = Any()
    private val audioTrack = createTrack()

    init {
        synchronized(trackLock) { audioTrack.play() }
        scope.launch {
            for (packet in packets) {
                if (!isActive || packet.epoch != epoch.get()) continue
                synchronized(trackLock) {
                    if (packet.epoch == epoch.get() && audioTrack.state == AudioTrack.STATE_INITIALIZED) {
                        audioTrack.write(packet.bytes, 0, packet.bytes.size, AudioTrack.WRITE_BLOCKING)
                    }
                }
            }
        }
    }

    fun beginTurn(): Long {
        val next = epoch.incrementAndGet()
        flush("new_live_scene")
        return next
    }

    fun enqueue(packetEpoch: Long, bytes: ByteArray) {
        if (bytes.isEmpty() || packetEpoch != epoch.get()) return
        if (packets.trySend(Packet(packetEpoch, bytes)).isFailure) {
            DiagnosticHub.record(
                "LIVE_AUDIO_PACKET_DROPPED",
                mapOf("reason" to "bounded_audio_queue", "bytes" to bytes.size),
            )
        }
    }

    fun interrupt(reason: String) {
        epoch.incrementAndGet()
        flush(reason)
    }

    fun close() {
        epoch.incrementAndGet()
        packets.close()
        scope.cancel()
        synchronized(trackLock) {
            runCatching { audioTrack.pause() }
            runCatching { audioTrack.flush() }
            runCatching { audioTrack.release() }
        }
    }

    private fun flush(reason: String) {
        synchronized(trackLock) {
            if (audioTrack.state != AudioTrack.STATE_INITIALIZED) return
            runCatching { audioTrack.pause() }
            runCatching { audioTrack.flush() }
            runCatching { audioTrack.play() }
        }
        DiagnosticHub.record("LIVE_AUDIO_FLUSHED", mapOf("reason" to reason))
    }

    private fun createTrack(): AudioTrack {
        val minimum = AudioTrack.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(0)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE_HZ)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(max(minimum * 2, FALLBACK_BUFFER_BYTES))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
    }

    private companion object {
        const val SAMPLE_RATE_HZ = 24_000
        const val PACKET_QUEUE_CAPACITY = 6
        const val FALLBACK_BUFFER_BYTES = 9_600
    }
}
