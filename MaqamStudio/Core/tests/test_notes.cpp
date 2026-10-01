#include "maqam/maqam.hpp"
#include "maqam/notes.hpp"
#include "maqam/pitch_detector.hpp"
#include "maqam/tuning.hpp"
#include "test_support.hpp"

#include <algorithm>

using namespace maqam;

namespace {

constexpr double kRate = 48000.0;
constexpr double kD4 = 293.6648;
constexpr std::size_t kHop = 480;  // 10 ms

std::vector<PitchFrame> track(const std::vector<float>& audio) {
    PitchDetectorConfig config;
    config.sampleRate = kRate;
    return trackPitch(audio.data(), audio.size(), config, kHop);
}

NoteSegmentConfig onTonic(double tonicHz) {
    NoteSegmentConfig config;
    config.referenceHz = tonicHz;
    return config;
}

void append(std::vector<float>& to, const std::vector<float>& from) { to.insert(to.end(), from.begin(), from.end()); }

std::vector<float> silence(double seconds) { return std::vector<float>(static_cast<std::size_t>(seconds * kRate), 0.0f); }

// A legato phrase: each degree held, joined by linear slides of `slide` seconds.
std::vector<float> legato(const std::vector<double>& degrees, double hold, double slide) {
    const double step = hold + slide;
    const double total = step * static_cast<double>(degrees.size());
    return synth::contour(kD4, total, kRate, [&](double t) {
        const auto index = std::min(degrees.size() - 1, static_cast<std::size_t>(t / step));
        const double within = t - static_cast<double>(index) * step;
        if (within < hold || index + 1 >= degrees.size()) return degrees[index];
        const double fraction = (within - hold) / slide;
        return degrees[index] + (degrees[index + 1] - degrees[index]) * fraction;
    }, 0.4, 4);
}

}  // namespace

TEST_CASE("separate held notes become one note each, with their quarter-tone pitch") {
    std::vector<float> audio = silence(0.2);
    const std::vector<double> bayati{0, 150, 300, 500};
    for (double cents : bayati) {
        append(audio, synth::tone(kD4 * std::exp2(cents / 1200.0), 0.5, kRate, 0.4, 4));
        append(audio, silence(0.12));
    }
    const auto notes = segmentNotes(track(audio), onTonic(kD4));
    CHECK(notes.size() == bayati.size());
    for (std::size_t i = 0; i < std::min(notes.size(), bayati.size()); ++i) {
        CHECK_NEAR(notes[i].cents, bayati[i], 3.0);
        const double expectedStart = 0.2 + 0.62 * static_cast<double>(i);
        CHECK_NEAR(notes[i].startSeconds, expectedStart, 0.04);
        CHECK_NEAR(notes[i].endSeconds, expectedStart + 0.5, 0.04);
        CHECK(notes[i].vibratoRateHz == 0.0);
        CHECK(notes[i].spreadCents < 3.0);
    }
}

TEST_CASE("slides between notes are trimmed, not averaged into them") {
    const std::vector<double> degrees{0, 150, 300, 150, 0};
    const auto notes = segmentNotes(track(legato(degrees, 0.45, 0.12)), onTonic(kD4));
    CHECK(notes.size() == degrees.size());
    for (std::size_t i = 0; i < std::min(notes.size(), degrees.size()); ++i) {
        CHECK_NEAR(notes[i].cents, degrees[i], 5.0);
    }
}

TEST_CASE("vibrato stays one note and is measured, not mistaken for movement") {
    const auto audio = synth::contour(kD4, 1.5, kRate, [](double t) {
        return 150.0 + 40.0 * std::sin(2.0 * synth::kPi * 5.5 * t);
    }, 0.4, 4);
    const auto notes = segmentNotes(track(audio), onTonic(kD4));
    CHECK(notes.size() == 1);
    if (notes.size() == 1) {
        CHECK_NEAR(notes[0].cents, 150.0, 6.0);
        CHECK_NEAR(notes[0].vibratoRateHz, 5.5, 0.6);
        CHECK_NEAR(notes[0].vibratoExtentCents, 40.0, 5.0);
    }
}

TEST_CASE("a quarter-tone step with no slide is two notes, not one averaged note") {
    const auto audio = synth::contour(kD4, 1.6, kRate, [](double t) { return t < 0.8 ? 0.0 : 50.0; }, 0.4, 4);
    const auto notes = segmentNotes(track(audio), onTonic(kD4));
    CHECK(notes.size() == 2);
    if (notes.size() == 2) {
        CHECK_NEAR(notes[0].cents, 0.0, 3.0);
        CHECK_NEAR(notes[1].cents, 50.0, 3.0);
        CHECK_NEAR(notes[1].startSeconds, 0.8, 0.1);
    }
}

TEST_CASE("a wide tarab vibrato of +/- 60 cents is still one note") {
    const auto audio = synth::contour(kD4, 1.6, kRate, [](double t) {
        return 300.0 + 60.0 * std::sin(2.0 * synth::kPi * 5.0 * t);
    }, 0.4, 4);
    const auto notes = segmentNotes(track(audio), onTonic(kD4));
    CHECK(notes.size() == 1);
    if (notes.size() == 1) {
        CHECK_NEAR(notes[0].cents, 300.0, 8.0);
        CHECK_NEAR(notes[0].vibratoExtentCents, 60.0, 8.0);
    }
}

TEST_CASE("a fast melisma keeps each short note and its quarter tones") {
    const std::vector<double> run{0, 150, 300, 150, 300, 500};
    const auto notes = segmentNotes(track(legato(run, 0.13, 0.02)), onTonic(kD4));
    CHECK(notes.size() == run.size());
    for (std::size_t i = 0; i < std::min(notes.size(), run.size()); ++i) {
        CHECK_NEAR(notes[i].cents, run[i], 8.0);
    }
}

TEST_CASE("a slow, wide vibrato is still one note") {
    const auto audio = synth::contour(kD4, 2.0, kRate, [](double t) {
        return 300.0 + 60.0 * std::sin(2.0 * synth::kPi * 4.0 * t);
    }, 0.4, 4);
    const auto notes = segmentNotes(track(audio), onTonic(kD4));
    CHECK(notes.size() == 1);
    if (notes.size() == 1) CHECK_NEAR(notes[0].vibratoRateHz, 4.0, 0.5);
}

TEST_CASE("a quick ornament or a short breath does not break the note") {
    std::vector<float> audio = synth::tone(kD4, 0.5, kRate, 0.4, 4);
    append(audio, synth::tone(kD4 * std::exp2(200.0 / 1200.0), 0.03, kRate, 0.4, 4));  // 30 ms flick up
    append(audio, synth::tone(kD4, 0.4, kRate, 0.4, 4));
    append(audio, silence(0.03));                                                  // 30 ms breath
    append(audio, synth::tone(kD4, 0.4, kRate, 0.4, 4));
    const auto notes = segmentNotes(track(audio), onTonic(kD4));
    CHECK(notes.size() == 1);
    if (!notes.empty()) CHECK_NEAR(notes[0].cents, 0.0, 3.0);
}

TEST_CASE("silence and noise produce no notes") {
    std::vector<float> audio = silence(0.5);
    append(audio, synth::noise(static_cast<std::size_t>(kRate), 0.3));
    CHECK(segmentNotes(track(audio), onTonic(kD4)).empty());
    CHECK(segmentNotes({}, onTonic(kD4)).empty());
}

TEST_CASE("intonation is judged against the maqam's quarter tones, degree by degree") {
    const MaqamDefinition* bayati = findMaqam("bayati");
    CHECK(bayati != nullptr);
    if (!bayati) return;
    // The second degree sung 20 cents sharp, everything else true.
    std::vector<float> audio;
    for (double cents : {0.0, 170.0, 300.0, 500.0}) {
        append(audio, synth::tone(kD4 * std::exp2(cents / 1200.0), 0.5, kRate, 0.4, 4));
        append(audio, silence(0.12));
    }
    const auto notes = segmentNotes(track(audio), onTonic(440.0));
    std::vector<NoteMatch> matches;
    const IntonationSummary summary = evaluateIntonation(bayati->scale, kD4, notes, 15.0, &matches);
    CHECK(summary.noteCount == 4);
    CHECK(summary.inTuneCount == 3);
    CHECK_NEAR(summary.inTuneFraction, 0.75, 0.03);
    CHECK(matches.size() == 4);
    if (matches.size() == 4) {
        CHECK(matches[1].target.degreeIndex == 1);
        CHECK_NEAR(matches[1].target.deviationCents, 20.0, 3.0);
        CHECK(!matches[1].inTune);
    }
    CHECK(summary.degrees[1].noteCount == 1);
    CHECK_NEAR(summary.degrees[1].meanDeviationCents, 20.0, 3.0);
    CHECK_NEAR(summary.degrees[2].meanDeviationCents, 0.0, 3.0);
}

TEST_CASE("singing Bayati in equal temperament is flagged, not accepted") {
    const MaqamDefinition* bayati = findMaqam("bayati");
    if (!bayati) { CHECK(false); return; }
    // E natural instead of E half-flat: a semitone singer's Bayati.
    std::vector<float> audio = synth::tone(kD4 * std::exp2(200.0 / 1200.0), 0.6, kRate, 0.4, 4);
    const auto notes = segmentNotes(track(audio), onTonic(440.0));
    std::vector<NoteMatch> matches;
    const IntonationSummary summary = evaluateIntonation(bayati->scale, kD4, notes, 15.0, &matches);
    CHECK(summary.noteCount == 1);
    CHECK(summary.inTuneCount == 0);
    if (!matches.empty()) {
        CHECK(matches[0].target.degreeIndex == 1);
        CHECK_NEAR(matches[0].target.deviationCents, 50.0, 3.0);
    }
}

TEST_CASE("the streaming tracker gives exactly the batch track, whatever the chunk size") {
    std::vector<float> audio = legato({0, 150, 300}, 0.3, 0.1);
    append(audio, silence(0.2));
    PitchDetectorConfig config;
    config.sampleRate = kRate;
    const auto batch = trackPitch(audio.data(), audio.size(), config, kHop);
    for (std::size_t chunk : {1u, 333u, 4096u, 48000u}) {
        PitchTracker tracker(config, kHop);
        for (std::size_t start = 0; start < audio.size(); start += chunk) {
            tracker.push(audio.data() + start, std::min(chunk, audio.size() - start));
        }
        const auto& streamed = tracker.frames();
        CHECK(streamed.size() == batch.size());
        bool same = streamed.size() == batch.size();
        for (std::size_t i = 0; same && i < batch.size(); ++i) {
            same = streamed[i].timeSeconds == batch[i].timeSeconds
                && streamed[i].estimate.frequencyHz == batch[i].estimate.frequencyHz
                && streamed[i].estimate.voiced == batch[i].estimate.voiced;
        }
        CHECK(same);
    }
}

TEST_CASE("the frame size grows with the sample rate so low voices still fit") {
    CHECK(frameSizeFor(44100.0, 60.0) == 2048);
    CHECK(frameSizeFor(48000.0, 60.0) == 2048);
    CHECK(frameSizeFor(96000.0, 60.0) == 4096);
    PitchDetectorConfig config;
    config.sampleRate = 96000.0;
    config.frameSize = frameSizeFor(config.sampleRate, config.minimumHz);
    PitchDetector detector(config);  // must not throw
    const auto tone = synth::tone(110.0, 0.2, 96000.0, 0.4, 4);
    const PitchEstimate estimate = detector.detect(tone.data() + 4000);
    CHECK(estimate.voiced);
    CHECK_NEAR(hzToCents(estimate.frequencyHz, 110.0), 0.0, 3.0);
}
