package com.abdullah.visionbridge

import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.abdullah.visionbridge.capture.CaptureRuntime
import com.abdullah.visionbridge.data.diagnostics.DiagnosticHub
import com.abdullah.visionbridge.data.diagnostics.DiagnosticTrace
import com.abdullah.visionbridge.data.gemini.FrameTurnTransport
import com.abdullah.visionbridge.data.gemini.LiveFrameEncoder
import com.abdullah.visionbridge.data.paddleocr.PaddleOcrEngine
import com.abdullah.visionbridge.data.vision.TextGroundingGate
import com.abdullah.visionbridge.domain.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest

/** Actual Bitmap/JPEG/ONNX tests. Emulator timings are not phone/cloud acceptance evidence. */
@RunWith(AndroidJUnit4::class)
class FrameRegressionOnDeviceTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun clearTextPresenceAndDiagnosticsOffOnUseTheSameSyntheticReplay()=runBlocking {
        val engine=PaddleOcrEngine(context);assertTrue(engine.ensureLoaded().isSuccess)
        val texts=mutableListOf<List<String>>()
        try {
            for(enabled in listOf(false,true)) {
                DiagnosticHub.setEvidenceCapture(enabled)
                val pass=mutableListOf<String>();val durations=mutableListOf<Double>()
                for(c in SyntheticFrameFixtures.clear) {
                    val bitmap=SyntheticFrameFixtures.render(c);val start=SystemClock.elapsedRealtimeNanos()
                    val trace=DiagnosticTrace("synthetic-${c.id}-$enabled",c.id,System.currentTimeMillis(),start)
                    try {
                        DiagnosticHub.frame(bitmap,c.id,"synthetic_replay",trace.fields())
                        pass+=engine.read(bitmap,LocalReadingQuality.MAXIMUM).text
                        durations+=(SystemClock.elapsedRealtimeNanos()-start)/1e6
                    } finally { bitmap.recycle() }
                }
                val detected=pass.count { it.isNotBlank() }
                assertTrue("clear text presence $detected/${pass.size}; diagnostics=$enabled",detected.toDouble()/pass.size>=.95)
                texts+=pass
                val sorted=durations.sorted()
                println("SYNTHETIC_ONNX_REPLAY diagnostics=$enabled count=${pass.size} medianMs=${sorted[sorted.size/2]} p90Ms=${sorted[17]} device=${android.os.Build.MODEL}; not cloud or release latency")
            }
            assertEquals("diagnostics must not change OCR input/output",texts[0],texts[1])
            DiagnosticHub.export() // Also drains copied images after original bitmaps were recycled.
        } finally { DiagnosticHub.setEvidenceCapture(false);engine.release("synthetic_replay_finished") }
    }
    @Test fun jpegIdentityAndRequestContainOneImageAndItsInstruction() {
        val bitmap=SyntheticFrameFixtures.render(SyntheticFrameFixtures.clear[5])
        try {
            for(profile in CaptureProfile.entries) {
                val settings=AppSettings(captureProfile=profile);val encoded=LiveFrameEncoder().encode(bitmap,settings)
                val hash=MessageDigest.getInstance("SHA-256").digest(encoded.bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
                assertEquals(hash,encoded.imageHash)
                val decoded=BitmapFactory.decodeByteArray(encoded.bytes,0,encoded.bytes.size)
                try { assertEquals(encoded.width,decoded.width);assertEquals(encoded.height,decoded.height) } finally { decoded.recycle() }
                assertTrue(encoded.quality>=92)
                val request=FrameTurnTransport.payload("synthetic-image",settings)
                assertEquals(1,request.getJSONArray("contents").length())
                val parts=request.getJSONArray("contents").getJSONObject(0).getJSONArray("parts")
                assertEquals("synthetic-image",parts.getJSONObject(0).getJSONObject("inlineData").getString("data"))
                assertTrue(parts.getJSONObject(1).getString("text").contains("Read only literal text"))
                assertEquals(0,request.getJSONObject("generationConfig").getInt("temperature"))
            }
        } finally { bitmap.recycle() }
    }
    @Test fun runtimeRejectsOldTurnAfterOneHundredReplacements() {
        val runtime=CaptureRuntime();val gate=runtime.turnGate;var previous:AnalysisResult?=null
        repeat(100) { i ->
            if(i>0)gate.invalidate { runtime.clearVisualResult() }
            val capture=AnalysisTurn("turn-$i","trace-$i","frame-$i",gate.generation(),AnalysisMode.TEXT_READING,
                1,1,"synthetic","synthetic","session-$i")
            assertTrue(gate.activate(capture))
            val turn=capture.copy(submittedAtNanos=2,imageHash="a".repeat(64));assertTrue(gate.bindSubmission(turn))
            val result=AnalysisResult("Current $i",AnalysisSource.LOCAL_OCR,turn=turn)
            assertTrue(runtime.result(result))
            previous?.let { assertFalse(runtime.result(it));runtime.displayed(it) }
            assertEquals(result,runtime.state.value.lastResult);previous=result
        }
    }
    @Test fun blackAndDegradedFramesCannotGroundUnrelatedWords()=runBlocking {
        val engine=PaddleOcrEngine(context);assertTrue(engine.ensureLoaded().isSuccess)
        val clear=SyntheticFrameFixtures.render(SyntheticFrameFixtures.clear[0])
        val variants=listOf(SyntheticFrameFixtures.black(),SyntheticFrameFixtures.blur(clear),SyntheticFrameFixtures.glare(clear),
            SyntheticFrameFixtures.rotated(clear,15f),SyntheticFrameFixtures.rotated(clear,90f))
        try {
            for(bitmap in variants) {
                val r=engine.read(bitmap,LocalReadingQuality.MAXIMUM)
                assertFalse(TextGroundingGate.evaluate("UNRELATED CAMERA ZX900",100,true,false,
                    TextGroundingGate.Evidence(r.text,r.confidence,r.detectedBoxCount)).accepted)
            }
            assertEquals("",engine.read(variants[0],LocalReadingQuality.MAXIMUM).text)
        } finally { variants.forEach { it.recycle() };clear.recycle();engine.release("synthetic_variants_finished") }
    }
}
