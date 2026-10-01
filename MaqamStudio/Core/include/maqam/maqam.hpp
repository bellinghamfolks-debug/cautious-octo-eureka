// Arabic maqam definitions and microtonal scale targets.
//
// A scale is a list of degrees, each a cents offset above the tonic, plus
// optional alternate intonations (for example Nahawand's raised seventh going
// up and lowered seventh coming down). Users may edit every offset; built-in
// definitions are starting points, not a fixed temperament.
#pragma once

#include <array>
#include <cstddef>
#include <string>
#include <vector>

namespace maqam {

constexpr std::size_t kMaxDegrees = 12;
constexpr std::size_t kMaxAlternates = 2;

struct ScaleDegree {
    double cents = 0.0;  // above the tonic, normally in [0, 1200)
    std::array<double, kMaxAlternates> alternates{};  // extra acceptable intonations
    std::size_t alternateCount = 0;
};

struct Scale {
    std::array<ScaleDegree, kMaxDegrees> degrees{};
    std::size_t degreeCount = 0;
    // When false, the octave above the tonic is not itself a target (Saba).
    bool octaveEquivalent = true;
};

struct MaqamDefinition {
    std::string id;          // stable key, e.g. "rast"
    std::string family;      // the maqam family it belongs to, e.g. "sikah"
    std::string arabicName;  // e.g. "راست"
    std::string englishName; // e.g. "Rast"
    std::string lowerJins;
    std::string upperJins;
    double typicalTonicHz = 0.0;  // common tonic at A4 = 440 Hz, for display and seeding
    Scale scale;
};

const std::vector<MaqamDefinition>& builtinMaqamat();
const MaqamDefinition* findMaqam(const std::string& id);

struct TargetMatch {
    double targetCents = 0.0;     // the matched target, as cents above the tonic (unfolded)
    double deviationCents = 0.0;  // performed minus target
    int degreeIndex = -1;         // which scale degree matched
    bool alternate = false;       // whether an alternate intonation matched
};

// The nearest scale target to a performed pitch `centsFromTonic` (any octave).
// Returns degreeIndex == -1 when the scale has no degrees.
TargetMatch nearestTarget(const Scale& scale, double centsFromTonic) noexcept;

}  // namespace maqam
