#include "maqam/pitch_detector.hpp"
#include "maqam/studio.hpp"
#include "test_support.hpp"

#include <algorithm>
#include <cstdio>

using namespace maqam;

namespace {

constexpr double kRate = 48000.0;

struct Take {
    std::vector<float> audio;
    std::vector<bool> noteMask;  // per sample: inside a sung note
};

// Sung notes with vibrato, gaps between them, a noise floor, and optionally
// mains hum, breaths in the gaps and sibilant bursts.
Take vocal(double noiseDbfs, bool hum, bool breaths, bool sibilant, double seconds = 8.0) {
    Take take;
    const std::size_t total = static_cast<std::size_t>(seconds * kRate);
    take.audio.assign(total, 0.0f);
    take.noteMask.assign(total, false);
    const double noiseAmplitude = std::pow(10.0, noiseDbfs / 20.0) * std::sqrt(3.0);
    const auto noise = synth::noise(total, noiseAmplitude, 99);
    for (std::size_t i = 0; i < total; ++i) take.audio[i] = noise[i];
    const double notes[] = {220.0, 247.5, 261.6, 293.7, 261.6, 247.5, 220.0, 196.0};
    double t = 0.3;
    for (double hz : notes) {
        const double length = 0.6;
        const auto note = synth::contour(hz, length, kRate, [](double x) { return 25.0 * std::sin(2 * synth::kPi * 5.5 * x); }, 0.25, 8);
        const auto start = static_cast<std::size_t>(t * kRate);
        for (std::size_t i = 0; i < note.size() && start + i < total; ++i) {
            const double fade = std::min(1.0, std::min(static_cast<double>(i), static_cast<double>(note.size() - i)) / 480.0);
            take.audio[start + i] += static_cast<float>(note[i] * fade);
            take.noteMask[start + i] = true;
        }
        const std::size_t gapStart = start + note.size();
        if (breaths) {
            const auto breath = synth::noise(static_cast<std::size_t>(0.25 * kRate), 0.02 * std::sqrt(3.0), static_cast<unsigned>(hz));
            for (std::size_t i = 0; i < breath.size() && gapStart + 2400 + i < total; ++i) {
                const double envelope = std::sin(synth::kPi * i / breath.size());
                take.audio[gapStart + 2400 + i] += static_cast<float>(breath[i] * envelope);
            }
        }
        if (sibilant) {
            const auto hiss = synth::noise(4800, 0.25, static_cast<unsigned>(hz) + 7);
            float y1 = 0.0f;
            for (std::size_t i = 0; i < hiss.size() && start + i < total; ++i) {
                const float highPassed = hiss[i] - y1;  // crude first difference: mostly highs
                y1 = hiss[i];
                take.audio[start + i] += highPassed;
            }
        }
        t += length + 0.35;
    }
    if (hum) {
        for (std::size_t i = 0; i < total; ++i) {
            const double s = i / kRate;
            take.audio[i] += static_cast<float>(0.006 * std::sin(2 * synth::kPi * 50 * s) + 0.003 * std::sin(2 * synth::kPi * 100 * s)
                                                + 0.002 * std::sin(2 * synth::kPi * 150 * s));
        }
    }
    return take;
}

StudioAnalysis analyse(const std::vector<float>& audio) {
    StudioAnalyzer analyzer(kRate);
    for (std::size_t start = 0; start < audio.size(); start += 4096) analyzer.push(audio.data() + start, std::min<std::size_t>(4096, audio.size() - start));
    return analyzer.finish();
}

// Per analysis frame: did the pitch tracker hear singing there?
std::vector<bool> voicedFrames(const std::vector<float>& audio, const StudioAnalysis& analysis) {
    PitchDetectorConfig config;
    config.sampleRate = kRate;
    const auto track = trackPitch(audio.data(), audio.size(), config, 480);
    std::vector<bool> voiced(analysis.frames.size(), false);
    for (std::size_t i = 0; i < voiced.size(); ++i) {
        const double centre = (i * analysis.hop + SpectralDenoiser::kFrameSize / 2.0) / kRate;
        const long frame = std::lround((centre - track.front().timeSeconds) / 0.01);
        if (frame >= 0 && static_cast<std::size_t>(frame) < track.size()) voiced[i] = track[static_cast<std::size_t>(frame)].estimate.voiced;
    }
    return voiced;
}

bool has(const std::vector<StudioReason>& reasons, ReasonCode code) {
    return std::any_of(reasons.begin(), reasons.end(), [&](const StudioReason& r) { return r.code == code; });
}

double goertzelDb(const std::vector<float>& x, std::size_t from, std::size_t to, double hz) {
    const double c = 2.0 * std::cos(2.0 * synth::kPi * hz / kRate);
    double s1 = 0.0, s2 = 0.0;
    for (std::size_t i = from; i < to; ++i) { const double s = x[i] + c * s1 - s2; s2 = s1; s1 = s; }
    return 10.0 * std::log10(s1 * s1 + s2 * s2 - c * s1 * s2 + 1e-30);
}

struct Rendered {
    std::vector<float> left, right;
    double lufs;
};

Rendered render(const Take& take, const StudioAnalysis& analysis, const StudioPlan& plan, const std::vector<bool>& voicedPerFrame) {
    // The chain takes voicing per pitch frame; here the analysis frames serve.
    const double hop = static_cast<double>(analysis.hop) / kRate;
    const double first = SpectralDenoiser::kFrameSize / 2.0 / kRate;
    const auto breaths = findBreaths(analysis, voicedPerFrame);
    auto run = [&](double gainDb, bool limit, Rendered& out) {
        StudioChain chain(plan, analysis, breaths, voicedPerFrame, first, hop, gainDb, limit);
        std::vector<float> input = take.audio;
        input.resize(take.audio.size() + chain.tail(), 0.0f);
        std::vector<float> l(input.size()), r(input.size());
        chain.process(input.data(), l.data(), r.data(), input.size());
        out.left.assign(l.begin() + static_cast<std::ptrdiff_t>(chain.latency()), l.end());
        out.right.assign(r.begin() + static_cast<std::ptrdiff_t>(chain.latency()), r.end());
        dsp::LoudnessMeter meter(kRate, 2);
        meter.push(out.left.data(), out.right.data(), out.left.size());
        out.lufs = meter.integratedLufs();
    };
    Rendered measure, final;
    run(0.0, false, measure);
    run(plan.loudnessTargetLufs - measure.lufs, true, final);
    return final;
}

}  // namespace

TEST_CASE("the studio analyser measures noise floor, hum and level") {
    CHECK_NEAR(analyse(vocal(-55.0, false, false, false).audio).noiseFloorDbfs, -55.0, 3.0);
    // With hum the room is louder: noise and hum power add up to about -45.6 dBFS.
    const Take noisy = vocal(-55.0, true, false, false);
    const StudioAnalysis a = analyse(noisy.audio);
    CHECK_NEAR(a.noiseFloorDbfs, -45.6, 3.0);
    CHECK(a.humHz == 50.0);
    CHECK(a.humStrengthDb > 10.0);
    CHECK(a.voiceLevelDbfs > -25.0 && a.voiceLevelDbfs < -10.0);
    CHECK(a.integratedLufs > -40.0 && a.integratedLufs < -10.0);
    CHECK(a.noisePower.size() == SpectralDenoiser::kFrameSize / 2 + 1);

    const StudioAnalysis clean = analyse(vocal(-90.0, false, false, false).audio);
    CHECK(clean.humHz == 0.0);
    CHECK(clean.noiseFloorDbfs < -80.0);
}

TEST_CASE("the plan follows the recording: noise, hum and sibilance switch stages on") {
    std::vector<StudioReason> noisyReasons, cleanReasons, sibilantReasons;
    const StudioAnalysis noisy = analyse(vocal(-55.0, true, false, false).audio);
    const StudioPlan noisyPlan = planStudio(noisy, 0, GenreProfile::arabicPop, &noisyReasons);
    CHECK(noisyPlan.denoiseDb > 0.0);
    CHECK(noisyPlan.humHz == 50.0);
    CHECK(has(noisyReasons, ReasonCode::denoise));
    CHECK(has(noisyReasons, ReasonCode::hum));

    const StudioAnalysis clean = analyse(vocal(-90.0, false, false, false).audio);
    const StudioPlan cleanPlan = planStudio(clean, 0, GenreProfile::arabicPop, &cleanReasons);
    CHECK(cleanPlan.denoiseDb == 0.0);
    CHECK(cleanPlan.humHz == 0.0);
    CHECK(has(cleanReasons, ReasonCode::alreadyClean));
    CHECK(has(cleanReasons, ReasonCode::noHum));
    CHECK(has(cleanReasons, ReasonCode::deEssLight));

    const StudioAnalysis sibilant = analyse(vocal(-90.0, false, false, true).audio);
    planStudio(sibilant, 0, GenreProfile::arabicPop, &sibilantReasons);
    CHECK(has(sibilantReasons, ReasonCode::deEss));
}

TEST_CASE("every profile gives a complete, distinct plan") {
    const StudioAnalysis a = analyse(vocal(-70.0, false, false, false).audio);
    for (int p = 0; p < static_cast<int>(GenreProfile::count); ++p) {
        std::vector<StudioReason> reasons;
        const StudioPlan plan = planStudio(a, 2, static_cast<GenreProfile>(p), &reasons);
        CHECK(plan.loudnessTargetLufs >= -18.0 && plan.loudnessTargetLufs <= -8.0);
        CHECK(plan.eqCount <= 8);
        CHECK(plan.ceilingDb <= -1.0);
        CHECK(has(reasons, ReasonCode::loudness));
        CHECK(has(reasons, ReasonCode::breaths));
    }
    const StudioPlan tarab = planStudio(a, 0, GenreProfile::tarab, nullptr);
    const StudioPlan natural = planStudio(a, 0, GenreProfile::natural, nullptr);
    const StudioPlan shilat = planStudio(a, 0, GenreProfile::shilat, nullptr);
    CHECK(tarab.reverbMix > natural.reverbMix);
    CHECK(shilat.delayMix > tarab.delayMix);
    CHECK(shilat.compressor.ratio > natural.compressor.ratio);
    CHECK(profileTuningPreset(GenreProfile::heavyAutoTune) == 2);
    CHECK(profileTuningPreset(GenreProfile::tarab) == 0);
}

TEST_CASE("breaths between notes are found, and none inside notes") {
    const Take take = vocal(-70.0, false, true, false);
    const StudioAnalysis a = analyse(take.audio);
    const auto breaths = findBreaths(a, voicedFrames(take.audio, a));
    CHECK(breaths.size() >= 6 && breaths.size() <= 8);
    for (const auto& region : breaths) {
        std::size_t inside = 0;
        for (std::uint64_t i = region.start; i < region.end && i < take.noteMask.size(); ++i) inside += take.noteMask[i] ? 1 : 0;
        CHECK(inside < (region.end - region.start) / 10);
    }
}

TEST_CASE("end to end: the chain cleans, hits the loudness target, and never clips") {
    const Take take = vocal(-55.0, true, true, false);
    const StudioAnalysis a = analyse(take.audio);
    const auto voiced = voicedFrames(take.audio, a);
    const StudioPlan plan = planStudio(a, findBreaths(a, voiced).size(), GenreProfile::arabicPop, nullptr);
    const Rendered out = render(take, a, plan, voiced);
    CHECK(out.left.size() >= take.audio.size());
    CHECK_NEAR(out.lufs, plan.loudnessTargetLufs, 0.7);
    double peak = 0.0;
    bool finite = true;
    for (std::size_t i = 0; i < out.left.size(); ++i) {
        peak = std::max({peak, static_cast<double>(std::fabs(out.left[i])), static_cast<double>(std::fabs(out.right[i]))});
        finite = finite && std::isfinite(out.left[i]) && std::isfinite(out.right[i]);
    }
    CHECK(finite);
    CHECK(peak <= std::pow(10.0, plan.ceilingDb / 20.0) + 1e-6);
    // Hum, relative to the voice, is much lower than it was.
    const std::size_t from = static_cast<std::size_t>(1.0 * kRate), to = static_cast<std::size_t>(7.0 * kRate);
    const double inputHum = goertzelDb(take.audio, from, to, 50.0) - goertzelDb(take.audio, from, to, 220.0);
    const double outputHum = goertzelDb(out.left, from, to, 50.0) - goertzelDb(out.left, from, to, 220.0);
    CHECK(outputHum < inputHum - 20.0);
    // The space makes it stereo; the voice itself stays centred.
    double difference = 0.0;
    for (std::size_t i = from; i < to; ++i) difference += std::fabs(out.left[i] - out.right[i]);
    CHECK(difference > 1.0);
}

TEST_CASE("every profile renders within loudness and ceiling") {
    const Take take = vocal(-65.0, false, true, false, 5.0);
    const StudioAnalysis a = analyse(take.audio);
    const auto voiced = voicedFrames(take.audio, a);
    for (int p = 0; p < static_cast<int>(GenreProfile::count); ++p) {
        const StudioPlan plan = planStudio(a, 3, static_cast<GenreProfile>(p), nullptr);
        const Rendered out = render(take, a, plan, voiced);
        double peak = 0.0;
        for (std::size_t i = 0; i < out.left.size(); ++i) peak = std::max({peak, static_cast<double>(std::fabs(out.left[i])), static_cast<double>(std::fabs(out.right[i]))});
        if (std::fabs(out.lufs - plan.loudnessTargetLufs) > 1.0) std::printf("    profile %d: %.2f LUFS for %.1f\n", p, out.lufs, plan.loudnessTargetLufs);
        CHECK_NEAR(out.lufs, plan.loudnessTargetLufs, 1.0);
        CHECK(peak <= std::pow(10.0, plan.ceilingDb / 20.0) + 1e-6);
    }
}
