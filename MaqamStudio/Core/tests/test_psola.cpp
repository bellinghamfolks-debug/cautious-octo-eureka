#include "maqam/pitch_detector.hpp"
#include "maqam/psola.hpp"
#include "maqam/tuning.hpp"
#include "maqam/fft.hpp"
#include "test_support.hpp"

#include <algorithm>
#include <complex>

using namespace maqam;

namespace {

constexpr double kRate = 48000.0;

struct Tracked {
    std::vector<PitchFrame> frames;
    std::vector<float> hz;
    double firstTime = 0.0;
    double hop = 0.01;
};

Tracked trackOf(const std::vector<float>& audio) {
    PitchDetectorConfig config;
    config.sampleRate = kRate;
    Tracked tracked;
    tracked.frames = trackPitch(audio.data(), audio.size(), config, 480);
    for (const PitchFrame& frame : tracked.frames) {
        tracked.hz.push_back(frame.estimate.voiced ? static_cast<float>(frame.estimate.frequencyHz) : 0.0f);
    }
    tracked.firstTime = tracked.frames.empty() ? 0.0 : tracked.frames.front().timeSeconds;
    return tracked;
}

GrainPlan planFor(const std::vector<float>& audio, const Tracked& tracked, const std::vector<double>& shift,
                  double formantCents = 0.0, bool preserve = true, std::size_t chunk = 4096) {
    MarkFinder finder(kRate, tracked.firstTime, tracked.hop, tracked.hz);
    for (std::size_t start = 0; start < audio.size(); start += chunk) {
        finder.push(audio.data() + start, std::min(chunk, audio.size() - start));
    }
    return planGrains(finder.finish(), shift, tracked.firstTime, tracked.hop, kRate, audio.size(), formantCents, preserve);
}

std::vector<float> renderWhole(const GrainPlan& plan, const std::vector<float>& audio) {
    std::vector<float> out(audio.size());
    const float* in[] = {audio.data()};
    float* outs[] = {out.data()};
    renderBlock(plan, in, 1, 0, audio.size(), 0, out.size(), outs);
    return out;
}

double middlePitchCents(const std::vector<float>& audio, double referenceHz) {
    PitchDetectorConfig config;
    config.sampleRate = kRate;
    PitchDetector detector(config);
    const PitchEstimate estimate = detector.detect(audio.data() + audio.size() / 2 - 1024);
    return estimate.voiced ? hzToCents(estimate.frequencyHz, referenceHz) : 1e9;
}

double rms(const std::vector<float>& audio, std::size_t from, std::size_t to) {
    double sum = 0.0;
    for (std::size_t i = from; i < to; ++i) sum += static_cast<double>(audio[i]) * audio[i];
    return std::sqrt(sum / static_cast<double>(to - from));
}

// A buzzy source through one vocal-tract-like resonance: a vowel with a formant.
std::vector<float> vowel(double f0, double formantHz, double seconds) {
    const std::size_t count = static_cast<std::size_t>(seconds * kRate);
    std::vector<float> out(count);
    const double r = std::exp(-synth::kPi * 120.0 / kRate);
    const double a1 = -2.0 * r * std::cos(2.0 * synth::kPi * formantHz / kRate);
    const double a2 = r * r;
    double y1 = 0.0, y2 = 0.0, phase = 0.0;
    for (std::size_t i = 0; i < count; ++i) {
        phase += f0 / kRate;
        double pulse = 0.0;
        if (phase >= 1.0) { phase -= 1.0; pulse = 1.0; }
        const double y = pulse - a1 * y1 - a2 * y2;
        y2 = y1; y1 = y;
        out[i] = static_cast<float>(y * 0.05);
    }
    return out;
}

// Frequency of the strongest harmonic near the middle: where the formant sits.
double strongestHarmonicHz(const std::vector<float>& audio) {
    const std::size_t n = 8192;
    FFT fft(n);
    std::vector<std::complex<double>> bins(n);
    const std::size_t start = audio.size() / 2 - n / 2;
    for (std::size_t i = 0; i < n; ++i) {
        const double window = 0.5 - 0.5 * std::cos(2.0 * synth::kPi * i / (n - 1));
        bins[i] = {audio[start + i] * window, 0.0};
    }
    fft.transform(bins.data(), false);
    std::size_t best = 1;
    for (std::size_t k = 10; k < n / 2; ++k) {
        if (std::abs(bins[k]) > std::abs(bins[best])) best = k;
    }
    return static_cast<double>(best) * kRate / n;
}

}  // namespace

TEST_CASE("with no shift the voice comes back sample for sample, in blocks or whole") {
    std::vector<float> audio = synth::contour(220.0, 0.6, kRate, [](double t) { return 30.0 * std::sin(2 * synth::kPi * 5.5 * t); }, 0.4, 5);
    const auto noise = synth::noise(static_cast<std::size_t>(0.2 * kRate), 0.05);
    audio.insert(audio.end(), noise.begin(), noise.end());
    const auto more = synth::tone(330.0, 0.4, kRate, 0.3, 4);
    audio.insert(audio.end(), more.begin(), more.end());

    const Tracked tracked = trackOf(audio);
    const GrainPlan plan = planFor(audio, tracked, std::vector<double>(tracked.frames.size(), 0.0));
    const auto whole = renderWhole(plan, audio);
    double worst = 0.0;
    for (std::size_t i = 0; i < audio.size(); ++i) worst = std::max(worst, std::fabs(static_cast<double>(whole[i] - audio[i])));
    CHECK(worst < 1e-5);

    // Block by block, reading only the input range each block needs.
    std::vector<float> blocks(audio.size());
    for (std::size_t start = 0; start < audio.size(); start += 1000) {
        const std::size_t frames = std::min<std::size_t>(1000, audio.size() - start);
        std::int64_t from = 0, to = 0;
        inputRangeFor(plan, static_cast<std::int64_t>(start), frames, from, to);
        to = std::min<std::int64_t>(to, static_cast<std::int64_t>(audio.size()));
        const float* in[] = {audio.data() + from};
        float* out[] = {blocks.data() + start};
        renderBlock(plan, in, 1, from, static_cast<std::size_t>(to - from), static_cast<std::int64_t>(start), frames, out);
    }
    bool same = true;
    for (std::size_t i = 0; i < audio.size() && same; ++i) same = std::fabs(blocks[i] - whole[i]) < 1e-6f;
    CHECK(same);
}

TEST_CASE("a constant shift moves the pitch by exactly that many cents, quarter tones included") {
    for (double shift : {50.0, -150.0, 350.0}) {
        const auto audio = synth::tone(220.0, 0.8, kRate, 0.3, 6);
        const Tracked tracked = trackOf(audio);
        const auto out = renderWhole(planFor(audio, tracked, std::vector<double>(tracked.frames.size(), shift)), audio);
        CHECK(out.size() == audio.size());
        CHECK_NEAR(middlePitchCents(out, 220.0), shift, 3.0);
        const std::size_t a = audio.size() / 4, b = 3 * audio.size() / 4;
        CHECK_NEAR(20.0 * std::log10(rms(out, a, b) / rms(audio, a, b)), 0.0, 1.0);
    }
}

TEST_CASE("formants stay where they were when the pitch moves") {
    const auto audio = vowel(150.0, 900.0, 0.8);
    CHECK_NEAR(strongestHarmonicHz(audio), 900.0, 80.0);
    const Tracked tracked = trackOf(audio);
    const std::vector<double> up(tracked.frames.size(), 400.0);  // f0 150 -> 189 Hz
    const auto kept = renderWhole(planFor(audio, tracked, up, 0.0, true), audio);
    CHECK_NEAR(middlePitchCents(kept, 150.0), 400.0, 5.0);
    CHECK_NEAR(strongestHarmonicHz(kept), 900.0, 120.0);

    const auto moved = renderWhole(planFor(audio, tracked, up, 0.0, false), audio);
    CHECK_NEAR(middlePitchCents(moved, 150.0), 400.0, 5.0);
    CHECK(strongestHarmonicHz(moved) > 1030.0);
}

TEST_CASE("formants can be shifted on purpose without moving the pitch") {
    const auto audio = vowel(150.0, 900.0, 0.8);
    const Tracked tracked = trackOf(audio);
    const auto out = renderWhole(planFor(audio, tracked, std::vector<double>(tracked.frames.size(), 0.0), 300.0, true), audio);
    CHECK_NEAR(middlePitchCents(out, 150.0), 0.0, 5.0);
    CHECK(strongestHarmonicHz(out) > 1000.0);
}

TEST_CASE("unvoiced sound passes through untouched even when a shift is asked for") {
    const auto audio = synth::noise(static_cast<std::size_t>(0.5 * kRate), 0.2);
    Tracked tracked = trackOf(audio);
    const auto out = renderWhole(planFor(audio, tracked, std::vector<double>(tracked.frames.size(), 200.0)), audio);
    double worst = 0.0;
    for (std::size_t i = 0; i < audio.size(); ++i) worst = std::max(worst, std::fabs(static_cast<double>(out[i] - audio[i])));
    CHECK(worst < 1e-5);
}
