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
 * Publishes safe on-device Latin OCR immediately, independently of the slower cloud lane.
 *
 * Only ML Kit Latin output is allowed to reach the user. This is a defense-in-depth boundary: even
 * if another local engine is added accidentally, its output is logged and suppressed until an
 * explicit reliability policy approves it.
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
        if (source != SAFE_LOCAL_SOURCE) {
            DiagnosticHub.record(
                "INSTANT_LOCAL_OCR_SUPPRESSED",
                mapOf(
                    "requestId" to requestId,
                    "source" to source,
                    "reason" to "source_not_approved_for_user_facing_output",
                    "text" to text,
                    "approvedSource" to SAFE_LOCAL_SOURCE,
                ),
            )
            return
        }

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

            runtime?.result(
                AnalysisResult(
                    text = cleaned,
                    source = AnalysisSource.LOCAL_OCR,
                    language = "en",
                )
            )
            DiagnosticHub.record(
                "INSTANT_LOCAL_OCR_PUBLISHED",
                mapOf(
                    "requestId" to requestId,
                    "source" to source,
                    "text" to cleaned,
                    "spokenDelta" to novel,
                    "language" to "en",
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

    /** Speaks an operational warning that is not OCR content. */
    suspend fun publishSystemNotice(text: String, code: String) {
        runtime?.notice(text)
        DiagnosticHub.record(
            "SYSTEM_NOTICE_PUBLISHED",
            mapOf(
                "code" to code,
                "text" to text,
                "speechEnabled" to settings.get().speechEnabled,
            ),
        )
        val currentSettings = settings.get()
        if (currentSettings.speechEnabled) {
            tts?.speak(
                text = text,
                urgent = true,
                rate = currentSettings.speechRate,
                interruptPrevious = true,
            )
        }
    }

    private fun isUseful(text: String): Boolean {
        val letters = text.count(Char::isLetter)
        val digits = text.count(Char::isDigit)
        val lines = text.lineSequence().count { it.isNotBlank() }
        return letters >= MIN_LETTERS || (letters + digits >= MIN_ALNUM && lines >= 2)
    }

    private const val SAFE_LOCAL_SOURCE = "MLKIT_LATIN"
    private const val MIN_LETTERS = 4
    private const val MIN_ALNUM = 6
}
