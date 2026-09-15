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

## 2026-09-15 recovery

Remote checkpoint 45a1673713cf9dd0040b333164710543f23e5b01 passed full Android CI
(run 34658859136). The scratch workspace reverted again. Local-only commit 84d326d
was not preserved remotely before interruption. Its patches are being reconstructed
from the work transcript and saved in smaller remote checkpoints. Old branches were retained.
Restored accepted-result content hashes and speech deltas carrying the original accepted revision.
Still restoring streaming lines, transport timing, viewport memory, synthetic Android replay and
private-ledger reconstruction. These restorations need their own Android validation.
No private image, ZIP, ledger or transcript was placed in GitHub or CI.

Restored complete-line optical streaming, separate network/base64/payload timing, late callback
rejection, and confirmed external-layout viewport memory with two regression tests. These are
restorations of the interrupted checkpoint, not new measured phone performance results.

Restored private replay reconstruction with its three safety/identity tests (12 Python tests pass),
and synthetic Android replay tests: twenty clear images in both diagnostic settings, blur/glare/
rotation/black, transmitted JPEG hash and atomic request, and 100 runtime replacements.
All current-session 207 analysis image references were recovered locally. New tests await CI.

## CI infrastructure and continued repair

Runs 34950388004, 34950580130 and 34950854818 stopped before source validation because
setup-android v3 defaults to the retired SDK package `tools`. Its official action.yml confirms
that default. CI now requests `platform-tools` explicitly; no gate has been removed or bypassed.
The exact local project check was attempted again and failed downloading Gradle due DNS.

Also fixed the PP-OCR deskew bitmap lifetime: a finally block now recycles it on no-box return,
failure and cancellation. Stable candidate timing now respects Smart Target's motion-compensated
stability, so camera shake alone need not perpetually restart the settling interval.
The synthetic device replay and these changes still require successful full CI.

Recovery files and subsequent fixes are now saved remotely, including the formerly local-only
synthetic replay. CI run 34951021443 got past the repaired SDK setup and entered the exact
project gate. A review also made the replay test explicitly return Unit (its export barrier
returns a File, which otherwise gives JUnit an invalid non-void test method).

## Accepted content verification checkpoint

Remote 6ce8cf427b843e120a11be9e9a47873de243d02e passed lint, unit tests and assemble
in run 34951179380. Managed-device job 104323347084 is still running at this checkpoint;
its result must be checked before claiming device validation.
Diagnostics now check that displayed/spoken content hashes belong to a previously accepted
revision of the same turn. Earlier accepted streaming prefixes remain legitimate; unknown,
future or malformed revisions are reported. Four regression tests cover these distinctions.
Frequent durable saves no longer cancel an already-running device suite; the latest pending
checkpoint is validated afterwards. No acceptance gate or test was removed.
The exact local project check still cannot download Gradle (services.gradle.org DNS failure).
Current phone latency, private golden post-fix replay and actual cloud accuracy remain unverified.

## Runtime, rendering and speech intervals

Run 34951179380 on 6ce8cf4 completed successfully: lint/unit/assemble and all 16 managed
Pixel 6 API 30 tests, including the synthetic PP-OCR diagnostics-off/on replay. This is not
evidence of phone/cloud end-to-end performance. Device log confirms 16 tests finished.
New instrumentation records runtimeAcceptance, uiRender, ttsQueue and ttsEngineStart intervals
with the accepted content hash. UI callbacks for superseded prefixes of the same turn are ignored,
and each current revision is acknowledged at most once. A new Android regression checks the
exported events and monotonic intervals; it awaits validation on this subsequent checkpoint.
Speech queue timing starts at eligibility, with original utterance queue age still reported
separately. An earlier delivered clause does not silently erase original queue age.

Performance acceptance now counts Base64/JSON request encoding in the preprocessing/encoding
budget and reports UI and speech separately. Local grounding includes cold ONNX loading.
All 15 Python measurement/private-replay tests pass, including three new budget/hash tests.
Local Android validation was attempted and remains blocked at the Gradle download DNS step;
remote CI is required for this batch. The runtime interval checkpoint is remote 471285b68ceac7985893b0f31fad13e40c1e6b9e.

Added the local explicit event/annotation join (build_frame_acceptance.py). It isolates process
and session, binds exact accepted revisions, rejects profile mismatch/old generations and preserves
missing opportunities; no temporal guessing and no text/image contents in generated rows.
All 21 Python tests pass. Local binding events now include profile and encoded dimensions/quality.
The annotation schema and private-path requirements are documented. Real phone observations and
independent usefulness annotations are still required; generated synthetic tests are not after-replay
performance evidence. Latest prior durable measurement checkpoint: 7948b3510c958da99166f9c57c4fa2d29311d812.

## Offline optical investigation

Run 34951835689 (9d136d2) passed full CI. Run 34952285952 (7948b35) also passed both
build and managed-device jobs, covering the new runtime/display interval test.
Added a generic offline host optical probe using checksum-verified production ONNX weights.
Dependencies were installed outside git in private-tools/python (onnxruntime, OpenCV, numpy).
The probe completed the locally defined 11-case subset. Its raw readings and case summaries
remain outside git under private-replay-current. No model request or image upload occurred.
It deliberately labels results exploratory: OpenCV resizing, unmerged regions and greedy CTC
differ from Android's full pipeline, and evidence JPEGs are not proven transmitted JPEGs.
It is not a phone benchmark, cloud replay, 95% recall measurement or release pass.
Reproduce with scripts/probe_private_optical_evidence.py using private paths and the pinned
models fetched by scripts/fetch_ocr_models.py. Output uses exclusive creation and flushes each
completed frame so an interrupted investigation does not overwrite prior work.

Full CI on 7a67a11 succeeded (run 34952513124): lint/unit/assemble and 17 managed-device
tests. The expanded private host optical probe is processing the complete selected sequence,
with at least 195/207 rows already flushed locally at this checkpoint. This is optical evidence
investigation only, not the requested production end-to-end replay.
Moved the TTS submission clock read after the synchronized eligibility-window read, preventing
a concurrent predecessor completion from producing a negative queue interval. This small timing
change requires the following checkpoint's validation. Previous durable helper checkpoint is
06bde7c3f29659dfa4e3dab186a25381a14644bf.

## Completed local sequence probes and reproducible environment

The host optical investigation finished all 207 selected evidence frames. Outputs and the
11-case detailed comparison remain PRIVATE LOCAL ONLY in private-replay-current. Detection
also sees camera UI elements; detected-box presence is not equivalent to useful target text.
The generic ViewportEvidenceProbe.kt executes production viewport/session logic over full
capture evidence using desktop JPEG decoding/resampling. On 157 full timeline images it
returned 8 explicitly unresolved frames, 2 dynamic boundaries and 147 retained confirmed
layouts. This is evidence about the pure viewport logic, not Android/device performance or
proof that all overlaid controls were removed. No private images or paths are in the helper.
The seven production ViewportResolverTest tests also passed locally via the Kotlin compiler
and JUnit jars bundled in Gradle 8.14.3.

Toolchain recovery found existing Android 36 SDK and Gradle caches under ../toolchain.
Running the exact project script with GRADLE_USER_HOME=../toolchain/gradle-user and
ANDROID_HOME/ANDROID_SDK_ROOT=../toolchain/android-sdk advanced beyond dependency lookup,
but this local Java 17 installation lacks JAVA_COMPILER (javac). Earlier default-home attempts
failed Gradle download DNS; an explicit standard proxy allowed that download. Do not confuse
these local environment failures with source test failures. CI with a complete JDK remains
the full project gate. Run 34993503859 on 06bde7c3 completed both jobs successfully.
Latest prior durable application checkpoint: b8e019ae2b87015c5c8404df0dd9a6a76468dad9.
