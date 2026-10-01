#!/usr/bin/env python3
"""Writes the audio fixtures used by the Swift tests into Tests/Fixtures.

Every file is synthesised from a formula, so the expected answers are known
exactly and nothing recorded from a person is committed. Standard library only.

  bayati_phrase_48k.wav   mono 48 kHz 16-bit: 0.5 s silence, then maqam Bayati on
                          D4 (0, 150, 300, 500 cents), 0.6 s per degree, with
                          harmonics. The 150 and 300 cent degrees are quarter-tone
                          based and must never be named as semitones.
  rast_stereo_44k.wav     stereo 44.1 kHz 16-bit: Rast on C4, left louder than right.
  clipped_48k.wav         mono 48 kHz 16-bit: a tone driven into full-scale clipping.
  empty.wav               a valid WAV header with no audio frames.
  corrupted.wav           a RIFF/WAVE header followed by garbage.
"""
from __future__ import annotations

import math
import pathlib
import struct
import wave

OUT = pathlib.Path(__file__).resolve().parent.parent / "Tests" / "Fixtures"

D4 = 293.6648
C4 = 261.6256
BAYATI = [0, 150, 300, 500]
RAST = [0, 200, 350, 500, 700, 900, 1050, 1200]


def note(hz: float, seconds: float, rate: int, amplitude: float = 0.4) -> list[float]:
    frames = int(seconds * rate)
    fade = int(0.01 * rate)
    samples = []
    for n in range(frames):
        t = n / rate
        value = (math.sin(2 * math.pi * hz * t)
                 + 0.5 * math.sin(2 * math.pi * 2 * hz * t)
                 + 0.25 * math.sin(2 * math.pi * 3 * hz * t)) / 1.75
        envelope = min(1.0, n / fade, (frames - n) / fade) if fade else 1.0
        samples.append(amplitude * envelope * value)
    return samples


def to_pcm16(samples: list[float]) -> bytes:
    return b"".join(struct.pack("<h", max(-32768, min(32767, round(s * 32767)))) for s in samples)


def write(name: str, rate: int, channels: list[list[float]]) -> None:
    frames = len(channels[0])
    interleaved = [channels[c][n] for n in range(frames) for c in range(len(channels))]
    with wave.open(str(OUT / name), "wb") as handle:
        handle.setnchannels(len(channels))
        handle.setsampwidth(2)
        handle.setframerate(rate)
        handle.writeframes(to_pcm16(interleaved))


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)

    rate = 48000
    phrase = [0.0] * int(0.5 * rate)
    for cents in BAYATI:
        phrase += note(D4 * 2 ** (cents / 1200), 0.6, rate)
    write("bayati_phrase_48k.wav", rate, [phrase])

    rate = 44100
    scale: list[float] = []
    for cents in RAST:
        scale += note(C4 * 2 ** (cents / 1200), 0.25, rate)
    write("rast_stereo_44k.wav", rate, [scale, [s * 0.5 for s in scale]])

    rate = 48000
    tone = [max(-1.0, min(1.0, 1.6 * math.sin(2 * math.pi * 220 * n / rate))) for n in range(rate // 2)]
    write("clipped_48k.wav", rate, [tone])

    write("empty.wav", 48000, [[]])

    garbage = bytes((n * 73 + 41) % 256 for n in range(4096))
    header = b"RIFF" + struct.pack("<I", 4 + len(garbage)) + b"WAVE"
    (OUT / "corrupted.wav").write_bytes(header + garbage)

    for path in sorted(OUT.iterdir()):
        print(f"{path.name}: {path.stat().st_size} bytes")


if __name__ == "__main__":
    main()
