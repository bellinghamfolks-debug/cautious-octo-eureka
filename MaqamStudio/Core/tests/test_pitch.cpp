#include "maqam/maqam.hpp"
#include "maqam/pitch_detector.hpp"
#include "maqam/tuning.hpp"
#include "test_support.hpp"

#include <algorithm>
#include <numeric>

using namespace maqam;

namespace {

PitchEstimate detectMiddle(const std::vector<float>& audio, const PitchDetectorConfig& config) {
    PitchDetector detector(config);
    const std::size_t start = audio.size() / 2 - config.frameSize / 2;
    return detector.detect(audio.data() + start);
}

PitchDetectorConfig configFor(double sampleRate) {
    PitchDetectorConfig config;
    config.sampleRate = sampleRate;
    return config;
}

double medianCents(const std::vector<PitchFrame>& track, double referenceHz, double from, double to) {
    std::vector<double> cents;
    for (const PitchFrame& frame : track) {
        if (frame.timeSeconds >= from && frame.timeSeconds <= to && frame.estimate.voiced) {
            cents.push_back(hzToCents(frame.estimate.frequencyHz, referenceHz));
        }
    }
    if (cents.empty()) return 1e9;
    std::sort(cents.begin(), cents.end());
    return cents[cents.size() / 2];
}

}  // namespace

TEST_CASE("pure tones across the singing range are detected within 3 cents") {
    for (double sampleRate : {44100.0, 48000.0}) {
        for (double hz : {82.41, 110.0, 196.0, 261.63, 440.0, 659.25, 987.77}) {
            const auto tone = signal::tone(hz, 0.5, sampleRate, 0.4, 1);
            const PitchEstimate estimate = detectMiddle(tone, configFor(sampleRate));
            CHECK(estimate.voiced);
            CHECK_NEAR(hzToCents(estimate.frequencyHz, hz), 0.0, 3.0);
            CHECK(estimate.confidence > 0.9);
        }
    }
}

TEST_CASE("harmonic-rich voice-like tones do not jump an octave") {
    for (double hz : {98.0, 146.83, 220.0, 392.0}) {
        const auto tone = signal::tone(hz, 0.5, 48000.0, 0.4, 8);
        const PitchEstimate estimate = detectMiddle(tone, configFor(48000.0));
        CHECK(estimate.voiced);
        CHECK_NEAR(hzToCents(estimate.frequencyHz, hz), 0.0, 5.0);
    }
}

TEST_CASE("a quarter tone is resolved: E half-flat is neither E nor E-flat") {
    const double cFour = 261.6255653;
    for (double cents : {300.0, 350.0, 400.0}) {
        const auto tone = signal::tone(centsToHz(cents, cFour), 0.5, 44100.0, 0.4, 5);
        const PitchEstimate estimate = detectMiddle(tone, configFor(44100.0));
        CHECK_NEAR(hzToCents(estimate.frequencyHz, cFour), cents, 4.0);
    }
}

TEST_CASE("pitch survives moderate noise (20 dB SNR)") {
    auto tone = signal::tone(233.08, 0.5, 48000.0, 0.3, 4);
    const auto hiss = signal::noise(tone.size(), 0.03);
    for (std::size_t i = 0; i < tone.size(); ++i) tone[i] += hiss[i];
    const PitchEstimate estimate = detectMiddle(tone, configFor(48000.0));
    CHECK(estimate.voiced);
    CHECK_NEAR(hzToCents(estimate.frequencyHz, 233.08), 0.0, 8.0);
}

TEST_CASE("silence and white noise are not reported as notes") {
    std::vector<float> silence(48000, 0.0f);
    CHECK(!detectMiddle(silence, configFor(48000.0)).voiced);
    const auto hiss = signal::noise(48000, 0.3, 99);
    PitchDetector detector(configFor(48000.0));
    int voiced = 0;
    for (std::size_t start = 0; start + 2048 <= hiss.size(); start += 2048) voiced += detector.detect(hiss.data() + start).voiced;
    CHECK(voiced <= 2);
}

TEST_CASE("vibrato is tracked, not flattened: the contour follows +/- 40 cents") {
    const double base = 220.0;
    const auto audio = signal::contour(base, 2.0, 48000.0, [](double t) { return 40.0 * std::sin(2.0 * signal::kPi * 5.5 * t); });
    const auto track = trackPitch(audio.data(), audio.size(), configFor(48000.0), 256);
    double low = 1e9, high = -1e9, worst = 0.0;
    for (const PitchFrame& frame : track) {
        if (!frame.estimate.voiced || frame.timeSeconds < 0.1 || frame.timeSeconds > 1.9) continue;
        const double cents = hzToCents(frame.estimate.frequencyHz, base);
        const double expected = 40.0 * std::sin(2.0 * signal::kPi * 5.5 * frame.timeSeconds);
        low = std::min(low, cents);
        high = std::max(high, cents);
        worst = std::max(worst, std::fabs(cents - expected));
    }
    CHECK(high > 30.0);
    CHECK(low < -30.0);
    CHECK(worst < 12.0);  // the 43 ms window smears a 5.5 Hz vibrato slightly
}

TEST_CASE("a sung Bayati phrase maps every note to its own degree, quarter tones intact") {
    // D, E half-flat, F, G, F, E half-flat, D: 0.4 s per note with 60 ms slides,
    // the way a phrase is sung rather than keyed.
    const double tonic = 293.6647679;
    const std::vector<double> notes = {0, 150, 300, 500, 300, 150, 0};
    const double noteSeconds = 0.4, glide = 0.06;
    const auto audio = signal::contour(tonic, notes.size() * noteSeconds, 44100.0, [&](double t) {
        const std::size_t index = std::min(notes.size() - 1, static_cast<std::size_t>(t / noteSeconds));
        const double into = t - index * noteSeconds;
        if (index > 0 && into < glide) {
            const double previous = notes[index - 1];
            return previous + (notes[index] - previous) * (into / glide);
        }
        return notes[index];
    });
    const auto track = trackPitch(audio.data(), audio.size(), configFor(44100.0), 256);
    const Scale& bayati = findMaqam("bayati")->scale;
    const std::vector<int> expectedDegree = {0, 1, 2, 3, 2, 1, 0};
    for (std::size_t i = 0; i < notes.size(); ++i) {
        const double from = i * noteSeconds + glide + 0.05;
        const double to = (i + 1) * noteSeconds - 0.05;
        const double sung = medianCents(track, tonic, from, to);
        CHECK_NEAR(sung, notes[i], 4.0);
        const TargetMatch match = nearestTarget(bayati, sung);
        CHECK(match.degreeIndex == expectedDegree[i]);
        CHECK_NEAR(match.deviationCents, 0.0, 4.0);
    }
}

TEST_CASE("the detector rejects configurations it cannot honour") {
    PitchDetectorConfig config;
    config.frameSize = 256;
    config.minimumHz = 40.0;  // needs a lag longer than half the frame
    bool threw = false;
    try { PitchDetector detector(config); } catch (const std::invalid_argument&) { threw = true; }
    CHECK(threw);
}
