#!/usr/bin/env bash
set -euo pipefail
python3 scripts/verify_project.py
./gradlew --no-daemon lintDebug testDebugUnitTest assembleDebug
printf '\nAPK: app/build/outputs/apk/debug/app-debug.apk\n'
