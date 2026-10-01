#include "maqam/maqam.hpp"

#include "maqam/tuning.hpp"

#include <cmath>
#include <initializer_list>
#include <limits>

namespace maqam {
namespace {

struct DegreeSpec {
    double cents;
    std::initializer_list<double> alternates;
};

Scale makeScale(std::initializer_list<DegreeSpec> degrees, bool octaveEquivalent = true) {
    Scale scale;
    scale.octaveEquivalent = octaveEquivalent;
    for (const DegreeSpec& spec : degrees) {
        if (scale.degreeCount == kMaxDegrees) break;
        ScaleDegree& degree = scale.degrees[scale.degreeCount++];
        degree.cents = spec.cents;
        for (double alternate : spec.alternates) {
            if (degree.alternateCount == kMaxAlternates) break;
            degree.alternates[degree.alternateCount++] = alternate;
        }
    }
    return scale;
}

// Frequencies at A4 = 440 Hz of the tonics these maqamat are usually notated on.
constexpr double C4 = 261.6255653;
constexpr double D4 = 293.6647679;
constexpr double Bb3 = 233.0818808;
constexpr double EHalfFlat4 = 320.2437; // E4 lowered by a quarter tone (350 cents above C4)

std::vector<MaqamDefinition> makeBuiltins() {
    // Quarter tones are written as 50-cent steps (150, 350, 1050 ...), the
    // conventional notation; singers' real intonation differs by region and is
    // why every degree stays editable.
    return {
        {"rast", "rast", "راست", "Rast", "jins rast", "jins rast", C4,
         makeScale({{0, {}}, {200, {}}, {350, {}}, {500, {}}, {700, {}}, {900, {}}, {1050, {1000}}})},
        {"bayati", "bayati", "بياتي", "Bayati", "jins bayati", "jins nahawand", D4,
         makeScale({{0, {}}, {150, {}}, {300, {}}, {500, {}}, {700, {}}, {800, {}}, {1000, {}}})},
        {"hijaz", "hijaz", "حجاز", "Hijaz", "jins hijaz", "jins nahawand", D4,
         makeScale({{0, {}}, {100, {}}, {400, {}}, {500, {}}, {700, {}}, {800, {850}}, {1000, {}}})},
        {"saba", "saba", "صبا", "Saba", "jins saba", "jins hijaz", D4,
         makeScale({{0, {}}, {150, {}}, {300, {}}, {400, {}}, {700, {}}, {800, {}}, {1000, {}}, {1100, {}}},
                   /*octaveEquivalent=*/false)},
        {"kurd", "kurd", "كرد", "Kurd", "jins kurd", "jins nahawand", D4,
         makeScale({{0, {}}, {100, {}}, {300, {}}, {500, {}}, {700, {}}, {800, {}}, {1000, {}}})},
        {"nahawand", "nahawand", "نهاوند", "Nahawand", "jins nahawand", "jins hijaz", C4,
         makeScale({{0, {}}, {200, {}}, {300, {}}, {500, {}}, {700, {}}, {800, {}}, {1100, {1000}}})},
        {"ajam", "ajam", "عجم", "Ajam", "jins ajam", "jins ajam", Bb3,
         makeScale({{0, {}}, {200, {}}, {400, {}}, {500, {}}, {700, {}}, {900, {}}, {1100, {}}})},
        {"sikah", "sikah", "سيكاه", "Sikah", "jins sikah", "jins rast", EHalfFlat4,
         makeScale({{0, {}}, {150, {}}, {350, {}}, {550, {}}, {700, {}}, {850, {}}, {1050, {}}})},
        // Branches (فروع) that singers move into most often.
        {"suznak", "rast", "سوزناك", "Suznak", "jins rast", "jins hijaz", C4,
         makeScale({{0, {}}, {200, {}}, {350, {}}, {500, {}}, {700, {}}, {800, {}}, {1100, {}}})},
        {"husseini", "bayati", "حسيني", "Husseini", "jins bayati", "jins bayati", D4,
         makeScale({{0, {}}, {150, {}}, {300, {}}, {500, {}}, {700, {}}, {850, {}}, {1000, {}}})},
        {"bayati_shuri", "bayati", "بياتي شوري", "Bayati Shuri", "jins bayati", "jins hijaz", D4,
         makeScale({{0, {}}, {150, {}}, {300, {}}, {600, {}}, {700, {}}, {800, {}}, {1000, {}}})},
        {"hijazkar", "hijaz", "حجازكار", "Hijazkar", "jins hijaz", "jins hijaz", C4,
         makeScale({{0, {}}, {100, {}}, {400, {}}, {500, {}}, {700, {}}, {800, {}}, {1100, {}}})},
        {"saba_zamzam", "saba", "صبا زمزم", "Saba Zamzam", "jins saba zamzam", "jins hijaz", D4,
         makeScale({{0, {}}, {100, {}}, {300, {}}, {400, {}}, {700, {}}, {800, {}}, {1000, {}}, {1100, {}}},
                   /*octaveEquivalent=*/false)},
        {"huzam", "sikah", "هزام", "Huzam", "jins sikah", "jins hijaz", EHalfFlat4,
         makeScale({{0, {}}, {150, {}}, {350, {}}, {450, {}}, {750, {}}, {850, {}}, {1050, {}}})},
        {"nawa_athar", "nawa_athar", "نوى أثر", "Nawa Athar", "jins nawa athar", "jins hijaz", C4,
         makeScale({{0, {}}, {200, {}}, {300, {}}, {600, {}}, {700, {}}, {800, {}}, {1100, {}}})},
    };
}

}  // namespace

const std::vector<MaqamDefinition>& builtinMaqamat() {
    static const std::vector<MaqamDefinition> builtins = makeBuiltins();
    return builtins;
}

const MaqamDefinition* findMaqam(const std::string& id) {
    for (const MaqamDefinition& definition : builtinMaqamat()) {
        if (definition.id == id) return &definition;
    }
    return nullptr;
}

TargetMatch nearestTarget(const Scale& scale, double centsFromTonic) noexcept {
    TargetMatch best;
    if (scale.degreeCount == 0) return best;
    double bestDistance = std::numeric_limits<double>::infinity();
    const double octave = std::floor(centsFromTonic / kCentsPerOctave);

    auto consider = [&](double degreeCents, int index, bool alternate) {
        // Each target is tried in the octave of the performed pitch and in the
        // neighbouring ones, so a note just below the tonic finds the seventh.
        for (int shift = -1; shift <= 1; ++shift) {
            const double candidate = degreeCents + (octave + shift) * kCentsPerOctave;
            // A scale that is not octave-equivalent (Saba) has no tonic above
            // the tonic: its eighth note is the lowered one it lists instead.
            if (!scale.octaveEquivalent && index == 0 && candidate > 0.0) continue;
            const double distance = std::fabs(centsFromTonic - candidate);
            if (distance < bestDistance) {
                bestDistance = distance;
                best.targetCents = candidate;
                best.deviationCents = centsFromTonic - candidate;
                best.degreeIndex = index;
                best.alternate = alternate;
            }
        }
    };

    for (std::size_t i = 0; i < scale.degreeCount; ++i) {
        const ScaleDegree& degree = scale.degrees[i];
        consider(degree.cents, static_cast<int>(i), false);
        for (std::size_t a = 0; a < degree.alternateCount; ++a) consider(degree.alternates[a], static_cast<int>(i), true);
    }
    return best;
}

}  // namespace maqam
