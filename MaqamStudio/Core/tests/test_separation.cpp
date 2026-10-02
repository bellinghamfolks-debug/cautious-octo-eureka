#include "maqam/separation.hpp"
#include "test_support.hpp"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <random>

using namespace maqam;

namespace {

constexpr double kRate = 44100.0;

struct Song {
    std::vector<float> vocal, band, mix;  // interleaved stereo
};

// A 2-second loop repeated: centred bass, a plucked pattern on the left, a held
// chord on the right, hi-hat noise slightly right. Over it, a centred voice
// singing a melody that does not repeat, with vibrato.
Song song(double seconds, bool stereo) {
    const auto frames = static_cast<std::size_t>(seconds * kRate);
    Song s;
    s.vocal.assign(2 * frames, 0.0f);
    s.band.assign(2 * frames, 0.0f);
    std::mt19937 rng(3);
    std::normal_distribution<double> noise(0.0, 1.0);
    const std::size_t loop = static_cast<std::size_t>(2.0 * kRate);
    std::vector<double> hat(loop, 0.0);
    for (std::size_t i = 0; i < loop; ++i) {
        const std::size_t beat = i % (loop / 8);
        hat[i] = beat < 2000 ? 0.05 * noise(rng) * std::exp(-static_cast<double>(beat) / 300.0) : 0.0;
    }
    const double bassNotes[4] = {55.0, 55.0, 73.4, 65.4};
    const double pluckNotes[8] = {220.0, 261.6, 329.6, 261.6, 293.7, 349.2, 440.0, 349.2};
    for (std::size_t i = 0; i < frames; ++i) {
        const std::size_t p = i % loop;
        const double t = static_cast<double>(p) / kRate;
        const double bass = 0.18 * std::sin(2 * synth::kPi * bassNotes[p / (loop / 4)] * t);
        const std::size_t step = p / (loop / 8);
        const double sinceStep = static_cast<double>(p % (loop / 8)) / kRate;
        double pluck = 0.0;
        for (int h = 1; h <= 4; ++h) pluck += std::sin(2 * synth::kPi * pluckNotes[step] * h * t) / (h * h);
        pluck *= 0.12 * std::exp(-sinceStep * 6.0);
        double chord = 0.0;
        for (double hz : {196.0, 246.9, 293.7}) chord += 0.04 * std::sin(2 * synth::kPi * hz * t) + 0.015 * std::sin(4 * synth::kPi * hz * t);
        const double left = bass + pluck + 0.3 * chord + 0.4 * hat[p];
        const double right = bass + 0.2 * pluck + chord + 1.0 * hat[p];
        s.band[2 * i] = static_cast<float>(stereo ? left : 0.5 * (left + right));
        s.band[2 * i + 1] = static_cast<float>(stereo ? right : 0.5 * (left + right));
    }
    // The voice: maqam-like steps, notes of 0.3 to 0.9 s, never the same phrase twice.
    std::uniform_real_distribution<double> length(0.3, 0.9);
    std::uniform_int_distribution<int> degree(0, 7);
    const double cents[8] = {0, 150, 300, 500, 700, 850, 1000, 1200};
    double phase = 0.0;
    std::size_t i = static_cast<std::size_t>(0.5 * kRate);
    while (i < frames) {
        const double hz0 = 293.7 * std::pow(2.0, cents[degree(rng)] / 1200.0);
        const auto noteFrames = static_cast<std::size_t>(length(rng) * kRate);
        for (std::size_t n = 0; n < noteFrames && i < frames; ++n, ++i) {
            const double t = static_cast<double>(n) / kRate;
            const double hz = hz0 * std::pow(2.0, 25.0 * std::sin(2 * synth::kPi * 5.5 * t) / 1200.0);
            phase += 2 * synth::kPi * hz / kRate;
            double v = 0.0;
            for (int h = 1; h <= 10; ++h) v += std::sin(h * phase) / h * (h == 3 || h == 4 ? 1.6 : 1.0);
            const double envelope = std::min(1.0, std::min(t, static_cast<double>(noteFrames - n) / kRate) * 30.0);
            s.vocal[2 * i] = s.vocal[2 * i + 1] = static_cast<float>(0.07 * envelope * v);
        }
        i += static_cast<std::size_t>(0.08 * kRate);  // breath
    }
    s.mix.resize(2 * frames);
    for (std::size_t k = 0; k < s.mix.size(); ++k) s.mix[k] = s.vocal[k] + s.band[k];
    return s;
}

struct Stems {
    std::vector<float> vocal, rest;
};

Stems separate(Separation& separation, const std::vector<float>& mix, bool classical) {
    const std::size_t frames = mix.size() / 2;
    for (std::size_t start = 0; start < frames; start += 3333) separation.analyse(mix.data() + 2 * start, std::min<std::size_t>(3333, frames - start));
    separation.finishAnalysis();
    if (classical) {
        for (std::size_t f = 0; f < separation.frameCount(); f += 100) separation.estimateClassical(f, 100);
    }
    Stems stems;
    std::vector<float> v(2 * 4096), r(2 * 4096);
    auto drain = [&] {
        std::size_t got;
        while ((got = separation.pull(v.data(), r.data(), 4096)) > 0) {
            stems.vocal.insert(stems.vocal.end(), v.begin(), v.begin() + static_cast<std::ptrdiff_t>(2 * got));
            stems.rest.insert(stems.rest.end(), r.begin(), r.begin() + static_cast<std::ptrdiff_t>(2 * got));
        }
    };
    for (std::size_t start = 0; start < frames; start += 5000) {
        separation.render(mix.data() + 2 * start, std::min<std::size_t>(5000, frames - start));
        drain();
    }
    separation.finishRender();
    drain();
    return stems;
}

// Signal-to-distortion ratio of an estimate, dB.
double sdr(const std::vector<float>& truth, const std::vector<float>& estimate) {
    double signal = 0.0, error = 0.0;
    for (std::size_t i = 0; i < truth.size() && i < estimate.size(); ++i) {
        signal += static_cast<double>(truth[i]) * truth[i];
        const double e = static_cast<double>(truth[i]) - estimate[i];
        error += e * e;
    }
    return 10.0 * std::log10(signal / std::max(error, 1e-30));
}

}  // namespace

TEST_CASE("the two stems always add back up to the mix, sample for sample") {
    const Song s = song(6.0, true);
    Separation separation(kRate);
    const Stems stems = separate(separation, s.mix, true);
    CHECK(stems.vocal.size() == s.mix.size());
    CHECK(stems.rest.size() == s.mix.size());
    double worst = 0.0;
    for (std::size_t i = 0; i < s.mix.size(); ++i) worst = std::max(worst, std::fabs(static_cast<double>(stems.vocal[i]) + stems.rest[i] - s.mix[i]));
    CHECK(worst < 1e-5);
}

TEST_CASE("a mask of one passes the whole mix as voice, and the stems line up in time") {
    const Song s = song(3.0, true);
    Separation separation(kRate);
    const std::size_t frames = s.mix.size() / 2;
    separation.analyse(s.mix.data(), frames);
    separation.finishAnalysis();
    std::vector<float> ones(separation.frameCount() * Separation::kBins, 1.0f);
    separation.setMasks(0, separation.frameCount(), ones.data());
    CHECK(separation.mask(5, 100) == 1.0f);
    separation.render(s.mix.data(), frames);
    separation.finishRender();
    std::vector<float> vocal(s.mix.size()), rest(s.mix.size());
    CHECK(separation.pull(vocal.data(), rest.data(), frames) == frames);
    double worstVocal = 0.0, worstRest = 0.0;
    for (std::size_t i = 0; i < s.mix.size(); ++i) {
        worstVocal = std::max(worstVocal, std::fabs(static_cast<double>(vocal[i]) - s.mix[i]));
        worstRest = std::max(worstRest, std::fabs(static_cast<double>(rest[i])));
    }
    CHECK(worstVocal < 1e-5);
    CHECK(worstRest < 1e-5);
}

TEST_CASE("magnitudes for a model are the mid channel's spectrum") {
    // A 1 kHz sine of amplitude 0.5 in both channels: the Hann-windowed peak
    // is amplitude * N / 4.
    const std::vector<float> tone = synth::tone(1000.0, 1.0, kRate, 0.5);
    std::vector<float> stereo(2 * tone.size());
    for (std::size_t i = 0; i < tone.size(); ++i) stereo[2 * i] = stereo[2 * i + 1] = tone[i];
    Separation separation(kRate);
    separation.analyse(stereo.data(), tone.size());
    separation.finishAnalysis();
    std::vector<float> frame(Separation::kBins);
    separation.magnitudes(20, 1, frame.data());
    const auto peak = static_cast<std::size_t>(std::max_element(frame.begin(), frame.end()) - frame.begin());
    CHECK_NEAR(peak * kRate / Separation::kFrameSize, 1000.0, kRate / Separation::kFrameSize);
    CHECK_NEAR(20 * std::log10(*std::max_element(frame.begin(), frame.end()) / (0.5 * Separation::kFrameSize / 4)), 0.0, 1.5);
}

TEST_CASE("the classical method pulls a centred voice out of a repeating stereo band") {
    const Song s = song(24.0, true);
    Separation separation(kRate);
    const Stems stems = separate(separation, s.mix, true);
    const double vocalBefore = sdr(s.vocal, s.mix), vocalAfter = sdr(s.vocal, stems.vocal);
    const double bandBefore = sdr(s.band, s.mix), bandAfter = sdr(s.band, stems.rest);
    std::printf("      stereo: voice %.1f -> %.1f dB, band %.1f -> %.1f dB\n", vocalBefore, vocalAfter, bandBefore, bandAfter);
    CHECK(vocalAfter - vocalBefore > 7.0);
    // The band's error is the voice's error with the sign turned, so its gain
    // is smaller by the level difference between them; it must still gain.
    CHECK(bandAfter - bandBefore > 1.5);
}

TEST_CASE("in mono, repetition alone still separates, less well") {
    const Song s = song(24.0, false);
    Separation separation(kRate);
    const Stems stems = separate(separation, s.mix, true);
    const double vocalBefore = sdr(s.vocal, s.mix), vocalAfter = sdr(s.vocal, stems.vocal);
    std::printf("      mono: voice %.1f -> %.1f dB\n", vocalBefore, vocalAfter);
    CHECK(vocalAfter - vocalBefore > 6.0);
}
