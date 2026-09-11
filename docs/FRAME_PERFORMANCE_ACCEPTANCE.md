# Frame-to-user acceptance contract

Status: measurement contract; no post-repair performance pass has been established.
Baseline: branch `codex/visionbridge-3.7-esight-viewport-settings`,
commit `6c5799c6b704b641c67b06e6cb914872353a8ddd`, version 3.8.3 (44).

## User-visible deadlines

All durations use a single monotonic clock. Start at the acquisition timestamp of the
first valid, stable frame of the target opportunity, **before** selection, tracking,
quality waiting, encoding, OCR, or network work. Keep this opportunity timestamp when a
better frame replaces it. Also report latency from the actual submitted frame's capture;
that secondary measure must not hide waiting for a better candidate.

Useful means accepted, current, grounded content delivered to the user. A first network
chunk, META/QUALITY header, placeholder, speculative transcription, NO_TEXT, "unclear",
obsolete speech, or incorrect answer is not useful text. The unavailable-image notice is
measured separately. Report first displayed and first audible content independently;
for speech-enabled accessibility runs the audible endpoint is the acceptance endpoint.
Use Android TTS onStart, never speak() return, as the software proxy for first audio;
validate that proxy against audio recording on the device. UI endpoint is rendered content,
not runtime StateFlow assignment. Keep missed opportunities in the denominator.

| Workload | Median capture-to-useful | P90 capture-to-useful |
| --- | ---: | ---: |
| TEXT_READING FAST | <= 2000 ms | <= 3000 ms |
| TEXT_READING STABLE | <= 3000 ms | <= 4500 ms |
| SCENE_DESCRIPTION BRIEF, meaningful change | <= 2000 ms | <= 3000 ms |
| SCENE_DESCRIPTION COMPREHENSIVE | Important first spoken clause <= 3000 ms for each predefined normal-condition case | Report distribution and misses; never wait for the whole description |

Stable quality waiting must be accompanied by paired frame-quality and recognition
accuracy results. Delay alone is not improvement. No lower image resolution, JPEG quality,
or smaller-text exclusion to manufacture a latency pass.

Preprocessing + encoding: p95 < 400 ms; stretch < 250 ms without OCR regression.
Report resize, enhancement, JPEG, copies, hash and Base64 individually. Network setup
must not be included in encoding. Parallel phases use interval union, not summed durations.
Local PP-OCR grounding is a separate measured stage, including cold initialization.

TTS: zero old-generation utterance starts. Proposed numerical operational budget for
"near zero": p90 queueAgeMs <= 250 ms, maximum <= 1000 ms for the first useful utterance
of each result. Report all utterances too. queueAge starts at eligibility/enqueue, including
engine initialization and any application queue. Do not reset it at engine submission.
Later details queued behind useful speech must not obstruct new visual analysis. New
visualGeneration cancels queued and running obsolete content, including SCENE_TAIL.

## Stage event contract

Every event carries sessionId, transportSessionId, turnId, traceId, frameId,
visualGeneration, mode, captureProfile, sceneDescriptionStyle and monotonic timestamps.
A submitted turn additionally binds model, promptVersion and SHA-256 of transmitted JPEG.
Identity must be immutable; a result without a matching submission fails validation.

Stages (explicit start/end nanoseconds, not diagnostic-writer arrival time): capture,
tracking, preprocessing, encoding, localGrounding, networkSetup, networkModel,
runtimeAcceptance, uiRender, ttsQueue, ttsEngineStart. Also emit first network chunk,
first validated text, first useful rendered text, first useful speech and turn completion.
Report n, missing n, median, p90, p95 and max. Incomplete stages remain missing, never zero.

Counts: selectedFrames, submittedFrames, completedTurns, cancelledAsObsolete,
suppressedByQuality, suppressedAsDuplicate; all other suppressions get explicit reasons.
Distinguish candidate replacement from dropped active requests. Report actual inter-send
and inter-completion intervals separately from end-to-end latency. No send-every-frame
quota: unchanged already-read targets may correctly be suppressed. Speech backpressure
must never cause an analysis skip. Report every selected frame's terminal disposition.

## Paired runs and release gate

Replay identical ordered inputs and capture times locally on the same device/network,
with identical settings, model, preprocessing/JPEG profiles and warm/cold setup. Record
device, Android, network conditions, model/prompt versions, run duration and input-set
identity. Alternate baseline/repair order; use at least 30 independently annotated target
opportunities per mode, plus the catastrophic golden cases. Fix normal-condition cases
before measuring. Report failures and censored/cancelled opportunities, including the
fraction producing useful content; never publish percentiles only for successful answers.

Run diagnostics enabled/disabled using an independent in-memory benchmark clock and
external speech timing so disabling diagnostics does not disable measurement. Compare
image-evidence writing on/off separately. Saving private JPEGs is local only.

The old ZIP lacks result/frame identity, runtime-result and UI-render records. Therefore
strict end-to-end useful-result latency cannot be reconstructed from it. Temporal matches
may support investigation but cannot turn guessed identity into measured acceptance.

The supplied eSight reference is landscape 1356x610, with image approximately x=68..1034,
y=76..533. Its portrait rotation is not evidence of the actual portrait app layout.
Test landscape, both rotations and the 1220x2712 capture separately; verify crop completeness
on synthetic layouts and local private evidence. Keep all user screenshots out of git.

Lint, unit tests and assemble are necessary, not performance evidence. Release and APK
publication remain blocked until device tests, golden replay, text-presence recall >=95%
on the predefined clear-text set, zero stale-output failures, independent Brief and
Comprehensive tests, and these measured performance gates pass. Missing evidence is
NOT_MEASURED, not PASS. No release is triggered by a development-branch push.

## Local evaluator input

`python3 scripts/measure_frame_pipeline.py EVENTS --output LOCAL_JSON` extracts legacy
aggregate counters and durations. `python3 scripts/evaluate_frame_acceptance.py REPLAY
--output LOCAL_JSON` checks **annotated** measurements. All input/output paths for real
captures stay outside this repository. Neither tool sends data over the network.

The replay JSON has an `opportunities` array. Each row contains `workload` (one of the
four table entries as `TEXT_READING/FAST`, etc.), `speechEnabled`, `normalCondition`,
`opportunityCapturedAtNanos`, independently annotated `groundTruthMatch`, and:

- `submitted`: turnId, traceId, frameId, visualGeneration, mode, model, promptVersion,
  transportSessionId, imageHash.
- `output`: the same exact identity plus accepted, useful and obsolete booleans.
- `timesNanos`: capturedAt, runtimeAcceptedAt, uiRenderedAt, ttsEligibleAt, ttsStartedAt.
- `stagesNanos`: each named stage maps to a list of `[start, end]` monotonic intervals.

Do not fabricate these rows from loose temporal matches in the legacy ZIP. The evaluator
uses annotated truth, not the model's confidence, for useful content. Its PASS is scoped
to supplied latency observations, **not** release approval, device execution proof, or
verification that annotations are accurate. Match annotations to private evidence locally.
Global stale-start testing, image quality, recall and paired-run comparability remain
independent gates. Missed opportunities produce missing/infinite tail percentiles.
