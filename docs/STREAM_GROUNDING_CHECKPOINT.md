# Stream / optical verification repair checkpoint

Base: `58bdafd541576b0f7100a0df84fad6c2c3fb48a5`, app 3.8.5 (46).

Confirmed code defect: `FrameTurnTransport` invoked a suspending partial-output callback
inside `CellularNetworkManager`'s 24-second request budget. That callback awaited a full
local PP-OCR page. Therefore optical verification blocked SSE consumption and could expire
the cloud request even after Gemini had returned content. This is not evidence of an
invalid API key, and increasing the cloud timeout would conceal the coupling.

Repair: a structured producer/consumer lane with one conflated pending cumulative snapshot.
Transport callbacks cannot suspend. The network request finishes independently of optical
verification; publication still awaits optical evidence and the exact turn gate. The final
snapshot is drained before a scene tail can be considered. Cancelling the parent cancels both
jobs; a transport failure cancels unverified pending output. No prompt, model, image resolution,
JPEG quality or grounding acceptance threshold is changed.

Regression tests cover a verifier slower than the request budget, bounded snapshot storage,
final snapshot delivery, late evidence after a generation change, cancellation of both jobs,
and transport failure while verification waits. `OUTPUT_VERIFICATION_READY` reports the wait
separately; `TRANSPORT_RESPONSE_COMPLETED` marks request completion independently.
`OUTPUT_VERIFICATION_WAIT_STARTED` marks entry even if the wait is later cancelled. The old
bundle did not record callback entry: its timeline is consistent with the confirmed code
defect but cannot by itself exclude an additional network stall. Do not present the timeline
as proof that this is the sole cause of the phone's failed turn.

The image-free measurement harness now flags a response followed by a request timeout with
no completed optical evidence, responses without runtime/UI output, and incomplete/mismatched
output identity. It joins only explicit turn IDs within the same session and process. These
are observational findings, not automatic causal or correctness verdicts. The new detector
flags the latest private replay; 24 synthetic Python harness tests pass.

| Latest phone session measurement | Before this repair | After on same phone/network |
| --- | ---: | --- |
| Selected frames | 36 | Not measured |
| Submitted turns | 1 | Not measured |
| Runtime/UI results | 0 | Not measured |
| Capture to first model chunk (not useful output) | 13.796 s, one sample | Not measured |
| Useful end-to-end latency | No accepted output | Not measured |

The deterministic regression test instead models a 100 ms request budget with a 1,000 ms
verification wait: the request must finish while verification is blocked, and no output may
publish before evidence is released. This proves timeout isolation only, not phone latency.

Status: local repository/secret checks passed. The required `./scripts/codex-check.sh` was
attempted but Gradle download failed with `UnknownHostException: services.gradle.org`.
Remote run `35956991860` passed lint, unit tests and assemble for the timeout-isolation
commit `562920c76f5f974b1d57cd99e80e10e55a2c0a94`; all 18 managed-device tests also passed.
The same missing-output symptom is now included in the app's `FrameIntegrityVerdict`,
with four synthetic regression tests. It excludes scene mode and unrelated turns/sessions
and does not let a later verification event hide the earlier timeout. This additional
diagnostic change is being validated by run `35957433844` on
`68fc1e2861db910d1704cb23af537b78e1a0b7e3`. This checkpoint is NOT an APK delivery or performance acceptance.

Remaining: full-page local OCR is still slow on the reporting phone; this change does not
prove FAST/STABLE latency, OCR accuracy, scene quality or real-device performance. Measure
network phases and progressive optical verification separately. Private diagnostic inputs
remain outside git; no captured content or images belong in CI. Keep application ID
`com.abdullah.visionbridge.stable` and the existing pinned private signer for the next APK.

## Protocol / performance review, 2026-09-24

- [Google Live reference](https://ai.google.dev/api/live): realtime audio, video and text are
  concurrent streams with no guaranteed cross-stream order. Client content supports a turn
  boundary, but remains part of conversation history. Retain the existing stateless request
  carrying one image and its instruction together; this repair does not return OCR to Live.
- [Google thinking controls](https://ai.google.dev/gemini-api/docs/generate-content/thinking):
  Gemini 3.6 Flash supports explicit thinking levels. A lower level is a possible latency
  experiment, not proof of unchanged reading/scene quality. The current diagnostic lacks a
  network phase breakdown and thought-token counts, so do not assign its initial 13.8-second
  delay specifically to model reasoning or change the model on that assumption.
- [ONNX XNNPACK configuration](https://onnxruntime.ai/docs/execution-providers/Xnnpack-ExecutionProvider.html)
  describes separate provider/runtime pools and recommends model-specific benchmarking.
  Current code uses default XNNPACK provider options with an explicitly sized ORT pool.
  Investigate this on the phone without lowering image resolution. Do not call thread
  contention a confirmed cause from the present event logs.

Next reproducible steps: inspect final CI for the latest source commit, then implement/measure
progressive optical verification and network phase timing. A same-phone replay is still
required for all latency acceptance targets. No private key or diagnostic media is in this
checkpoint. The signed 3.8.5 remains the last delivered binary, not a build of these changes.
