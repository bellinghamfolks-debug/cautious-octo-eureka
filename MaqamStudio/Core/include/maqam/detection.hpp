// Which maqam, on which tonic, from the sung notes — with an honest confidence.
//
// Every candidate tonic (a pitch class the singer actually used) is paired
// with every scale. Each pair is scored by how well it explains the notes,
// time-weighted: a note on a degree (or an alternate) costs nothing, a note
// between degrees costs more the further it is, up to a cap so one stray
// ornament cannot dominate. Scales are compared in quarter tones exactly as
// defined; nothing is matched on a 12-tone grid.
//
// Many maqamat share a pitch set with a different tonic (Rast on C and Sikah on
// E half-flat use the same notes), so the tonic needs its own evidence: the
// note the performance comes to rest on, the share of time spent on it, and
// whether it is among the lowest notes sung. Scores become probabilities over
// all pairs; with little singing, or singing that does not tell maqamat apart,
// the probabilities stay spread out and the result says so.
#pragma once

#include "maqam/maqam.hpp"
#include "maqam/notes.hpp"

#include <cstddef>
#include <vector>

namespace maqam {

struct DetectionConfig {
    double minimumSeconds = 3.0;        // sung time below which no claim is made
    std::size_t minimumPitchClasses = 3;
    double sigmaCents = 18.0;           // how far from a degree still counts as "on" it
    double floorLogLikelihood = -4.5;   // the most one second of an off-scale note can cost
    double clusterCents = 30.0;         // notes this close share a pitch class
    double noteWeightCapSeconds = 2.0;  // a single long note counts at most this much
    double finalNoteBonus = 1.5;        // tonic evidence, in seconds-equivalent of fit
    double tonicShareBonus = 3.0;
    double lowNoteBonus = 0.75;
};

struct MaqamCandidate {
    std::size_t scaleIndex = 0;
    double tonicHz = 0.0;               // as the singer sang it, not snapped to A = 440
    double probability = 0.0;
    double meanDeviationCents = 0.0;    // time-weighted distance of the notes from this scale
};

struct MaqamDetection {
    bool enoughData = false;
    double sungSeconds = 0.0;
    std::size_t pitchClasses = 0;
    double tonicHz = 0.0;               // of the best candidate
    double tonicConfidence = 0.0;       // total probability of every candidate on that tonic
    std::vector<MaqamCandidate> ranked; // most probable first
};

MaqamDetection detectMaqam(const std::vector<SungNote>& notes, const std::vector<Scale>& scales,
                           const DetectionConfig& config = DetectionConfig{});

}  // namespace maqam
