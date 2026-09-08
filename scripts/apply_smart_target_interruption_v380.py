#!/usr/bin/env python3
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SERVICE = ROOT / "app/src/main/java/com/abdullah/visionbridge/capture/MediaProjectionService.kt"
LIVE = ROOT / "app/src/main/java/com/abdullah/visionbridge/data/gemini/GeminiLiveSession.kt"
SETTINGS = ROOT / "app/src/main/java/com/abdullah/visionbridge/ui/SettingsScreen.kt"
VIEWMODEL = ROOT / "app/src/main/java/com/abdullah/visionbridge/ui/MainViewModel.kt"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    if old not in text:
        raise SystemExit(f"3.8 smart target patch failed: {label} anchor not found")
    return text.replace(old, new, 1)


def patch_service() -> None:
    text = SERVICE.read_text()
    if "SMART_TARGET_INTERRUPTION_V380" in text:
        return

    tracker_anchor = '''    private val sceneTargetTracker = VisualTargetTracker(
        maximumDissimilarity = SCENE_TARGET_DISSIMILARITY,
        maximumChromaDifference = SCENE_TARGET_CHROMA,
    )
    private val frameQueueLock = Any()
'''
    tracker_replacement = '''    private val sceneTargetTracker = VisualTargetTracker(
        maximumDissimilarity = SCENE_TARGET_DISSIMILARITY,
        maximumChromaDifference = SCENE_TARGET_CHROMA,
    )

    // Observer-only trackers for Gemini Live. They never gate or cancel capture. A 64px/two-level
    // pyramid keeps motion compensation while avoiding the half-second tracking cost of the old
    // cloud path. Two agreeing frames are still required before a target replacement is believed.
    private val liveTextTargetTracker = VisualTargetTracker(
        maximumDissimilarity = LIVE_TEXT_TARGET_DISSIMILARITY,
        maximumChromaDifference = LIVE_TEXT_TARGET_CHROMA,
        framesToConfirm = SMART_TARGET_CONFIRM_FRAMES,
    )
    private val liveSceneTargetTracker = VisualTargetTracker(
        maximumDissimilarity = LIVE_SCENE_TARGET_DISSIMILARITY,
        maximumChromaDifference = LIVE_SCENE_TARGET_CHROMA,
        framesToConfirm = SMART_TARGET_CONFIRM_FRAMES,
    )
    private var lastSmartTargetTrackAtElapsedMs = 0L
    private val frameQueueLock = Any()
'''
    text = replace_once(text, tracker_anchor, tracker_replacement, "live smart trackers")

    # Reset the observer when the viewport, mode or explicit local/cloud lane changes. Otherwise a
    # reference captured with one geometry could be compared with another geometry and look like a
    # target replacement even though the user never moved.
    policy_anchor = '''                val viewportPolicyChanged =
                    newSettings.viewportMode != activeSettings.viewportMode ||
                        newSettings.mode != activeSettings.mode
'''
    policy_replacement = '''                val viewportPolicyChanged =
                    newSettings.viewportMode != activeSettings.viewportMode ||
                        newSettings.mode != activeSettings.mode ||
                        newSettings.useLocalOcr != activeSettings.useLocalOcr
'''
    text = replace_once(text, policy_anchor, policy_replacement, "viewport/route policy reset")

    reset_policy_anchor = '''                    activeViewport = null
                    lastViewportProbeAtElapsedMs = 0L
                }
                activeSettings = newSettings
'''
    reset_policy_replacement = '''                    activeViewport = null
                    lastViewportProbeAtElapsedMs = 0L
                    liveTextTargetTracker.reset()
                    liveSceneTargetTracker.reset()
                    lastSmartTargetTrackAtElapsedMs = 0L
                }
                activeSettings = newSettings
'''
    text = replace_once(text, reset_policy_anchor, reset_policy_replacement, "smart tracker settings reset")

    cloud_pattern = re.compile(
        r'''        if \(cloudLive\) \{\n            // Do not call onVisualTargetChanged here\. 3\.4 did that.*?            submitLatestFrame\(PendingFrame\(view, trace\)\)\n            return\n        \}\n\n        val tracker = if \(scene\) sceneTargetTracker else textTargetTracker''',
        re.DOTALL,
    )
    cloud_replacement = '''        if (cloudLive) {
            // SMART_TARGET_INTERRUPTION_V380: Live keeps its one-active/one-latest backpressure,
            // while a separate observer decides whether speech should survive a real target swap.
            // The observer never blocks a frame and never closes the WebSocket.
            val smartNow = SystemClock.elapsedRealtime()
            val smartDue = lastSmartTargetTrackAtElapsedMs == 0L ||
                smartNow - lastSmartTargetTrackAtElapsedMs >= SMART_TARGET_TRACK_INTERVAL_MS
            val smartDecision = if (smartDue) {
                lastSmartTargetTrackAtElapsedMs = smartNow
                val smartTracker = if (scene) liveSceneTargetTracker else liveTextTargetTracker
                val smartStarted = SystemClock.elapsedRealtimeNanos()
                val decision = smartTracker.evaluate(
                    BitmapFrames.trackedFrame(
                        view,
                        baseSize = SMART_TARGET_BASE_SIZE,
                        depth = SMART_TARGET_PYRAMID_DEPTH,
                    ),
                )
                val smartTrackingMs =
                    (SystemClock.elapsedRealtimeNanos() - smartStarted) / 1_000_000.0
                val policy = SmartTargetInterruptionPolicy.evaluate(decision, settings.mode)
                DiagnosticHub.record(
                    "SMART_TARGET_POLICY_DECISION",
                    trace.fields(
                        mapOf(
                            "lane" to "GEMINI_LIVE",
                            "action" to policy.action.name,
                            "policyReason" to policy.reason,
                            "confidence" to policy.confidence,
                            "trackerReason" to decision.reason,
                            "targetChanged" to decision.targetChanged,
                            "targetTrackId" to decision.trackId,
                            "structuralDissimilarity" to decision.dissimilarity,
                            "unalignedDissimilarity" to decision.unalignedDissimilarity,
                            "chromaDifference" to decision.chromaDifference,
                            "alignmentCoverage" to decision.coverage,
                            "registrationMethod" to decision.method,
                            "motionTranslationX" to decision.translationX,
                            "motionTranslationY" to decision.translationY,
                            "motionScale" to decision.scale,
                            "motionRotationDegrees" to decision.rotationDegrees,
                            "consecutiveCandidateFrames" to decision.consecutiveCandidateFrames,
                            "trackingMs" to smartTrackingMs,
                            "observerOnly" to true,
                        ),
                    ),
                )
                if (policy.action != SmartTargetInterruptionPolicy.Action.NONE) {
                    val interruptNow = settings.interruptSpeechOnVisualChange &&
                        policy.action == SmartTargetInterruptionPolicy.Action.IMMEDIATE
                    container.coordinator.onVisualTargetChanged(interruptNow)
                    DiagnosticHub.record(
                        "SMART_TARGET_TRANSITION_APPLIED",
                        trace.fields(
                            mapOf(
                                "lane" to "GEMINI_LIVE",
                                "action" to policy.action.name,
                                "interruptSettingEnabled" to settings.interruptSpeechOnVisualChange,
                                "speechInterruptedNow" to interruptNow,
                                "networkTurnCancelled" to false,
                                "webSocketClosed" to false,
                                "latestFrameWillWin" to true,
                            ),
                        ),
                    )
                }
                decision
            } else {
                null
            }

            DiagnosticHub.record(
                "VISUAL_TARGET_DECISION",
                trace.fields(
                    mapOf(
                        "targetChanged" to (smartDecision?.targetChanged ?: false),
                        "decisionReason" to (smartDecision?.reason ?: "smart_target_observer_throttled"),
                        "targetTrackId" to smartDecision?.trackId,
                        "registrationMethod" to (smartDecision?.method ?: "SMART_OBSERVER_THROTTLED"),
                        "trackingMs" to 0.0,
                        "cloudLiveDirect" to true,
                        "smartTargetObserver" to true,
                        "backpressure" to "ONE_ACTIVE_ONE_LATEST",
                    ),
                ),
            )
            DiagnosticHub.frame(
                bitmap = view,
                frameId = frameId,
                stage = "selected_input",
                metadata = trace.fields(
                    mapOf(
                        "mode" to settings.mode.name,
                        "captureProfile" to settings.captureProfile.name,
                        "targetChanged" to (smartDecision?.targetChanged ?: false),
                        "cloudLiveDirect" to true,
                        "smartTargetObserver" to true,
                        "frameMeanAbsoluteDifference" to changeDecision.meanAbsoluteDifference,
                        "frameChangedPixelRatio" to changeDecision.changedPixelRatio,
                    ),
                ),
            )
            DiagnosticHub.record(
                "FRAME_SELECTED_FOR_ANALYSIS",
                trace.fields(
                    mapOf(
                        "cloudLiveDirect" to true,
                        "smartTargetObserver" to true,
                        "backpressure" to "ONE_ACTIVE_ONE_LATEST",
                    ),
                ),
            )
            submitLatestFrame(PendingFrame(view, trace))
            return
        }

        val tracker = if (scene) sceneTargetTracker else textTargetTracker'''
    text, count = cloud_pattern.subn(cloud_replacement, text, count=1)
    if count != 1:
        raise SystemExit(f"3.8 smart target patch failed: Live observer block count={count}")

    local_anchor = '''        if (visualTargetChanged) {
            container.coordinator.onVisualTargetChanged(settings.interruptSpeechOnVisualChange)
        }
'''
    local_replacement = '''        val localSmartPolicy = SmartTargetInterruptionPolicy.evaluate(targetDecision, settings.mode)
        DiagnosticHub.record(
            "SMART_TARGET_POLICY_DECISION",
            trace.fields(
                mapOf(
                    "lane" to "LOCAL_PPOCR",
                    "action" to localSmartPolicy.action.name,
                    "policyReason" to localSmartPolicy.reason,
                    "confidence" to localSmartPolicy.confidence,
                    "trackerReason" to targetDecision.reason,
                    "targetChanged" to visualTargetChanged,
                    "targetTrackId" to targetDecision.trackId,
                    "structuralDissimilarity" to targetDecision.dissimilarity,
                    "unalignedDissimilarity" to targetDecision.unalignedDissimilarity,
                    "chromaDifference" to targetDecision.chromaDifference,
                    "alignmentCoverage" to targetDecision.coverage,
                    "registrationMethod" to targetDecision.method,
                    "trackingMs" to trackingMs,
                ),
            ),
        )
        if (localSmartPolicy.action != SmartTargetInterruptionPolicy.Action.NONE) {
            val interruptNow = settings.interruptSpeechOnVisualChange &&
                localSmartPolicy.action == SmartTargetInterruptionPolicy.Action.IMMEDIATE
            container.coordinator.onVisualTargetChanged(interruptNow)
            DiagnosticHub.record(
                "SMART_TARGET_TRANSITION_APPLIED",
                trace.fields(
                    mapOf(
                        "lane" to "LOCAL_PPOCR",
                        "action" to localSmartPolicy.action.name,
                        "interruptSettingEnabled" to settings.interruptSpeechOnVisualChange,
                        "speechInterruptedNow" to interruptNow,
                    ),
                ),
            )
        }
'''
    text = replace_once(text, local_anchor, local_replacement, "local smart interruption")

    reset_anchor = '''        textTargetTracker.reset()
        sceneTargetTracker.reset()
        activeViewport = null
        lastViewportProbeAtElapsedMs = 0L
'''
    reset_replacement = '''        textTargetTracker.reset()
        sceneTargetTracker.reset()
        liveTextTargetTracker.reset()
        liveSceneTargetTracker.reset()
        lastSmartTargetTrackAtElapsedMs = 0L
        activeViewport = null
        lastViewportProbeAtElapsedMs = 0L
'''
    text = replace_once(text, reset_anchor, reset_replacement, "projection smart tracker reset")

    constants_anchor = '''        private const val TEXT_TARGET_CHROMA = 26.0
        private const val SCENE_TARGET_CHROMA = 18.0
        private const val DROPPED_PREVIEW_INTERVAL_MS = 1_000L
'''
    constants_replacement = '''        private const val TEXT_TARGET_CHROMA = 26.0
        private const val SCENE_TARGET_CHROMA = 18.0

        // The Live observer is smaller and slightly more conservative than the local full tracker.
        // It is not an analysis gate; it exists only to decide speech freshness.
        private const val LIVE_TEXT_TARGET_DISSIMILARITY = 0.30
        private const val LIVE_SCENE_TARGET_DISSIMILARITY = 0.28
        private const val LIVE_TEXT_TARGET_CHROMA = 32.0
        private const val LIVE_SCENE_TARGET_CHROMA = 24.0
        private const val SMART_TARGET_CONFIRM_FRAMES = 2
        private const val SMART_TARGET_BASE_SIZE = 64
        private const val SMART_TARGET_PYRAMID_DEPTH = 2
        private const val SMART_TARGET_TRACK_INTERVAL_MS = 260L

        private const val DROPPED_PREVIEW_INTERVAL_MS = 1_000L
'''
    text = replace_once(text, constants_anchor, constants_replacement, "smart target constants")

    # Make diagnostics say what the old internal field means in this build.
    settings_diag_anchor = '        "interruptSpeechOnVisualChange" to settings.interruptSpeechOnVisualChange,\n'
    settings_diag_replacement = '''        "interruptSpeechOnVisualChange" to settings.interruptSpeechOnVisualChange,
        "smartTargetInterruptionEnabled" to settings.interruptSpeechOnVisualChange,
        "smartTargetPolicyVersion" to "V380",
'''
    text = replace_once(text, settings_diag_anchor, settings_diag_replacement, "smart settings diagnostics")

    text = text.replace(
        "// ESIGHT_FIXED_VIEWPORT_V370\nclass MediaProjectionService : Service() {",
        "// ESIGHT_FIXED_VIEWPORT_V370\n// SMART_TARGET_INTERRUPTION_V380\nclass MediaProjectionService : Service() {",
        1,
    )
    SERVICE.write_text(text)


def patch_live() -> None:
    text = LIVE.read_text()
    if "SMART_TARGET_LIVE_STALE_GUARD_V380" in text:
        return

    pattern = re.compile(
        r'''    fun onVisualTargetChanged\(interruptSpeech: Boolean\) \{.*?\n    \}\n\n    fun reset\(\) \{''',
        re.DOTALL,
    )
    replacement = '''    // SMART_TARGET_LIVE_STALE_GUARD_V380
    fun onVisualTargetChanged(interruptSpeech: Boolean) {
        val turnWasInFlight = responseInFlight
        visualGeneration += 1L
        synchronized(sendLock) {
            lastFrameSentAtElapsedMs = 0L
            // A target transition invalidates the old semantic probe. The active network turn is
            // still allowed to reach its normal boundary; the next frame starts after that gate.
            sceneProbeOutstanding = false
            sceneProbeStartedAtElapsedMs = 0L
        }

        // Never close or cancel the WebSocket here. Mark the old turn stale so its remaining
        // transcription is ignored, let Gemini emit the normal turn boundary, then release the
        // backpressure gate as a handled supersession rather than a Live failure.
        if (turnWasInFlight) {
            staleAudioBlocked = true
            staleAudioPacketsBlocked = 0
        }
        synchronized(transcriptLock) { transcript = StringBuilder() }

        if (interruptSpeech) {
            // Gemini PCM is intentionally not the audible path in this build. Stop the local Live
            // TTS that is actually being heard. Keeping this separate from the socket is what makes
            // immediate target switching safe.
            tts.supersedeLiveSpeech("smart_target_immediate_change")
            audioPlayer.interrupt("smart_target_immediate_change")
        }

        DiagnosticHub.record(
            "LIVE_VISUAL_TARGET_CHANGED",
            mapOf(
                "interruptSpeechSetting" to interruptSpeech,
                "smartTargetPolicy" to true,
                "activeTurnMarkedStale" to turnWasInFlight,
                "networkTurnCancelled" to false,
                "webSocketClosed" to false,
                "visualGeneration" to visualGeneration,
            ),
        )
    }

    fun reset() {'''
    text, count = pattern.subn(replacement, text, count=1)
    if count != 1:
        raise SystemExit(f"3.8 smart target patch failed: Live target-change replacement count={count}")

    # A stale turn caused by a deliberate target supersession is not a transport/model failure. The
    # waiting submitFrame returns handled=true, allowing the service's latest queued frame to run
    # without an erroneous "Gemini Live failed" announcement.
    text = text.replace(
        '                completeActiveTurn(false, "stale_turn_complete")\n',
        '                completeActiveTurn(true, "smart_target_superseded")\n',
        1,
    )
    if 'completeActiveTurn(true, "smart_target_superseded")' not in text:
        raise SystemExit("3.8 smart target patch failed: stale turn completion anchor not found")

    LIVE.write_text(text)


def patch_settings_ui() -> None:
    text = SETTINGS.read_text()
    old = '''                AccessibleSwitchRow(
                    title = "إيقاف النطق عند تغيّر المحتوى",
                    description = "يوقف النتيجة الحالية عند الانتقال إلى محتوى مختلف، ثم ينطق أحدث نتيجة.",
                    checked = state.settings.interruptSpeechOnVisualChange,
                    onCheckedChange = onInterruptSpeechChange,
                )
'''
    new = '''                AccessibleSwitchRow(
                    title = "الانتقال الذكي بين الأهداف",
                    description = if (state.settings.interruptSpeechOnVisualChange) {
                        "مفعّل. يتجاهل اهتزاز النظارة والتقريب والدوران وتغيّر الإضاءة. " +
                            "إذا تأكد انتقال قوي إلى هدف آخر يوقف الكلام فوراً، أما الانتقال المحتمل " +
                            "فيترك العبارة الحالية تنتهي ثم ينتقل إلى أحدث هدف."
                    } else {
                        "معطّل. يظل VisionBridge يكتشف الهدف الجديد ويحلله، لكنه لا يقطع الكلام الجاري فوراً."
                    },
                    checked = state.settings.interruptSpeechOnVisualChange,
                    onCheckedChange = onInterruptSpeechChange,
                )
'''
    text = replace_once(text, old, new, "smart interruption settings row")
    SETTINGS.write_text(text)


def patch_viewmodel() -> None:
    text = VIEWMODEL.read_text()
    old = '''    fun setInterruptSpeechOnVisualChange(enabled: Boolean) = viewModelScope.launch {
        container.settingsRepository.setInterruptSpeechOnVisualChange(enabled)
    }
'''
    if old not in text:
        # 3.7 source may still carry the compact one-line form before finalize patches touch mode.
        old = '''    fun setInterruptSpeechOnVisualChange(enabled: Boolean) = viewModelScope.launch {
        container.settingsRepository.setInterruptSpeechOnVisualChange(enabled)
    }
'''
    new = '''    fun setInterruptSpeechOnVisualChange(enabled: Boolean) = viewModelScope.launch {
        container.settingsRepository.setInterruptSpeechOnVisualChange(enabled)
        message.value = if (enabled) {
            "تم تفعيل الانتقال الذكي بين الأهداف. لن يقطع الكلام إلا عند انتقال قوي ومؤكد."
        } else {
            "تم تعطيل القطع الذكي. سيستمر اكتشاف الأهداف الجديدة من دون قطع الكلام الجاري فوراً."
        }
    }
'''
    text = replace_once(text, old, new, "smart interruption feedback")
    VIEWMODEL.write_text(text)


patch_service()
patch_live()
patch_settings_ui()
patch_viewmodel()
print("Applied VisionBridge 3.8 Smart Target Interruption")
