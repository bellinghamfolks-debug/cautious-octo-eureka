# Frame integrity repair — current recovery checkpoint

Status: development in progress; no release or performance acceptance.
Base: `6c5799c6b704b641c67b06e6cb914872353a8ddd`, version 3.8.3 (44),
`codex/visionbridge-3.7-esight-viewport-settings`.
Durable work branch: `codex/visionbridge-frame-integrity`.

## Recovery and preservation

On 2026-09-11 the local worktree reverted to an earlier snapshot again. All previous
implementation was recovered from remote commit `ae09b56398f37db2b98a11ae290dfaa792a9dd85`,
tree `d130eda33aa64c30611c939dae1e9b64bf51146e`. Old local branches were retained.
Before resuming after interruption, fetch this work branch and inspect its latest HEAD.
Do not overwrite a dirty worktree. GitHub commits may differ from earlier local checkpoint
hashes; verify trees. Never merge the old reconstruction merely to reconcile those hashes.
Commit and save incremental source checkpoints. Private captures, diagnostic ZIP, transcripts,
frame ledgers and replay outputs remain outside this repository and all CI artifacts.

## Verified before this checkpoint

Exact commit ae09b56: GitHub Actions run 34619350681 succeeded.
- `./scripts/codex-check.sh`: lintDebug, testDebugUnitTest, assembleDebug succeeded.
- Managed Pixel 6 API 30 emulator: 12 instrumented tests ran and completed successfully.
- Nine Python measurement tests succeeded.
- Earlier 49 targeted host Kotlin tests covered turn identity, grounding/retry, geometry,
  tracking and Smart Target. This is not a count of the whole Gradle suite.
These results do not establish correctness/latency on the user's phone or private golden set.
The new speech deadline changes below still require their own complete Android CI gate.

## Implemented

- Materialized effective Kotlin formerly mutated by eleven preBuild scripts; removed those
  mutations so reviewed source equals compiled source.
- Immutable AnalysisTurn + TurnGate govern production frame submission, result, UI and TTS.
  Retired GeminiLiveSession/LiveCloudCoordinator. FrameTurnTransport submits image and task
  in one stateless request; callbacks capture that immutable request identity and image hash.
- Mandatory local PP-OCR token grounding for cloud text; conservative NO_TEXT/low-confidence
  retry; quality-aware stable candidates and optical duplicate verification without cancelling
  an already accepted reading. Actual device precision and recall are not yet measured.
- One analysis lane and one pending candidate independent of speech, atomic queue release.
- READ_TEXT and optional SCENE_TAIL separated; comprehensive description streams clauses.
- Boundary-confirmed dynamic viewport and explicit 1220x2712/rotation geometry tests.
- Encoder component timings and transmitted JPEG hash, original quality profiles preserved.
- Cheap-first structural tracker checks; bounded asynchronous diagnostic evidence with explicit
  capacity skips and an export barrier.
- Monotonic runtime/UI/TTS endpoint timestamps and local-only measurement/acceptance tools.
- Validation-only CI: synthetic reports only; no APK/release publication or signing-key creation.

## Current additions

- Enforce a one-second visual TTS start budget at submission, onStart and a start watchdog.
  Late callbacks are validated atomically and cannot stop newer speech. A delivered same-turn
  predecessor permits ordered continuation; original queue age remains reported separately
  from engine waiting time. Scene tails cannot borrow a reading's continuation window.
- Correct settings/UI model identity to the actual stateless model. Keep PP-OCR available
  when cloud reading is selected because it remains the mandatory optical validator.

## Next work, in order

1. Validate speech deadline integration and add independent Brief/Comprehensive policies/tests,
   semantic dedupe, unavailable-image lifecycle, latest/best candidate selection.
2. Complete stage/opportunity instrumentation, invariant diagnostics, and diagnostics off/on tests.
   Review grounding/network concurrency, scene confidence limitations, black frames with controls.
3. Build synthetic replay fixtures (Arabic/English/mixed/numbers/small/tilted/blur/glare/black/
   rotations/target transitions/scenes). Private replay stays local; rebuild its ledgers if lost.
4. Run the full Android gates again. Reproduce private cases and >=95% clear-text presence recall;
   measure every user latency budget with paired same-device/network runs before release.
5. Produce an honest before/after report. Missing device/cloud replay measurements are NOT MEASURED,
   never inferred from passing builds or simulated clocks. No APK link until all acceptance gates.

Protocol basis: https://ai.google.dev/api/live documents concurrent realtime modalities without
cross-stream ordering guarantees. https://ai.google.dev/api/generate-content provides image and
task in one request. The selected Gemini 3.6 Flash model's actual accuracy/latency still needs replay.

## Latest work after the speech checkpoint

Speech/settings checkpoint `a155a1b27d3dd1f32c7c856c4bade84cc99191ca` passed full CI
(run 34658165845), including the emulator job. The following newer additions still need CI:
- Independent Brief/Comprehensive prefix, quality, dedupe and first-scene/retry policies;
  compensated tracker evidence drives subsequent probes, not raw shake/lighting difference.
- One bounded pending candidate retains a sharper stable image for at most 750 ms; a new
  generation always wins. Local text now shares the coordinator's retry policy.
- Shared one-announcement outage lifecycle invalidates old results and speech immediately.
- Parallel optical verification/cloud submission for new targets; optical duplicate checks
  remain a preflight only for a previously accepted target. No result bypasses grounding.
- Added explicit capture, change detection, tracking, crop, quality, encoding and local-grounding
  intervals; first eligible target capture survives improved candidates in the same generation.
- Export verdict detects stale output, wrong image identity, missing identity, excessive
  suppression, persistent viewport fallback, NO_TEXT/optical conflicts and long speech queues.
29 focused JVM tests covering these policies and the prior identity/grounding cases passed.
These are policy tests, not proof of scene recognition, device latency or the private replay.
Remaining immediate work: finish network/runtime/UI intervals and result revision telemetry,
synthetic on-device regression fixtures, diagnostic off/on A/B, private golden replay and the
full CI gate for these additions. The physical-phone/cloud acceptance remains unmeasured.
