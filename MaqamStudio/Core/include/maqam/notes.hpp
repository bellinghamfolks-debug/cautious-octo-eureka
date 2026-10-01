// From a pitch track to sung notes, and from sung notes to intonation.
//
// Arabic singing is not a sequence of fixed pitches: notes are approached by
// slides, held with vibrato, and decorated with quick ornaments. Segmentation
// therefore follows the *centre* of the pitch, not each frame: a note ends
// when the voice stops or moves away and stays away, vibrato does not split a
// note, and the slide into a note is trimmed from it rather than averaged into
// it. Nothing is rounded to a semitone; a note's pitch is its median in cents.
#pragma once

#include "maqam/maqam.hpp"
#include "maqam/pitch_detector.hpp"

#include <array>
#include <cstddef>
#include <vector>

namespace maqam {

struct NoteSegmentConfig {
    double minimumNoteSeconds = 0.08;  // shorter stable stretches are ornaments or noise
    double maximumGapSeconds = 0.06;   // an unvoiced gap shorter than this stays inside the note
    double splitCents = 65.0;          // moving this far from the note's centre...
    double splitHoldSeconds = 0.05;    // ...for this long starts a new note
    // A smaller step, such as a quarter tone, is found from the average pitch
    // over a window longer than a vibrato cycle, which vibrato cannot move.
    double shiftCents = 33.0;
    double shiftWindowSeconds = 0.2;
    double trimCents = 35.0;           // edge frames this far from the median are a slide, not the note
    double minimumConfidence = 0.55;   // frames below are treated as unvoiced
    double referenceHz = 440.0;        // cents are measured from this pitch
};

struct SungNote {
    double startSeconds = 0.0;
    double endSeconds = 0.0;
    double hz = 0.0;              // median pitch
    double cents = 0.0;           // median pitch, cents above referenceHz
    double spreadCents = 0.0;     // half the 10th-90th percentile range: how steady it was held
    double vibratoRateHz = 0.0;   // 0 when there is no vibrato
    double vibratoExtentCents = 0.0;  // half peak-to-peak, 0 when there is no vibrato
    std::size_t firstFrame = 0;   // into the pitch track
    std::size_t frameCount = 0;
};

std::vector<SungNote> segmentNotes(const std::vector<PitchFrame>& track, const NoteSegmentConfig& config);

struct NoteMatch {
    TargetMatch target;    // degree and deviation, from nearestTarget
    bool inTune = false;   // |deviation| <= tolerance
};

// How one scale degree was sung across the performance.
struct DegreeTendency {
    std::size_t noteCount = 0;
    double seconds = 0.0;
    double meanDeviationCents = 0.0;  // time-weighted: positive means sung sharp
};

struct IntonationSummary {
    std::size_t noteCount = 0;
    std::size_t inTuneCount = 0;
    double totalSeconds = 0.0;
    double inTuneFraction = 0.0;            // of sung time
    double meanAbsoluteDeviationCents = 0.0;  // time-weighted
    std::array<DegreeTendency, kMaxDegrees> degrees{};
};

// Matches every note to the nearest target of `scale` on `tonicHz`.
// `matches`, when given, receives one entry per note.
IntonationSummary evaluateIntonation(const Scale& scale, double tonicHz, const std::vector<SungNote>& notes,
                                     double toleranceCents, std::vector<NoteMatch>* matches);

}  // namespace maqam
