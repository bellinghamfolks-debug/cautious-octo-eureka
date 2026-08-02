# Root-cause evidence report — VisionBridge 2.1.1

Phase 1 of the repair mandate. Nothing in the code has been changed yet; this document exists so
that the repairs that follow are answers to measured failures rather than adjusted thresholds.

**Evidence base.** `061bab58-…20260802_104809_942-NO-IMAGES.zip`: 7 sessions, 52,097 events,
2,109 automatic findings, recorded on 2026-08-02 between 08:29 and 10:48 local time. Every number
below was recomputed from `sessions/*/events.jsonl`, not copied from the summary file.

Two sessions carry almost all of the failure:

| Session | Duration | What the user was doing |
|---|---|---|
| `20260802_102920_741` | 467.3 s | Reading a perfume bottle and screens with the local reader |
| `20260802_103708_033` | 417.8 s | Cloud mode; capture died at 13 s and the app went silent for 216 s |

---

## 1. Root-cause table

| # | Defect | Site | Severity |
|---|---|---|---|
| RC-1 | A second `createVirtualDisplay()` is called on a live projection, which Android rejects and punishes by killing the projection | `MediaProjectionService.kt:634` | Fatal |
| RC-2 | "The target changed" is decided by an unaligned whole-frame pixel difference, so moving the object counts as replacing it | `FrameChangeDetector.kt:144-165`, `:306-339` | Fatal |
| RC-3 | Recognition takes 6.5× longer than a target survives, so results are structurally stale before they exist | `FrameAnalysisCoordinator.kt:419-460` | Fatal |
| RC-4 | The reading ledger commits at *enqueue* time, so speech that was cut off is recorded as heard | `FrameAnalysisCoordinator.kt:841-842` | Fatal |
| RC-5 | Every TTS terminal state — done, error, stopped, interrupted — completes the same `Deferred<Unit>`, so no caller can tell spoken from silenced | `BilingualTtsEngine.kt:341-347`, `:579-582`, `:653` | Fatal |
| RC-6 | A character-count noise floor discards genuine short content such as a product name | `ReadingLedger.kt:73-75`, `:119`, `:138` | Major |
| RC-7 | The request deadline is a coroutine `withTimeout`, which does not run when the process is not running; a 24 s budget fired at 221.6 s | `CellularNetworkManager.kt:98`, `FrameAnalysisCoordinator.kt:425`, `:982` | Major |
| RC-8 | A request whose target is gone is *marked* stale but never cancelled; it keeps the single lane and the cellular lease | `FrameAnalysisCoordinator.kt:951-961` | Major |
| RC-9 | When surface recovery throws, the `ImageReader` has already been swapped, so the failed repair also destroys the working path | `MediaProjectionService.kt:630-646` | Major |
| RC-10 | Projection death is terminal: `stopSelf()`, no recovery, no spoken notice | `MediaProjectionService.kt:87-93` | Major |
| RC-11 | The cellular lease resolves on `onAvailable` without requiring `NET_CAPABILITY_VALIDATED` | `CellularNetworkManager.kt:119-127` | Moderate |

---

## 2. RC-1 — the illegal second virtual display

### Evidence

Three `FAILURE` events, one per capture session, all identical:

```
stage:     CAPTURE_SURFACE_RECOVERY
exception: java.lang.SecurityException
message:   Don't re-use the resultData to retrieve the same projection instance, and don't use a
           token that has timed out. Don't take multiple captures by invoking
           MediaProjection#createVirtualDisplay multiple times on the same instance.
at android.hardware.display.IDisplayManager$Stub$Proxy.createVirtualDisplay(IDisplayManager.java:1878)
```

Each is followed by `PROJECTION_SYSTEM_STOPPED` within 1–3 ms:

| Session | Recovery attempt | Projection stopped | Gap |
|---|---|---|---|
| `…102229_647` | 1785655552061 | 1785655552062 | 1 ms |
| `…103708_033` | 1785656241117 | 1785656241117 | 0 ms |
| `…104405_870` | 1785656658801 | 1785656658804 | 3 ms |

Three attempts, three deaths. The success rate of this recovery path is zero.

### Cause

`recreateVirtualDisplayAfterBlackFeed()` (`MediaProjectionService.kt:619`) calls
`projection.createVirtualDisplay(...)` on the same `MediaProjection` that already owns a display.
Android 14 forbids exactly this. The code that does it correctly is thirty lines above it:
`resizeCapture()` at `:587` re-points the existing display with `display.resize()` and
`display.setSurface()` and never asks for a second one.

### Reproduction

1. Start capture. Let the shared view go black or blank for `UNAVAILABLE_FEED_NOTICE_AFTER_MS`.
2. `observeVisualFeedHealth()` posts `recreateVirtualDisplayAfterBlackFeed()`.
3. `SecurityException`; the system stops the projection; `stopSelf()`.

### Invariant violated

*One MediaProjection consent yields exactly one `createVirtualDisplay()` call for its whole life.*
A surface must be replaced through `resize()`/`setSurface()` on the display we already hold.

---

## 3. RC-2 and RC-3 — the target changes faster than it can be read

### Evidence

Session `20260802_102920_741`, 467.3 s of a user holding a perfume bottle:

| Measure | Value |
|---|---|
| `VISUAL_TARGET_DECISION` | 1,680 |
| `VISUAL_TARGET_CHANGED` | 439 |
| Median gap between target changes | **343 ms** |
| 10th-percentile gap | 168 ms |
| Shortest gap | 103 ms |
| `PPOCR_PAGE_READ` latency, p50 | **2,231 ms** |
| `PPOCR_PAGE_READ` latency, p95 | 4,412 ms |
| `CLOUD_ACTIVE_REQUEST_MARKED_STALE` | 431 for 153 analyses — 2.8 invalidations per analysis |

At the moment of each accepted change, the measured difference sat on the threshold, not far above it:

| Field | Threshold | p50 at the moment of change | min |
|---|---|---|---|
| `meanAbsoluteDifference` | 19 | 22.87 | 11.29 |
| `changedPixelRatio` | 0.32 | 0.3229 | 0.1493 |

436 of 439 changes carry `decisionReason: fast_change_threshold_met`. Not one was a genuinely new
subject: it was the same bottle for the whole 467 seconds.

### Cause

`FrameChangeDetector.signature()` (`:306`) reduces the frame to a 24×24 luminance grid, and
`difference()` (`:327`) compares the two grids **cell by cell at the same index**. There is no
motion estimation and no alignment. One grid cell is 1/24 of the frame — about 45 px on a
1080-wide capture. A hand that drifts further than that in 100 ms makes every cell disagree with
its predecessor, and the OR at `:156-158` fires. The detector cannot distinguish *the subject
moved* from *the subject was replaced*, because it never measures motion.

RC-3 is the consequence and is arithmetic: a target lives 343 ms; recognition needs 2,231 ms.
A result is invalidated on average 6.5 times over before it is produced. No threshold value can
repair this, because the pipeline is being asked to answer a question about a target that is
already three generations gone by the time the answer exists.

### Invariants violated

*A target change means the subject in front of the user changed — not that it moved.*
*A result must be able to outlive its own production time.*

---

## 4. RC-4, RC-5, RC-6 — content is recorded as heard before it is heard

This is the chain that produced the user's actual complaint: *"I put it on very clear, large
English text like a perfume and it doesn't read it."*

### Evidence: the perfume frames

The mandate named four frames. The local reader recognized the label correctly in every one:

| Frame | `PPOCR_PAGE_READ` text | Mean confidence | What happened next |
|---|---|---|---|
| F000000682 | `BLEU 0 / CHANEL / D` | 0.928 | `DOCUMENT_READING_SKIPPED — continuation_below_noise_floor` |
| F000000734 | `O / 0 / BLEU D / CHANEL` | 0.891 | Accepted → **interrupted mid-speech** |
| F000001135 | `BLEU O / DE / CHANEL D / ParFuM` | 0.876 | Accepted → **interrupted mid-speech** |
| F000002602 | `O / BLEU 0 / CHANEL D / PARFUM` | 0.873 | `DOCUMENT_READING_SKIPPED — continuation_below_noise_floor` |

**The OCR was never the problem.** The words reached the speech layer and were destroyed there.

### Evidence: the delivery gap

Whole session, measured end to end:

| Stage | Count | Characters |
|---|---|---|
| Pages recognized (`PPOCR_PAGE_READ`) | 153 | — |
| Accepted for speech (`DOCUMENT_READING_ACCEPTED`) | 24 | 719 |
| Skipped by the ledger | 129 | — |
| Utterances submitted to the engine | 29 | 484 |
| Utterances started (`TTS_UTTERANCE_STARTED`) | 29 | — |
| **Utterances that reached `onDone()`** | **15** | **200** |
| Cut off mid-word (`TTS_UTTERANCE_INTERRUPTED`) | 14 | 284 |
| Dropped before the worker | 3 | 26 |
| Segments dropped for generation change | 4 | 70 |

153 recognitions of a clearly legible label produced **200 characters of audio**. 59% of what was
submitted was cut off mid-word. Two of the interrupted utterances are literally `BLEU D\nCHANEL`
and `BLEU O\nParFuM`.

Session-wide skip reasons: `already_read_completely` 86, `continuation_below_noise_floor` 42.

### Cause RC-4

```kotlin
// FrameAnalysisCoordinator.kt:838-842
blocks.forEach { block -> tts.speakReadingBlock(readingId, block, settings.speechRate) }
tts.finishReading(readingId)
readingLedger.recordSpoken(decision.document)
```

`speakReadingBlock` puts the text on an unbounded channel. `finishReading` marks the stream closed.
Neither waits for audio. `recordSpoken` then writes the **entire document** into the ledger as
heard, typically hundreds of milliseconds before the first word is spoken and seconds before the
last. When RC-2 fires 300 ms later and `onVisualTargetChanged` interrupts the queue, the ledger
still holds the page as fully delivered — so the next recognition of the very same bottle returns
`already_read_completely`, and the user never hears it. The doc comment at `ReadingLedger.kt:89`
already states the correct contract — *"Only call this once speech has actually been accepted"* —
and the call site does not honour it.

### Cause RC-5

`completeUtterance()` (`:341`) is the single handler for `onDone`, `onError`, and `onStop`, and
`interruptInternal()` (`:653`) calls `state.completion.complete(Unit)` as well. All four outcomes
resolve the same `CompletableDeferred<Unit>`, so at `:579-582`:

```kotlin
val completed = withTimeoutOrNull(timeoutMs) { completion.await(); true } ?: false
```

`completed == true` means *something terminal happened*, not *the user heard it*. `speakRequest`
then proceeds to the next segment as though the last one had been spoken. There is no type in the
codebase that can express "this was interrupted", and therefore no way for the ledger to learn it.

### Cause RC-6

```kotlin
// ReadingLedger.kt:73-75
addition.length < ALWAYS_NOISE_CHARACTERS ||                       // 12
    (addition.length < MIN_CONTINUATION_CHARACTERS && coverage >= NOISE_FLOOR_COVERAGE)
```

The rule treats *short* as *noise*. But the addition it is discarding is text like:

```
'BLEU\nDE 0\nCHANEL\nD\nPARFUM'
'0\nBLEU\nCHANEL D\nPARFUM'
'X OLEU\nDE\nCHANEL 0\nD'
```

A product name, a room number, a bus number and a platform number are all shorter than 24
characters, and all of them are the entire reason someone points the glasses at something. The
comment at `:136` cites the BLEU/CHANEL case as the *justification* for the rule; the diagnostics
show the same case as its clearest victim.

### Invariants violated

*Content is owed until it has been heard; only `onDone()` discharges the debt.*
*A speech outcome must be distinguishable from a speech attempt.*
*Brevity is not noise.*

---

## 5. RC-7, RC-8, RC-10 — the request that outlived its deadline by 197 seconds

### Evidence

Timeline of session `20260802_103708_033`, offsets from session start:

```
  0.2 s  CLOUD_FRAME_LAUNCHING            F000000001
  7.5 s  GEMINI_REPOSITORY_RETURNED       F000000001   (healthy, 7.3 s)
  7.6 s  CLOUD_FRAME_LAUNCHING            F000000081
  7.9 s  HTTP_REQUEST_STARTED             F000000081
 13.1 s  CAPTURE_SURFACE_RECOVERY_STARTED
 13.1 s  FAILURE  SecurityException        (RC-1)
 13.1 s  PROJECTION_SYSTEM_STOPPED
        ——— 216 seconds with no events of any kind ———
229.2 s  CLOUD_HEALTH_SNAPSHOT            activeAgeMs 221584, timeoutMs 48000
229.2 s  CLOUD_WATCHDOG_RECOVERY
229.2 s  CLOUD_ANALYSIS_BUDGET_EXCEEDED   budgetMs 24000, elapsedMs 221604
229.2 s  FAILURE  SSE_CONNECTION_FAILURE   InterruptedIOException: timeout (221280 ms)
```

Every bound in the system was blown through at once:

| Bound | Configured | Actually enforced at |
|---|---|---|
| `ANALYSIS_BUDGET_MS` | 24,000 ms | 221,605 ms |
| `CLOUD_WATCHDOG_TIMEOUT_MS` | 48,000 ms | 221,584 ms |
| OkHttp `callTimeout` | 45,000 ms | 221,280 ms |

The same shape appears in session `…104405_870` at 92,311 ms against the same 24,000 ms budget.
Health snapshots elsewhere in the log are 30 s apart (259.2, 289.2, 319.2) and then jump 88.8 s to
408.0 — the loop misses ticks repeatedly, not once.

### Cause RC-7

The budget is `withTimeout(ANALYSIS_BUDGET_MS)` (`CellularNetworkManager.kt:98`) and the watchdog
is `while (isActive) { delay(HEALTH_CHECK_INTERVAL_MS); runHealthCheck() }`
(`FrameAnalysisCoordinator.kt:102-107`). Both are *relative* timers driven by the coroutine
scheduler, and both need the process to be scheduled in order to fire. Once the projection died at
13.1 s the service stopped, the process stopped being scheduled, and nothing measured anything for
216 s — including OkHttp's own watchdog thread, which is why even `callTimeout` did not save it.

The system has three overlapping timeouts and no wall-clock deadline. What is missing is a single
absolute instant, captured when the request is created, checked against `elapsedRealtime()` at
every resumption and at every frame arrival, rather than three countdowns that all stop together.

### Cause RC-8

`onVisualTargetChanged` (`:951-961`) records `CLOUD_ACTIVE_REQUEST_MARKED_STALE` with
`requestCancelled: false` and deliberately lets the request run. 460 such marks across the bundle,
431 in the perfume session alone. Each one is work the user has already walked away from, still
holding the single-slot lane, the cellular lease and the radio. The comment at `:908-911` presents
this as a design choice; the measurements show it is the mechanism by which a dead target blocks a
live one.

### Cause RC-10

`MediaProjection.Callback.onStop` (`:87-93`) records the event, releases, sets a UI state and calls
`stopSelf()`. `container.runtime.stopped(...)` writes a message to a screen. For a blind user
holding glasses that have silently stopped working, there is no audio, no retry, and no way to know
anything happened. The 216-second hole in the log is 216 seconds during which the user was
receiving nothing and had no way to find out.

### Invariants violated

*One request, one absolute deadline, enforced against wall-clock time.*
*Work for an abandoned target is released, not merely labelled.*
*A capture that dies announces itself in audio and attempts recovery.*

---

## 6. RC-9 and RC-11 — two smaller correctness holes

**RC-9.** In `recreateVirtualDisplayAfterBlackFeed()` (`:630-643`) the field assignment
`imageReader = reader` happens *before* `createVirtualDisplay` throws. The old reader — the one
still attached to the live display — is never restored, and the field now points at a reader with
no producer. Because RC-1 kills the projection microseconds later this is currently masked, but any
future failure in this path leaves the capture in a worse state than before the repair was
attempted. A repair must be built on the side and swapped in only on success.

**RC-11.** `acquireCellularNetwork()` (`:117-142`) builds a request with `TRANSPORT_CELLULAR` and
`NET_CAPABILITY_INTERNET` and resolves on `onAvailable`. `onAvailable` means the network came up,
not that it carries traffic. Without `NET_CAPABILITY_VALIDATED` the app can bind its sockets to a
cellular network that is still authenticating or sitting behind a captive portal, and the resulting
stall is indistinguishable from a slow model — one plausible contributor to the 92 s and 221 s
requests above, though not provable from this bundle alone.

---

## 7. Repair plan

Ordered so that each step is verifiable before the next depends on it.

**P3-a — projection ownership.** Delete `recreateVirtualDisplayAfterBlackFeed()`. Route every
surface transition through the `resize()`/`setSurface()` path that `resizeCapture()` already uses,
built on the side and swapped in only after the new reader is proven. `createVirtualDisplay` gets
one call site, guarded by an assertion that fails loudly in debug if reached twice.

**P3-b — projection death.** `onStop` speaks a notice through the TTS engine before stopping, and
offers re-consent rather than ending in silence.

**P3-c — request ownership.** One absolute deadline per request, captured at creation, carried in
the request object, checked at every resumption and on every arriving frame — not only by a timer
that stops when the process stops. A stale request is cancelled at the point it is marked stale,
and its cellular lease released with it. `NET_CAPABILITY_VALIDATED` added to the network request.

**P4 — target tracking.** Replace the index-aligned grid difference with: motion estimation between
consecutive frames, alignment by the estimated transform, residual structural change measured only
*after* alignment, and a temporal consensus state machine that requires agreement across several
frames before declaring a new target. A stable `targetTrackId` survives translation and scale, so
holding a bottle steadier than a human hand can manage is no longer a requirement for being read to.

**P5 — speech delivery.** Introduce an explicit outcome type — `COMPLETED`, `INTERRUPTED`,
`FAILED`, `SUPERSEDED_BEFORE_START`, `CANCELLED_BY_USER` — returned per utterance and aggregated
per reading. `recordSpoken` is called only for the portion whose outcome is `COMPLETED`. Content
that was owed and not delivered is retained and resumed when the same target returns. The
character-count noise rule is deleted; "have they heard this" is answered by the ledger of what was
actually spoken, which is the only thing that ever answered it correctly.

**P6 — OCR.** Only where fixtures still fail after the above. On this bundle's evidence the local
reader is not the defect: mean confidence 0.87–0.93 on the frames the user complained about.

---

## 8. Regression risks

| Change | Risk | Guard |
|---|---|---|
| Removing surface recovery | A genuinely blank mirror now stays blank | Keep the notice and the spoken message; recover via `setSurface()` instead of a new display |
| Motion-compensated tracking | A real page turn is missed because it looks like motion | Residual change after alignment is the deciding signal, and consensus requires agreement across frames in both directions |
| Consensus delay before declaring a change | Slower first read of a genuinely new target | Bound the consensus window in frames, not seconds; measure first-read latency in the acceptance run |
| Committing the ledger only on `onDone()` | A page could be read twice if the outcome is lost | Treat an unknown outcome as *not delivered* and let the coverage rule suppress the duplicate; repetition is a smaller harm than silence |
| Deleting the noise floor | Recognition jitter is spoken as new content | Coverage-based suppression stays; only the length rule goes |
| Cancelling stale requests | A result that would have arrived is thrown away | Cancel only when the target has genuinely changed under the new P4 definition, which fires far less often |
| Absolute deadlines | A slow but healthy request is cut off | Set the deadline from the observed p95, and record every enforcement so the next bundle can show whether it is too tight |

---

## 9. What this report does not yet establish

- RC-11's contribution to the long requests is plausible but not proven; the bundle has no
  `NetworkCapabilities` snapshot at bind time. Adding one is part of P3-c.
- The 216-second hole is consistent with process freezing after the service stopped, but the bundle
  cannot distinguish freezing from Doze. The repair does not depend on which it was: both are
  answered by an absolute deadline rather than a relative timer.
- Arabic *recognition* quality is separately weak on UI screens in this bundle
  (`'م ال ال\nالح الين\nالدل'`). That is a Phase 6 question and is not part of the delivery failure
  described here.
