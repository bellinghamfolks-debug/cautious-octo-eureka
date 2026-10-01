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

if [[ "${SKIP_TESTS:-0}" != "1" ]]; then
  xcodebuild test \
    -project MaqamStudio.xcodeproj \
    -scheme MaqamStudio \
    -destination "id=$SIMULATOR_ID" \
    -resultBundlePath "$OUT/Tests.xcresult" \
    CODE_SIGNING_ALLOWED=NO \
    | tee "$OUT/test.log" | grep -E "error:|warning: .*MaqamStudio/App|Test Case .* (passed|failed)|Executed|\*\* TEST" || true
  if ! grep -q "\*\* TEST SUCCEEDED \*\*" "$OUT/test.log"; then
    grep -E "error:|failed|XCTAssert" "$OUT/test.log" | head -100 >&2 || true
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
