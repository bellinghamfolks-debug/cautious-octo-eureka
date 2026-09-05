package com.abdullah.visionbridge.data.speech

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.abdullah.visionbridge.data.diagnostics.DiagnosticHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/** Low-latency PCM player for Gemini Live speech. */
class LivePcmAudioPlayer {
    private data class Packet(val epoch: Long, val bytes: ByteArray, val sampleRateHz: Int)

    private val epoch = AtomicLong(0L)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Do not DROP_OLDEST here. The previous queue silently threw away speech packets whenever
    // Gemini produced audio faster than AudioTrack consumed it; the result on a real Xiaomi device
    // was missing syllables and speech the user described as unintelligible. Stale epochs are still
    // discarded immediately by the consumer, so an unlimited transport queue does not make old
    // visual turns audible after a target change.
    private val packets = Channel<Packet>(Channel.UNLIMITED)
    private val trackLock = Any()
    private var configuredSampleRateHz = DEFAULT_SAMPLE_RATE_HZ
    private var audioTrack = createTrack(configuredSampleRateHz)

    init {
        synchronized(trackLock) { audioTrack.play() }
        scope.launch {
            for (packet in packets) {
                if (!isActive || packet.epoch != epoch.get()) continue
                synchronized(trackLock) {
                    if (packet.epoch != epoch.get()) continue
                    ensureSampleRateLocked(packet.sampleRateHz)
                    if (audioTrack.state == AudioTrack.STATE_INITIALIZED) {
                        val written = audioTrack.write(
                            packet.bytes,
                            0,
                            packet.bytes.size,
                            AudioTrack.WRITE_BLOCKING,
                        )
                        if (written < 0) {
                            DiagnosticHub.record(
                                "LIVE_AUDIO_WRITE_FAILED",
                                mapOf("code" to written, "bytes" to packet.bytes.size),
                            )
                        }
                    }
                }
            }
        }
    }

    fun beginTurn(reason: String = "new_live_turn"): Long {
        val next = epoch.incrementAndGet()
        flush(reason)
        return next
    }

    fun enqueue(packetEpoch: Long, bytes: ByteArray, sampleRateHz: Int = DEFAULT_SAMPLE_RATE_HZ) {
        if (bytes.isEmpty() || packetEpoch != epoch.get()) return
        val normalizedRate = sampleRateHz.takeIf { it in 8_000..96_000 } ?: DEFAULT_SAMPLE_RATE_HZ
        packets.trySend(Packet(packetEpoch, bytes, normalizedRate))
    }

    fun interrupt(reason: String) {
        epoch.incrementAndGet()
        flush(reason)
    }

    fun currentEpoch(): Long = epoch.get()

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

    private fun ensureSampleRateLocked(sampleRateHz: Int) {
        if (sampleRateHz == configuredSampleRateHz) return
        runCatching { audioTrack.pause() }
        runCatching { audioTrack.flush() }
        runCatching { audioTrack.release() }
        configuredSampleRateHz = sampleRateHz
        audioTrack = createTrack(sampleRateHz)
        audioTrack.play()
        DiagnosticHub.record(
            "LIVE_AUDIO_FORMAT_CHANGED",
            mapOf("sampleRateHz" to sampleRateHz),
        )
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

    private fun createTrack(sampleRateHz: Int): AudioTrack {
        val minimum = AudioTrack.getMinBufferSize(
            sampleRateHz,
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
                    .setSampleRate(sampleRateHz)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(max(minimum * 2, FALLBACK_BUFFER_BYTES))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
    }

    companion object {
        const val DEFAULT_SAMPLE_RATE_HZ = 24_000
        private const val FALLBACK_BUFFER_BYTES = 9_600
    }
}
