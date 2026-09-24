# Build 47 follow-up: advisory grounding

Starting HEAD: cd812f139bb7286ec0e1f4099a3f2631199d96b6. Last delivered app: 3.8.6 (47).

The new user specification explicitly makes trustGateEnabled=false nonblocking: optional
local evidence may not withhold cloud output. The former coordinator ignored that setting.
This checkpoint creates a separate, single-slot optional verifier and leaves required local
reading / strict grounding separate. Optional evidence never publishes or cancels cloud
content. It uses a separate low-thread-count engine, a 640px detector, at most two crops,
a 1.2-second cooperative timeout and a two-timeout circuit breaker. A native ONNX call can
outlive cancellation; its occupied background slot is retained until it exits, without
holding the cloud lane or starting concurrent verifier jobs.

Streaming uses natural speech blocks without waiting for a newline or a completed model turn.
First speakable, first content submitted to TTS and first content started are separate events.
Target cancellation still invalidates old visual UI/speech. Optional cancellation alone never
retracts a committed answer. Pending promotion discards old-generation candidates under the
queue lock and releases the slot for a fresh candidate.

The cloud Smart Target tracker has cooperative 50ms registration budgeting with cheap
structural fallback and temporal consensus. Expensive feature/RANSAC recovery remains in the
local tracker. The former observerOnly label was misleading: this tracker can invalidate
turns, and the diagnostic now says so. This is not a measured phone p95 claim.

Evidence now uses session-qualified unique filenames. Export audits references against the
actual file inventory, reports missing/colliding historical evidence prominently, and records
actual exported counts. Existing overwritten images cannot be recovered. Configured timeline
windows, expected queue replacement and owned obsolete cancellation are not pipeline crashes.

Validation in progress: new coroutine, speech-boundary, diagnostic classification and device
export regression tests. Required local codex-check attempted; project/secret scan passed,
Gradle download blocked by services.gradle.org DNS. CI required. No private diagnostic media
is committed. Application ID and signer must remain unchanged. Phone latency acceptance,
network phase investigation and a same-phone replay remain outstanding.
