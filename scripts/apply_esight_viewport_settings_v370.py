#!/usr/bin/env python3
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SERVICE = ROOT / "app/src/main/java/com/abdullah/visionbridge/capture/MediaProjectionService.kt"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    if old not in text:
        raise SystemExit(f"3.7 eSight/settings patch failed: {label} anchor not found")
    return text.replace(old, new, 1)


text = SERVICE.read_text()
if "ESIGHT_FIXED_VIEWPORT_V370" not in text:
    text = replace_once(
        text,
        "import com.abdullah.visionbridge.capture.vision.Viewport\n",
        "import com.abdullah.visionbridge.capture.vision.Viewport\nimport com.abdullah.visionbridge.capture.vision.EsightViewportCalibration\n",
        "eSight calibration import",
    )
    text = replace_once(
        text,
        "import com.abdullah.visionbridge.domain.model.CaptureProfile\n",
        "import com.abdullah.visionbridge.domain.model.CaptureProfile\nimport com.abdullah.visionbridge.domain.model.ViewportMode\n",
        "viewport mode import",
    )

    collector_anchor = '''                val evidenceChanged =
                    newSettings.captureFailureEvidence != activeSettings.captureFailureEvidence
                activeSettings = newSettings
'''
    collector_replacement = '''                val evidenceChanged =
                    newSettings.captureFailureEvidence != activeSettings.captureFailureEvidence
                val viewportPolicyChanged =
                    newSettings.viewportMode != activeSettings.viewportMode ||
                        newSettings.mode != activeSettings.mode
                if (viewportPolicyChanged) {
                    DiagnosticHub.record(
                        "VIEWPORT_POLICY_CHANGED",
                        mapOf(
                            "oldViewportMode" to activeSettings.viewportMode.name,
                            "newViewportMode" to newSettings.viewportMode.name,
                            "oldAnalysisMode" to activeSettings.mode.name,
                            "newAnalysisMode" to newSettings.mode.name,
                        ),
                    )
                    activeViewport = null
                    lastViewportProbeAtElapsedMs = 0L
                }
                activeSettings = newSettings
'''
    text = replace_once(text, collector_anchor, collector_replacement, "settings viewport policy reset")

    # The 3.6.2 accuracy patch altered this function after source checkout. Replace the complete
    # generated function so no content-driven horizontal crop can survive underneath the fixed
    # eSight policy.
    crop_pattern = re.compile(
        r'''    private fun cropToViewport\(source: Bitmap, trace: DiagnosticTrace\): Bitmap \{.*?\n    \}\n\n    private fun settingsMap''',
        re.DOTALL,
    )
    crop_replacement = '''    /**
     * Crops the mirrored screen using the policy the user actually selected.
     *
     * ESIGHT_TEXT_SAFE deliberately does not inspect the photographed content at all while reading
     * text. The real Share Your View geometry is stable even when the camera points at a dark room,
     * a white page or a dense energy label, so content must never be allowed to move the crop.
     */
    private fun cropToViewport(source: Bitmap, trace: DiagnosticTrace): Bitmap {
        val mode = activeSettings.viewportMode
        val analysisMode = activeSettings.mode
        val wantsFixed = mode == ViewportMode.ESIGHT_FIXED ||
            (mode == ViewportMode.ESIGHT_TEXT_SAFE && analysisMode == AnalysisMode.TEXT_READING)
        val calibrated = EsightViewportCalibration.rectFor(
            mode = mode,
            analysisMode = analysisMode,
            width = source.width,
            height = source.height,
        )

        val rect: Viewport.Rect? = if (calibrated != null) {
            val changed = activeViewport != calibrated
            activeViewport = calibrated
            if (changed) {
                DiagnosticHub.record(
                    "ESIGHT_VIEWPORT_APPLIED",
                    trace.fields(
                        calibrated.fields() +
                            EsightViewportCalibration.pixelFields(source.width, source.height) +
                            mapOf(
                                "viewportMode" to mode.name,
                                "analysisMode" to analysisMode.name,
                                "sourceWidth" to source.width,
                                "sourceHeight" to source.height,
                                "sourceAspect" to source.width.toDouble() / source.height.toDouble(),
                                "secondaryContentCrop" to false,
                                "calibration" to "REAL_ESIGHT_SHARE_VIEW_1356x610",
                                "accuracyPolicy" to "FIXED_GEOMETRY_NOT_CONTENT",
                            ),
                    ),
                )
            }
            calibrated
        } else {
            if (wantsFixed) {
                DiagnosticHub.record(
                    "ESIGHT_VIEWPORT_FALLBACK_AUTO",
                    trace.fields(
                        mapOf(
                            "viewportMode" to mode.name,
                            "analysisMode" to analysisMode.name,
                            "sourceWidth" to source.width,
                            "sourceHeight" to source.height,
                            "sourceAspect" to source.width.toDouble() / source.height.toDouble(),
                            "reason" to "capture_aspect_outside_calibration",
                        ),
                    ),
                )
            }

            val now = SystemClock.elapsedRealtime()
            if (now - lastViewportProbeAtElapsedMs >= VIEWPORT_PROBE_INTERVAL_MS) {
                lastViewportProbeAtElapsedMs = now
                val measured = runCatching { Viewport.detect(BitmapFrames.aspectPlane(source)) }
                    .onFailure { DiagnosticHub.failure("VIEWPORT_DETECTION", it, trace.fields()) }
                    .getOrNull()
                if (measured != activeViewport) {
                    DiagnosticHub.record(
                        "VIEWPORT_CHANGED",
                        trace.fields(
                            (measured?.fields() ?: mapOf("viewportArea" to null)) + mapOf(
                                "applied" to (measured != null),
                                "sourceWidth" to source.width,
                                "sourceHeight" to source.height,
                                "viewportMode" to mode.name,
                                "analysisMode" to analysisMode.name,
                            ),
                        ),
                    )
                }
                activeViewport = measured
            }
            activeViewport
        }

        val applied = rect ?: return source
        val left = (applied.left * source.width).toInt().coerceIn(0, source.width - 1)
        val top = (applied.top * source.height).toInt().coerceIn(0, source.height - 1)
        val rightExclusive = (applied.right * source.width).toInt().coerceIn(left + 1, source.width)
        val bottomExclusive = (applied.bottom * source.height).toInt().coerceIn(top + 1, source.height)
        val width = rightExclusive - left
        val height = bottomExclusive - top
        if (left == 0 && top == 0 && width == source.width && height == source.height) return source

        return runCatching {
            Bitmap.createBitmap(source, left, top, width, height).also { cropped ->
                if (cropped !== source) source.recycle()
            }
        }.getOrElse {
            DiagnosticHub.failure(
                "VIEWPORT_CROP",
                it,
                trace.fields(
                    mapOf(
                        "viewportMode" to mode.name,
                        "analysisMode" to analysisMode.name,
                        "leftPx" to left,
                        "topPx" to top,
                        "widthPx" to width,
                        "heightPx" to height,
                    ),
                ),
            )
            source
        }
    }

    private fun settingsMap'''
    text, count = crop_pattern.subn(crop_replacement, text, count=1)
    if count != 1:
        raise SystemExit(f"3.7 eSight/settings patch failed: cropToViewport replacement count={count}")

    # A new projection must announce/re-apply its viewport even if the previous capture used the
    # same policy.
    reset_anchor = '''        frameChangeDetector.reset()
        textTargetTracker.reset()
        sceneTargetTracker.reset()
'''
    reset_replacement = '''        frameChangeDetector.reset()
        textTargetTracker.reset()
        sceneTargetTracker.reset()
        activeViewport = null
        lastViewportProbeAtElapsedMs = 0L
'''
    text = replace_once(text, reset_anchor, reset_replacement, "reset viewport state")

    settings_anchor = '''        "sceneDescriptionStyle" to settings.sceneDescriptionStyle.name,
        "captureFailureEvidence" to settings.captureFailureEvidence,
'''
    settings_replacement = '''        "sceneDescriptionStyle" to settings.sceneDescriptionStyle.name,
        "describeAlongsideText" to settings.describeAlongsideText,
        "viewportMode" to settings.viewportMode.name,
        "liveAccuracyGuard" to true,
        "legacyCloudFallbackAllowed" to false,
        "captureFailureEvidence" to settings.captureFailureEvidence,
'''
    # 3.6.1 may already have inserted describeAlongsideText earlier in this map. Avoid a duplicate.
    if '"viewportMode" to settings.viewportMode.name' not in text:
        if '"describeAlongsideText" to settings.describeAlongsideText' in text:
            settings_replacement = '''        "sceneDescriptionStyle" to settings.sceneDescriptionStyle.name,
        "viewportMode" to settings.viewportMode.name,
        "liveAccuracyGuard" to true,
        "legacyCloudFallbackAllowed" to false,
        "captureFailureEvidence" to settings.captureFailureEvidence,
'''
        text = replace_once(text, settings_anchor, settings_replacement, "settings diagnostics")

    # Marker kept in generated source so the script is idempotent across lint/test/assemble tasks.
    text = text.replace(
        "class MediaProjectionService : Service() {",
        "// ESIGHT_FIXED_VIEWPORT_V370\nclass MediaProjectionService : Service() {",
        1,
    )
    SERVICE.write_text(text)

print("Applied VisionBridge 3.7 eSight fixed viewport and verified settings runtime patch")
