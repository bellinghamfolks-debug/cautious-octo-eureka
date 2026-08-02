# What changed in 2.2.0 and 2.3.0, and what it was measured against

Companion to [ROOT_CAUSE_2.1.1.md](ROOT_CAUSE_2.1.1.md), which states the defects. This document
states the repairs, the evidence each one answers, and what is still open.

Every number here was recomputed from `061bab58-…20260802_104809_942-NO-IMAGES.zip` — 7 sessions,
52,097 events — or produced by a test in this repository.

---

## 1. The user's complaint, traced end to end

> "I put it on very clear, large English text like a perfume and it doesn't read it."

The reader was never the problem. On the four frames named in the report it recognized the label
correctly, at 0.87–0.93 confidence:

| Frame | Recognized | Confidence | What happened |
|---|---|---|---|
| F000000682 | `BLEU 0 / CHANEL / D` | 0.928 | discarded as noise |
| F000000734 | `O / 0 / BLEU D / CHANEL` | 0.891 | spoken, cut off mid-word |
| F000001135 | `BLEU O / DE / CHANEL D / ParFuM` | 0.876 | spoken, cut off mid-word |
| F000002602 | `O / BLEU 0 / CHANEL D / PARFUM` | 0.873 | discarded as noise |

Three defects in series destroyed it after recognition:

1. The target-change detector fired every 343 ms (median) while one bottle was held still — 439
   times in 467 seconds — because it compared frames cell by cell at the same index and so could
   not tell a bottle that moved from a bottle that was replaced.
2. Each of those "changes" interrupted speech. 29 utterances were submitted, 15 reached `onDone`,
   14 were cut off mid-word — including `BLEU D / CHANEL` and `BLEU O / ParFuM`.
3. The ledger had already recorded each page as heard at the moment it was *queued*, so the
   interrupted words were never offered again: 86 later recognitions returned
   `already_read_completely`.

Whatever survived that met a fourth rule which discarded any addition shorter than 24 characters —
42 times in that session, on text including `BLEU / DE / CHANEL / PARFUM`.

**153 recognitions of a legible label produced 200 characters of audio.**

---

## 2. Repairs

### Speech is recorded when it is heard, not when it is queued

`SpeechOutcome` distinguishes `COMPLETED` from `INTERRUPTED`, `FAILED`,
`SUPERSEDED_BEFORE_START` and `CANCELLED_BY_USER`. Previously every terminal callback — `onDone`,
`onError`, `onStop` — and every interrupt resolved the same `CompletableDeferred<Unit>`, so no
caller could tell a spoken word from a silenced one.

`ReadingDeliveryTracker` accounts for a reading block by block and reports the **completed prefix**.
Speech is sequential, so a block after an interrupted one was never heard whatever the engine
reports about it. `ReadingLedger.recordDelivered` stores only that prefix; the rest stays owed and
comes back on the next recognition of the same page.

`FrameAnalysisCoordinator` no longer writes to the ledger at all from the queueing path. It writes
from the engine's delivery callback.

### The character-count noise rule is gone

Jitter is now identified by what it is rather than how long it is. The jitter in the device log is
loose glyphs — `O`, `D`, `0`, `=`, `c`, `X` — and an addition must contain a word of two or more
letters or digits to be spoken. `PARFUM`, `DE`, a room number and a gate number all qualify; a
stray glyph off a bottle's shoulder does not. A page that genuinely is one character long is not a
continuation and reaches speech through the fresh-page path regardless.

### A target that moved is not a target that changed

Every piece of the old detector is gone: the 24×24 sampling grid, the global grayscale signature,
mean absolute difference as the arbiter, the changed-pixel ratio, and acceptance from a single
frame. What replaced it lives in `capture/vision`.

| Stage | What it does |
|---|---|
| `Warp` | One 3×3 projective transform type, so every estimator's answer is comparable |
| `ImagePlane` / `FramePyramid` | Multi-resolution Y′CbCr — colour is carried, not discarded |
| `LucasKanade` | Pyramidal, six-parameter affine, inverse-compositional |
| `Features` | FAST-9 corners, Harris ranking, oriented BRIEF over a scale space |
| `Homography` | Normalised DLT inside RANSAC, refitted over its inliers |
| `StructuralResidual` | SSIM over the aligned overlap, plus a chroma term |

Lucas-Kanade answers translation, rotation and scale together, because a person holding an object
does all three at once. Feature matching and a homography take over where a gradient descent cannot
reach — a large jump, a heavy rotation, or a flat page now being viewed from an angle, which an
affine model cannot express at all. Structural similarity then measures what is left, so the same
label under changed lighting reads as the same content while a different label at identical
brightness does not.

Accuracy against ground truth: translation to 0.02 px, rotation to 0.02°, scale to 0.001, and a
known projective transform recovered to 1e-6.

**Defects found by measuring rather than by reading**, each fixed:

| Symptom | Cause |
|---|---|
| 7 of a possible 200 feature matches survived a 15° rotation | BRIEF sampled raw pixels; it assumes a smoothed patch |
| A 24-pixel error at full resolution | The 16×16 pyramid level reported a 2.98-cell shift where the truth was 0.375, and every step down doubled it |
| Two entirely different pages scored 0.18 against a 0.30 bar | The SSIM average was dominated by windows of blank paper |
| A 25% zoom stopped at scale 1.007 | The convergence test compared a raw coefficient against a fixed bound |
| A zoom that leaves 64% of the template in view stalled | Inverse composition reused a Hessian built over pixels the error no longer covered |
| A 1.25× zoom sat at a local minimum | The objective is not convex on a page of text; the coarsest level now tries several seeds |
| A seed hijacked a case the plain descent handled | Seeding now runs only when the plain descent ends unconverged or far off |

**Thresholds sit inside a measured gap.** Over 29 views of one page — translated, rotated, zoomed,
relit and noised — the worst score is 0.226; over 13 genuinely different subjects the best is
0.299. The shipped values are 0.26 for text and 0.24 for scene. `SeparationTest` asserts both the
gap and that the thresholds lie inside it, so neither can drift unnoticed. The gap is only 0.073
wide, which is why the two-frame consensus matters as much as the threshold.

**Cost**, on a desktop JVM: 12.8 ms for the common path, 26 ms when the feature fallback runs,
against a recognition pass of 2,231 ms. Feature detection is lazy, so a steady hand never pays for
it. Not yet measured on an ARM device.

A stable `targetTrackId` survives motion, and the diagnostics record the aligned and unaligned
measurements side by side, plus the registration method, the estimated rotation, scale and
projective terms, and the feature inlier ratio.

The frame-change detector is untouched. "Is anything happening" is a different question from "is
this a different subject", and a raw difference is the right answer to the first.

### One virtual display per consent

`recreateVirtualDisplayAfterBlackFeed()` is gone. It asked a live `MediaProjection` for a second
virtual display, which Android 14 answers with `SecurityException` and then stops the projection —
three attempts across the bundle, three dead sessions, each within 3 ms. Recovery now attaches a
fresh surface to the display already held, via `setSurface()`, which is what `resizeCapture()`
thirty lines above it was already doing correctly.

The replacement `ImageReader` is published only after the framework accepts the surface. It used to
be assigned first, so a failed repair left the service listening to a reader with no producer.
`resizeCapture()` had the same ordering and is fixed with it.

### A capture that dies says so

`MediaProjection.Callback.onStop` speaks a notice before the service stops, on the speech engine's
own scope so it survives the teardown. The old path wrote to a screen; the log shows that followed
by 216 seconds of silence.

### One absolute deadline per request

`AnalysisDeadline` states the bound as an instant fixed when the request starts, rather than as a
countdown. Every bound in 2.1.1 was a countdown driven by a scheduler, and on the device they all
stopped together: a 24,000 ms budget, a 48,000 ms watchdog and a 45,000 ms OkHttp call timeout let
one request run for 221,605 ms, and a second for 92,311 ms.

The coordinator now checks the lane **on every arriving frame** as well as from the health loop. A
frame is proof the app is running, which makes it the one moment a stalled request is certain to be
noticed. The backstops moved from 42 s and 48 s to 27 s and 30 s, and OkHttp's own timeouts came
down beneath them (connect 8 s, read 20 s, write 12 s, call 26 s) instead of sitting uselessly
above.

### A request whose target is gone is cancelled

460 requests across the bundle were marked stale with `requestCancelled: false`, 431 of them in the
one session where the "changes" were a bottle being held still. Leaving them running was the right
call while target changes were spurious. Now that the tracker separates motion from replacement, a
stale request is cancelled and its cellular lease released. A scene description the user has asked
not to interrupt is the one request still allowed to outlive its target.

### The cellular lease asks for a network that works

`NET_CAPABILITY_VALIDATED` is now part of the network request. `onAvailable` means the radio came
up, not that it carries traffic, and a network stuck behind a captive portal is indistinguishable
from a slow model from inside the app. If validation does not arrive within 10 s the lease falls
back to an unvalidated network and records that it did, so a carrier that never sets the flag costs
a delay rather than the feature.

---

## 3. Tests

| Suite | Cases | What it holds |
|---|---|---|
| `ReadingLedgerTest` | 14 | Interrupted pages stay owed; product names and room numbers reach speech; loose glyphs do not |
| `ReadingDeliveryTrackerTest` | 10 | Delivery is a completed prefix; every non-completed outcome leaves the block owed |
| `VisualTargetTrackerTest` | 16 | Drift, rotation, zoom and all three at once are tracked; a replaced subject is confirmed in two frames; one occluded frame does not abandon a reading |
| `RegistrationTest` | 20 | Lucas-Kanade, features, homography and SSIM against exact ground truth |
| `SeparationTest` | 2 | Same-subject and different-subject populations stay separated, and the thresholds lie between them |
| `ProjectionOwnershipTest` | 4 | Exactly one `createVirtualDisplay()`; recovery re-points the display it holds; a stopped projection is spoken |
| `AnalysisDeadlineTest` | 6 | A 197-second gap in scheduling does not extend a deadline |
| `DocumentSpeechPolicyTest`, `SpeechTextToolsTest`, `GeminiStreamProtocolTest`, `PaddleOcrPipelineTest`, `LocalReadingQualityTest` | existing | unchanged |

Each new suite was confirmed red against the commit before its repair. `ProjectionOwnershipTest`
run against `7977bff` fails all four cases, including `expected:<1> but was:<2>` call sites.

---

## 4. What is still open

**Deskew fired at −23.9° twice.** Two of 153 detections in the bundle produced a page-skew estimate
near the top of the correctable range, on captures of phone UI rather than paper. There is no
ground truth in a no-images bundle, and the existing tilt fixtures measure a real benefit from
deskew, so nothing was changed. The correlation and line count behind each estimate should be
recorded alongside `deskewDegrees` so the next bundle can settle whether these were genuine.

**Half of all detected lines are discarded.** 605 of 1,196 line decisions ended in
`droppedEverything: true`. Inspecting them, the drop looks correct — the highest confidence among
them is 0.549 and the content is single glyphs — so this is the detector proposing boxes on a
bottle's edges and reflections rather than text being lost. It is worth reducing because each
discarded box still costs a recognition pass, but it is not a correctness defect.

**Arabic recognition on dense UI screens is weak.** Completed utterances in the bundle include
`م ال ال / الح الين / الدل`. This is a recognition-quality question, separate from the delivery
failure repaired here, and it needs frames rather than events to work on.

**The 216-second hole is consistent with process freezing** after the service stopped, but a
no-images bundle cannot distinguish freezing from Doze. The repair does not depend on which it was:
both are answered by an absolute deadline rather than a relative timer.

**Not yet validated on a device.** Everything above is measured against the bundle and against
tests. The next bundle from the field is what confirms it, and the fields to look at are
`DOCUMENT_READING_DELIVERED` (delivered versus owed characters), `VISUAL_TARGET_DECISION`
(`targetTrackId`, and aligned versus unaligned differences), and the absence of
`CAPTURE_SURFACE_RECOVERY` failures.
