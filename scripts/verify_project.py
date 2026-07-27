#!/usr/bin/env python3
"""Fast repository checks that run before the expensive Android build."""
from __future__ import annotations

import pathlib
import re
import sys
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[1]
REQUIRED = [
    "settings.gradle.kts",
    "build.gradle.kts",
    "app/build.gradle.kts",
    "app/src/main/AndroidManifest.xml",
    "app/src/main/java/com/abdullah/visionbridge/capture/MediaProjectionService.kt",
    "app/src/main/java/com/abdullah/visionbridge/data/gemini/GeminiVisionRepository.kt",
    ".github/workflows/android-ci.yml",
    "AGENTS.md",
]

errors: list[str] = []
for relative in REQUIRED:
    if not (ROOT / relative).is_file():
        errors.append(f"Missing required file: {relative}")

for xml_file in (ROOT / "app/src/main/res").rglob("*.xml"):
    try:
        ET.parse(xml_file)
    except ET.ParseError as exc:
        errors.append(f"Invalid XML {xml_file.relative_to(ROOT)}: {exc}")

manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
for permission in (
    "android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION",
    "android.permission.INTERNET",
    "android.permission.ACCESS_NETWORK_STATE",
):
    if permission not in manifest:
        errors.append(f"Manifest lacks {permission}")
if 'android:foregroundServiceType="mediaProjection"' not in manifest:
    errors.append("MediaProjection service type is not declared")

# Catch accidental committed Google API keys. This pattern is intentionally strict.
key_pattern = re.compile(r"AIza[0-9A-Za-z_-]{30,}")
for file in ROOT.rglob("*"):
    if not file.is_file() or ".git" in file.parts or file.suffix in {".jar", ".png", ".zip"}:
        continue
    try:
        text = file.read_text(encoding="utf-8")
    except UnicodeDecodeError:
        continue
    if key_pattern.search(text):
        errors.append(f"Possible committed Google API key in {file.relative_to(ROOT)}")

if errors:
    print("Repository verification failed:", file=sys.stderr)
    for error in errors:
        print(f" - {error}", file=sys.stderr)
    raise SystemExit(1)
print("Repository structure and secret scan passed.")
