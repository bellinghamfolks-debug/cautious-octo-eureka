package com.abdullah.visionbridge

import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.abdullah.visionbridge.data.diagnostics.DiagnosticRecorder
import com.abdullah.visionbridge.data.diagnostics.EvidenceStore
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class EvidenceNamespaceOnDeviceTest {
    @Test fun identicalFrameIdsInTwoSessionsRemainTwoImagesAndCountsMatch()=runBlocking {
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(base.cacheDir,"evidence-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context=object:ContextWrapper(base) {
            override fun getApplicationContext()=this
            override fun getFilesDir()=File(root,"files").apply { mkdirs() }
            override fun getCacheDir()=File(root,"cache").apply { mkdirs() }
        }
        val recorder=DiagnosticRecorder(context)
        val bitmap=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888)
        try {
            recorder.evidenceStore.enabled=true
            val names=mutableListOf<String>()
            repeat(2) {
                val session=recorder.startSession(emptyMap())
                bitmap.eraseColor(if(it==0) android.graphics.Color.WHITE else android.graphics.Color.BLUE)
                val name=checkNotNull(recorder.evidenceStore.capture(bitmap,"F000000005","analysis_input_selected_input"))
                assertTrue(name.startsWith("$session/"));names+=name
                recorder.record("EVIDENCE_FRAME_CAPTURED",mapOf("frameId" to "F000000005","file" to name))
                recorder.record("EVIDENCE_TIMELINE_STARTED",mapOf("durationMs" to 600000)) // Legacy field must also be safe.
                recorder.record("FRAME_DROPPED",mapOf("reason" to "newer_candidate"))
                recorder.record("ANALYSIS_DISPATCH_CANCELLED",mapOf("expected" to true,"reason" to "obsolete_or_stopped"))
                recorder.endSession("synthetic_test")
            }
            assertEquals(2,names.distinct().size)
            assertEquals(2,EvidenceStore(recorder.evidenceStore.directory).frameCount())
            ZipFile(recorder.export()).use { zip ->
                fun json(name:String)=JSONObject(zip.getInputStream(zip.getEntry(name)).bufferedReader().readText())
                val images=zip.entries().asSequence().filter { it.name.endsWith(".jpg") }.toList()
                assertEquals(2,images.size)
                assertEquals(2,json("export_manifest.json").getInt("evidenceFrameCount"))
                assertTrue(json("EVIDENCE_INTEGRITY.json").getBoolean("valid"))
                names.forEach { assertNotNull(zip.getEntry("evidence/$it")) }
                val summary=json("diagnostic_summary.json")
                val text=summary.toString()
                assertTrue(text.contains("\"imageCount\":1"))
                val findings=json("automatic_findings.json").toString()
                assertFalse(findings.contains("CRITICAL_PIPELINE_STALL"))
                assertFalse(findings.contains("UNEXPECTED_FRAME_OR_QUEUE_DROP"))
                assertFalse(findings.contains("APP_STAGE_FAILURE"))
            }
        } finally { bitmap.recycle();recorder.clearHistory();root.deleteRecursively() }
    }
}
