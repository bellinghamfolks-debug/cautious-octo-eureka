# Frame integrity repair — incomplete development checkpoint

Base: `6c5799c6b704b641c67b06e6cb914872353a8ddd`, 3.8.3 (44),
`codex/visionbridge-3.7-esight-viewport-settings`.

Workspace maintenance removed the earlier uncommitted implementation. The repository and
private diagnostic attachment were recovered. This checkpoint deliberately records what
is actually present, rather than claiming the lost implementation is still installed.
No private images, ZIP, transcripts or frame ledgers are part of this repository.

Present:
- Materialized the exact effective source previously created by eleven preBuild Python
  mutations; retired those mutations so reviewable Kotlin is the Kotlin being built.
- Added immutable AnalysisTurn and synchronized TurnGate with race/identity tests.
  **These foundations are not yet connected to the production result/TTS path.**
- Added boundary-confirmed viewport resolution, explicit 1220x2712 handling, two rotations,
  conservative unresolved reporting and synthetic geometry tests.
- Corrected encoder timing to exclude connection setup; measured JPEG, copies, hash and
  total separately while preserving every original resolution and quality profile.
- Fixed the two-phase pending-frame release race by updating pending/processing atomically.
- Added the performance acceptance contract and local-only aggregate/annotated replay tools.
- Changed CI to validation only: no automatic APK/release publication or signing-key creation.

Verified so far: 21 pure Kotlin/JUnit geometry and identity tests executed with the host
Kotlin compiler; nine Python measurement tests; repository verification; diff whitespace.
These are **not** the complete Gradle test suite or Android device tests.
The required `./scripts/codex-check.sh` was attempted; initial failures were network/proxy
resolution of Gradle and Android build dependencies. A retry with an isolated Gradle home
is being investigated. No APK or performance acceptance has been established.

Outstanding before any release:
1. Replace mutable Live result attribution with one stateless image+instruction request per
   accepted frame; propagate exact turn/image identity through callbacks, runtime, UI and TTS.
2. Integrate local PP-OCR grounding, stable-quality candidate retries and bounded analysis
   scheduling independently of speech. Do not reuse the Live speech backpressure policy.
3. Enforce stale-turn and utterance expiry checks at actual output boundaries; stream the
   important scene clause before full Comprehensive completion; keep READ_TEXT and SCENE_TAIL separate.
4. Complete cheap-first Smart Target changes, asynchronous bounded evidence writes and
   invariant diagnostics. Test both scene styles and unavailable-image handling independently.
5. Replay all private inputs locally, annotate golden failures, compare matching before/after
   runs, verify >=95% clear-text presence recall and all user latency budgets on a device.
6. Run full lint/unit/assemble/instrumented tests and diagnostics enabled/disabled A/B.

Official protocol basis: [Live API](https://ai.google.dev/api/live) documents concurrent
realtime modality streams without cross-stream ordering guarantees;
[generateContent](https://ai.google.dev/api/generate-content) provides image and task parts
in one request. Model quality and real network latency still require measured validation.
