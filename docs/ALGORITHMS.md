# VisionBridge — Algorithms and Techniques

**Version 3.0.0 (build 26) · ~16,600 lines of Kotlin · 80 source files · 286 unit tests + 4 on-device tests**

A reference for technical review. Every technique actually in the shipped code is listed, with its
provenance and an honest assessment of whether it is current practice, deliberately classical, or a
known weak point. Nothing aspirational is included.

Platform: native Android (Kotlin), minSdk 26, compileSdk/targetSdk 36, AGP 8.13, Gradle 8.14.3. **No C++, no JNI, no CMake, no OpenCV.**
The only native binaries in the APK are ONNX Runtime's own `.so` files.

---

## 0. How to read the assessment column

| Mark | Meaning |
| --- | --- |
| **Current** | Matches what a team would choose today. |
| **Classical** | Older than 2015, still the right tool. Age is not a defect when the problem has not changed. |
| **Deliberate simplification** | A stronger method exists and was rejected for a stated reason (size, latency, dependency weight). |
| **Weak point** | I would change this given time. Flagged honestly. |

---

## 1. Screen capture and frame acquisition

| Technique | Where | Notes | Assessment |
| --- | --- | --- | --- |
| `MediaProjection` + `VirtualDisplay` + `ImageReader` | `MediaProjectionService` | One consent, one `createVirtualDisplay()` for the session's life. Resizes use `VirtualDisplay.setSurface()` / `resize()`. | **Current** — required API; the single-display rule is an Android 14 constraint learned from a crash. |
| `MediaProjection.Callback.onCapturedContentResize` | same | API 34+, keeps the capture surface matched to the shared app. | **Current** |
| Row-stride-aware `RGBA_8888` → `Bitmap` | `ImageFrameConverter` | Hardware buffers pad rows; naive copies shear the image. | **Current** |

The app captures the **phone's mirrored screen**, not a camera. The subject is whatever the eSight
"Share your view" app is displaying. This constraint drives section 2.

---

## 2. Viewport detection (new in 2.7.0)

**Problem.** A mirrored screen contains the app that carries the image: letterbox bars, a column of
large controls, an icon rail. Measured cost from a device log: 42% of all recognised "text lines" in
one session were interface glyphs (`0`×34, `D`×25, `O`×19), the cloud description literally named the
controls, and the resolution controller measured its text height from the buttons and collapsed the
detector to 640 px on a 2712 px capture.

**Method.** Per-row and per-column *inertness*: mean luminance **and** mean absolute first difference
along the strip. A strip is dead only if it is both very dark and nearly flat. The live region is the
longest unbroken run of live rows and of live columns.

```
inert(strip) ⟺ mean(luma) ≤ 12  ∧  mean|Δluma| ≤ 2      (0..255 scale)
viewport     = longestRun(¬inert columns) × longestRun(¬inert rows)
```

| Design choice | Reason |
| --- | --- |
| Variation, not brightness | A navy bottle in a dim room is darker than the interface next to it. A brightness threshold removed its label in testing. A letterbox bar is *flat*; a photograph never is, even at 26/255 — sensor noise guarantees it. |
| Longest run, not edge trimming | The controls are lively; the pure-black gutter beside them is not, so the run terminates there and leaves them outside. Edge trimming cannot do this. |
| Returns null when the whole frame is live | A phone screenshot is never cropped. The operation can only remove inert margins. |

**Provenance.** Custom. Closest published relatives are letterbox/pillarbox detection in video
transcoding (`ffmpeg cropdetect`, which thresholds on brightness only) and border removal in document
imaging. **Assessment: Current for the problem, but bespoke.** A learned approach (segmenting the
"live video region" of a screen recording) would generalise better and is worth considering if the
target app's chrome changes. Validated against three real captures plus two negative controls before
being written, and covered by 9 unit tests.

---

## 3. Visual target tracking — "did the subject move, or was it replaced?"

This is the core computer-vision pipeline. It replaced a 24×24 grayscale sampling grid with mean
absolute difference — a design that declared 439 "new targets" in 467 seconds while the user held one
bottle still.

### 3.1 Image representation

| Technique | Notes | Assessment |
| --- | --- | --- |
| Y′CbCr planes (luma + 2 chroma) | A red label replaced by a blue one with the same layout is invisible in grayscale; it is obvious in chroma. | **Current** |
| Image pyramid, 4 levels, separable **Burt–Adelson** `[1 4 6 4 1]/16` halving, min level 32 px | Coarse-to-fine search. Replaced a 2×2 box average: a box filter's side lobes fold energy above the new Nyquist limit back as aliasing, and on text that aliasing is *stable between frames* — something a tracker can lock onto instead of the text. | **Classical and correct** (Burt & Adelson 1983). |

### 3.2 Dense registration

| Technique | Provenance | Assessment |
| --- | --- | --- |
| Pyramidal **Lucas–Kanade** with a 6-parameter **affine** warp | Lucas & Kanade 1981; Bouguet 2001 (pyramidal) | **Classical, still standard.** |
| **Inverse-compositional** formulation | Baker & Matthews, *Lucas-Kanade 20 Years On*, IJCV 2004 | **Current best practice** for this family. Template gradients, steepest-descent images and the Hessian are precomputed once per level; each iteration costs only a warp, a subtraction and 6 dot products. |
| Hessian recomputation when coverage drops | Own addition | Necessary correctness fix: a 25% zoom leaves 64% of the template in view, and reusing a Hessian computed over pixels the error no longer covers stalls the descent. |
| Multi-start seeding at the coarsest level only | Own addition | Escapes local minima on quasi-periodic content (text). Gated by a `needsSeeding()` test so it does not hijack cases plain descent solves. |
| Corner-displacement convergence test | Own addition | Replaced a fixed bound on a raw warp coefficient, which stopped a 25% zoom at 1.007. |

Measured accuracy against synthetic ground truth: **translation 0.02 px, rotation 0.02°, scale 0.001**.

### 3.3 Sparse fallback (wide baseline / perspective)

| Technique | Provenance | Assessment |
| --- | --- | --- |
| **FAST-9** corner detection | Rosten & Drummond, ECCV 2006 | **Classical**, still the standard cheap detector. |
| **Harris / Shi–Tomasi** cornerness scoring | Harris & Stephens 1988; Shi & Tomasi 1994 | **Classical** |
| **Oriented BRIEF** descriptors, 256-bit, over a scale space | Calonder et al. ECCV 2010; Rublee et al. (ORB) ICCV 2011 | **Classical.** This is essentially a hand-rolled ORB. Modern learned descriptors (SuperPoint 2018, DISK 2020) are stronger but cost a neural network per frame. **Deliberate simplification.** |
| 5×5 box blur before descriptor sampling | Own fix | Without it only 7 of 200 matches survived a 15° rotation — below the 8 a homography needs. Detection still runs on the sharp plane. |
| **Lowe ratio test** (0.82) + cross-check | Lowe, IJCV 2004 | **Classical**, still standard. |
| **RANSAC**, locally optimised, quality ordered, marginally scored | Fischler & Bolles 1981; LO-RANSAC (Chum 2003); PROSAC (Chum & Matas 2005); MAGSAC++ (Barath 2020) | **Current.** PROSAC ordering draws early samples from the best matches; LO-RANSAC refits a promising minimal model over its own inliers before scoring; a smooth residual-decayed quality replaces the hard inlier count, so the answer no longer hinges on one threshold. Full VSAC's SPRT verification is still absent — unwarranted at tens of matches. |
| **Normalised DLT** (Hartley conditioning) for homography | Hartley, PAMI 1997 | **Classical, mandatory.** Unnormalised DLT is numerically indefensible. |
| 4-point minimal solve (direct 8×8) | standard | **Classical** |

Measured homography reprojection error on synthetic data: **1e-6**.

### 3.4 Numerical kernel

| Technique | Assessment |
| --- | --- |
| Gaussian elimination with partial pivoting | **Classical.** Correct for 6×6 and 8×8. |
| **One-sided Jacobi SVD** for the smallest singular vector | **Current.** Replaced inverse power iteration on `MᵀM`, which squared the condition number and gave back most of what Hartley normalisation buys. Jacobi never forms the normal equations and resolves small singular values to high *relative* accuracy — the property that matters when the answer *is* the smallest one. |

### 3.5 Change decision

| Technique | Provenance | Assessment |
| --- | --- | --- |
| **SSIM** (luminance × contrast-structure) on the registered pair | Wang, Bovik, Sheikh & Simoncelli, TIP 2004 | **Classical, still the standard perceptual metric.** LPIPS (2018) is better correlated with human judgement but needs a network. **Deliberate simplification.** |
| Contrast weighting by `√max(varA, varB)` | Own fix | Without it, two entirely different pages of mostly-blank paper scored 0.18 against a 0.30 bar. |
| Chroma difference as an independent trigger | Own | Catches same-layout / different-colour replacements. |
| Two-frame consensus before declaring a new target | Own | The measured separation gap is only 0.073 wide (worst same-subject 0.226, best different-subject 0.299), so a single frame on the wrong side must not decide. |

**Assessment of section 3 as a whole: sound, classical, correctly assembled.** A modern alternative
would be a learned tracker or optical-flow network (RAFT 2020). That would be heavier, need a GPU
delegate, and would not obviously beat inverse-compositional LK on a rigid planar subject. The
current design is defensible.

---

## 4. On-device OCR

### 4.1 Models

| Model | Source | Vintage |
| --- | --- | --- |
| `PP-OCRv5` mobile text detection | PaddleOCR, via RapidAI/RapidOCR ONNX export | **2025 — current** |
| `PP-OCRv5` Arabic recognition (multilingual) | same | **2025 — current** |
| `PP-OCRv5` English/Latin recognition | same | **2025 — current** |
| `PP-OCRv4` text-line orientation classifier | same | 2023 — current enough; v5 has no separate cls export |

Runtime: **ONNX Runtime Android AAR 1.28.0**, with the **XNNPACK** execution provider registered
best-effort. Models are pinned by SHA-256 checksum and fetched at build time; the checksum check is
not relaxable. Dictionaries are read from each model's own ONNX metadata (`character` key), which
structurally prevents pairing a dictionary with the wrong weights — a failure mode that produces
fluent nonsense rather than an error.

**Assessment: current.** PP-OCRv5 is state of the art for a mobile-size OCR stack in 2025–26.
Alternatives worth naming to a consultant: Apple Vision / ML Kit (no Arabic parity, closed),
Tesseract (clearly obsolete for this), TrOCR or a small VLM (far heavier, better on hard text).

### 4.2 Detection post-processing

| Technique | Provenance | Assessment |
| --- | --- | --- |
| **DB — Differentiable Binarization** probability map | Liao et al., AAAI 2020 | **Current** — DB is the standard detector head. |
| Connected-component flood fill (iterative, explicit stack) | classical | **Classical.** Recursion overflows on full-page paragraphs, hence the explicit stack. |
| Region score = **mean** probability inside the region | PaddleOCR convention | Correct — peak would let a few bright noise pixels pass as text. |
| "Unclip" dilation by `0.4 × min(w,h)` | approximation of PaddleOCR's Vatti clipping | **Deliberate simplification.** Real Vatti polygon offsetting (Clipper) is more faithful for rotated/curved text; uniform dilation on an axis-aligned box is coarser. **Weak point for curved text.** |
| Axis-aligned boxes only (no rotated rectangles / polygons) | own | **Weak point.** PP-OCR natively supports quadrilaterals. Axis-aligned boxes on tilted text include background and neighbouring lines, which is why section 4.4 exists. |
| Rejection census (4 named reasons, largest rejected region described) | own, 2.6.0 | Diagnostic instrumentation, not an algorithm. Answers "never detected" vs "detected then discarded". |

### 4.3 Recognition

| Technique | Provenance | Assessment |
| --- | --- | --- |
| **CRNN**-style recognition head | Shi, Bai & Yao, PAMI 2017 | **Classical, still the mobile standard.** |
| **CTC** decoding: greedy, then **prefix beam search** when the greedy reading is uncertain | Graves et al., ICML 2006; Graves & Jaitly, ICML 2014 | **Current.** Greedy maximises the best single alignment; CTC defines a string's probability as the sum over every alignment that collapses to it, and the two disagree exactly where a reading is most likely to be wrong. The beam runs only below a confidence floor, so a clean page still decodes at greedy cost. A lexicon/LM score is still absent. |
| Confidence = mean probability of **kept** characters only | own | Blank steps dominate; including them makes a wrong reading look confident. |
| Dual-head selection: Arabic head first, English specialist when any Latin appears | own | Rests on the Arabic head being multilingual, which is verified at load time by reading its own dictionary rather than assumed. |
| 180° orientation classifier with a high threshold | PP-OCR cls | Asymmetric cost: leaving a line alone costs one bad line; rotating a correct one costs a good line. |
| ImageNet normalisation for detection, symmetric `(x/255−0.5)/0.5` for recognition | PaddleOCR training convention | Not interchangeable — mixing them produces confident nonsense. |

### 4.4 Layout and script handling

| Technique | Provenance | Assessment |
| --- | --- | --- |
| Line grouping by vertical overlap, then horizontal merge of adjacent boxes | classical | Letterspaced type arrives as one blob per glyph; a recogniser shown a single letter has nothing to condition on. |
| **Skew estimation by least squares** over box centres, then page rotation and re-detection | classical | Deskewing the *page* and re-detecting beats straightening each crop: padding a wide strip vertically makes the text a sliver of the crop height and reads worse than the tilt did. |
| **Projection-profile** line segmentation (for text-height probing) | classical, 1990s document analysis | **Classical.** Simple, fast, and adequate because it only estimates a scale, never a transcription. |
| **Bidi reordering** via `java.text.Bidi` | Full UAX #9, backed by ICU | **Current.** Replaced a hand-written subset that recognised LTR runs from a character set assembled from cases that had already gone wrong — no notion of European versus Arabic numbers, neutral resolution, bracket pairs or isolates. Correctness rests on the reordering being an involution at levels 1–2, which is stated in the source rather than assumed; deeper nesting cannot arise from OCR output. Verified by logical→visual→logical round trips through the platform. |

---

## 5. Adaptive resolution control (2.5.0)

Replaced a user-facing "fast / balanced / maximum" menu. The three presets were three points on one
curve the app can evaluate itself.

**Closed form.** A DB detector works best when a text line is roughly 12–40 px tall *in its own
input*. For text `h` px tall in a capture of long edge `S`, the detector working at long edge `E`
sees `h·E/S`. Setting that to the target height:

```
E* = S · targetHeight / h          (targetHeight = 22)
```

| Technique | Assessment |
| --- | --- |
| Closed-form solve for working resolution | **Custom, and the right shape** — one measurable quantity, no preference to express. |
| **Ladder quantisation** (7 rungs, ~⅓ apart) | Necessary: ONNX Runtime re-plans on input-shape change, so a continuum is expensive. |
| **Deadband** (0.18) — hysteresis | **Classical control theory.** Stops oscillation across a rung boundary. |
| **Feed-forward** from the tracker's measured subject scale | Anticipates a zoom instead of correcting it a frame late. Standard feed-forward control. |
| **Bracketing search** when nothing is found (+1, −1, +2, −2 …) | "No text" and "text at the wrong scale" are indistinguishable from outside; only one is fixed by looking harder. |
| Text-height probe by row projection profile, for frames with no prior measurement | Deliberately conservative — reports nothing rather than a number it does not believe. |

**Assessment: this is a control problem solved as a control problem.** It is unusual in an OCR app
and, in my view, the most defensible original design in the codebase. Its known failure mode is
exactly what section 2 fixes: the measurement is only as good as what is in the frame.

---

## 6. Networking and cloud

| Technique | Notes | Assessment |
| --- | --- | --- |
| Gemini via **REST + SSE streaming**, OkHttp 5.4.0 | Sentence/phrase buffering before speech so the user does not hear network chunk boundaries. | **Current** |
| Per-request **network binding** (`ConnectivityManager.requestNetwork` + socket binding) | Only Gemini sockets and DNS ride the cellular network; the rest of the device is untouched. | **Current, and the correct API.** |
| `NET_CAPABILITY_VALIDATED` requested, with a recorded fallback | A radio that is up is not a network that carries traffic. A captive portal is indistinguishable from a slow model from inside the app. | **Current** |
| **Link-rate-aware upload budget** (2.7.0) | `linkDownstreamBandwidthKbps` at bind time sizes the JPEG. Derived from a session where 8 of 12 requests died at exactly 20.3 s on a link reporting **14 kbps** against Wi-Fi's 30,000. | **Custom.** Sound in principle; the risk is that the platform's bandwidth estimate is coarse. Instrumented so the next bundle settles it. |
| Absolute deadlines (`elapsedRealtime` instants, not countdowns) | A countdown stops when the process stops: a 24,000 ms budget was once enforced at 221,605 ms. | **Correct and non-obvious.** |

---

## 7. Speech

| Technique | Assessment |
| --- | --- |
| Android `TextToSpeech` with `UtteranceProgressListener`, per-utterance outcome tracking | **Current** — platform API. |
| Script-run segmentation (Arabic / Latin) with per-segment locale | Necessary for bilingual lines. |
| **Delivery ledger**: a page counts as read only when the engine reports the user heard it | Fixes a real defect — recording at enqueue time meant 29 utterances submitted, 15 completed, and the difference silently marked as heard. |
| Continuation matching: token normalisation, line-key equality, substring containment (≥4 chars), token-containment ≥0.82 | **Custom heuristics, and the weakest link in the app.** See section 9. |

---

## 8. Diagnostics

| Technique | Assessment |
| --- | --- |
| JSONL append-only event log with a bounded fsync policy | Standard. |
| **Difference hash (dHash)**, 64-bit, one-way | Perceptual hashing, ~2013 vintage, non-reconstructive by design. **Classical, fit for purpose.** |
| **Laplacian variance** focus measure | Pech-Pacheco et al., ICPR 2000. **Classical, still the standard cheap blur metric.** |
| Histogram percentiles (p05/p50/p95), Shannon entropy, dark/bright pixel ratios | Standard image statistics. |
| **Outcome-rule verdict engine** (15 rules producing a ranked diagnosis) | **Custom.** Compares what the pipeline produced against what the user received. Replaced 2,109 per-event "a stage was slow" findings that said nothing. |
| Finding budget (burst + 25% escalation, remainder counted and summarised once) | Rate limiting with a preserved tail. |

Explicit privacy property: **no image data of any kind leaves the device by default.** Screen frames
are captured only when the user explicitly enables failure-frame capture, are bounded at 40 frames /
24 MB, and the count is declared in the archive name, manifest and readme.

---

## 9. Honest weak points

Listed so a reviewer does not have to find them.

0. **Not yet done, and named for a reviewer:** the detector still reduces regions to axis-aligned
   rectangles rather than quadrilaterals; DB unclip is still uniform dilation rather than polygon
   offsetting; the feature stack is still hand-rolled ORB rather than a learned detector/descriptor
   (XFeat, EdgePoint2); target identity still ends at SSIM plus colour rather than a semantic
   embedding; the OCR models are PP-OCRv5 rather than v6, and the orientation classifier is v4;
   inference is ONNX Runtime on CPU rather than LiteRT with an NPU delegate. Each is a real upgrade
   with a real cost — new model assets, a new runtime, or a refactor of the detection geometry — and
   none of them is a line change.

1. **Continuation/dedup heuristics (`DocumentSpeechPolicy`, `ReadingLedger`).** Hand-tuned string
   rules deciding whether a page is "the same page". This has produced two real user-visible bugs:
   a 24-character noise floor that discarded "PARFUM" 42 times, and substring matching that let
   one-character interface glyphs suppress "NIVEA" and "BLEU DE CHANEL" seven times. Both are fixed,
   but the *approach* remains fragile. A better design would track content identity by detected box
   geometry rather than by string similarity.
2. **Axis-aligned detection boxes.** PP-OCR supports quadrilaterals; this pipeline reduces everything
   to a rectangle and compensates with page-level deskew. Curved or strongly rotated text is a known
   gap.
3. **CTC greedy decoding with no language model.** Short product names and codes would benefit most
   from beam search with a lexicon.
4. **Uniform dilation instead of Vatti polygon offsetting** in DB post-processing.
5. **Inverse power iteration instead of SVD** for the homography null vector.
6. **350 hand-tuned constants.** Each is individually justified in a comment with the measurement
   behind it, but the *interactions* are not documented — and the most recent serious defect was
   exactly an interaction between two individually correct components (the resolution controller
   measuring text height from interface buttons).
7. **Device testing is new, thin, and infrastructure-dependent.** 286 pure-JVM tests plus four
   on-device tests running on a Gradle managed device — the latter need KVM on the runner, which
   the first attempt did not have. They run in their own CI job so an emulator that will not boot
   fails the run without stopping the APK. The four are: the models load, a page reads end to end, and the viewport geometry works
   on a real `Bitmap`. That closes the "nothing has ever run on Android" gap, but four tests are a
   floor, not coverage. MediaProjection itself, the foreground service, rotation, TTS interruption
   and network switching are still unexercised.

---

## 10. Summary for a reviewer

- **Vision/tracking:** classical, correctly assembled, measured against ground truth. Not the newest
  possible, deliberately so — the alternatives are learned models with real cost.
- **OCR models:** current (PP-OCRv5, 2025). The surrounding post-processing is classical and has two
  named simplifications.
- **Resolution control:** original, and the strongest design decision in the codebase.
- **Viewport detection:** original, necessary, bespoke; the most likely place for a regression.
- **Networking:** correct use of modern Android APIs, with one novel link-aware sizing rule.
- **Text-continuation logic:** the weakest part, and the source of most user-visible defects to date.
- **Diagnostics:** unusually strong; the app diagnoses itself from its own logs.

The honest one-line summary: **the mathematics is sound and mostly classical, the models are current,
and the risk lives in the hand-written heuristics that glue them together — not in the algorithms
themselves.**
