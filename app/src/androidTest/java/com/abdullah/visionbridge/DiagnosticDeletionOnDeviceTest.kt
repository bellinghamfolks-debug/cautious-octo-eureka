package com.abdullah.visionbridge

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.abdullah.visionbridge.data.diagnostics.DiagnosticRecorder
import com.abdullah.visionbridge.data.diagnostics.EvidenceStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class DiagnosticDeletionOnDeviceTest {
    @Test fun deletesHistoryAndPreservesSettingsAndFreshRecording() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "deletion-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext() = this
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        }
        val recorder = DiagnosticRecorder(context)
        try {
            val sentinel = File(context.filesDir, "settings-sentinel").apply { writeText("preserve") }
            recorder.startSession(emptyMap())
            recorder.record("OLD_PRIVATE_MARKER", emptyMap())
            recorder.evidenceStore.directory.mkdirs()
            File(recorder.evidenceStore.directory, "old.jpg").writeBytes(byteArrayOf(1, 2, 3))
            // Persisted files must be deleted even after the in-memory counters reset.
            val restored = EvidenceStore(recorder.evidenceStore.directory)
            assertEquals(1, restored.frameCount())
            restored.clear()
            val oldExport = recorder.export()
            assertTrue(oldExport.exists())
            recorder.clearHistory()
            assertFalse(oldExport.exists())
            assertTrue(recorder.evidenceStore.directory.listFiles().orEmpty().isEmpty())
            assertEquals("preserve", sentinel.readText())
            recorder.record("FRESH_MARKER", emptyMap())
            ZipFile(recorder.export()).use { zip ->
                val entries = zip.entries().asSequence().toList()
                assertFalse(entries.any { it.name.startsWith("evidence/") })
                val events = entries.filter { it.name.endsWith("events.jsonl") }
                    .joinToString { zip.getInputStream(it).bufferedReader().readText() }
                assertFalse(events.contains("OLD_PRIVATE_MARKER"))
                assertTrue(events.contains("FRESH_MARKER"))
            }
            recorder.clearHistory()
            recorder.clearHistory()
        } finally {
            recorder.clearHistory()
            root.deleteRecursively()
        }
    }
}
