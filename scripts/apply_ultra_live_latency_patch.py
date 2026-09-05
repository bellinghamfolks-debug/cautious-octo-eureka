#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MEDIA = ROOT / "app/src/main/java/com/abdullah/visionbridge/capture/MediaProjectionService.kt"
LIVE = ROOT / "app/src/main/java/com/abdullah/visionbridge/data/gemini/GeminiLiveSession.kt"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    if old not in text:
        raise SystemExit(f"latency patch failed: {label} anchor not found")
    return text.replace(old, new, 1)


def patch_media() -> None:
    text = MEDIA.read_text()
    if "ULTRA_LIVE_DIRECT_PIPELINE" in text:
        return

    text = replace_once(
        text,
        """        val settings = activeSettings\n        val now = System.currentTimeMillis()\n""",
        """        val settings = activeSettings\n        // ULTRA_LIVE_DIRECT_PIPELINE: cloud visual tasks do not wait behind the legacy\n        // stable-capture/registration stack. Local PP-OCR keeps that path unchanged.\n        val cloudLive = settings.mode == AnalysisMode.SCENE_DESCRIPTION || !settings.useLocalOcr\n        val now = System.currentTimeMillis()\n""",
        "cloud-live mode",
    )

    text = replace_once(
        text,
        """        val minimumInterval = when {\n            settings.mode == AnalysisMode.SCENE_DESCRIPTION -> SCENE_FRAME_INTERVAL_MS\n            settings.captureProfile == CaptureProfile.FAST_TEXT -> FAST_FRAME_INTERVAL_MS\n            else -> STABLE_FRAME_INTERVAL_MS\n        }\n""",
        """        val minimumInterval = when {\n            cloudLive && settings.mode == AnalysisMode.SCENE_DESCRIPTION -> 1_000L\n            cloudLive -> 120L\n            settings.mode == AnalysisMode.SCENE_DESCRIPTION -> SCENE_FRAME_INTERVAL_MS\n            settings.captureProfile == CaptureProfile.FAST_TEXT -> FAST_FRAME_INTERVAL_MS\n            else -> STABLE_FRAME_INTERVAL_MS\n        }\n""",
        "capture interval",
    )

    text = replace_once(
        text,
        """        val changeDecision = when {\n            settings.mode == AnalysisMode.SCENE_DESCRIPTION -> frameChangeDetector.evaluateFast(\n                bitmap = bitmap,\n                minimumMeanDifference = SCENE_MIN_MEAN_DIFFERENCE,\n                minimumChangedRatio = SCENE_MIN_CHANGED_RATIO,\n            )\n            settings.captureProfile == CaptureProfile.STABLE -> frameChangeDetector.evaluateStable(\n                bitmap = bitmap,\n                minimumMeanDifference = STABLE_MIN_MEAN_DIFFERENCE,\n                minimumChangedRatio = STABLE_MIN_CHANGED_RATIO,\n                stableForMs = STABLE_FRAME_DURATION_MS,\n                now = now,\n            )\n            else -> frameChangeDetector.evaluateFast(\n                bitmap = bitmap,\n                minimumMeanDifference = FAST_MIN_MEAN_DIFFERENCE,\n                minimumChangedRatio = FAST_MIN_CHANGED_RATIO,\n            )\n        }\n""",
        """        val cloudTextMeanThreshold =\n            if (settings.captureProfile == CaptureProfile.FAST_TEXT) 3.5 else 8.0\n        val cloudTextRatioThreshold =\n            if (settings.captureProfile == CaptureProfile.FAST_TEXT) 0.028 else 0.075\n        val changeDecision = when {\n            // Scene Live is sampled continuously at 1 FPS. The model's semantic/proactive gate,\n            // not local pixel motion, decides whether anything worth speaking actually changed.\n            cloudLive && settings.mode == AnalysisMode.SCENE_DESCRIPTION ->\n                frameChangeDetector.evaluateFast(bitmap, 0.0, 0.0)\n            // Cloud text gets the first changed frame immediately. No 900 ms settling window.\n            cloudLive -> frameChangeDetector.evaluateFast(\n                bitmap = bitmap,\n                minimumMeanDifference = cloudTextMeanThreshold,\n                minimumChangedRatio = cloudTextRatioThreshold,\n            )\n            settings.mode == AnalysisMode.SCENE_DESCRIPTION -> frameChangeDetector.evaluateFast(\n                bitmap = bitmap,\n                minimumMeanDifference = SCENE_MIN_MEAN_DIFFERENCE,\n                minimumChangedRatio = SCENE_MIN_CHANGED_RATIO,\n            )\n            settings.captureProfile == CaptureProfile.STABLE -> frameChangeDetector.evaluateStable(\n                bitmap = bitmap,\n                minimumMeanDifference = STABLE_MIN_MEAN_DIFFERENCE,\n                minimumChangedRatio = STABLE_MIN_CHANGED_RATIO,\n                stableForMs = STABLE_FRAME_DURATION_MS,\n                now = now,\n            )\n            else -> frameChangeDetector.evaluateFast(\n                bitmap = bitmap,\n                minimumMeanDifference = FAST_MIN_MEAN_DIFFERENCE,\n                minimumChangedRatio = FAST_MIN_CHANGED_RATIO,\n            )\n        }\n""",
        "change detector",
    )

    text = replace_once(
        text,
        """        val changeThresholds = when {\n            settings.mode == AnalysisMode.SCENE_DESCRIPTION -> Pair(\n                SCENE_MIN_MEAN_DIFFERENCE,\n                SCENE_MIN_CHANGED_RATIO,\n            )\n            settings.captureProfile == CaptureProfile.STABLE -> Pair(\n                STABLE_MIN_MEAN_DIFFERENCE,\n                STABLE_MIN_CHANGED_RATIO,\n            )\n            else -> Pair(FAST_MIN_MEAN_DIFFERENCE, FAST_MIN_CHANGED_RATIO)\n        }\n""",
        """        val changeThresholds = when {\n            cloudLive && settings.mode == AnalysisMode.SCENE_DESCRIPTION -> Pair(0.0, 0.0)\n            cloudLive -> Pair(cloudTextMeanThreshold, cloudTextRatioThreshold)\n            settings.mode == AnalysisMode.SCENE_DESCRIPTION -> Pair(\n                SCENE_MIN_MEAN_DIFFERENCE,\n                SCENE_MIN_CHANGED_RATIO,\n            )\n            settings.captureProfile == CaptureProfile.STABLE -> Pair(\n                STABLE_MIN_MEAN_DIFFERENCE,\n                STABLE_MIN_CHANGED_RATIO,\n            )\n            else -> Pair(FAST_MIN_MEAN_DIFFERENCE, FAST_MIN_CHANGED_RATIO)\n        }\n""",
        "diagnostic thresholds",
    )

    text = text.replace(
        '"requiredStableMs" to if (settings.captureProfile == CaptureProfile.STABLE) STABLE_FRAME_DURATION_MS else 0L,',
        '"requiredStableMs" to if (!cloudLive && settings.captureProfile == CaptureProfile.STABLE) STABLE_FRAME_DURATION_MS else 0L,',
        1,
    ).replace(
        '"thresholdLogic" to if (settings.captureProfile == CaptureProfile.STABLE) "AND" else "OR",',
        '"thresholdLogic" to if (cloudLive) "LIVE_DIRECT" else if (settings.captureProfile == CaptureProfile.STABLE) "AND" else "OR",',
        1,
    )

    text = replace_once(
        text,
        """        val scene = settings.mode == AnalysisMode.SCENE_DESCRIPTION\n        val tracker = if (scene) sceneTargetTracker else textTargetTracker\n""",
        """        val scene = settings.mode == AnalysisMode.SCENE_DESCRIPTION\n\n        if (cloudLive) {\n            // The old tracker is excellent for protecting a long local OCR read, but a field log\n            // measured ~0.5 s median and multi-second worst cases before the cloud even saw a frame.\n            // Live already has a semantic scene gate, so running homography/SSIM first duplicates\n            // the expensive decision. Text uses the cheap coarse change gate above instead.\n            val visualTargetChanged = !scene\n            if (visualTargetChanged) {\n                container.coordinator.onVisualTargetChanged(settings.interruptSpeechOnVisualChange)\n            }\n            DiagnosticHub.record(\n                "VISUAL_TARGET_DECISION",\n                trace.fields(\n                    mapOf(\n                        "targetChanged" to visualTargetChanged,\n                        "decisionReason" to if (scene) "cloud_live_semantic_gate" else "cloud_live_coarse_text_change",\n                        "targetTrackId" to null,\n                        "registrationMethod" to "BYPASSED_FOR_LIVE",\n                        "trackingMs" to 0.0,\n                        "cloudLiveDirect" to true,\n                    ),\n                ),\n            )\n            DiagnosticHub.frame(\n                bitmap = view,\n                frameId = frameId,\n                stage = "selected_input",\n                metadata = trace.fields(\n                    mapOf(\n                        "mode" to settings.mode.name,\n                        "captureProfile" to settings.captureProfile.name,\n                        "targetChanged" to visualTargetChanged,\n                        "cloudLiveDirect" to true,\n                        "frameMeanAbsoluteDifference" to changeDecision.meanAbsoluteDifference,\n                        "frameChangedPixelRatio" to changeDecision.changedPixelRatio,\n                    ),\n                ),\n            )\n            DiagnosticHub.record("FRAME_SELECTED_FOR_ANALYSIS", trace.fields(mapOf("cloudLiveDirect" to true)))\n            submitLatestFrame(PendingFrame(view, trace))\n            return\n        }\n\n        val tracker = if (scene) sceneTargetTracker else textTargetTracker\n""",
        "tracker bypass",
    )

    MEDIA.write_text(text)


def patch_live() -> None:
    text = LIVE.read_text()
    if "ULTRA_LOW_LATENCY_THINKING" in text:
        return

    text = replace_once(
        text,
        """    private data class LiveProfile(\n        val model: String,\n        val proactiveAudio: Boolean,\n        val semanticSceneGate: Boolean,\n    )\n""",
        """    private data class LiveProfile(\n        val model: String,\n        val proactiveAudio: Boolean,\n        val semanticSceneGate: Boolean,\n        val mediaResolution: String,\n        val thinkingLevel: String? = null,\n        val thinkingBudget: Int? = null,\n    )\n""",
        "live profile",
    )

    text = text.replace(
        'val fingerprint = fingerprint("$apiKey|${profile.model}|${profile.proactiveAudio}")',
        'val fingerprint = fingerprint("$apiKey|${profile.model}|${profile.proactiveAudio}|${profile.mediaResolution}|${profile.thinkingLevel}|${profile.thinkingBudget}")',
        1,
    )

    text = replace_once(
        text,
        """            put("generationConfig", buildJsonObject {\n                put("responseModalities", buildJsonArray { add(JsonPrimitive("AUDIO")) })\n                put("mediaResolution", "MEDIA_RESOLUTION_MEDIUM")\n            })\n""",
        """            put("generationConfig", buildJsonObject {\n                put("responseModalities", buildJsonArray { add(JsonPrimitive("AUDIO")) })\n                put("mediaResolution", profile.mediaResolution)\n                // ULTRA_LOW_LATENCY_THINKING: 2.5 Live otherwise uses dynamic thinking. Scene\n                // change detection is intentionally simple, so spending reasoning tokens here only\n                // adds seconds. Gemini 3.1 uses MINIMAL for the same latency-first policy.\n                put("thinkingConfig", buildJsonObject {\n                    profile.thinkingLevel?.let { put("thinkingLevel", it) }\n                    profile.thinkingBudget?.let { put("thinkingBudget", it) }\n                })\n                put("speechConfig", buildJsonObject { put("languageCode", "ar-XA") })\n            })\n""",
        "generation config",
    )

    text = replace_once(
        text,
        """        AnalysisMode.TEXT_READING -> LiveProfile(\n            model = TEXT_LIVE_MODEL,\n            proactiveAudio = false,\n            semanticSceneGate = false,\n        )\n        AnalysisMode.SCENE_DESCRIPTION -> LiveProfile(\n            model = SCENE_SEMANTIC_LIVE_MODEL,\n            proactiveAudio = true,\n            semanticSceneGate = true,\n        )\n""",
        """        AnalysisMode.TEXT_READING -> LiveProfile(\n            model = TEXT_LIVE_MODEL,\n            proactiveAudio = false,\n            semanticSceneGate = false,\n            mediaResolution = "MEDIA_RESOLUTION_MEDIUM",\n            thinkingLevel = "MINIMAL",\n        )\n        AnalysisMode.SCENE_DESCRIPTION -> LiveProfile(\n            model = SCENE_SEMANTIC_LIVE_MODEL,\n            proactiveAudio = true,\n            semanticSceneGate = true,\n            mediaResolution = "MEDIA_RESOLUTION_LOW",\n            thinkingBudget = 0,\n        )\n""",
        "profile latency config",
    )

    LIVE.write_text(text)


patch_media()
patch_live()
print("Applied VisionBridge ultra-low-latency Live patch")
