# مقام ستوديو · Maqam Studio

An iOS app for Arabic singing: record or import a vocal, hear it against its
maqam with real quarter tones, and (in later phases) tune and finish it
automatically. Arabic first, English second, built to be used fully with
VoiceOver.

## Status

| Phase | Scope | State |
|---|---|---|
| 1 | Project structure, import, playback, recording, waveform, save/open | **Implemented** |
| 2 | Pitch track, note segmentation, intonation against the maqam, pitch curve, note list, live tuner | **Implemented** |
| 3 | Maqam engine UI: per-degree tuning, A4 reference, tuning tables, custom maqam editor, library import/export | **Implemented** |
| 4 | Pitch correction: Natural/Strong/Robotic and every control, formant preservation and shift, manual note pinning, A/B with measured before/after | **Implemented (offline)**; live correction comes with the live mode |
| 5 | Maqam and tonic detection with confidence, manual override | **Implemented** |
| 6 | Auto Studio: analysis, adaptive chain, 12 genre profiles, decisions in words, Pro mode, A/B | **Implemented** |
| 7 | Export, stem separation interface | Not started |

Nothing in the interface is a placeholder: a control appears only when the
work behind it exists.

## Layout

```
MaqamStudio/
  Core/                 C++17 DSP core, no platform dependencies
    include/maqam/      fft, levels, tuning, maqam, pitch_detector, notes
    src/                implementations
    capi/maqam_core.h   the C interface Swift calls (plain C types only)
    tests/              48 cases: quarter tones, maqam phrases, vibrato, melisma, noise
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

## Notes and intonation

The original is decoded once; levels, waveform and a 10 ms pitch track come
out of the same pass. Notes are segmented on the *centre* of the pitch: where
the voice is locally flat that is the pitch itself (so fast melisma keeps its
short notes), and where it keeps moving it is the mean over one vibrato cycle
(so a wide or slow vibrato stays one note). A note ends at a gap of more than
60 ms or a sustained move; quarter-tone steps are found from window means;
slides into a note are trimmed rather than averaged in. Vibrato rate and
extent are measured on the raw pitch.

Each note is matched to the nearest target of the chosen maqam on the chosen
tonic, with the user's per-degree offsets applied, and counted in tune within
±15 cents. The summary says, per degree, how far sharp or flat it was sung on
average, in words. The live tuner runs the same detector on copies of the
microphone input on its own queue, never on the render thread.

## Maqamat, tuning and the library

Built-in maqamat come from the C++ core and are never edited. A project can
move any degree of its maqam by up to ±100 cents (undoable); those
adjustments can be saved as a named tuning table and applied to other
projects in the same maqam. The A4 reference (415–466 Hz) changes note names
only.

The user's own maqamat — new, or copied from a built-in — live in one JSON
library in Application Support, written atomically and validated on every
save and import: the tonic at 0, rising degrees inside the octave, at most 12
degrees and 2 alternates each. A project using a custom maqam stores a copy
of it, so editing or deleting it in the library, or opening the project on
another device, never breaks the project. The library exports to a file and
imports without replacing anything.

## Pitch correction

`computeCorrection` turns the notes into a per-frame shift in cents towards
each note's maqam target (quarter tones and the user's degree tuning
included). Vibrato is the residue around the note's centre, averaged over
exactly one of its cycles, and is kept, scaled or re-paced; the slide into a
note survives because correction ramps in over the retune time; slides and
ornaments between notes are moved with their neighbours, not snapped, unless
transition sensitivity says otherwise. Natural, Strong and Robotic are presets
of the same controls; any note can be left as sung or pinned to a degree by
hand.

The shift is applied by TD-PSOLA: one grain per period, so formants stay put
(or move on purpose with formant shift). Marks are found while streaming,
grains are planned from the shift curve, and the output is rendered block by
block from just the input each block needs — sample-identical to rendering the
whole file. With no shift the output equals the input exactly. The corrected
audio is written to `renders/tuned.caf` beside the untouched original, then
analysed again so the reported "after" accuracy is measured, not assumed.

## Maqam and tonic detection

Every pitch class the singer used is a tonic candidate, and every candidate is
paired with every scale (built-in and the user's own). A pair scores by how
well it explains the notes, time-weighted, with a capped Gaussian cost per
note against the exact degrees and alternates; tonic evidence (the resting
note, time on it, the lowest notes) separates maqamat that share a pitch set,
such as Rast on C and Sikah on E half-flat. Scores become probabilities over
all pairs. The tonic is reported exactly as sung, so tuning follows the
singer rather than A = 440.

Measured on synthetic presentations of all 15 built-ins: 15/15 correct with
intonation off by up to ±20 cents; at ±25 cents Bayati and Husseini (50 cents
apart on one degree) become a low-confidence tie. Singing that does not tell
maqamat apart (only Bayati's lower jins) gives 48% / 48% rather than a false
answer, and under 3 seconds or 3 pitch classes no claim is made. Detection is
applied automatically only when nothing is chosen and confidence is at least
60%; a choice made by hand is never replaced, only questioned.

## Auto Studio

AUTO STUDIO listens to the voice once (the tuned take when one is current,
otherwise the original) and measures what the decisions need: integrated
loudness (BS.1770-4), peaks and clipping, the room's noise floor and its
spectrum, mains hum at 50 or 60 Hz, rumble, the voice's long-term spectrum,
sibilance, level spread, and breaths (unvoiced, noisy, quieter than the
singing, 0.12–0.8 s). A plan is then made for the chosen profile, and every
stage records why it is on, off, or set as it is; the app reads those reasons
as sentences ("Mains hum at 50 Hz, 24 dB above its surroundings; it and its
harmonics were removed").

The chain, in order: high-pass, hum notches, spectral noise reduction,
plosive control (only on unvoiced low bursts), breath reduction, leveler,
corrective EQ, de-esser and harshness control, compressor or multiband
compressor, saturation, exciter, stereo reverb and echo, then gain to the
profile's loudness target and a look-ahead limiter at -1 dBFS. The chain is
run once to measure its loudness and again at the gain that reaches the
target, so the reported "after" numbers are measured from the written file.
The result goes to `renders/studio.caf`; the original is never changed.

The 12 profiles (Khaleeji, Arabic Pop, Tarab, Shilat, Iraqi, Egyptian,
Levantine, Acoustic, Clean Studio, Modern Commercial, Natural, Heavy
Auto-Tune) are starting points chosen by ear for each style, not measured
standards. Each also names the tuning style it pairs with; when a take has
never been tuned, AUTO STUDIO tunes it in that style first. Pro mode exposes
every value of the plan, and "back to automatic" discards the edits.

Measured in the tests: every profile lands within 1 LU of its target with
peaks at or below -1 dBFS; hum drops by at least 20 dB against the voice;
noise reduction removes about 13 dB of steady noise while the voice moves by
less than 0.2 dB; the K-weighting filter matches the BS.1770 reference
coefficients at 48 kHz.

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
