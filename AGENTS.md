# Codex operating guide

## Goal
Maintain a native Kotlin Android accessibility application that captures a user-approved screen stream with MediaProjection and produces spoken OCR or scene descriptions.

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

## Important files
- `capture/MediaProjectionService.kt`: foreground capture lifecycle and frame throttling.
- `capture/FrameAnalysisCoordinator.kt`: hybrid local/cloud analysis policy.
- `data/network/CellularNetworkManager.kt`: per-request cellular acquisition.
- `data/gemini/GeminiVisionRepository.kt`: official Gemini REST call.
- `data/security/AndroidKeystoreApiKeyStore.kt`: AES-GCM key storage.
- `data/speech/BilingualTtsEngine.kt`: Arabic/English speech segmentation.
- `ui/MainScreen.kt`: TalkBack-first Compose interface.
