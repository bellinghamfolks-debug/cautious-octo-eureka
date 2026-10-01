// Microtonal pitch arithmetic.
//
// Every pitch in Maqam Studio is a real number of cents. Nothing in this file
// rounds to a 12-tone grid: a quarter tone is 50 cents, a Bayati second may be
// 150 or 140 cents, and both survive every conversion unchanged.
#pragma once

#include <cmath>

namespace maqam {

constexpr double kCentsPerOctave = 1200.0;
constexpr double kDefaultA4Hz = 440.0;

inline double hzToCents(double hz, double referenceHz) noexcept {
    return (hz > 0.0 && referenceHz > 0.0) ? kCentsPerOctave * std::log2(hz / referenceHz) : 0.0;
}

inline double centsToHz(double cents, double referenceHz) noexcept {
    return referenceHz * std::exp2(cents / kCentsPerOctave);
}

// Position inside the octave, in [0, 1200).
inline double foldToOctave(double cents) noexcept {
    double folded = std::fmod(cents, kCentsPerOctave);
    if (folded < 0.0) folded += kCentsPerOctave;
    if (folded >= kCentsPerOctave) folded -= kCentsPerOctave;
    return folded;
}

// Shortest signed distance from `from` to `to` on the pitch-class circle,
// in (-600, 600].
inline double circularDifference(double to, double from) noexcept {
    double difference = foldToOctave(to - from);
    if (difference > kCentsPerOctave / 2.0) difference -= kCentsPerOctave;
    return difference;
}

// A pitch expressed against the 24-tone (quarter-tone) chromatic used to name
// Arabic notes: `step` counts quarter tones above C, `remainderCents` is what
// is left, in [-25, 25). This exists for labels only; tuning never uses it.
struct QuarterToneName {
    int octave = 4;    // scientific pitch octave
    int step = 0;      // 0..23, quarter tones above C
    double remainderCents = 0.0;
};

QuarterToneName nameQuarterTone(double hz, double a4Hz = kDefaultA4Hz) noexcept;

}  // namespace maqam
