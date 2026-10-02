#!/usr/bin/env bash
# Builds and tests Maqam Studio on macOS, then packages an unsigned IPA.
# Used by GitHub Actions and Codemagic so both run exactly the same steps.
#
#   scripts/ci-ios.sh            generate, build, test, package
#   SKIP_TESTS=1 scripts/ci-ios.sh   generate, build, package
set -euo pipefail

cd "$(dirname "$0")/.."
OUT="${OUT:-build/ci}"
mkdir -p "$OUT"

command -v xcodegen >/dev/null || brew install xcodegen
xcodebuild -version
xcodegen generate

# The newest available iPhone simulator, whatever this Xcode ships with.
SIMULATOR_ID="$(xcrun simctl list devices available -j | python3 -c '
import json, sys
runtimes = json.load(sys.stdin)["devices"]
best = None
for runtime, devices in runtimes.items():
    if "iOS" not in runtime:
        continue
    version = tuple(int(p) for p in runtime.rsplit("iOS-", 1)[-1].split("-") if p.isdigit())
    for device in devices:
        if device["name"].startswith("iPhone") and (best is None or version > best[0]):
            best = (version, device["udid"], device["name"])
print(best[1] if best else "")
')"
if [[ -z "$SIMULATOR_ID" ]]; then
  echo "No iPhone simulator available" >&2
  exit 1
fi
echo "Simulator: $SIMULATOR_ID"

# Crash reports from before this run, so only new ones are shown on failure.
REPORTS="$HOME/Library/Logs/DiagnosticReports"
STAMP="$OUT/.started"
touch "$STAMP"

# When the app crashes (e.g. "crashed with signal abrt before establishing
# connection"), xcodebuild says only that; the reason and the crashing thread
# are in the simulator's crash report.
print_crash_reports() {
  local found=0
  while IFS= read -r report; do
    found=1
    echo "----- crash report: $report" >&2
    python3 - "$report" >&2 <<'PY' || head -150 "$report" >&2
import json, sys
text = open(sys.argv[1], encoding="utf-8", errors="replace").read()
header, _, body = text.partition("\n")
info = json.loads(body)
print("exception:", json.dumps(info.get("exception")))
print("termination:", json.dumps(info.get("termination")))
for key in ("asi", "lastExceptionBacktrace", "ktriageinfo"):
    if key in info:
        print(key + ":", json.dumps(info[key])[:4000])
images = info.get("usedImages", [])
threads = info.get("threads", [])
crashed = next((t for t in threads if t.get("triggered")), threads[0] if threads else {})
print("crashed thread:", crashed.get("name", ""), crashed.get("queue", ""))
for frame in crashed.get("frames", [])[:40]:
    image = images[frame["imageIndex"]]["name"] if frame.get("imageIndex", -1) < len(images) else "?"
    print("  ", image, frame.get("symbol", hex(frame.get("imageOffset", 0))), frame.get("sourceFile", ""), frame.get("sourceLine", ""))
PY
  done < <(find "$REPORTS" -maxdepth 2 \( -name 'MaqamStudio*.ips' -o -name 'xctest*.ips' \) -newer "$STAMP" 2>/dev/null | head -3)
  if [[ $found == 0 ]]; then echo "(no new crash reports in $REPORTS)" >&2; fi

  # The app's own last words: an uncaught exception's reason, a Swift fatal
  # error message, or a dyld failure, from the simulator's unified log.
  echo "----- simulator log for MaqamStudio (last 10 minutes)" >&2
  xcrun simctl spawn "$SIMULATOR_ID" log show --last 10m --style compact \
    --predicate 'process == "MaqamStudio" AND (messageType == error OR messageType == fault OR eventMessage CONTAINS[c] "exception" OR eventMessage CONTAINS[c] "fatal" OR eventMessage CONTAINS[c] "abort" OR eventMessage CONTAINS[c] "dyld")' \
    2>&1 | tail -80 >&2 || true

  # Diagnostics xcodebuild kept in the result bundle (stdout/stderr, crash logs).
  local diagnostics="$OUT/diagnostics"
  rm -rf "$diagnostics"
  if xcrun xcresulttool export diagnostics --path "$OUT/Tests.xcresult" --output-path "$diagnostics" >/dev/null 2>&1; then
    while IFS= read -r file; do
      echo "----- $file" >&2
      tail -60 "$file" >&2
    done < <(find "$diagnostics" -type f \( -name '*.crash' -o -name '*.ips' -o -name '*StandardOutputAndStandardError*' -o -name '*.txt' \) 2>/dev/null | head -8)
  else
    echo "(could not export diagnostics from the result bundle)" >&2
  fi
  (cd "$OUT" && zip -qry Tests.xcresult.zip Tests.xcresult) || true
}

if [[ "${SKIP_TESTS:-0}" != "1" ]]; then
  xcodebuild test \
    -project MaqamStudio.xcodeproj \
    -scheme MaqamStudio \
    -destination "id=$SIMULATOR_ID" \
    -resultBundlePath "$OUT/Tests.xcresult" \
    CODE_SIGNING_ALLOWED=NO \
    | tee "$OUT/test.log" | grep -E "error:|warning: .*MaqamStudio/App|Test Case .* (passed|failed)|Executed|\*\* TEST" || true
  if ! grep -q "\*\* TEST SUCCEEDED \*\*" "$OUT/test.log"; then
    grep -E "error:|failed|XCTAssert|crashed|signal" "$OUT/test.log" | head -100 >&2 || true
    print_crash_reports
    echo "Tests failed" >&2
    exit 1
  fi
fi

# A device build without signing: it proves the app compiles for arm64 and gives
# Codemagic (or a signing step) something to sign.
xcodebuild archive \
  -project MaqamStudio.xcodeproj \
  -scheme MaqamStudio \
  -configuration Release \
  -destination "generic/platform=iOS" \
  -archivePath "$OUT/MaqamStudio.xcarchive" \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY="" \
  | tee "$OUT/archive.log" | grep -E "error:|\*\* ARCHIVE" || true
if ! grep -q "\*\* ARCHIVE SUCCEEDED \*\*" "$OUT/archive.log"; then
  grep -E "error:" "$OUT/archive.log" | head -100 >&2 || true
  echo "Archive failed" >&2
  exit 1
fi

rm -rf "$OUT/Payload" "$OUT/MaqamStudio-unsigned.ipa"
mkdir -p "$OUT/Payload"
cp -R "$OUT/MaqamStudio.xcarchive/Products/Applications/MaqamStudio.app" "$OUT/Payload/"
(cd "$OUT" && zip -qry MaqamStudio-unsigned.ipa Payload)
echo "Unsigned IPA: $OUT/MaqamStudio-unsigned.ipa"
