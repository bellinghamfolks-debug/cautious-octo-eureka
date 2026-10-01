# مقام ستوديو · Maqam Studio

An iOS app for Arabic singing: record or import a vocal, hear it against its
maqam with real quarter tones, and (in later phases) tune and finish it
automatically. Arabic first, English second, built to be used fully with
VoiceOver.

## Status

| Phase | Scope | State |
|---|---|---|
| 1 | Project structure, import, playback, recording, waveform, save/open | **Implemented** — this folder |
| 2 | Pitch track, pitch curve, note list | Core detector done and tested; UI not started |
| 3 | Maqam engine UI: custom maqamat, tuning tables, per-degree offsets | Built-in catalog and matcher done; manual choice of maqam and tonic in the app |
| 4 | Pitch correction (offline, then live) | Not started |
| 5 | Maqam and tonic detection with confidence | Not started. The app says so and asks for a manual choice |
| 6 | Auto Studio chain and genre profiles | Not started |
| 7 | Export, stem separation interface | Not started |

Nothing in the interface is a placeholder: a control appears only when the
work behind it exists.

## Layout

```
MaqamStudio/
  Core/                 C++17 DSP core, no platform dependencies
    include/maqam/      fft, levels, tuning, maqam, pitch_detector
    src/                implementations
    capi/maqam_core.h   the C interface Swift calls (plain C types only)
    tests/              34 cases: quarter tones, maqam phrases, vibrato, noise
  App/
    Core/               Swift wrappers over the C API, AppError
    Audio/              AudioEngine (play + record), session, file reader
    Project/            ProjectDocument, ProjectStore, UndoHistory
    Accessibility/      Announcer, AudioDescription (waveform as sentences)
    Localization/       L10n: runtime Arabic/English switching
    UI/                 SwiftUI views
    Resources/          ar.lproj, en.lproj, asset catalog
  Tests/Unit            XCTest: projects, audio files, maqam, localization, undo
  Tests/Fixtures        synthesised WAVs (tools/make_fixtures.py)
  tools/                localization checker, fixture and icon generators
  scripts/              ci-core.sh (any OS), ci-ios.sh (macOS)
  project.yml           XcodeGen project definition
```

## Rules the code keeps

- **Microtones are never rounded.** Pitch is cents from the tonic; maqam
  degrees are cents (Rast's third is 350, Bayati's second 150). Note names use
  24 quarter-tone steps, and only for naming.
- **The original is never modified.** It is copied into the project byte for
  byte, made read-only, and checked by SHA-256 each time the project opens.
  Edits are parameters in `project.json`.
- **The audio thread is never blocked.** The input tap copies the buffer and
  hands it to a writer queue; no file I/O or allocation happens on the render
  thread. The pitch detector preallocates everything and `mq_pitch_detect` is
  real-time safe. Offline analysis runs on a background task in one-second
  chunks.
- **Local by default.** No audio leaves the device. Any future cloud feature
  will be off until the user turns it on, and labelled as cloud.
- **Accessibility does not depend on the waveform.** The waveform is also an
  Audio Graph, an adjustable control, and a spoken description of where the
  singing, the pauses and the loudest moment are. Every control has a label,
  progress and state changes are announced, and scales are read as note names.

## Projects

A project is a folder `<uuid>.maqamproj` in the app's Documents/Projects:
`project.json` (atomic saves), `project.json.bak` (previous good save, used
automatically if the main file is damaged), `analysis.json` (cache, tied to the
original's checksum), `audio/original.*`, and `session.lock`. A lock left by an
earlier run means the app ended while the project was open, and it is reopened
with a recovery notice. Undo and redo cover every edit.

## Building

Requirements: Xcode 16 or later, [XcodeGen](https://github.com/yonaskolb/XcodeGen).

```bash
cd MaqamStudio
xcodegen generate
open MaqamStudio.xcodeproj
```

Checks:

```bash
./scripts/ci-core.sh   # C++ core (+ sanitizers on Linux), localization, fixtures
./scripts/ci-ios.sh    # macOS: build, unit tests on a simulator, unsigned IPA
```

CI runs both: GitHub Actions (`.github/workflows/maqam-studio.yml`) and
Codemagic (`codemagic.yaml` at the repository root). Signing is not configured
here; no signing material is ever committed.
