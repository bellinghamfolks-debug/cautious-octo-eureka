#!/usr/bin/env python3
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SERVICE = ROOT / "app/src/main/java/com/abdullah/visionbridge/capture/MediaProjectionService.kt"
SETTINGS = ROOT / "app/src/main/java/com/abdullah/visionbridge/ui/SettingsScreen.kt"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    if old not in text:
        raise SystemExit(f"3.8.1 current-live patch failed: {label} anchor not found")
    return text.replace(old, new, 1)


service = SERVICE.read_text()
if "SMART_TARGET_SCENE_OBSERVER_RATE_V381" not in service:
    service = replace_once(
        service,
        "            cloudLive && settings.mode == AnalysisMode.SCENE_DESCRIPTION -> 1_000L\n",
        "            // SMART_TARGET_SCENE_OBSERVER_RATE_V381: sample locally for target switching at\n"
        "            // ~4 Hz. GeminiLiveSession still enforces its own 1 FPS transport limit, so this\n"
        "            // improves interruption reaction time without increasing model traffic.\n"
        "            cloudLive && settings.mode == AnalysisMode.SCENE_DESCRIPTION -> 260L\n",
        "scene observer capture rate",
    )
    SERVICE.write_text(service)

settings = SETTINGS.read_text()
if "CURRENT_LIVE_MODEL_ONLY_V381" not in settings:
    # Remove the old selectable-model surface completely. This product now has one cloud model;
    # a one-item dropdown would imply that older model paths still exist.
    model_pattern = re.compile(
        r'''\n                SectionTitle\("نموذج Gemini"\)\n                ExposedDropdownMenuBox\(.*?\n                \}\n\n                SectionTitle\("القراءة مع الوصف"\)''',
        re.DOTALL,
    )
    model_replacement = '''
                // CURRENT_LIVE_MODEL_ONLY_V381
                SectionTitle("Gemini Live")
                Text(
                    text = "Gemini 3.1 Flash Live هو النموذج السحابي الحالي الوحيد. لا توجد نماذج قديمة أو اختيار نموذج في هذا الإصدار.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics {
                        contentDescription =
                            "النموذج السحابي الحالي الوحيد: Gemini 3.1 Flash Live. لا توجد نماذج قديمة."
                    },
                )

                SectionTitle("القراءة مع الوصف")'''
    settings, count = model_pattern.subn(model_replacement, settings, count=1)
    if count != 1:
        raise SystemExit(f"3.8.1 current-live patch failed: model chooser removal count={count}")

    # The state variable exists only to drive the removed dropdown.
    settings = settings.replace(
        '    var modelMenuExpanded by remember { mutableStateOf(false) }\n',
        '',
        1,
    )
    SETTINGS.write_text(settings)

print("Applied current Gemini Live-only UI and fast Smart Target observer rate")
