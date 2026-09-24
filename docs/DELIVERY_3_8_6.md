# 3.8.6 validation candidate — not a performance-approved release

Source: `0770ba9b3287493e667d664787e4eae0271a6aa5`.
CI: https://github.com/bellinghamfolks-debug/cautious-octo-eureka/actions/runs/35958240101
Package: `com.abdullah.visionbridge.stable`, versionCode **47**.
Version: `3.8.6-stable-signing-test4`.
Permanent signer SHA-256: `349726219c7a59aee813745ff6d00599cf396f03cf6e2b3f48dcd29f023df9bd`.

## What this candidate changes

- A bounded producer/consumer lane prevents local OCR verification from blocking Gemini's
  stream reader and consuming the HTTP analysis timeout. It retains cumulative snapshots,
  drains the final snapshot and cancels both jobs when the turn becomes obsolete.
- Exact turn identity is checked again after waiting for evidence. Text still cannot pass
  without the existing independent optical grounding gate. Scene tails remain behind accepted
  reading and within the same turn.
- New diagnostics separate output verification wait from completed transport, and flag
  responses that time out before optical verification or delivery. Offline and in-app checks
  avoid assigning another turn's or session's events to the failed request.
- Five coroutine regression tests and four app verdict regression tests cover the repair;
  the Python measurement suite now has 24 passing synthetic tests.

## Validation and delivery receipt

- CI lint, unit tests, assemble, managed-device tests (18) and candidate packaging all passed.
- The final ARM64 APK was signed locally with the retained private key. `apksigner verify`
  confirms the pinned certificate; `aapt` confirms the package and versionCode 47.
- APK: `VisionBridge-3.8.6-stable-signed-arm64.apk`.
- Final APK SHA-256: `5624463933cf832dff99f41e60b666717b98e2a2e741ecf0345c0d85369194fd`.
- The delivered signature matches the prior signed 3.8.5 (46); this is an in-place update
  for that package, not a new app identity. No signing key was regenerated.
- CI artifact 10791822710 belongs to the exact source commit above; archive SHA-256:
  `3fbc9d0c3d9a8873788fe68e0a8927aaa2b93e869c692e6461ff64333697b862`.
  The raw CI APK is not the final signed download.

## Acceptance limits

The reporting phone session selected 36 frames, submitted one turn and displayed no result.
The first model chunk arrived about 13.8 seconds after capture, but no useful user output
followed. That chunk delay is not end-to-end useful latency.

Same-phone/network after measurements are unavailable. Full-page PP-OCR latency remains a
separate issue. FAST, STABLE, describeAlongsideText, BRIEF, COMPREHENSIVE and the private golden
set have not earned new quality/performance acceptance from this change. Synthetic coroutine
and managed-device tests cannot prove those targets. Private images, model responses and
signing material are excluded from the repository and CI.
