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

## Subsequent recovery checkpoint (not compiled, not release-ready)

Added FrameTurnTransport (stateless image+instruction SSE), FrameBoundCoordinator,
TextGroundingGate, QualityRetryPolicy and tests. Added result/diagnostic turn fields and
runtime rejection. **Integration is unfinished:** AppContainer still selects the old
coordinator, MediaProjectionService does not yet pass Candidate, and BilingualTtsEngine
still needs speakTurn/invalidateVisualContent plus atomic stale checks. These references
must be implemented before the new source can compile. UI acknowledgement is also pending.
Do not mistake this recovery checkpoint for a working application or validated repair.

Next exact steps:
- Complete TTS turn/context propagation and expiry enforcement, then wire AppContainer and
  PendingFrame/Candidate; remove production GeminiLiveSession and LiveCloudCoordinator.
- Inspect coordinator concurrency (policy mutation under one authority, cancellation while
  grounding, first-frame stable retry, scene deduplication). cropCompleteness=1.0 is currently
  a placeholder and MUST be replaced with measured evidence before acceptance.
- Compile the integrated source, run full tests, expand regression coverage, then device replay.
- Fix remaining diagnostic evidence synchronous writes and tracking cost; implement full stage
  timing and useful-output opportunity measurements. These are not implemented by the Python tools.

A previous snapshot has GitHub Actions run 34591658552. It covers the earlier checkpoint,
not the subsequent stateless-coordinator additions. Check its exact head SHA before using results.

## Integration checkpoint

AppContainer now selects FrameBoundCoordinator. MediaProjectionService carries Candidate
with PendingFrame; cloud change detection allows stable-quality retries. BilingualTtsEngine
now accepts immutable speakTurn context, rejects stale queue/segment/start callbacks, and
performs the final speak submission under TurnGate. UI acknowledgement records after a
Compose frame. These changes are saved for recovery but **not yet compiled or device tested**.
The earlier 'integration unfinished' paragraph describes the prior checkpoint only.

Still review: coordinator locking/cancellation, first-candidate quality settling, measured
crop completeness, per-utterance hard start timeout and speech queue budgets; full telemetry,
scene dedupe and tests. Existing unreferenced Live classes have not yet been deleted.

## Review checkpoint after integration

Retired GeminiLiveSession/LiveCloudCoordinator from source. Fixed metadata language header,
rewrote atomic request JSON construction, moved retry-state mutation into turn activation,
recorded missing-identity rejection and reused capture traceId as turnId. Replaced the crop
completeness constant with an explicitly conservative edge-contact proxy (still needs optical
box/crop validation). UI now includes scene tail. 26 pure Kotlin tests and nine Python tests pass.
Full Gradle gate progressed to an explicit missing JAVA_COMPILER capability in system JRE;
a full verified JDK 17 is being provisioned. No full Android build/device/performance pass yet.

## Evidence/tracker checkpoint

Fixed two viewport error-handler references reported by CI. Added cheap identity structural
residual before expensive motion registration, preserving the existing thresholds and fallback.
Moved opt-in evidence JPEG writes to one bounded worker (two bitmap snapshots maximum), with
explicit skip/write/copy/queue telemetry and export barrier. Explicit discard invalidates queued
writes under a shared lock; disabling new capture preserves already accepted snapshots.
These additions still need full Android/unit/instrumented verification. Local full build now
has a verified JDK but failed while resolving Android runtime dependencies due network access.

## Candidate verification and timing checkpoint

Moved active output-turn replacement to actual submission, after preprocessing/grounding.
Periodic optical verification of an already-read target now remains possible; identical local
text suppresses a cloud request without clearing the accepted result or interrupting speech.
Negative/uncertain readings still retry. Target invalidation clears optical identity.
Added actual monotonic runtime/UI/TTS endpoint timestamps and candidate quality/network-setup
measurements. Full stage decomposition and opportunity-level replay instrumentation remain pending.
Re-running tracker/Smart Target tests after the cheap-first change. Full Android Gradle dependency
resolution remains unreliable locally; GitHub CI is also being checked against exact commit SHAs.
