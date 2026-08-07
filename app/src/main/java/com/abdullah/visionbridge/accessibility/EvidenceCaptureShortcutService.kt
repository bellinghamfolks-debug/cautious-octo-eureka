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
 * Puts the mode a user actually changes mid-session onto Android's accessibility button.
 *
 * The class name is historical — it first carried the diagnostic-capture toggle — and is kept
 * deliberately. Renaming it would make Android treat this as a different service and silently
 * switch it off, and asking someone who navigates by touch and speech to walk back through the
 * accessibility settings is a worse cost than an inaccurate identifier.
 *
 * What it does now is the cycle in [ShortcutCycle]: describe, read, stop, and round again.
 *
 * The problem it solves is a sequencing one. Screen capture mirrors the display, so eSight's shared
 * view has to stay in front for anything to be captured at all; opening VisionBridge to change the
 * mode replaces that view, and by the time the mode is changed the thing the user was pointing at
 * is no longer being captured. Android's accessibility button — the floating shortcut, or the
 * volume-key shortcut, whichever the user has assigned — is reachable from inside any app, and is a
 * surface a TalkBack user already knows.
 *
 * Two properties keep this from costing more than it gives:
 *
 * - **It reads nothing.** The service subscribes to no event types and does not request window
 *   content, which is visible in its configuration and enforced by the system. Asking a blind user
 *   to enable a second accessibility service is only reasonable if the second one demonstrably
 *   cannot see anything. This one receives a press.
 * - **It only moves settings the user already owns**, with the same defaults and the same storage
 *   as the settings screen.
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
                "MODE_SHORTCUT_AVAILABILITY_CHANGED",
                mapOf("available" to available),
            )
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        accessibilityButtonController.registerAccessibilityButtonCallback(buttonCallback)
        DiagnosticHub.record(
            "MODE_SHORTCUT_CONNECTED",
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
        DiagnosticHub.record("MODE_SHORTCUT_DISCONNECTED")
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
                "MODE_SHORTCUT_PRESSED",
                mapOf("source" to source, "applied" to false, "reason" to "container_unavailable"),
            )
            return
        }
        scope.launch {
            // Screen sharing can only be granted from an activity, so a press before it exists has
            // nothing to cycle. Saying which one press is still needed is more use than silence.
            if (!container.runtime.state.value.isRunning) {
                DiagnosticHub.record(
                    "MODE_SHORTCUT_PRESSED",
                    mapOf("source" to source, "applied" to false, "reason" to "capture_not_running"),
                )
                container.tts.speakUrgentNotice(ShortcutCycle.NO_CAPTURE_ANNOUNCEMENT)
                container.runtime.notice(ShortcutCycle.NO_CAPTURE_ANNOUNCEMENT)
                return@launch
            }
            val settings = container.settingsRepository.settings.first()
            val current = ShortcutCycle.stateOf(settings.mode, container.runtime.analysing.value)
            val step = ShortcutCycle.next(current)

            // Analysis is suspended in memory first so the very next frame already obeys the press,
            // and the mode is persisted second so it survives a restart.
            container.runtime.setAnalysing(step.analysing)
            step.mode?.let { container.settingsRepository.setMode(it) }
            if (!step.analysing) container.tts.stop()

            DiagnosticHub.record(
                "MODE_SHORTCUT_PRESSED",
                mapOf(
                    "source" to source,
                    "applied" to true,
                    "from" to current.name,
                    "to" to step.state.name,
                    "mode" to step.mode?.name,
                ),
            )
            container.tts.speakUrgentNotice(step.announcement)
            container.runtime.notice(step.announcement)
        }
    }

    private fun container(): AppContainer? =
        (application as? VisionBridgeApp)?.container
}
