package com.abdullah.visionbridge.data.diagnostics

import android.content.Context
import java.io.File

/** Creates the bounded shareable archive after [DiagnosticHub.flush] has completed. */
internal object SmartDiagnosticExport {
    fun create(context: Context): File {
        val appContext = context.applicationContext
        val root = File(appContext.filesDir, "diagnostic_black_box")
        val sessions = File(root, "sessions")
        return CompactDiagnosticExporter(
            context = appContext,
            root = root,
            sessionsDir = sessions,
        ).export()
    }
}
