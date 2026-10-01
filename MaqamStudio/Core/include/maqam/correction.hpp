// Arabic pitch correction: where each moment of the voice should be moved to.
//
// The correction is computed per pitch frame as a shift in cents. Notes are
// moved to their maqam target (quarter tones included, never a 12-TET grid);
// what makes the singing Arabic is kept by construction:
//
//  - vibrato is the residue around the note's moving centre, and is kept
//    (scaled by vibratoAmount, or re-paced to vibratoRateHz);
//  - the slide into a note is kept: correction ramps in over the retune time
//    from the note's start, so the voice still arrives the way it was sung;
//  - transitions and ornaments between notes are not snapped: their shift is
//    interpolated between the neighbouring notes' shifts, so a slide keeps its
//    shape and only its ends move, unless transitionSensitivity asks for more;
//  - slow drift within a held note is corrected only as much as asked.
#pragma once

#include "maqam/maqam.hpp"
#include "maqam/notes.hpp"
#include "maqam/pitch_detector.hpp"

#include <cstddef>
#include <vector>

namespace maqam {

struct CorrectionSettings {
    double retuneMs = 80.0;              // time for the correction to reach full strength in a note
    double strength = 0.8;               // 0 = none, 1 = all the way to the target
    double humanize = 0.5;               // keeps natural variation on held notes (0-1)
    double vibratoAmount = 1.0;          // natural vibrato kept: 0 removes, 1 keeps, >1 widens
    double vibratoRateHz = 0.0;          // 0 keeps the singer's rate; otherwise re-paces vibrato
    double transitionSensitivity = 0.0;  // 0 leaves slides between notes shaped as sung, 1 snaps them
    double driftCorrection = 0.3;        // share of slow drift within a note removed (0-1)
    double smoothingMs = 15.0;           // final smoothing of the shift curve
    double maximumShiftCents = 1200.0;
};

// Per-note choices made by hand.
struct NoteOverride {
    bool bypass = false;             // leave this note exactly as sung
    bool hasTarget = false;          // pin it to this target instead of the nearest one
    double targetCentsFromTonic = 0.0;
};

struct CorrectionResult {
    std::vector<double> shiftCents;       // per pitch frame
    std::vector<double> noteTargetCents;  // per note, from the tonic (unfolded), NaN when bypassed
};

// `notes` must come from segmentNotes on the same `track`.
// `overrides` may be empty or hold one entry per note.
CorrectionResult computeCorrection(const std::vector<PitchFrame>& track, const std::vector<SungNote>& notes,
                                   const Scale& scale, double tonicHz, const CorrectionSettings& settings,
                                   const std::vector<NoteOverride>& overrides);

}  // namespace maqam
