#include "maqam/dsp.hpp"
#include "test_support.hpp"

#include <algorithm>

using namespace maqam::dsp;

namespace {

constexpr double kRate = 48000.0;

double rmsDb(const std::vector<float>& x, std::size_t from = 0, std::size_t to = 0) {
    if (to == 0) to = x.size();
    double sum = 0.0;
    for (std::size_t i = from; i < to; ++i) sum += static_cast<double>(x[i]) * x[i];
    return 10.0 * std::log10(sum / static_cast<double>(to - from) + 1e-30);
}

// Steady-state gain of a processor on a sine, in dB.
template <typename Process>
double sineGainDb(double hz, Process process) {
    auto in = synth::tone(hz, 0.5, kRate, 0.25, 1);
    std::vector<float> out(in.size());
    for (std::size_t i = 0; i < in.size(); ++i) out[i] = process(in[i]);
    return rmsDb(out, in.size() / 2, in.size()) - rmsDb(in, in.size() / 2, in.size());
}

}  // namespace

TEST_CASE("cookbook filters have the response they are set to") {
    Biquad hp;
    hp.set(Biquad::Type::highPass, kRate, 100.0, 0.7071);
    CHECK_NEAR(gainToDb(hp.magnitudeAt(100.0, kRate)), -3.01, 0.1);
    CHECK(gainToDb(hp.magnitudeAt(25.0, kRate)) < -23.0);
    Biquad peak;
    peak.set(Biquad::Type::peaking, kRate, 3000.0, 1.0, 6.0);
    CHECK_NEAR(gainToDb(peak.magnitudeAt(3000.0, kRate)), 6.0, 0.01);
    CHECK_NEAR(gainToDb(peak.magnitudeAt(200.0, kRate)), 0.0, 0.2);
    Biquad notch;
    notch.set(Biquad::Type::notch, kRate, 50.0, 10.0);
    CHECK(gainToDb(notch.magnitudeAt(50.0, kRate)) < -60.0);
    CHECK_NEAR(gainToDb(notch.magnitudeAt(220.0, kRate)), 0.0, 0.1);
    Biquad shelf;
    shelf.set(Biquad::Type::highShelf, kRate, 8000.0, 0.7071, 4.0);
    CHECK_NEAR(gainToDb(shelf.magnitudeAt(18000.0, kRate)), 4.0, 0.4);
    // And a real signal agrees with the formula.
    peak.reset();
    CHECK_NEAR(sineGainDb(3000.0, [&](float x) { return peak.process(x); }), 6.0, 0.1);
}

TEST_CASE("the multiband split sums back to a flat response") {
    MultibandCompressor multiband;
    CompressorSettings unity;
    unity.ratio = 1.0;
    unity.kneeDb = 0.0;
    multiband.prepare(kRate, 250.0, 4000.0, {unity, unity, unity});
    for (double hz : {60.0, 250.0, 1000.0, 4000.0, 9000.0}) {
        multiband.reset();
        CHECK_NEAR(sineGainDb(hz, [&](float x) { return multiband.process(x); }), 0.0, 0.3);
    }
}

TEST_CASE("the compressor settles on its static curve") {
    CompressorSettings settings;
    settings.thresholdDb = -20.0;
    settings.ratio = 4.0;
    settings.kneeDb = 0.0;
    Compressor compressor;
    compressor.prepare(kRate, settings);
    // A sine peaking at -6 dBFS is 14 dB over: out = -20 + 14/4 = -16.5, a 10.5 dB cut.
    auto in = synth::tone(1000.0, 1.0, kRate, dbToGain(-6.0), 1);
    std::vector<float> out(in.size());
    for (std::size_t i = 0; i < in.size(); ++i) out[i] = compressor.process(in[i]);
    CHECK_NEAR(rmsDb(out, in.size() / 2, in.size()) - rmsDb(in, in.size() / 2, in.size()), -10.5, 0.6);
    CHECK_NEAR(Compressor::staticCurveDb(-30.0, settings), -30.0, 1e-9);
}

TEST_CASE("the limiter never exceeds its ceiling and leaves quiet audio alone") {
    Limiter limiter;
    limiter.prepare(kRate, -1.0, 1.5, 80.0);
    auto loud = synth::tone(200.0, 0.5, kRate, 1.6, 1);  // peaks at +4 dBFS
    const double ceiling = dbToGain(-1.0);
    double worst = 0.0;
    for (float sample : loud) {
        float l = sample, r = -sample;
        limiter.process(l, r);
        worst = std::max({worst, static_cast<double>(std::fabs(l)), static_cast<double>(std::fabs(r))});
    }
    CHECK(worst <= ceiling + 1e-6);
    CHECK(limiter.maximumReductionDb() > 4.0);

    limiter.reset();
    auto quiet = synth::tone(200.0, 0.2, kRate, 0.2, 1);
    std::vector<float> out;
    for (float sample : quiet) { float l = sample, r = sample; limiter.process(l, r); out.push_back(l); }
    const std::size_t latency = limiter.latency();
    double difference = 0.0;
    for (std::size_t i = latency; i < quiet.size(); ++i) difference = std::max(difference, static_cast<double>(std::fabs(out[i] - quiet[i - latency])));
    CHECK(difference < 1e-6);
}

TEST_CASE("BS.1770 loudness reads the reference tones correctly") {
    // A 1 kHz sine at -23 dBFS in both channels is -23 LUFS; in one channel, -26.
    auto tone = synth::tone(1000.0, 5.0, kRate, dbToGain(-23.0), 1);
    LoudnessMeter stereo(kRate, 2);
    stereo.push(tone.data(), tone.data(), tone.size());
    CHECK_NEAR(stereo.integratedLufs(), -23.0, 0.1);
    LoudnessMeter mono(kRate, 1);
    mono.push(tone.data(), nullptr, tone.size());
    CHECK_NEAR(mono.integratedLufs(), -26.01, 0.1);
    // Silence is gated out, not averaged in.
    std::vector<float> withSilence(tone);
    withSilence.insert(withSilence.end(), static_cast<std::size_t>(5 * kRate), 0.0f);
    LoudnessMeter gated(kRate, 2);
    gated.push(withSilence.data(), withSilence.data(), withSilence.size());
    CHECK_NEAR(gated.integratedLufs(), -23.0, 0.2);
}

TEST_CASE("the de-esser cuts sibilance and leaves vowels") {
    DynamicBandSettings settings;
    settings.frequencyHz = 7000.0;
    settings.q = 1.2;
    settings.thresholdDb = -30.0;
    settings.ratio = 6.0;
    settings.maximumCutDb = 10.0;
    DynamicBand deEsser;
    deEsser.prepare(kRate, settings);
    CHECK(sineGainDb(7000.0, [&](float x) { return deEsser.process(x * 2.0f) / 2.0f; }) < -4.0);
    deEsser.reset();
    CHECK_NEAR(sineGainDb(300.0, [&](float x) { return deEsser.process(x); }), 0.0, 0.3);
}

TEST_CASE("the leveler brings phrases toward the target and holds through silence") {
    Leveler leveler;
    leveler.prepare(kRate, -20.0, 9.0, 9.0, -50.0);
    auto quiet = synth::tone(300.0, 3.0, kRate, dbToGain(-29.0), 1);  // sine peak -29: RMS-equivalent -29
    std::vector<float> out(quiet.size());
    for (std::size_t i = 0; i < quiet.size(); ++i) out[i] = leveler.process(quiet[i]);
    CHECK_NEAR(rmsDb(out, out.size() - 24000, out.size()) + 3.01, -20.0, 1.0);
    // Silence after it does not make the gain run away.
    for (int i = 0; i < 48000; ++i) leveler.process(0.0f);
    std::vector<float> again(4800);
    for (std::size_t i = 0; i < again.size(); ++i) again[i] = leveler.process(quiet[i]);
    CHECK(rmsDb(again) + 3.01 < -10.0);
}

TEST_CASE("reverb rings out, decays, and is wide") {
    Reverb reverb;
    reverb.prepare(kRate, 0.7, 0.4, 1.0, 20.0);
    std::vector<float> left(static_cast<std::size_t>(3 * kRate)), right(left.size());
    for (std::size_t i = 0; i < left.size(); ++i) reverb.process(i == 0 ? 1.0f : 0.0f, left[i], right[i]);
    const double early = rmsDb(left, 2400, 24000);
    const double late = rmsDb(left, 96000, 120000);
    CHECK(early > late + 20.0);
    CHECK(late > -200.0);
    double lr = 0, ll = 0, rr = 0;
    for (std::size_t i = 2400; i < 48000; ++i) { lr += left[i] * right[i]; ll += left[i] * left[i]; rr += right[i] * right[i]; }
    CHECK(std::fabs(lr / std::sqrt(ll * rr)) < 0.5);
}

TEST_CASE("delay repeats on time, quieter each time, across the sides") {
    Delay delay;
    delay.prepare(kRate, 250.0, 0.5, 20000.0, true);
    std::vector<float> left(static_cast<std::size_t>(kRate)), right(left.size());
    for (std::size_t i = 0; i < left.size(); ++i) delay.process(i == 0 ? 1.0f : 0.0f, left[i], right[i]);
    const std::size_t step = 12000;
    CHECK_NEAR(left[step], 1.0, 1e-6);
    CHECK(std::fabs(right[2 * step]) > 0.3 && std::fabs(right[2 * step]) < 0.6);
    CHECK(std::fabs(left[2 * step]) < 1e-6);
}

TEST_CASE("saturation is transparent when quiet and rounds off when loud") {
    Saturator saturator;
    saturator.prepare(6.0, 1.0);
    CHECK_NEAR(saturator.process(0.001f), 0.001, 1e-5);
    CHECK(saturator.process(0.9f) < 0.6f);
}
