package com.abdullah.visionbridge.data.ocr

import com.abdullah.visionbridge.capture.CaptureRuntime
import com.abdullah.visionbridge.data.diagnostics.DiagnosticHub
import com.abdullah.visionbridge.data.speech.BilingualTtsEngine
import com.abdullah.visionbridge.domain.model.AnalysisResult
import com.abdullah.visionbridge.domain.model.AnalysisSource
import com.abdullah.visionbridge.domain.model.AppSettings
import com.abdullah.visionbridge.domain.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicReference

/**
 * Publishes useful on-device OCR immediately, independently of the slower cloud lane.
 *
 * This bridge is process-scoped because capture and OCR already live for the application lifetime.
 * Request IDs prevent an older local result from replacing a newer frame.
 */
internal object InstantLocalOcrBridge {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val settings = AtomicReference(AppSettings())
    private val publishMutex = Mutex()

    @Volatile private var runtime: CaptureRuntime? = null
    @Volatile private var tts: BilingualTtsEngine? = null
    private var activeRequestId = Long.MIN_VALUE
    private var publishedForRequest = ""

    fun initialize(
        settingsRepository: SettingsRepository,
        runtime: CaptureRuntime,
        tts: BilingualTtsEngine,
    ) {
        this.runtime = runtime
        this.tts = tts
        scope.launch {
            settingsRepository.settings.collectLatest(settings::set)
        }
    }

    suspend fun publish(requestId: Long, text: String, source: String) {
        val cleaned = LocalOcrTextMerger.merge(text, "")
        if (!isUseful(cleaned)) {
            DiagnosticHub.record(
                "INSTANT_LOCAL_OCR_SUPPRESSED",
                mapOf(
                    "requestId" to requestId,
                    "source" to source,
                    "reason" to "insufficient_useful_text",
                    "text" to text,
                ),
            )
            return
        }

        publishMutex.withLock {
            if (requestId < activeRequestId) {
                DiagnosticHub.record(
                    "INSTANT_LOCAL_OCR_SUPPRESSED",
                    mapOf(
                        "requestId" to requestId,
                        "activeRequestId" to activeRequestId,
                        "source" to source,
                        "reason" to "older_local_request",
                    ),
                )
                return
            }
            if (requestId > activeRequestId) {
                activeRequestId = requestId
                publishedForRequest = ""
            }

            val novel = LocalOcrTextMerger.novel(publishedForRequest, cleaned)
            if (novel.isBlank()) return
            publishedForRequest = LocalOcrTextMerger.merge(cleaned, publishedForRequest)

            val language = if (cleaned.any { it in '\u0600'..'\u06FF' }) "mixed" else "en"
            runtime?.result(
                AnalysisResult(
                    text = cleaned,
                    source = AnalysisSource.LOCAL_OCR,
                    language = language,
                )
            )
            DiagnosticHub.record(
                "INSTANT_LOCAL_OCR_PUBLISHED",
                mapOf(
                    "requestId" to requestId,
                    "source" to source,
                    "text" to cleaned,
                    "spokenDelta" to novel,
                    "language" to language,
                ),
            )

            val currentSettings = settings.get()
            if (currentSettings.speechEnabled) {
                DiagnosticHub.record(
                    "TTS_REQUESTED",
                    mapOf(
                        "text" to novel,
                        "source" to "INSTANT_LOCAL_OCR_$source",
                        "rate" to currentSettings.speechRate,
                        "interruptPrevious" to false,
                    ),
                )
                tts?.speak(
                    text = novel,
                    urgent = false,
                    rate = currentSettings.speechRate,
                    interruptPrevious = false,
                )
            }
        }
    }

    private fun isUseful(text: String): Boolean {
        val letters = text.count(Char::isLetter)
        val digits = text.count(Char::isDigit)
        val lines = text.lineSequence().count { it.isNotBlank() }
        return letters >= MIN_LETTERS || (letters + digits >= MIN_ALNUM && lines >= 2)
    }

    private const val MIN_LETTERS = 4
    private const val MIN_ALNUM = 6
}
