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


Second checkpoint: version 3.8.7 (48), same application ID and pinned signer.
The first repair commit 9cbccb6e100f4c5c3abe8bd758e555d29029031d passed
lint, unit tests, assemble and instrumented Android tests in run 35994754766.
The second checkpoint must pass its own gates before packaging.

Added per-request DNS, connect (including TLS), TLS, upload, response-header wait,
first model text and streaming duration measurements. Connection reuse is explicit;
missing phases are not invented as zero. Server processing cannot be separated from
network transit without server timestamps. A synthetic local SSE test checks that the
actual SSE factory retains the HTTP listener. No credentials, request bodies, addresses
or URLs enter this timing report. First speakable text excludes NO_TEXT/NO_CHANGE.

The active frame-bound transport previously omitted thinkingConfig and mediaResolution.
The old GeminiVisionRepository HIGH setting is not used by this transport. Keep image
resolution, JPEG quality, model and default model image resolution unchanged. Set explicit
minimal thinking for literal OCR and low for scene descriptions. These are supported by
Gemini 3.6 Flash per Google's current Generate Content thinking documentation, checked
2026-09-24: https://ai.google.dev/gemini-api/docs/generate-content/thinking
This is a candidate setting, not a measured accuracy or speed claim. MEDIUM/HIGH quality
and latency comparison remains unmeasured; private evidence is never uploaded for it.
Media documentation: https://ai.google.dev/gemini-api/docs/generate-content/media-resolution

Phone release gates remain FAST median <=2s/P90 <=3s; STABLE median <=3s/P90 <=4s;
BRIEF median <=2s/P90 <=3s; comprehensive first useful speech <=3s in normal conditions;
preprocessing+encoding p95 <400ms without reducing text quality. Separately measure
first-speakable to display / TTS submit / TTS start. Optional grounding must add no await.
A real before/after phone replay and private golden accuracy comparison are outstanding,
so the package is a test update, not a performance-approved release.

The in-app verdict now identifies a completed response held in verification until
turn cancellation. The measurement harness reports speakable-to-runtime/UI/TTS
delays using explicit same-frame identities and aggregates separate HTTP phases.
The STABLE acceptance script enforces the latest requested 4000ms P90, with a
regression proving that 4200ms fails. Missing measurements remain unknown, never zero.
Pre-submission capture stages join by exact session/process/frame/trace, since a turn does
not yet exist during capture. Optional grounding is explicitly inapplicable to publication
latency rather than silently required as a missing stage. Its independent duration remains
in the optional-verification event timeline.
