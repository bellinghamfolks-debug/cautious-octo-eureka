#!/usr/bin/env python3
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SERVICE = ROOT / "app/src/main/java/com/abdullah/visionbridge/capture/MediaProjectionService.kt"
LIVE = ROOT / "app/src/main/java/com/abdullah/visionbridge/data/gemini/GeminiLiveSession.kt"
SCREEN = ROOT / "app/src/main/java/com/abdullah/visionbridge/ui/SettingsScreen.kt"
VIEWMODEL = ROOT / "app/src/main/java/com/abdullah/visionbridge/ui/MainViewModel.kt"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    if old not in text:
        raise SystemExit(f"3.8 smart-target patch failed: {label} anchor not found")
    return text.replace(old, new, 1)


# --------------------------- capture / target policy ---------------------------
service = SERVICE.read_text()
if "SMART_TARGET_INTERRUPTION_V380" not in service:
    service = replace_once(
        service,
        "    private val frameChangeDetector = FrameChangeDetector()\n",
        "    private val frameChangeDetector = FrameChangeDetector()\n" +
        "    private val smartTargetChangeGate = SmartTargetChangeGate()\n",
        "smart target gate field",
    )

    cloud_pattern = re.compile(
        r'''        if \(cloudLive\) \{\n            // Do not call onVisualTargetChanged here\..*?            submitLatestFrame\(PendingFrame\(view, trace\)\)\n            return\n        \}\n\n        val tracker =''',
        re.DOTALL,
    )
    cloud_replacement = '''        if (cloudLive) {
            // SMART_TARGET_INTERRUPTION_V380: Live still keeps the cheap one-active/one-latest
            // analysis lane, but target identity is no longer bypassed. A tiny perceptual signature
            // runs only on the already-cropped eSight camera viewport and requires temporal
            // agreement before it can retire speech.
            val smartTarget = smartTargetChangeGate.evaluate(view, settings.mode)
            val confirmedNewTarget = smartTarget.action != SmartTargetChangeGate.Action.KEEP
            val interruptNow = settings.interruptSpeechOnVisualChange &&
                smartTarget.action == SmartTargetChangeGate.Action.INTERRUPT
            DiagnosticHub.record(
                "SMART_TARGET_DECISION",
                trace.fields(
                    mapOf(
                        "route" to "GEMINI_LIVE",
                        "action" to smartTarget.action.name,
                        "reason" to smartTarget.reason,
                        "distance" to smartTarget.distance,
                        "candidateDistance" to smartTarget.candidateDistance,
                        "candidateFrames" to smartTarget.candidateFrames,
                        "candidateAgeMs" to smartTarget.candidateAgeMs,
                        "smartInterruptionEnabled" to settings.interruptSpeechOnVisualChange,
                        "interruptNow" to interruptNow,
                    ),
                ),
            )
            if (confirmedNewTarget) {
                // false still advances visual generation and invalidates stale model output; it only
                // means an already-audible sentence is allowed to finish.
                container.coordinator.onVisualTargetChanged(interruptNow)
            }

            DiagnosticHub.record(
                "VISUAL_TARGET_DECISION",
                trace.fields(
                    mapOf(
                        "targetChanged" to confirmedNewTarget,
                        "decisionReason" to smartTarget.reason,
                        "targetTrackId" to null,
                        "registrationMethod" to "SMART_LIVE_SIGNATURE_V380",
                        "trackingMs" to 0.0,
                        "cloudLiveDirect" to true,
                        "backpressure" to "ONE_ACTIVE_ONE_LATEST",
                        "smartAction" to smartTarget.action.name,
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
                        "targetChanged" to confirmedNewTarget,
                        "cloudLiveDirect" to true,
                        "smartAction" to smartTarget.action.name,
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
                        "backpressure" to "ONE_ACTIVE_ONE_LATEST",
                        "smartAction" to smartTarget.action.name,
                    ),
                ),
            )
            submitLatestFrame(PendingFrame(view, trace))
            return
        }

        val tracker ='''
    service, count = cloud_pattern.subn(cloud_replacement, service, count=1)
    if count != 1:
        raise SystemExit(f"3.8 smart-target patch failed: cloud Live block replacement count={count}")

    old_local = '''        if (visualTargetChanged) {
            container.coordinator.onVisualTargetChanged(settings.interruptSpeechOnVisualChange)
        }
'''
    new_local = '''        if (visualTargetChanged) {
            val smartAction = SmartTargetChangeGate.actionForTrackedTarget(targetDecision)
            val interruptNow = settings.interruptSpeechOnVisualChange &&
                smartAction == SmartTargetChangeGate.Action.INTERRUPT
            DiagnosticHub.record(
                "SMART_TARGET_DECISION",
                trace.fields(
                    mapOf(
                        "route" to "LOCAL_PPOCR",
                        "action" to smartAction.name,
                        "reason" to targetDecision.reason,
                        "structuralDissimilarity" to targetDecision.dissimilarity,
                        "chromaDifference" to targetDecision.chromaDifference,
                        "alignmentCoverage" to targetDecision.coverage,
                        "smartInterruptionEnabled" to settings.interruptSpeechOnVisualChange,
                        "interruptNow" to interruptNow,
                    ),
                ),
            )
            if (smartAction != SmartTargetChangeGate.Action.KEEP) {
                container.coordinator.onVisualTargetChanged(interruptNow)
            }
        }
'''
    service = replace_once(service, old_local, new_local, "local smart interruption")

    # Mode / crop geometry changes start a fresh identity space.
    viewport_reset = '''                    activeViewport = null
                    lastViewportProbeAtElapsedMs = 0L
                }
                activeSettings = newSettings
'''
    viewport_reset_new = '''                    activeViewport = null
                    lastViewportProbeAtElapsedMs = 0L
                    smartTargetChangeGate.reset()
                }
                activeSettings = newSettings
'''
    service = replace_once(service, viewport_reset, viewport_reset_new, "viewport smart target reset")

    reset_anchor = '''        textTargetTracker.reset()
        sceneTargetTracker.reset()
'''
    reset_new = '''        textTargetTracker.reset()
        sceneTargetTracker.reset()
        smartTargetChangeGate.reset()
'''
    service = replace_once(service, reset_anchor, reset_new, "capture smart target reset")

    if '"smartTargetInterruption" to settings.interruptSpeechOnVisualChange' not in service:
        service = service.replace(
            '        "interruptSpeechOnVisualChange" to settings.interruptSpeechOnVisualChange,\n',
            '        "interruptSpeechOnVisualChange" to settings.interruptSpeechOnVisualChange,\n' +
            '        "smartTargetInterruption" to settings.interruptSpeechOnVisualChange,\n',
            1,
        )
    SERVICE.write_text(service)


# --------------------------- Live stale-turn suppression ---------------------------
live = LIVE.read_text()
if "SMART_LIVE_STALE_TURN_V380" not in live:
    live = replace_once(
        live,
        "    @Volatile private var activeResponseMode: AnalysisMode? = null\n",
        "    @Volatile private var activeResponseMode: AnalysisMode? = null\n" +
        "    @Volatile private var activeResponseGeneration = Long.MIN_VALUE\n",
        "active response generation field",
    )
    live = replace_once(
        live,
        "            activeResponseMode = settings.mode\n",
        "            activeResponseMode = settings.mode\n            activeResponseGeneration = generation\n",
        "capture active response generation",
    )

    method_pattern = re.compile(
        r'''    fun onVisualTargetChanged\(interruptSpeech: Boolean\) \{.*?\n    \}\n\n    fun reset\(\)''',
        re.DOTALL,
    )
    method_replacement = '''    fun onVisualTargetChanged(interruptSpeech: Boolean) {
        visualGeneration += 1L
        synchronized(sendLock) { lastFrameSentAtElapsedMs = 0L }
        synchronized(transcriptLock) { transcript = StringBuilder() }

        if (interruptSpeech) {
            // SMART_LIVE_STALE_TURN_V380: retire only speech that is already audible. Do not close
            // the WebSocket and do not cancel the active Gemini turn. The turn boundary releases
            // backpressure normally, while the generation check below prevents stale text from ever
            // being spoken as the new target.
            tts.supersedeLiveSpeech("smart_target_changed")
            audioPlayer.interrupt("smart_target_changed")
        }
        DiagnosticHub.record(
            "LIVE_VISUAL_TARGET_CHANGED",
            mapOf(
                "smartTargetInterruption" to true,
                "interruptSpeech" to interruptSpeech,
                "activeResponseGeneration" to activeResponseGeneration,
                "visualGeneration" to visualGeneration,
                "socketPreserved" to true,
                "activeTurnCancelled" to false,
            ),
        )
    }

    fun reset()'''
    live, count = method_pattern.subn(method_replacement, live, count=1)
    if count != 1:
        raise SystemExit(f"3.8 smart-target patch failed: Live target method replacement count={count}")

    # 3.6.2 has already produced finalText and scene before its sentinel handling. Drop a response
    # that belongs to the target we just left, but let Gemini finish the turn so the persistent
    # socket stays healthy and the service can promote its one latest frame.
    stale_anchor = '''            val textNoReliableContent = !scene && (
                finalText.trim().equals("NO_TEXT", ignoreCase = true) ||
                    finalText.trim().equals("NO_CHANGE", ignoreCase = true)
            )
'''
    stale_replacement = '''            val staleTargetTurn = activeResponseGeneration != visualGeneration
            if (staleTargetTurn) {
                DiagnosticHub.record(
                    "LIVE_STALE_TURN_SUPPRESSED",
                    mapOf(
                        "capturedGeneration" to activeResponseGeneration,
                        "currentGeneration" to visualGeneration,
                        "charactersDiscarded" to finalText.length,
                        "mode" to activeResponseMode?.name,
                        "socketPreserved" to true,
                    ),
                )
                if (scene) {
                    synchronized(sendLock) {
                        sceneProbeOutstanding = false
                        sceneProbeStartedAtElapsedMs = 0L
                    }
                }
                completeActiveTurn(true, "stale_target_turn_complete")
                return
            }

            val textNoReliableContent = !scene && (
                finalText.trim().equals("NO_TEXT", ignoreCase = true) ||
                    finalText.trim().equals("NO_CHANGE", ignoreCase = true)
            )
'''
    live = replace_once(live, stale_anchor, stale_replacement, "stale Live turn suppression")

    # Reset the captured generation with the rest of Live state.
    live = live.replace(
        "        activeResponseMode = null\n        responseInFlight = false\n",
        "        activeResponseMode = null\n        activeResponseGeneration = Long.MIN_VALUE\n        responseInFlight = false\n",
        2,
    )
    LIVE.write_text(live)


# --------------------------- settings surface ---------------------------
screen = SCREEN.read_text()
if "SMART_TARGET_SETTINGS_V380" not in screen:
    # The underlying 3.7 repository already forced these retired switches off, but the old Compose
    # rows were still visible. Remove the misleading controls instead of leaving placebo settings.
    screen = re.sub(
        r'''\n                SectionTitle\("نموذج Gemini"\).*?\n                SectionTitle\("القراءة مع الوصف"\)''',
        '\n                SectionTitle("القراءة مع الوصف")',
        screen,
        count=1,
        flags=re.DOTALL,
    )
    screen = re.sub(
        r'''\n                    AccessibleSwitchRow\(\n                        title = "التحقق من موثوقية OCR".*?\n                    \)''',
        '',
        screen,
        count=1,
        flags=re.DOTALL,
    )
    screen = re.sub(
        r'''\n                AccessibleSwitchRow\(\n                    title = "استخدام بيانات الجوال لطلبات Gemini".*?\n                \)''',
        '',
        screen,
        count=1,
        flags=re.DOTALL,
    )

    old_switch = '''                AccessibleSwitchRow(
                    title = "إيقاف النطق عند تغيّر المحتوى",
                    description = "يوقف النتيجة الحالية عند الانتقال إلى محتوى مختلف، ثم ينطق أحدث نتيجة.",
                    checked = state.settings.interruptSpeechOnVisualChange,
                    onCheckedChange = onInterruptSpeechChange,
                )
'''
    new_switch = '''                // SMART_TARGET_SETTINGS_V380
                AccessibleSwitchRow(
                    title = "الانتقال الذكي بين الأهداف",
                    description = if (state.settings.interruptSpeechOnVisualChange) {
                        "يتجاهل اهتزاز النظارة والتقريب وتغيّر الإضاءة. عند تأكيد هدف مختلف، يسمح للتغيّر المتوسط بإنهاء الجملة الحالية، ويقطع الكلام فوراً فقط عند تغيّر قوي ومؤكد."
                    } else {
                        "يستمر VisionBridge في اكتشاف الهدف الجديد ومنع النتائج القديمة، لكنه لا يقطع جملة تُنطق حالياً."
                    },
                    checked = state.settings.interruptSpeechOnVisualChange,
                    onCheckedChange = onInterruptSpeechChange,
                )
'''
    screen = replace_once(screen, old_switch, new_switch, "smart target settings row")
    SCREEN.write_text(screen)


vm = VIEWMODEL.read_text()
old_vm = '''    fun setInterruptSpeechOnVisualChange(enabled: Boolean) = viewModelScope.launch {
        container.settingsRepository.setInterruptSpeechOnVisualChange(enabled)
    }
'''
new_vm = '''    fun setInterruptSpeechOnVisualChange(enabled: Boolean) = viewModelScope.launch {
        container.settingsRepository.setInterruptSpeechOnVisualChange(enabled)
        message.value = if (enabled) {
            "تم تفعيل الانتقال الذكي بين الأهداف"
        } else {
            "تم إيقاف قطع الكلام الذكي. سيبقى منع النتائج القديمة مفعلاً"
        }
        DiagnosticHub.record("SMART_TARGET_SETTING_CHANGED", mapOf("enabled" to enabled))
    }
'''
if old_vm in vm:
    vm = vm.replace(old_vm, new_vm, 1)
VIEWMODEL.write_text(vm)

print("Applied VisionBridge 3.8 smart target interruption and stale-turn suppression")
