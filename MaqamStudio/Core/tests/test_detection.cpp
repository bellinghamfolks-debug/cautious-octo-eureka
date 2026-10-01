#include "maqam/detection.hpp"
#include "maqam/maqam.hpp"
#include "maqam/notes.hpp"
#include "maqam/pitch_detector.hpp"
#include "maqam/tuning.hpp"
#include "test_support.hpp"

#include <cstdio>
#include <string>

using namespace maqam;

namespace {

std::vector<Scale> allScales() {
    std::vector<Scale> scales;
    for (const MaqamDefinition& maqam : builtinMaqamat()) scales.push_back(maqam.scale);
    return scales;
}

std::size_t indexOf(const std::string& id) {
    const auto& all = builtinMaqamat();
    for (std::size_t i = 0; i < all.size(); ++i) if (all[i].id == id) return i;
    return all.size();
}

// Deterministic, roughly uniform in [-1, 1].
double jitter(unsigned& state) {
    state = state * 1664525u + 1013904223u;
    return static_cast<double>(state >> 8) / static_cast<double>(1u << 24) * 2.0 - 1.0;
}

struct Builder {
    double tonicHz;
    double detune = 0.0;
    unsigned seed = 7;
    double time = 0.0;
    std::vector<SungNote> notes;

    void add(double cents, double seconds) {
        SungNote note;
        note.startSeconds = time;
        note.endSeconds = time + seconds;
        note.hz = tonicHz * std::exp2((cents + detune * jitter(seed)) / 1200.0);
        note.cents = hzToCents(note.hz, 440.0);
        notes.push_back(note);
        time += seconds + 0.05;
    }
};

// Up the scale (to the octave, or the top degree for non-octave maqamat),
// back down, and rest on the tonic: how a maqam is commonly presented.
std::vector<SungNote> phrase(const MaqamDefinition& maqam, double detune = 0.0, double tonicHz = 0.0) {
    Builder b{tonicHz > 0.0 ? tonicHz : maqam.typicalTonicHz, detune};
    const Scale& scale = maqam.scale;
    b.add(0.0, 0.6);
    for (std::size_t d = 1; d < scale.degreeCount; ++d) b.add(scale.degrees[d].cents, 0.4);
    if (scale.octaveEquivalent) b.add(1200.0, 0.5);
    for (std::size_t d = scale.degreeCount - 1; d >= 1; --d) {
        const ScaleDegree& degree = scale.degrees[d];
        b.add(degree.alternateCount > 0 ? degree.alternates[0] : degree.cents, 0.35);
    }
    b.add(0.0, 1.2);
    return b.notes;
}

}  // namespace

TEST_CASE("every built-in maqam is recognised on its tonic from a sung presentation") {
    const auto scales = allScales();
    const auto& all = builtinMaqamat();
    std::size_t correct = 0;
    for (std::size_t i = 0; i < all.size(); ++i) {
        for (double detune : {0.0, 12.0}) {
            const MaqamDetection result = detectMaqam(phrase(all[i], detune), scales);
            CHECK(result.enoughData);
            const bool right = !result.ranked.empty() && result.ranked[0].scaleIndex == i
                && std::fabs(hzToCents(result.tonicHz, all[i].typicalTonicHz)) < 10.0;
            if (right) ++correct;
            else {
                std::printf("    missed %s (detune %.0f): got %s p=%.2f\n", all[i].id.c_str(), detune,
                            result.ranked.empty() ? "-" : all[result.ranked[0].scaleIndex].id.c_str(),
                            result.ranked.empty() ? 0.0 : result.ranked[0].probability);
            }
            if (right && detune == 0.0) CHECK(result.ranked[0].probability > 0.5);
        }
    }
    CHECK(correct == 2 * all.size());
}

TEST_CASE("the tonic is reported as sung, not snapped to A = 440") {
    const MaqamDefinition* bayati = findMaqam("bayati");
    if (!bayati) { CHECK(false); return; }
    const double lowered = bayati->typicalTonicHz * std::exp2(-37.0 / 1200.0);  // a singer 37 cents low
    const MaqamDetection result = detectMaqam(phrase(*bayati, 0.0, lowered), allScales());
    CHECK(!result.ranked.empty() && result.ranked[0].scaleIndex == indexOf("bayati"));
    CHECK_NEAR(hzToCents(result.tonicHz, lowered), 0.0, 2.0);
    CHECK(result.tonicConfidence >= result.ranked[0].probability);
}

TEST_CASE("the same notes with a different resting note give a different maqam") {
    // Rast on C and Sikah on E half-flat share their notes; only the tonic differs.
    const MaqamDefinition* rast = findMaqam("rast");
    const MaqamDefinition* sikah = findMaqam("sikah");
    if (!rast || !sikah) { CHECK(false); return; }
    const auto onRast = detectMaqam(phrase(*rast), allScales());
    const auto onSikah = detectMaqam(phrase(*sikah, 0.0, rast->typicalTonicHz * std::exp2(350.0 / 1200.0)), allScales());
    CHECK(onRast.ranked[0].scaleIndex == indexOf("rast"));
    CHECK(onSikah.ranked[0].scaleIndex == indexOf("sikah"));
    CHECK(std::fabs(hzToCents(onSikah.tonicHz, onRast.tonicHz) - 350.0) < 5.0);
}

TEST_CASE("quarter tones decide between maqamat that differ only by them") {
    // Bayati's second is 150, Kurd's 100; Rast's third 350, Ajam's 400 (from the same tonic).
    const auto scales = allScales();
    const MaqamDefinition* bayati = findMaqam("bayati");
    const MaqamDefinition* kurd = findMaqam("kurd");
    if (!bayati || !kurd) { CHECK(false); return; }
    CHECK(detectMaqam(phrase(*bayati), scales).ranked[0].scaleIndex == indexOf("bayati"));
    CHECK(detectMaqam(phrase(*kurd, 0.0, bayati->typicalTonicHz), scales).ranked[0].scaleIndex == indexOf("kurd"));
}

TEST_CASE("too little singing makes no claim") {
    Builder b{293.66};
    b.add(0.0, 0.6);
    b.add(150.0, 0.5);
    const MaqamDetection result = detectMaqam(b.notes, allScales());
    CHECK(!result.enoughData);
    CHECK(detectMaqam({}, allScales()).ranked.empty());
}

TEST_CASE("singing that cannot tell maqamat apart is reported as uncertain") {
    // Only the tonic, fourth and fifth: shared by most maqamat.
    Builder b{293.66};
    for (int i = 0; i < 4; ++i) { b.add(0.0, 0.5); b.add(500.0, 0.5); b.add(700.0, 0.5); }
    b.add(0.0, 1.0);
    const MaqamDetection result = detectMaqam(b.notes, allScales());
    CHECK(result.enoughData);
    CHECK(result.ranked[0].probability < 0.35);
    CHECK(result.tonicConfidence > 0.6);  // the tonic itself is still clear
}

TEST_CASE("detection works from audio through the pitch tracker and note finder") {
    const MaqamDefinition* hijaz = findMaqam("hijaz");
    if (!hijaz) { CHECK(false); return; }
    const auto notes = phrase(*hijaz);
    std::vector<float> audio;
    for (const SungNote& note : notes) {
        const auto tone = synth::tone(note.hz, note.endSeconds - note.startSeconds, 48000.0, 0.3, 5);
        audio.insert(audio.end(), tone.begin(), tone.end());
        audio.insert(audio.end(), static_cast<std::size_t>(0.05 * 48000.0), 0.0f);
    }
    PitchDetectorConfig config;
    config.sampleRate = 48000.0;
    const auto track = trackPitch(audio.data(), audio.size(), config, 480);
    NoteSegmentConfig segment;
    const auto found = segmentNotes(track, segment);
    const MaqamDetection result = detectMaqam(found, allScales());
    CHECK(result.enoughData);
    CHECK(!result.ranked.empty() && result.ranked[0].scaleIndex == indexOf("hijaz"));
    CHECK_NEAR(hzToCents(result.tonicHz, hijaz->typicalTonicHz), 0.0, 5.0);
}

TEST_CASE("singing only the lower jins leaves its branches undecided, but the tonic clear") {
    // Bayati and Husseini share everything below the sixth degree.
    Builder b{293.66};
    for (int i = 0; i < 3; ++i) {
        for (double cents : {0.0, 150.0, 300.0, 500.0, 700.0, 500.0, 300.0, 150.0}) b.add(cents, 0.4);
    }
    b.add(0.0, 1.2);
    const MaqamDetection result = detectMaqam(b.notes, allScales());
    CHECK(result.ranked.size() >= 2);
    const std::size_t first = result.ranked[0].scaleIndex;
    const std::size_t second = result.ranked[1].scaleIndex;
    CHECK((first == indexOf("bayati") && second == indexOf("husseini"))
          || (first == indexOf("husseini") && second == indexOf("bayati")));
    CHECK(result.ranked[0].probability < 0.6);
    CHECK(result.tonicConfidence > 0.9);
}
