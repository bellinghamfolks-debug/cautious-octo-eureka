package com.abdullah.visionbridge.data.gemini

import android.graphics.Bitmap
import android.os.SystemClock
import com.abdullah.visionbridge.domain.model.AnalysisMode
import com.abdullah.visionbridge.domain.model.AppSettings
import com.abdullah.visionbridge.domain.model.CaptureProfile
import com.abdullah.visionbridge.domain.model.SceneDescriptionStyle
import java.io.ByteArrayOutputStream

/**
 * Fast encoder dedicated to Gemini Live.
 *
 * One resize plus one JPEG encode only. Dense labels need more pixels than a general scene, so the
 * stable text profile keeps enough detail for small Arabic/English print while scene frames remain
 * compact for latency. Backpressure in the Live turn lane prevents higher text detail from creating
 * a request storm.
 */
class LiveFrameEncoder {
    data class EncodedFrame(
        val bytes: ByteArray,
        val mimeType: String,
        val width: Int,
        val height: Int,
        val quality: Int,
        val scaleMs: Double,
        val compressionMs: Double,
    )

    fun encode(source: Bitmap, settings: AppSettings): EncodedFrame {
        val targetEdge = when (settings.mode) {
            AnalysisMode.TEXT_READING -> if (settings.captureProfile == CaptureProfile.STABLE) {
                TEXT_STABLE_EDGE
            } else {
                TEXT_FAST_EDGE
            }
            AnalysisMode.SCENE_DESCRIPTION -> if (
                settings.sceneDescriptionStyle == SceneDescriptionStyle.BRIEF
            ) {
                SCENE_BRIEF_EDGE
            } else {
                SCENE_COMPREHENSIVE_EDGE
            }
        }
        val quality = when (settings.mode) {
            AnalysisMode.TEXT_READING -> if (settings.captureProfile == CaptureProfile.STABLE) {
                TEXT_STABLE_QUALITY
            } else {
                TEXT_FAST_QUALITY
            }
            AnalysisMode.SCENE_DESCRIPTION -> SCENE_QUALITY
        }

        val scaleStarted = SystemClock.elapsedRealtimeNanos()
        val scaled = scaleToLongEdge(source, targetEdge)
        val scaleMs = elapsedMs(scaleStarted)
        return try {
            val compressionStarted = SystemClock.elapsedRealtimeNanos()
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
            val bytes = out.toByteArray()
            val compressionMs = elapsedMs(compressionStarted)
            EncodedFrame(
                bytes = bytes,
                mimeType = "image/jpeg",
                width = scaled.width,
                height = scaled.height,
                quality = quality,
                scaleMs = scaleMs,
                compressionMs = compressionMs,
            )
        } finally {
            if (scaled !== source) scaled.recycle()
        }
    }

    private fun scaleToLongEdge(source: Bitmap, targetEdge: Int): Bitmap {
        val current = maxOf(source.width, source.height).coerceAtLeast(1)
        if (current <= targetEdge) return source
        val factor = targetEdge.toDouble() / current
        return Bitmap.createScaledBitmap(
            source,
            (source.width * factor).toInt().coerceAtLeast(1),
            (source.height * factor).toInt().coerceAtLeast(1),
            true,
        )
    }

    private fun elapsedMs(started: Long): Double =
        (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0

    private companion object {
        const val TEXT_STABLE_EDGE = 1440
        const val TEXT_FAST_EDGE = 960
        const val SCENE_BRIEF_EDGE = 640
        const val SCENE_COMPREHENSIVE_EDGE = 760
        const val TEXT_STABLE_QUALITY = 88
        const val TEXT_FAST_QUALITY = 82
        const val SCENE_QUALITY = 74
    }
}
