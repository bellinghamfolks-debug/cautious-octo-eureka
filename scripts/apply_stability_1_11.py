#!/usr/bin/env python3
"""Apply the generated VisionBridge 1.11.0 stability implementation."""
from __future__ import annotations

import base64
import gzip
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PAYLOADS = {
    "scripts/stability-1.11.0/coordinator.kt.gz.b64":
        "app/src/main/java/com/abdullah/visionbridge/capture/FrameAnalysisCoordinator.kt",
    "scripts/stability-1.11.0/tts.kt.gz.b64":
        "app/src/main/java/com/abdullah/visionbridge/data/speech/BilingualTtsEngine.kt",
}


def main() -> None:
    for payload_path, destination_path in PAYLOADS.items():
        encoded = (ROOT / payload_path).read_text(encoding="ascii").strip()
        content = gzip.decompress(base64.b64decode(encoded))
        destination = ROOT / destination_path
        destination.parent.mkdir(parents=True, exist_ok=True)
        if not destination.exists() or destination.read_bytes() != content:
            destination.write_bytes(content)

    gradle = ROOT / "app/build.gradle.kts"
    text = gradle.read_text(encoding="utf-8")
    text = text.replace("versionCode = 13", "versionCode = 14")
    text = text.replace('versionName = "1.10.1"', 'versionName = "1.11.0"')
    gradle.write_text(text, encoding="utf-8")


if __name__ == "__main__":
    main()
