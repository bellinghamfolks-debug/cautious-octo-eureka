package com.abdullah.visionbridge.accessibility

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.abdullah.visionbridge.VisionBridgeApp
import com.abdullah.visionbridge.data.diagnostics.DiagnosticHub
import com.abdullah.visionbridge.di.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Puts failure-frame capture on Android's accessibility button, so it can be switched at the moment
 * it is needed rather than at the only moment the settings screen is reachable.
 *
 * The problem this solves is a sequencing one. Screen capture mirrors whatever is on the display,
 * so the eSight shared view has to stay in front; opening VisionBridge to reach a switch replaces
 * that view, and by the time the switch has been flipped the page that would not read is no longer
 * being captured. The one control the user needs mid-session was the one control that could not be
 * reached mid-session.
 *
 * Android's accessibility button — the floating shortcut in the navigation bar, or the volume-key
 * shortcut, whichever the user has configured — is reachable from inside any app without leaving
 * it. That is exactly the shape of the requirement, and it is a surface a blind user already knows
 * how to reach because TalkBack uses it.
 *
 * Two decisions keep this from being a privacy cost rather than a diagnostic gain:
 *
 * - **It reads nothing.** The service declares no event types and does not request window content,
 *   which is visible in its configuration and enforced by the system. An accessibility service that
 *   could read the screen would be a heavier thing to ask a blind user to enable than the feature
 *   is worth. This one is a button and nothing else.
 * - **It only moves a switch the user already owns.** The state it writes is the same stored
 *   setting the settings screen writes, with the same default of off.
 *
 * Every press is spoken, because the button has no visible state and the person pressing it cannot
 * see one anyway.
 */
class EvidenceCaptureShortcutService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val buttonCallback = object : AccessibilityButtonController.AccessibilityButtonCallback() {
        override fun onClicked(controller: AccessibilityButtonController) {
            toggle(source = "accessibility_button")
        }

        override fun onAvailabilityChanged(
            controller: AccessibilityButtonController,
            available: Boolean,
        ) {
            DiagnosticHub.record(
                "EVIDENCE_SHORTCUT_AVAILABILITY_CHANGED",
                mapOf("available" to available),
            )
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        accessibilityButtonController.registerAccessibilityButtonCallback(buttonCallback)
        DiagnosticHub.record(
            "EVIDENCE_SHORTCUT_CONNECTED",
            mapOf(
                "buttonAvailable" to accessibilityButtonController.isAccessibilityButtonAvailable,
                "readsScreenContent" to false,
            ),
        )
    }

    /** Nothing is subscribed to, so nothing arrives. Kept because the base class requires it. */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        runCatching {
            accessibilityButtonController.unregisterAccessibilityButtonCallback(buttonCallback)
        }
        DiagnosticHub.record("EVIDENCE_SHORTCUT_DISCONNECTED")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun toggle(source: String) {
        val container = container()
        if (container == null) {
            DiagnosticHub.record(
                "EVIDENCE_SHORTCUT_PRESSED",
                mapOf("source" to source, "applied" to false, "reason" to "container_unavailable"),
            )
            return
        }
        scope.launch {
            val settings = container.settingsRepository.settings.first()
            val action = EvidenceShortcut.press(
                currentlyEnabled = settings.captureFailureEvidence,
                framesAlreadyHeld = container.diagnostics.evidenceStore.frameCount(),
                captureRunning = container.runtime.state.value.isRunning,
            )
            // Applied to the live store first and persisted second: the store is what the capture
            // path consults on the very next frame, and a DataStore write that has not landed yet
            // would otherwise lose the first failure after the press — which is usually the one the
            // user pressed the button for.
            DiagnosticHub.setEvidenceCapture(action.enable)
            container.settingsRepository.setCaptureFailureEvidence(action.enable)
            if (action.markProblem) {
                DiagnosticHub.markProblemAsync("شُغّل حفظ اللقطات من زر إمكانية الوصول")
            }
            DiagnosticHub.record(
                "EVIDENCE_SHORTCUT_PRESSED",
                mapOf(
                    "source" to source,
                    "applied" to true,
                    "enabled" to action.enable,
                    "captureRunning" to container.runtime.state.value.isRunning,
                    "framesHeld" to container.diagnostics.evidenceStore.frameCount(),
                ),
            )
            container.tts.speakUrgentNotice(action.announcement)
            container.runtime.notice(action.announcement)
        }
    }

    private fun container(): AppContainer? =
        (application as? VisionBridgeApp)?.container
}
