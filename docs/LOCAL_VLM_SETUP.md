# On-device VLM engine — setup and operation

VisionBridge can run both of its features entirely on the phone: **Read** (OCR)
and **Describe** (scene description). The on-device engine is an optional
fallback selected by a single switch in Settings. Cloud Gemini remains the
default and is untouched by anything in this document.

---

## Read this before you enable it

Two honest limitations, because they affect a blind user's trust in what they
hear:

1. **Arabic transcription quality is materially below cloud Gemini.** A 3B-class
   quantized VLM is good at English and Latin-script layouts and noticeably
   weaker at dense Arabic, especially small text, diacritics and mixed RTL/LTR
   tables. The app mitigates hallucination structurally — greedy decoding for
   Read, the loop guard, the sanitizer, and the reading ledger that refuses to
   re-read a page — but it cannot make a small model see glyphs it cannot
   resolve. For reading bank balances, medication labels or anything where a
   wrong digit matters, the cloud engine is the correct choice.
2. **It is slow.** Expect 3–15 seconds per frame for Describe and 10–40 seconds
   for a dense page with Read on a 2024 flagship, versus roughly 1–3 seconds for
   cloud. The pipeline handles this correctly — a page is read once, completely,
   and the next analysis is refused while the current one is still being spoken
   — but the latency is real.

The on-device engine earns its place when there is no connectivity, when data
cost matters, or when screen content must not leave the device. It is not a
drop-in quality replacement.

---

## Step 1 — Architecture and dependencies

The engine is a native library built from a pinned llama.cpp, plus a thin JNI
bridge and a Kotlin repository that implements the app's existing
`VisionAiRepository` interface.

```
ui/SettingsScreen ──(useLocalVlm switch)──► SettingsRepository
                                                  │
capture/FrameAnalysisCoordinator                  │
        │                                         ▼
        └─► AnalyzeFrameUseCase ─► data/vision/RoutingVisionRepository
                                          │                  │
                              (switch off)│                  │(switch on)
                                          ▼                  ▼
                        data/gemini/GeminiVisionRepository   data/localvlm/LocalVlmVisionRepository
                                                                        │
                                                             LocalVlmEngine (JNI)
                                                                        │
                                                             libvisionbridge_vlm.so
                                                             └── libmtmd + llama.cpp (static)
```

Because the local repository implements the same interface, everything
downstream is shared and unchanged: the reading ledger that guarantees a page is
spoken once and completely, the ordered lossless speech queue, the diagnostics
recorder, and the capture coordinator. **The coordinator cannot tell which
engine produced the text.**

### Files

| Path | Role |
| --- | --- |
| `app/src/main/cpp/CMakeLists.txt` | Fetches and builds the pinned llama.cpp, arm64-only |
| `app/src/main/cpp/vlm_bridge.cpp` | JNI bridge: load, generate, cancel, free |
| `data/localvlm/LocalVlmEngine.kt` | Handle lifecycle, memory, threading, image packing |
| `data/localvlm/LocalVlmModelStore.kt` | Locates, validates and installs the GGUF files |
| `data/localvlm/LocalVlmPrompts.kt` | The two prompts, chat template, token budgets |
| `data/localvlm/VlmOutputSanitizer.kt` | UTF-8 cleanup, bidi stripping, loop suppression |
| `data/localvlm/LocalVlmVisionRepository.kt` | Implements `VisionAiRepository` |
| `data/vision/RoutingVisionRepository.kt` | Cloud vs local routing |

### 64-bit enforcement, three independent layers

1. `build.gradle.kts` — `abiFilters += "arm64-v8a"` in both `defaultConfig.ndk`
   and `externalNativeBuild.cmake`.
2. `CMakeLists.txt` — configuration **fails** if `ANDROID_ABI` is anything other
   than `arm64-v8a`, so a stray command-line or IDE build cannot produce a
   32-bit object.
3. CI — the native job unzips the APK and fails if any ABI other than
   `arm64-v8a` appears under `lib/`.

Only what the engine needs is compiled. Tests, examples, the server, cURL and
OpenMP are off; everything is statically linked into one `.so`; and the linker
drops unused sections.

`LLAMA_BUILD_COMMON` and `LLAMA_BUILD_TOOLS` must both stay **on**, because
llama.cpp gates `add_subdirectory(tools)` on both and `libmtmd` lives there.
That subdirectory also defines `llama-cli`, `llama-bench`, `llama-mtmd-cli` and
a dozen other executables. They are never compiled: Gradle restricts the build
to a single target.

```kotlin
externalNativeBuild { cmake { targets += "visionbridge_vlm" } }
```

Without that line AGP builds every target CMake defines, which is both the bloat
this project is meant to avoid and the difference between a two-minute and a
twenty-minute build.

### Two compiler settings that must not be changed back

- **Do not put `-fno-rtti` in the global `CMAKE_CXX_FLAGS`.** Flags set at the
  top level are inherited by the llama.cpp subproject, and `llama-context.cpp`
  uses `dynamic_cast` on the KV cache. Visibility and section flags belong on
  the `visionbridge_vlm` target via `target_compile_options`, never globally.
- **Do not set `GGML_CPU_ARM_ARCH`.** Forcing an `-mcpu` value made ggml's ARM
  feature probe fail with "Failed to get ARM features", because the probe
  compiles a test with the host compiler, which rejects an AArch64 CPU name.
  Left alone, ggml detects dot-product and fp16 support for the
  `aarch64-linux-android` target itself and compiles its ARM quantization and
  repack kernels. This also means there is **no** Armv8.2 hardware floor: any
  arm64-v8a device is supported, subject only to having enough RAM.

### Build flag

The native build adds roughly fifteen minutes on a cold cache.

```bash
./gradlew assembleDebug                                  # with the local engine
./gradlew assembleDebug -Pvisionbridge.enableLocalVlm=false   # Kotlin only, fast
```

With the flag off the app still builds and runs; turning the switch on simply
reports the engine unavailable.

### Toolchain

- Android NDK 27 (`ndk;27.0.12077973`) and CMake 3.22.1 via `sdkmanager`.
- ARM CPU features are detected by ggml for the target, not forced. See the two
  compiler settings above that must not be changed back.

---

## Step 2 — Choosing and installing the model

Weights are **never bundled in the APK**. They are one to three gigabytes, far
past any store limit, and dead weight for users who stay on cloud.

You need **two GGUF files**: the quantized model and its multimodal projector.

### Recommended

| | Model | Projector | RAM at runtime |
| --- | --- | --- | --- |
| Best bilingual quality | Qwen2.5-VL 7B, `Q4_K_M` (~4.7 GB) | `mmproj` F16 (~1.4 GB) | 12 GB device only |
| **Recommended balance** | **Qwen2.5-VL 3B, `Q4_K_M` (~2.2 GB)** | **`mmproj` F16 (~1.3 GB)** | **8 GB device** |
| Lowest footprint | Qwen2-VL 2B, `Q4_K_M` (~1.5 GB) | `mmproj` F16 (~1.3 GB) | 6 GB device |

Qwen2.5-VL is the recommended family: it has the strongest Arabic and the
strongest document/OCR behaviour of the small open VLMs, and llama.cpp's
`libmtmd` supports it. LLaVA-family models are supported by the same code path
but are effectively English-only and should not be used here.

> Verify licence terms for your distribution before shipping any weights.

### Installing on the device

The app copies the files into its own private storage, so no storage permission
is involved and the files are removed when the app is uninstalled.

1. Put both `.gguf` files somewhere the system file picker can reach (Downloads,
   or a USB/SD volume).
2. Open **الإعدادات → محرك التحليل**.
3. Tap **تثبيت ملف النموذج (GGUF)** and pick the quantized model.
4. Tap **تثبيت ملف الرؤية (mmproj)** and pick the projector.
5. Turn on **استخدام الذكاء المحلي على الجهاز**.

Each import is validated by size and by the `GGUF` magic bytes, is streamed to a
`.partial` file first, and is only renamed into place on success — an
interrupted copy can never leave a half-written model that fails to load with a
confusing error.

For development, `adb` is equivalent:

```bash
adb push qwen2.5-vl-3b-q4_k_m.gguf \
  /sdcard/Android/data/com.abdullah.visionbridge/files/
# then import it through the picker, or for a rooted/debug device:
adb shell run-as com.abdullah.visionbridge mkdir -p files/models
```

The store expects exactly these names in `filesDir/models/`:
`vlm-model.gguf` and `vlm-mmproj.gguf`.

---

## Step 3 — Engine initialization and memory management

Memory is the binding constraint, so the lifecycle is explicit rather than
lazy-forever.

- **Loaded on demand, off the main thread.** `ensureLoaded()` runs on
  `Dispatchers.IO` behind a mutex; repeat calls are free once resident, so it is
  safe to call before every frame.
- **Weights are `mmap`'d, never `mlock`'d.** The kernel can evict clean pages
  under pressure instead of the process being killed. This is the single most
  important setting for surviving a 2 GB model on a phone.
- **KV cache is F16 and cleared after every frame.** A single image plus one page
  of text never needs a growing cache, and holding it doubles idle memory
  between captures.
- **One inference at a time**, serialized by a mutex. Batching images is what
  pushes a 3B VLM past the memory ceiling.
- **Released on `onTrimMemory` and on capture stop.** A foreground service
  holding two gigabytes it is not using is a service Android will kill
  mid-sentence. The next frame reloads from the mmap'd file, which is far
  cheaper than a process restart.
- **Threads:** `availableProcessors() - 1`, clamped to 2–6, so capture, TTS and
  the UI are not starved during a long transcription.
- **CPU only** (`GPU_LAYERS = 0`). Android GPU backends for llama.cpp remain
  unreliable across vendors, and a wrong answer read aloud confidently is worse
  than a slow one. Raise it deliberately after testing on target hardware.

---

## Step 4 — The cloud/local router

`RoutingVisionRepository` reads `useLocalVlm` per request, so toggling the
switch takes effect on the next captured frame without restarting capture. Both
**Read** and **Describe** follow the switch together.

Two deliberate behaviours:

- **No silent fallback from local to cloud.** Choosing the on-device engine is
  frequently a choice about where screen content goes. Quietly uploading a frame
  because a model file was missing would break that expectation without the user
  ever being told. A missing or unloadable model raises an actionable error,
  which the coordinator speaks.
- **The trust gate is bypassed in local mode.** That gate cross-checks Gemini's
  Latin tokens against ML Kit output from the same frame; it is a cloud-specific
  check and does not apply to a transcription that never left the device.

The Gemini API key is not required while the switch is on. Capture, scene
description and reading all work with no key and no network.

---

## Step 5 — Prompts and anti-loop execution

Both operations use ChatML with the image placed **before** the instruction;
Qwen-VL attends noticeably better to an instruction that follows its visual
tokens.

`executeLocalRead` → `LocalVlmPrompts.build(TEXT_READING, …)`
`executeLocalDescribe` → `LocalVlmPrompts.build(SCENE_DESCRIPTION, style)`

The prompts are deliberately much shorter than the cloud prompts. A 3B quantized
model does not reliably follow a nine-clause instruction list; long prompts make
it start summarizing the instructions instead of the image.

### Four independent defences against the fragment-looping failure

Prompting alone does not stop a small model from cycling. The defences are
layered so that no single one has to be perfect:

1. **Prompt** — "exactly once, top to bottom", "do not repeat any line you have
   already transcribed", and an explicit "stop immediately when you reach the
   last line".
2. **Sampling** — greedy decoding (`temperature = 0`) for Read, so nothing is
   invented, plus a `1.12` repetition penalty over a 320-token window.
3. **`VlmLoopGuard`** — watches the produced text and **stops generation** the
   moment a block of ≥12 characters repeats three times consecutively, ignoring
   whitespace differences. A repetition penalty discourages repeating *tokens*;
   it does nothing about a model re-emitting a whole clause with different
   spacing, which is the form the bug actually takes. This also saves several
   seconds of wasted decoding.
4. **`VlmOutputSanitizer`** — removes chat-template tokens, conversational
   openers in both languages, markdown fences, stuttered characters, adjacent
   duplicate lines, and any repeating tail the guard left behind.

Above all of that, the app's existing `ReadingLedger` still decides whether the
page deserves to be spoken at all, so even a perfect-looking duplicate
transcription of a page already read produces silence rather than a repeat.

### Bilingual UTF-8 correctness

- Token pieces are emitted to Kotlin **only on UTF-8 character boundaries**. A
  Qwen tokenizer routinely splits a single Arabic code point across two tokens,
  and forwarding half a sequence produces replacement characters that a screen
  reader announces as garbage. `incomplete_utf8_tail` in the bridge holds the
  partial bytes back until the next token completes the character.
- Strings cross JNI as real UTF-8 via `new String(byte[], UTF_8)`, not
  `NewStringUTF`, which uses modified UTF-8.
- The sanitizer strips explicit bidi controls (LRM, RLM, embeddings, overrides,
  isolates) while **keeping ZWNJ and ZWJ**, which carry meaning in Arabic-script
  orthography. Correct reading order comes from applying the Unicode bidi
  algorithm to clean text, not from overrides a language model guessed at.
- Lone surrogates and `U+FFFD` from truncated boundaries are dropped.

Mixed runs keep their visual order exactly: `ابدأ OpenAI ثم اضغط Save الآن.`
survives sanitization unchanged, which is covered by a unit test.

---

## Step 6 — Verifying an installation

```bash
./scripts/codex-check.sh                  # lint + unit tests + APK
unzip -Z1 app/build/outputs/apk/debug/app-debug.apk 'lib/*'   # must show arm64-v8a only
```

On device, export the diagnostics bundle from Settings and confirm the timeline
contains:

| Event | Meaning |
| --- | --- |
| `VISION_ENGINE_SELECTED` | `engine=LOCAL_VLM`, plus install and load state |
| `LOCAL_VLM_LOAD_STARTED` / `_COMPLETED` | Load time and free memory around it |
| `LOCAL_VLM_GENERATE_STARTED` / `_COMPLETED` | Per-frame latency and output size |
| `LOCAL_VLM_LOOP_DETECTED` | The guard cut a degenerate repetition |
| `LOCAL_VLM_OUTPUT_SANITIZED` | Raw versus cleaned text, side by side |
| `LOCAL_VLM_MEMORY_PRESSURE` / `_RELEASED` | Model unloaded and why |
| `DOCUMENT_READING_ACCEPTED` / `_SKIPPED` | The ledger's decision for that page |

`LOCAL_VLM_OUTPUT_SANITIZED` records the raw and cleaned text together, which is
the fastest way to tell a model-quality problem from a post-processing one.
