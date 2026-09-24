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

Status: local repository/secret checks passed. The required `./scripts/codex-check.sh` was
attempted but Gradle download failed with `UnknownHostException: services.gradle.org`.
Remote CI is required. This checkpoint is NOT an APK delivery or performance acceptance.

Remaining: full-page local OCR is still slow on the reporting phone; this change does not
prove FAST/STABLE latency, OCR accuracy, scene quality or real-device performance. Measure
network phases and progressive optical verification separately. Private diagnostic inputs
remain outside git; no captured content or images belong in CI. Keep application ID
`com.abdullah.visionbridge.stable` and the existing pinned private signer for the next APK.
