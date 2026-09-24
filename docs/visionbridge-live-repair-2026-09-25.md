# VisionBridge live-pipeline repair — 2026-09-25

## Field evidence from build 48

The camera, image capture, API key, network, Gemini response parsing, and Android TTS engine are all demonstrably functional. The broken behavior comes from pipeline policy and transport design.

1. **Cloud speech self-cancels before it starts.**
   - A valid cloud result reached `TextToSpeech.speak()` successfully.
   - Voice activation consumed roughly 0.95–1.30 s.
   - The old one-second visual speech deadline then stopped or rejected the utterance before `onStart()`.
   - Result: correct Gemini text existed, `speak()` returned success, but the user heard no content.
   - Fix is committed separately: deadline starts after engine submission, READ_TEXT freshness is owned by TurnGate, and Arabic/English voices are cached/prewarmed.

2. **The active cloud transport is not Live.**
   - Runtime wiring is `FrameBoundCoordinator -> FrameTurnTransport -> streamGenerateContent?alt=sse`.
   - Build-48 diagnostics label some events `cloudLiveDirect=true`, but no Live WebSocket is in the active AppContainer path.
   - Reused HTTP connections still needed ~3.48–6.16 s from upload completion to response headers. Upload itself was only ~47–54 ms. This is model/turn latency, not bandwidth.
   - Therefore Android TTS tuning cannot make the existing cloud path feel live.

3. **Per-frame HTTP is repeatedly cancelled by target changes.**
   - Session A: 62 selected frames, 3 cloud submissions.
   - Session B: 93 selected frames, 2 cloud submissions.
   - Several submitted turns were invalidated before first useful output because a new visual generation cancelled the active frame-bound job.
   - ONE_ACTIVE_ONE_LATEST is acceptable for fallback HTTP, but it cannot be the primary live-vision transport.

4. **Optional PP-OCR is no longer the primary blocker.**
   - Build 48 correctly records `ADVISORY_NONBLOCKING`.
   - Optional grounding can time out without preventing the model result from being committed.
   - Do not reintroduce synchronous grounding into the user-visible output path.

5. **Evidence integrity is now good.**
   - Latest export: 189 referenced images, 189 exported images, no missing or colliding references.
   - Keep the session-qualified evidence naming.

## Required architecture

### A. Primary cloud path: persistent Gemini 3.8 Live

Create a new immutable-turn transport. Do not restore the retired mutable Live implementation wholesale.

- Model: `gemini-3.8-live`.
- Protocol: one persistent stateful WebSocket for the capture session.
- Open and finish Live setup when capture starts, before the first useful visual candidate.
- Video input: individual JPEG frames through `realtimeInput.video`.
- Respect the Live video limit. The primary cadence is at most one accepted visual frame per second, with immediate submission when a genuinely new target is confirmed.
- Response: native model audio.
- Enable output audio transcription for UI text, diagnostics, reading ledger, and accessibility history.
- Cloud speech must bypass Android TTS completely.

### B. Direct audio path

`Gemini Live audio packet -> LivePcmAudioPlayer -> AudioTrack`

There must be no intermediate:
- complete-response wait,
- local OCR wait,
- sentence-size wait,
- Android `TextToSpeech`,
- one-second pre-start deadline.

Record:
- `LIVE_FRAME_SENT`
- `LIVE_FIRST_AUDIO_PACKET`
- `LIVE_AUDIO_PLAYBACK_STARTED`
- `LIVE_OUTPUT_TRANSCRIPTION`
- `LIVE_TURN_COMPLETE`
- `LIVE_TURN_INTERRUPTED`

### C. Immutable live-turn identity

Every accepted visual target receives:

```
LiveTurn(
  epoch,
  visualGeneration,
  frameId,
  capturedAtNanos,
  imageHash
)
```

Rules:
1. Only one current epoch may publish audio or transcription.
2. A new confirmed target increments epoch before submission.
3. Old-epoch PCM is rejected by `LivePcmAudioPlayer`.
4. Server `interrupted=true` immediately flushes old playback and is normal control flow, not an app failure.
5. `turnComplete` closes the current response state.
6. Never relabel late packets from turn N as turn N+1.
7. No global mutable "current frame" may be read by an asynchronous callback.

### D. Visual supersession

On a confirmed new visual target:
1. freeze the new immutable `LiveTurn`;
2. invalidate old publication rights;
3. flush old audio if interruption is enabled;
4. send the new frame;
5. send client content for that frame with a complete turn boundary;
6. let the Live server interrupt the old generation;
7. keep the WebSocket open.

Do not close/reopen the socket for target changes.

### E. Text-reading behavior

For cloud TEXT_READING:
- ask for literal visible text only;
- native Live audio reads it immediately;
- output transcription updates the UI/ledger;
- strict trust OFF: transcription/audio is publishable immediately;
- strict trust ON: any optical validation may affect final trust metadata or retry policy, but must not hold the first audio packet unless the user explicitly selected strict verified reading.

For local OCR:
- continue using Android TTS;
- use the fixed post-submit engine-start watchdog and prewarmed voices.

### F. Fallback

Keep `FrameTurnTransport` only as a fallback for:
- Live setup failure,
- unsupported configuration,
- controlled diagnostics.

Fallback rules:
- never block on advisory grounding;
- never discard a completed usable response only because optional verification timed out;
- preserve immutable frame identity;
- keep direct streamed TTS chunks;
- do not restart HTTP merely because speech is still playing.

## Code areas

Primary:
- `AppContainer.kt`: wire the new Live transport and `LivePcmAudioPlayer`.
- new `GeminiLiveTransport.kt`: persistent WebSocket and immutable epoch state.
- `MediaProjectionService.kt`: send confirmed targets to Live at <=1 fps; stop claiming `cloudLiveDirect=true` until this path is actually wired.
- `LivePcmAudioPlayer.kt`: keep epoch filtering; add first-playback timing.
- `FrameBoundCoordinator.kt`: fallback/local path and publication identity, not the owner of the primary Live socket.
- `FrameTurnTransport.kt`: fallback HTTP/SSE only.

Do not put optional PP-OCR, tracking, or Android TTS in the critical Live audio path.

## Release gates

A candidate is not "live" unless device diagnostics prove all of these:

- capture -> Live frame submission: median <= 0.5 s;
- Live submission -> first audio packet: median <= 1.2 s;
- capture -> audible playback: median <= 1.5 s, P90 <= 2.5 s;
- zero valid cloud utterances killed by a pre-submit TTS deadline;
- every confirmed new target gets a Live submission without waiting for the previous model response to finish;
- no audio packet from an obsolete epoch is played;
- no old response is relabelled as a new frame;
- optional grounding never blocks first audio when strict trust is off;
- projection loss is announced audibly and represented separately from model/pipeline failure.

The existing build-48 HTTP/SSE path cannot satisfy these gates consistently. The transport must change, not just its timeout constants.
