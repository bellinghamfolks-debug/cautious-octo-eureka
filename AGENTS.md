# Codex operating guide

## Goal
Maintain a native Kotlin Android accessibility application that captures a user-approved screen stream with MediaProjection and produces spoken OCR or scene descriptions.

## One path only
There is exactly one reading path and one describing path. An earlier parallel analysis stack
(`FrameAnalysisCoordinator`, `GeminiVisionRepository`, `RoutingVisionRepository`,
`PaddleOcrVisionRepository`, `AnalyzeFrameUseCase`, `VisionAiRepository`, `TextImageEnhancer`) was
deleted in build 65: 2,883 lines that nothing could reach, kept alive only by referring to each
other. It cost real debugging time — every audit of "does X run on the live path?" had to rule it
out first. Do not reintroduce a second engine. If a file is not reachable from
`MediaProjectionService`, `VerifiedSettingsScreen` or the manifest, delete it.

## Non-negotiable constraints
- Native Android only. No WebView, React Native, Flutter, or browser UI.
- Kotlin, Jetpack Compose, Coroutines, StateFlow, and the existing manual dependency container.
- Never commit an API key, signing key, captured frame, or user content.
- Do not replace Android Keystore encryption with plain preferences.
- Do not bind the whole process to cellular. Bind only Gemini HTTP sockets and DNS through the selected `Network`.
- MediaProjection consent is one session and one `createVirtualDisplay()` call. Preserve callback registration and cleanup.
- Keep all controls usable with TalkBack: meaningful labels, large targets, logical focus order, no color-only state.
- Scene output is assistive, not a safety guarantee. Keep the warning in the UI and README.

## Validation required before finishing any change
Run exactly:

```bash
./scripts/codex-check.sh
```

A change is not complete until `lintDebug`, `testDebugUnitTest`, and `assembleDebug` all pass.

There are no build flags and no native compilation step. The project is Kotlin only: the on-device
reader is PP-OCRv5 driven through the prebuilt ONNX Runtime Android AAR, so a full build is minutes,
not the hour a llama.cpp compile used to take.

The four PP-OCR models are not committed. `scripts/fetch_ocr_models.py` fetches them into
`app/src/main/assets/ppocr/` against pinned SHA-256 digests, and Gradle's `preBuild` depends on it,
so the first build of a fresh clone needs network access. Never commit the fetched assets, and never
relax the checksum check — it is what stops a mirror serving a model that reads plausible nonsense.

Pure logic is covered by `testDebugUnitTest`, which runs on a workstation. Anything that depends on
ONNX Runtime actually executing — model loading, arm64, XNNPACK, memory, end-to-end accuracy — is
covered by `app/src/androidTest/.../PaddleOcrOnDeviceTest.kt`, which needs a real device. The
`Device test (Firebase Test Lab)` workflow runs it on demand; see `docs/DEVICE_TESTING.md`. Do not
assert on-device behaviour from unit tests alone.

## Important files
- `capture/MediaProjectionService.kt`: foreground capture lifecycle, frame throttling, and the one
  place that chooses a lane for each frame.
- `capture/FrameBoundCoordinator.kt`: the single analysis lane. One turn is active at a time and a
  superseding turn cancels it.
- `data/gemini/GeminiLiveTransport.kt`: the persistent Live WebSocket. Scene description is answered
  as native audio; a reading is answered as native audio plus a `report_visible_text` function call
  whose arguments carry the page's exact characters, because an audio session's only other text is a
  transcript of its own speech. Read `LiveModelDirectory` and `LiveCloseVerdict` before changing how
  a model is chosen or a refusal is judged — both encode verdicts measured on a real device.
- `data/network/CellularNetworkManager.kt`: per-request cellular acquisition.
- `data/security/AndroidKeystoreApiKeyStore.kt`: AES-GCM key storage.
- `data/speech/BilingualTtsEngine.kt`: Arabic/English speech segmentation and the ordered, lossless
  reading queue. Blocks of one reading are never dropped for capacity; only a superseding reading
  discards them.
- `data/speech/ReadingLedger.kt`: decides whether a recognized page is new, a continuation of one the
  user has partly heard, or already read. Text reading has no per-block de-duplication.
- `data/speech/DocumentSpeechPolicy.kt`: pure line-level page identity and continuation rules.
- `ui/MainScreen.kt`: minimal TalkBack-first surface — status, mode, start/stop.
- `ui/SettingsScreen.kt`: everything configured once — key, model, capture accuracy, speech,
  diagnostics.
- Routing between cloud and on-device is `data/gemini/LiveTransportRouting.kt` plus the lane choice
  in `MediaProjectionService`. Never add a silent local-to-cloud fallback; choosing the local engine
  is a choice about where screen content goes.
- `data/paddleocr/`: the optional on-device reader — PP-OCRv5 detection, orientation and the Arabic
  and English recognition heads, run through ONNX Runtime. `PaddleOcrEngine` is driven directly by
  `FrameBoundCoordinator`. It reads text only and refuses scene description by design; do not paper
  over that with a stub description. See `docs/LOCAL_OCR_SETUP.md`.
- `data/paddleocr/RecognizedLine.kt`: the rule that picks between the two recognizers' readings of
  the same crop. The two confidences are not comparable — read the comment there before changing it.
