#include "maqam/cleanup.hpp"
#include "maqam/fft.hpp"
#include "test_support.hpp"

#include <algorithm>
#include <complex>

using namespace maqam;

namespace {

constexpr double kRate = 48000.0;

double rmsDb(const std::vector<float>& x, std::size_t from, std::size_t to) {
    double sum = 0.0;
    for (std::size_t i = from; i < to; ++i) sum += static_cast<double>(x[i]) * x[i];
    return 10.0 * std::log10(sum / static_cast<double>(to - from) + 1e-30);
}

// Average Hann-windowed frame power of `x`, as the studio analyser measures noise.
std::vector<float> noiseProfile(const std::vector<float>& x) {
    const std::size_t n = SpectralDenoiser::kFrameSize;
    FFT fft(n);
    std::vector<double> sum(n / 2 + 1, 0.0);
    std::size_t frames = 0;
    std::vector<std::complex<double>> bins(n);
    for (std::size_t start = 0; start + n <= x.size(); start += n / 4, ++frames) {
        for (std::size_t i = 0; i < n; ++i) bins[i] = {x[start + i] * (0.5 - 0.5 * std::cos(2 * synth::kPi * i / n)), 0.0};
        fft.transform(bins.data(), false);
        for (std::size_t k = 0; k <= n / 2; ++k) sum[k] += std::norm(bins[k]);
    }
    std::vector<float> profile(n / 2 + 1);
    for (std::size_t k = 0; k <= n / 2; ++k) profile[k] = static_cast<float>(sum[k] / static_cast<double>(frames));
    return profile;
}

}  // namespace

TEST_CASE("the denoiser passes clean audio unchanged, exactly one frame late") {
    SpectralDenoiser denoiser;
    denoiser.prepare(std::vector<float>(SpectralDenoiser::kFrameSize / 2 + 1, 0.0f), 20.0);
    const auto tone = synth::tone(330.0, 0.5, kRate, 0.3, 4);
    std::vector<float> out(tone.size());
    for (std::size_t i = 0; i < tone.size(); ++i) out[i] = denoiser.process(tone[i]);
    const std::size_t latency = denoiser.latency();
    double worst = 0.0;
    for (std::size_t i = latency + SpectralDenoiser::kFrameSize; i < tone.size(); ++i) {
        worst = std::max(worst, static_cast<double>(std::fabs(out[i] - tone[i - latency])));
    }
    CHECK(worst < 1e-4);
}

TEST_CASE("the denoiser lowers background noise and keeps the voice") {
    const std::size_t second = static_cast<std::size_t>(kRate);
    const auto noise = synth::noise(3 * second, 0.02);
    auto signal = noise;
    const auto tone = synth::tone(300.0, 1.0, kRate, 0.3, 6);
    for (std::size_t i = 0; i < second; ++i) signal[second + i] += tone[i];
    SpectralDenoiser denoiser;
    denoiser.prepare(noiseProfile(std::vector<float>(noise.begin(), noise.begin() + second)), 18.0);
    std::vector<float> out(signal.size());
    for (std::size_t i = 0; i < signal.size(); ++i) out[i] = denoiser.process(signal[i]);
    const std::size_t l = denoiser.latency();
    // Noise-only third second (shifted by the latency): at least 10 dB lower.
    CHECK(rmsDb(out, 2 * second + l + 4800, 3 * second) < rmsDb(signal, 2 * second + 4800, 3 * second - l) - 10.0);
    // The tone's level stays within 1 dB.
    CHECK_NEAR(rmsDb(out, second + l + 9600, 2 * second + l - 9600), rmsDb(signal, second + 9600, 2 * second - 9600), 1.0);
}

TEST_CASE("hum and its harmonics are notched out, the voice is not") {
    const auto voice = synth::tone(220.0, 1.0, kRate, 0.3, 4);
    std::vector<float> hum(voice.size());
    for (std::size_t i = 0; i < hum.size(); ++i) {
        const double t = i / kRate;
        hum[i] = static_cast<float>(0.05 * std::sin(2 * synth::kPi * 50 * t) + 0.03 * std::sin(2 * synth::kPi * 100 * t)
                                    + 0.02 * std::sin(2 * synth::kPi * 150 * t));
    }
    HumRemover remover;
    remover.prepare(kRate, 50.0, 4);
    std::vector<float> humOut(hum.size()), voiceOut(voice.size());
    for (std::size_t i = 0; i < hum.size(); ++i) humOut[i] = remover.process(hum[i]);
    remover.reset();
    for (std::size_t i = 0; i < voice.size(); ++i) voiceOut[i] = remover.process(voice[i]);
    const std::size_t from = hum.size() / 2;
    CHECK(rmsDb(humOut, from, hum.size()) < rmsDb(hum, from, hum.size()) - 30.0);
    CHECK_NEAR(rmsDb(voiceOut, from, voice.size()), rmsDb(voice, from, voice.size()), 0.5);
}

TEST_CASE("a plosive before a note is softened; low sung notes never trigger it") {
    // 0.3 s of room, a 40 ms thump (a "b" into the microphone), then the note.
    const std::size_t at = static_cast<std::size_t>(0.25 * kRate);
    const std::size_t noteStart = static_cast<std::size_t>(0.3 * kRate);
    const auto note = synth::tone(110.0, 1.0, kRate, 0.3, 8);
    std::vector<float> signal(noteStart, 0.0f);
    signal.insert(signal.end(), note.begin(), note.end());
    for (std::size_t i = 0; i < 1920; ++i) {
        signal[at + i] += static_cast<float>(0.6 * std::sin(2 * synth::kPi * 40 * i / kRate) * std::exp(-static_cast<double>(i) / 600.0));
    }
    PlosiveTamer tamer;
    tamer.prepare(kRate, 18.0);
    std::vector<float> out(signal.size());
    for (std::size_t i = 0; i < signal.size(); ++i) out[i] = tamer.process(signal[i], i >= noteStart);
    CHECK(tamer.events() == 1);
    CHECK(rmsDb(out, at, at + 1920) < rmsDb(signal, at, at + 1920) - 3.0);
    CHECK_NEAR(rmsDb(out, noteStart + 9600, noteStart + 38400), rmsDb(signal, noteStart + 9600, noteStart + 38400), 0.2);

    // Low male notes starting and stopping, all voiced: nothing happens.
    for (double hz : {82.0, 110.0, 147.0}) {
        PlosiveTamer quiet;
        quiet.prepare(kRate, 18.0);
        std::vector<float> phrase;
        for (int k = 0; k < 4; ++k) {
            const auto part = synth::tone(hz, 0.5, kRate, 0.4, 8);
            phrase.insert(phrase.end(), part.begin(), part.end());
        }
        double worst = 0.0;
        for (float sample : phrase) worst = std::max(worst, static_cast<double>(std::fabs(quiet.process(sample, true) - sample)));
        CHECK(quiet.events() == 0);
        CHECK(worst < 1e-6);
    }
}

TEST_CASE("breath regions are lowered with short fades, the rest untouched") {
    RegionAttenuator attenuator;
    attenuator.prepare(kRate, {{24000, 33600}}, 12.0);
    std::vector<float> ones(48000, 1.0f), out(ones.size());
    for (std::size_t i = 0; i < ones.size(); ++i) out[i] = attenuator.process(ones[i]);
    CHECK_NEAR(out[10000], 1.0, 1e-6);
    CHECK_NEAR(out[28000], std::pow(10.0, -12.0 / 20.0), 1e-6);
    CHECK(out[23800] < 1.0f && out[23800] > out[28000]);
    CHECK_NEAR(out[40000], 1.0, 1e-6);
}
