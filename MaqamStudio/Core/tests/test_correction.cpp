#include "maqam/correction.hpp"
#include "maqam/maqam.hpp"
#include "maqam/notes.hpp"
#include "maqam/psola.hpp"
#include "maqam/tuning.hpp"
#include "test_support.hpp"

#include <algorithm>
#include <cmath>

using namespace maqam;

namespace {

constexpr double kRate = 48000.0;
constexpr double kD4 = 293.6648;

const Scale& scaleOf(const char* id) {
    static const Scale empty{};
    const MaqamDefinition* maqam = findMaqam(id);
    return maqam ? maqam->scale : empty;
}

std::vector<PitchFrame> trackOf(const std::vector<float>& audio) {
    PitchDetectorConfig config;
    config.sampleRate = kRate;
    return trackPitch(audio.data(), audio.size(), config, 480);
}

std::vector<SungNote> notesOf(const std::vector<PitchFrame>& track) {
    NoteSegmentConfig config;
    config.referenceHz = kD4;
    return segmentNotes(track, config);
}

// Exact, analysis-free settings: move notes all the way at once, keep vibrato and drift.
CorrectionSettings plain() {
    CorrectionSettings settings;
    settings.retuneMs = 0;
    settings.strength = 1;
    settings.humanize = 0;
    settings.vibratoAmount = 1;
    settings.driftCorrection = 0;
    settings.smoothingMs = 0;
    return settings;
}

std::vector<float> tuned(const std::vector<float>& audio, const std::vector<PitchFrame>& track,
                         const std::vector<double>& shift) {
    std::vector<float> hz;
    for (const PitchFrame& frame : track) hz.push_back(frame.estimate.voiced ? static_cast<float>(frame.estimate.frequencyHz) : 0.0f);
    MarkFinder finder(kRate, track.front().timeSeconds, 0.01, hz);
    finder.push(audio.data(), audio.size());
    const GrainPlan plan = planGrains(finder.finish(), shift, track.front().timeSeconds, 0.01, kRate, audio.size(), 0.0, true);
    std::vector<float> out(audio.size());
    const float* in[] = {audio.data()};
    float* outs[] = {out.data()};
    renderBlock(plan, in, 1, 0, audio.size(), 0, out.size(), outs);
    return out;
}

std::vector<float> held(double cents, double seconds, double vibratoCents = 0.0) {
    return synth::contour(kD4, seconds, kRate, [=](double t) {
        return cents + vibratoCents * std::sin(2.0 * synth::kPi * 5.5 * t);
    }, 0.4, 5);
}

}  // namespace

TEST_CASE("a sharp note is moved to its quarter-tone degree, not to a semitone") {
    const auto track = trackOf(held(170.0, 0.8));
    const auto notes = notesOf(track);
    CHECK(notes.size() == 1);
    const auto result = computeCorrection(track, notes, scaleOf("bayati"), kD4, plain(), {});
    CHECK_NEAR(result.noteTargetCents[0], 150.0, 1e-9);
    const std::size_t middle = track.size() / 2;
    CHECK_NEAR(result.shiftCents[middle], -20.0, 2.0);

    // The same note against Rast goes up to Rast's 200, and a note at 340 to 350, not 300 or 400.
    CHECK_NEAR(computeCorrection(track, notes, scaleOf("rast"), kD4, plain(), {}).shiftCents[middle], 30.0, 2.0);
    const auto third = trackOf(held(340.0, 0.8));
    CHECK_NEAR(computeCorrection(third, notesOf(third), scaleOf("rast"), kD4, plain(), {}).shiftCents[third.size() / 2], 10.0, 2.0);
}

TEST_CASE("strength, retune speed and vibrato amount do what they say") {
    const auto track = trackOf(held(170.0, 1.0, 35.0));
    const auto notes = notesOf(track);
    CHECK(notes.size() == 1);

    CorrectionSettings half = plain();
    half.strength = 0.5;
    const auto halfway = computeCorrection(track, notes, scaleOf("bayati"), kD4, half, {});
    CorrectionSettings slow = plain();
    slow.retuneMs = 150;
    const auto ramped = computeCorrection(track, notes, scaleOf("bayati"), kD4, slow, {});
    CorrectionSettings flat = plain();
    flat.vibratoAmount = 0;
    const auto flattened = computeCorrection(track, notes, scaleOf("bayati"), kD4, flat, {});

    double halfMean = 0.0, flatSpread = 0.0, keptSpread = 0.0;
    std::size_t used = 0;
    const auto kept = computeCorrection(track, notes, scaleOf("bayati"), kD4, plain(), {});
    for (std::size_t i = 0; i < track.size(); ++i) {
        const double t = track[i].timeSeconds;
        if (t < notes[0].startSeconds + 0.2 || t > notes[0].endSeconds - 0.1 || !track[i].estimate.voiced) continue;
        const double sung = hzToCents(track[i].estimate.frequencyHz, kD4);
        halfMean += halfway.shiftCents[i];
        flatSpread = std::max(flatSpread, std::fabs(sung + flattened.shiftCents[i] - 150.0));
        keptSpread = std::max(keptSpread, std::fabs(sung + kept.shiftCents[i] - 150.0));
        ++used;
    }
    CHECK(used > 20);
    CHECK_NEAR(halfMean / static_cast<double>(used), -10.0, 3.0);
    CHECK(flatSpread < 8.0);                 // vibrato removed
    CHECK(keptSpread > 25.0);                // vibrato kept
    // Retune: almost nothing at the very start, nearly all of it after a few time constants.
    std::size_t first = 0;
    while (first < track.size() && track[first].timeSeconds < notes[0].startSeconds) ++first;
    CHECK(std::fabs(ramped.shiftCents[first]) < std::fabs(kept.shiftCents[first]) * 0.5 + 1.0);
    std::size_t later = first;
    while (later < track.size() && track[later].timeSeconds < notes[0].startSeconds + 0.6) ++later;
    CHECK_NEAR(ramped.shiftCents[later], kept.shiftCents[later], 3.0);
}

TEST_CASE("a slide between notes keeps its shape; only its ends move") {
    // 170 (to 150), slide, 320 (to 300): both notes 20 sharp.
    const auto audio = synth::contour(kD4, 1.4, kRate, [](double t) {
        if (t < 0.5) return 170.0;
        if (t < 0.7) return 170.0 + (t - 0.5) / 0.2 * 150.0;
        return 320.0;
    }, 0.4, 5);
    const auto track = trackOf(audio);
    const auto notes = notesOf(track);
    CHECK(notes.size() == 2);
    const auto result = computeCorrection(track, notes, scaleOf("bayati"), kD4, plain(), {});
    for (std::size_t i = 0; i < track.size(); ++i) {
        const double t = track[i].timeSeconds;
        if (t > 0.55 && t < 0.65) CHECK_NEAR(result.shiftCents[i], -20.0, 4.0);  // the slide is moved, not snapped
    }
    CorrectionSettings snappy = plain();
    snappy.transitionSensitivity = 1.0;
    const auto snapped = computeCorrection(track, notes, scaleOf("bayati"), kD4, snappy, {});
    bool anyDifferent = false;
    for (std::size_t i = 0; i < track.size(); ++i) {
        const double t = track[i].timeSeconds;
        if (t > 0.55 && t < 0.65 && std::fabs(snapped.shiftCents[i] - result.shiftCents[i]) > 5.0) anyDifferent = true;
    }
    CHECK(anyDifferent);
}

TEST_CASE("notes chosen by hand are pinned or left alone") {
    std::vector<float> audio = held(170.0, 0.6);
    const auto gap = std::vector<float>(static_cast<std::size_t>(0.15 * kRate), 0.0f);
    audio.insert(audio.end(), gap.begin(), gap.end());
    const auto second = held(320.0, 0.6);
    audio.insert(audio.end(), second.begin(), second.end());
    const auto track = trackOf(audio);
    const auto notes = notesOf(track);
    CHECK(notes.size() == 2);
    if (notes.size() != 2) return;
    std::vector<NoteOverride> overrides(2);
    overrides[0].bypass = true;
    overrides[1].hasTarget = true;
    overrides[1].targetCentsFromTonic = 350.0;
    const auto result = computeCorrection(track, notes, scaleOf("bayati"), kD4, plain(), overrides);
    CHECK(std::isnan(result.noteTargetCents[0]));
    CHECK_NEAR(result.noteTargetCents[1], 350.0, 1e-9);
    for (std::size_t i = 0; i < track.size(); ++i) {
        const double t = track[i].timeSeconds;
        if (t > notes[0].startSeconds && t < notes[0].endSeconds && track[i].estimate.voiced) CHECK(result.shiftCents[i] == 0.0);
        if (t > notes[1].startSeconds + 0.05 && t < notes[1].endSeconds - 0.05) CHECK_NEAR(result.shiftCents[i], 30.0, 2.0);
    }
}

TEST_CASE("end to end: an off-pitch phrase with vibrato comes out on the maqam, vibrato intact") {
    // Bayati sung loosely: tonic 12 flat, second 25 sharp with vibrato, third 18 flat.
    const auto audio = synth::contour(kD4, 2.1, kRate, [](double t) {
        if (t < 0.6) return -12.0;
        if (t < 0.7) return -12.0 + (t - 0.6) / 0.1 * 187.0;
        if (t < 1.4) return 175.0 + 30.0 * std::sin(2.0 * synth::kPi * 5.5 * (t - 0.7));
        if (t < 1.5) return 175.0 + (t - 1.4) / 0.1 * 107.0;
        return 282.0;
    }, 0.4, 6);
    const auto track = trackOf(audio);
    const auto notes = notesOf(track);
    CHECK(notes.size() == 3);
    CorrectionSettings natural;  // the defaults: the "Natural" preset
    const auto correction = computeCorrection(track, notes, scaleOf("bayati"), kD4, natural, {});
    CorrectionSettings full = plain();
    full.retuneMs = 40;
    full.smoothingMs = 10;
    const auto exact = computeCorrection(track, notes, scaleOf("bayati"), kD4, full, {});
    const auto output = tuned(audio, track, exact.shiftCents);
    CHECK(output.size() == audio.size());

    const auto after = notesOf(trackOf(output));
    CHECK(after.size() == 3);
    if (after.size() == 3) {
        CHECK_NEAR(after[0].cents, 0.0, 4.0);
        CHECK_NEAR(after[1].cents, 150.0, 4.0);
        CHECK_NEAR(after[2].cents, 300.0, 4.0);
        CHECK_NEAR(after[1].vibratoExtentCents, notes[1].vibratoExtentCents, 8.0);
        CHECK(after[1].vibratoRateHz > 4.5);
    }
    // Natural moves the second note most of the way and keeps its vibrato.
    double naturalShift = correction.shiftCents[static_cast<std::size_t>(1.1 / 0.01)];
    CHECK(naturalShift < -5.0 && naturalShift > -40.0);
}

TEST_CASE("robotic settings flatten each note onto its degree") {
    const auto audio = held(165.0, 1.0, 30.0);
    const auto track = trackOf(audio);
    const auto notes = notesOf(track);
    CorrectionSettings robotic = plain();
    robotic.vibratoAmount = 0;
    robotic.driftCorrection = 1;
    robotic.transitionSensitivity = 1;
    const auto result = computeCorrection(track, notes, scaleOf("bayati"), kD4, robotic, {});
    const auto after = notesOf(trackOf(tuned(audio, track, result.shiftCents)));
    CHECK(after.size() == 1);
    if (!after.empty()) {
        CHECK_NEAR(after[0].cents, 150.0, 4.0);
        CHECK(after[0].spreadCents < 6.0);
        CHECK(after[0].vibratoRateHz == 0.0);
    }
}
