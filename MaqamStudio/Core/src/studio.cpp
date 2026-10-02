#include "maqam/studio.hpp"

#include <algorithm>
#include <cmath>
#include <numeric>

namespace maqam {
namespace {

constexpr std::size_t kFrame = SpectralDenoiser::kFrameSize;
constexpr std::size_t kBins = kFrame / 2 + 1;
constexpr int kLevelSteps = 141;  // -140 .. 0 dBFS in 1 dB steps

double percentile(std::vector<double> values, double fraction) {
    if (values.empty()) return 0.0;
    std::sort(values.begin(), values.end());
    const double position = fraction * static_cast<double>(values.size() - 1);
    const auto lower = static_cast<std::size_t>(position);
    const std::size_t upper = std::min(values.size() - 1, lower + 1);
    return values[lower] + (values[upper] - values[lower]) * (position - static_cast<double>(lower));
}

double goertzelPower(const std::vector<float>& data, const std::vector<double>& window, double hz, double sampleRate) {
    const double coefficient = 2.0 * std::cos(2.0 * dsp::kPi * hz / sampleRate);
    double s1 = 0.0, s2 = 0.0;
    for (std::size_t i = 0; i < data.size(); ++i) {
        const double s = data[i] * window[i] + coefficient * s1 - s2;
        s2 = s1;
        s1 = s;
    }
    return s1 * s1 + s2 * s2 - coefficient * s1 * s2;
}

// What each style asks of the chain. Starting points chosen by ear, not standards.
struct Traits {
    double highPassHz;
    double warmthDb, presenceDb, airDb;
    double ratio, depthDb, attackMs, releaseMs;
    bool multiband;
    double driveDb, saturationMix, exciter;
    double reverbMix, reverbSize, reverbDamping, preDelayMs;
    double delayMix, delayMs, delayFeedback;
    double lufs;
    double breathDb;
    int tuning;  // 0 natural, 1 strong, 2 robotic
};

const Traits& traits(GenreProfile profile) {
    static const std::array<Traits, static_cast<std::size_t>(GenreProfile::count)> table{{
        // khaleeji: warm, present, plate-like space and a soft echo
        {85, 1.0, 1.5, 2.0, 3.0, 8, 12, 140, false, 4, 0.25, 0.10, 0.18, 0.72, 0.45, 25, 0.08, 330, 0.25, -14, 8, 0},
        // arabic pop: forward and bright, controlled, short space
        {90, 0.5, 2.5, 3.0, 4.0, 10, 8, 110, true, 5, 0.30, 0.15, 0.14, 0.60, 0.40, 20, 0.07, 250, 0.20, -12, 10, 1},
        // tarab: natural dynamics, large warm hall, no echo
        {70, 1.5, 1.0, 1.0, 2.0, 6, 20, 200, false, 2, 0.15, 0.05, 0.25, 0.85, 0.50, 30, 0.00, 0, 0.0, -16, 6, 0},
        // shilat: dense, bright, prominent echo and wide hall
        {95, 0.5, 3.0, 2.5, 5.0, 12, 6, 100, true, 6, 0.35, 0.15, 0.20, 0.80, 0.40, 20, 0.15, 380, 0.35, -11, 12, 1},
        // iraqi: warm and present with a long room and a light echo
        {80, 1.0, 2.0, 1.5, 3.0, 8, 12, 150, false, 4, 0.25, 0.08, 0.22, 0.80, 0.45, 25, 0.08, 300, 0.25, -14, 8, 0},
        // egyptian: bright, present, medium room, short echo
        {85, 0.5, 2.0, 2.0, 3.5, 9, 10, 130, false, 4, 0.30, 0.10, 0.18, 0.70, 0.40, 20, 0.06, 280, 0.20, -13, 8, 0},
        // levantine: clear and airy, medium room
        {85, 0.5, 1.5, 2.0, 3.0, 8, 12, 140, false, 3, 0.20, 0.08, 0.16, 0.65, 0.40, 20, 0.06, 260, 0.20, -14, 8, 0},
        // acoustic: light touch, small room
        {70, 0.5, 0.5, 1.0, 2.0, 6, 20, 200, false, 1, 0.10, 0.00, 0.12, 0.50, 0.50, 15, 0.00, 0, 0.0, -16, 6, 0},
        // clean studio: corrective only, almost dry
        {80, 0.0, 0.0, 0.0, 3.0, 8, 12, 150, false, 0, 0.00, 0.00, 0.06, 0.30, 0.50, 10, 0.00, 0, 0.0, -14, 8, 0},
        // modern commercial: loud, bright, dense, short space and echo
        {100, 0.5, 3.0, 3.5, 4.0, 12, 5, 90, true, 5, 0.30, 0.20, 0.12, 0.55, 0.35, 15, 0.08, 220, 0.25, -10, 12, 1},
        // natural: as recorded, gently cleaned
        {60, 0.0, 0.0, 0.0, 1.8, 5, 25, 250, false, 0, 0.00, 0.00, 0.08, 0.40, 0.50, 10, 0.00, 0, 0.0, -16, 4, 0},
        // heavy auto-tune: robotic tuning, bright, with echo
        {100, 0.5, 3.0, 3.0, 4.0, 10, 6, 100, true, 4, 0.30, 0.15, 0.14, 0.60, 0.35, 15, 0.10, 240, 0.25, -11, 12, 2},
    }};
    const auto index = static_cast<std::size_t>(profile);
    return table[index < table.size() ? index : static_cast<std::size_t>(GenreProfile::natural)];
}

// Average power of the long-term spectrum between two frequencies, in dB.
double bandDb(const StudioAnalysis& analysis, double lowHz, double highHz) {
    if (analysis.spectrumDb.empty()) return -160.0;
    const double binHz = analysis.sampleRate / kFrame;
    const auto first = static_cast<std::size_t>(std::max(1.0, std::floor(lowHz / binHz)));
    const auto last = static_cast<std::size_t>(std::min(static_cast<double>(kBins - 1), std::ceil(highHz / binHz)));
    double sum = 0.0;
    std::size_t count = 0;
    for (std::size_t k = first; k <= last; ++k) { sum += std::pow(10.0, analysis.spectrumDb[k] / 10.0); ++count; }
    return count ? 10.0 * std::log10(sum / static_cast<double>(count) + 1e-30) : -160.0;
}

// Long-term spectrum smoothed over +/- `octaves`/2 around each bin.
std::vector<double> smoothed(const StudioAnalysis& analysis, double octaves) {
    std::vector<double> out(kBins, -160.0);
    const double binHz = analysis.sampleRate / kFrame;
    for (std::size_t k = 1; k < kBins; ++k) {
        const double hz = k * binHz;
        const double low = hz * std::pow(2.0, -octaves / 2.0);
        const double high = hz * std::pow(2.0, octaves / 2.0);
        out[k] = bandDb(analysis, low, high);
    }
    return out;
}

}  // namespace

// ------------------------------------------------------------------ analysis

StudioAnalyzer::StudioAnalyzer(double sampleRate) : loudness_(sampleRate, 1) {
    result_.sampleRate = sampleRate;
    window_.resize(kFrame);
    for (std::size_t i = 0; i < kFrame; ++i) window_[i] = 0.5 - 0.5 * std::cos(2.0 * dsp::kPi * i / kFrame);
    ring_.assign(kFrame, 0.0f);
    bins_.assign(kFrame, {0.0, 0.0});
    spectraByLevel_.assign(kLevelSteps, std::vector<double>(kBins, 0.0));
    framesByLevel_.assign(kLevelSteps, 0);
    humBlock_.assign(static_cast<std::size_t>(std::round(0.5 * sampleRate)), 0.0f);
    humWindow_.resize(humBlock_.size());
    for (std::size_t i = 0; i < humWindow_.size(); ++i) {
        humWindow_[i] = 0.5 - 0.5 * std::cos(2.0 * dsp::kPi * i / humWindow_.size());
    }
}

void StudioAnalyzer::push(const float* mono, std::size_t count) {
    if (!mono) return;
    loudness_.push(mono, nullptr, count);
    for (std::size_t i = 0; i < count; ++i) {
        const float x = mono[i];
        const double magnitude = std::fabs(x);
        result_.peakDbfs = std::max(result_.peakDbfs, dsp::gainToDb(magnitude));
        if (magnitude >= 0.999) ++result_.clippedSamples;
        ring_[ringIndex_] = x;
        ringIndex_ = (ringIndex_ + 1) % kFrame;
        ++seen_;
        if (seen_ >= kFrame && ++sinceFrame_ >= SpectralDenoiser::kHop) {
            sinceFrame_ = 0;
            analyseFrame();
        }
        humBlock_[humFill_++] = x;
        if (humFill_ == humBlock_.size()) {
            analyseHumBlock();
            humFill_ = 0;
        }
    }
    result_.samples += count;
}

void StudioAnalyzer::analyseFrame() {
    double energy = 0.0;
    for (std::size_t i = 0; i < kFrame; ++i) {
        const float x = ring_[(ringIndex_ + i) % kFrame];
        energy += static_cast<double>(x) * x;
        bins_[i] = {x * window_[i], 0.0};
    }
    fft_.transform(bins_.data(), false);
    const double binHz = result_.sampleRate / kFrame;
    StudioFrame frame;
    frame.levelDb = static_cast<float>(10.0 * std::log10(energy / kFrame + 1e-30));
    double total = 0.0, low = 0.0, mid = 0.0, high = 0.0, logSum = 0.0, linearSum = 0.0;
    std::size_t flatBins = 0;
    const int step = std::clamp(static_cast<int>(std::lround(frame.levelDb)) + 140, 0, kLevelSteps - 1);
    std::vector<double>& accumulate = spectraByLevel_[static_cast<std::size_t>(step)];
    for (std::size_t k = 1; k < kBins; ++k) {
        const double power = std::norm(bins_[k]);
        accumulate[k] += power;
        const double hz = k * binHz;
        total += power;
        if (hz < 90.0) low += power;
        if (hz >= 300.0 && hz < 3000.0) mid += power;
        if (hz >= 5000.0 && hz < 9000.0) high += power;
        if (hz >= 300.0 && hz < 8000.0) { logSum += std::log(power + 1e-30); linearSum += power; ++flatBins; }
    }
    ++framesByLevel_[static_cast<std::size_t>(step)];
    frame.lowRatio = static_cast<float>(total > 0.0 ? low / total : 0.0);
    frame.highRatioDb = static_cast<float>(10.0 * std::log10((high + 1e-30) / (mid + 1e-30)));
    if (flatBins > 0 && linearSum > 0.0) {
        frame.flatness = static_cast<float>(std::exp(logSum / flatBins) / (linearSum / flatBins));
    }
    result_.frames.push_back(frame);
}

void StudioAnalyzer::analyseHumBlock() {
    double energy = 0.0;
    for (float x : humBlock_) energy += static_cast<double>(x) * x;
    const double levelDb = 10.0 * std::log10(energy / humBlock_.size() + 1e-30);
    const std::vector<double>& window = humWindow_;
    auto ratioFor = [&](double base) {
        double sum = 0.0;
        for (int h = 1; h <= 3; ++h) {
            const double hz = base * h;
            const double on = goertzelPower(humBlock_, window, hz, result_.sampleRate);
            const double around = 0.5 * (goertzelPower(humBlock_, window, hz - 7.0, result_.sampleRate)
                                         + goertzelPower(humBlock_, window, hz + 7.0, result_.sampleRate));
            sum += 10.0 * std::log10((on + 1e-30) / (around + 1e-30));
        }
        return sum / 3.0;
    };
    humBlocks_.push_back({levelDb, ratioFor(50.0), ratioFor(60.0)});
}

StudioAnalysis StudioAnalyzer::finish() {
    StudioAnalysis& r = result_;
    r.integratedLufs = loudness_.integratedLufs();
    std::vector<double> levels;
    for (const StudioFrame& frame : r.frames) {
        if (frame.levelDb > -100.0f) levels.push_back(frame.levelDb);  // digital silence is not the room
    }
    r.noiseFloorDbfs = levels.empty() ? -160.0 : percentile(levels, 0.10);

    std::vector<double> voice;
    std::vector<double> sibilance;
    double rumble = 0.0;
    for (const StudioFrame& frame : r.frames) {
        if (frame.levelDb > r.noiseFloorDbfs + 15.0 && frame.levelDb > -60.0f) {
            voice.push_back(frame.levelDb);
            sibilance.push_back(frame.highRatioDb);
            rumble += frame.lowRatio;
        }
    }
    if (!voice.empty()) {
        r.voiceLevelDbfs = percentile(voice, 0.5);
        r.levelSpreadDb = percentile(voice, 0.9) - percentile(voice, 0.1);
        r.sibilanceDb = percentile(sibilance, 0.95);
        r.rumbleRatio = rumble / static_cast<double>(voice.size());
    }

    // Noise: the quietest tenth of the non-silent frames, averaged.
    r.noisePower.assign(kBins, 0.0f);
    const std::size_t wanted = std::max<std::size_t>(1, levels.size() / 10);
    std::size_t taken = 0;
    std::vector<double> sum(kBins, 0.0);
    for (int step = 40; step < kLevelSteps && taken < wanted; ++step) {  // from -100 dBFS up
        const auto s = static_cast<std::size_t>(step);
        if (framesByLevel_[s] == 0) continue;
        for (std::size_t k = 0; k < kBins; ++k) sum[k] += spectraByLevel_[s][k];
        taken += framesByLevel_[s];
    }
    if (taken > 0) for (std::size_t k = 0; k < kBins; ++k) r.noisePower[k] = static_cast<float>(sum[k] / taken);

    // Voice spectrum: frames within 10 dB of the typical singing level and above.
    std::fill(sum.begin(), sum.end(), 0.0);
    std::size_t voiceFrames = 0;
    const int fromStep = std::clamp(static_cast<int>(std::lround(r.voiceLevelDbfs - 10.0)) + 140, 0, kLevelSteps - 1);
    for (int step = fromStep; step < kLevelSteps; ++step) {
        const auto s = static_cast<std::size_t>(step);
        for (std::size_t k = 0; k < kBins; ++k) sum[k] += spectraByLevel_[s][k];
        voiceFrames += framesByLevel_[s];
    }
    r.spectrumDb.assign(kBins, -160.0f);
    if (voiceFrames > 0) {
        for (std::size_t k = 0; k < kBins; ++k) r.spectrumDb[k] = static_cast<float>(10.0 * std::log10(sum[k] / voiceFrames + 1e-30));
    }

    // Hum: judged in the quieter blocks, where the voice does not mask it.
    if (!humBlocks_.empty()) {
        std::vector<double> blockLevels;
        for (const HumBlock& block : humBlocks_) blockLevels.push_back(block.levelDb);
        const double quiet = percentile(blockLevels, 0.3);
        std::vector<double> r50, r60;
        for (const HumBlock& block : humBlocks_) {
            if (block.levelDb <= quiet + 1e-9) { r50.push_back(block.ratio50Db); r60.push_back(block.ratio60Db); }
        }
        const double m50 = percentile(r50, 0.5);
        const double m60 = percentile(r60, 0.5);
        r.humStrengthDb = std::max(m50, m60);
        r.humHz = r.humStrengthDb >= 10.0 ? (m50 >= m60 ? 50.0 : 60.0) : 0.0;
    }
    return r;
}

std::vector<RegionAttenuator::Region> findBreaths(const StudioAnalysis& a, const std::vector<bool>& voiced) {
    std::vector<RegionAttenuator::Region> regions;
    if (a.voiceLevelDbfs <= -159.0) return regions;
    const double hopSeconds = static_cast<double>(a.hop) / a.sampleRate;
    std::size_t runStart = 0;
    std::size_t runLength = 0;
    auto close = [&](std::size_t endExclusive) {
        const double seconds = runLength * hopSeconds;
        if (runLength > 0 && seconds >= 0.12 && seconds <= 0.8) {
            const std::uint64_t centreFirst = runStart * a.hop + kFrame / 2;
            const std::uint64_t centreLast = (endExclusive - 1) * a.hop + kFrame / 2;
            regions.push_back({centreFirst - a.hop / 2, centreLast + a.hop / 2});
        }
        runLength = 0;
    };
    for (std::size_t i = 0; i < a.frames.size(); ++i) {
        const StudioFrame& f = a.frames[i];
        const bool sung = i < voiced.size() && voiced[i];
        const bool breath = !sung && f.levelDb > a.noiseFloorDbfs + 6.0 && f.levelDb < a.voiceLevelDbfs - 8.0
            && f.flatness > 0.2f;
        if (breath) {
            if (runLength == 0) runStart = i;
            ++runLength;
        } else {
            close(i);
        }
    }
    close(a.frames.size());
    return regions;
}

int profileTuningPreset(GenreProfile profile) { return traits(profile).tuning; }

// ---------------------------------------------------------------------- plan

StudioPlan planStudio(const StudioAnalysis& a, std::size_t breathCount, GenreProfile profile,
                      std::vector<StudioReason>* reasons) {
    StudioPlan plan;
    plan.profile = profile;
    const Traits& t = traits(profile);
    auto say = [&](ReasonCode code, double first = 0.0, double second = 0.0) {
        if (reasons) reasons->push_back({code, first, second});
    };
    auto addEq = [&](dsp::Biquad::Type type, double hz, double q, double gainDb) {
        if (plan.eqCount >= static_cast<int>(plan.eq.size()) || std::fabs(gainDb) < 0.25) return;
        plan.eq[static_cast<std::size_t>(plan.eqCount++)] = {type, hz, q, gainDb};
    };
    const double voice = a.voiceLevelDbfs > -100.0 ? a.voiceLevelDbfs : -24.0;

    if (a.clippedSamples > 0) say(ReasonCode::clippedInput, static_cast<double>(a.clippedSamples));

    // Low end.
    plan.highPassHz = t.highPassHz;
    if (a.rumbleRatio > 0.05) {
        plan.highPassHz = std::max(plan.highPassHz, 110.0);
        say(ReasonCode::rumble, a.rumbleRatio * 100.0, plan.highPassHz);
    } else {
        say(ReasonCode::highPass, plan.highPassHz);
    }

    // Hum.
    if (a.humHz > 0.0) {
        plan.humHz = a.humHz;
        plan.humHarmonics = 4;
        say(ReasonCode::hum, a.humHz, a.humStrengthDb);
    } else {
        say(ReasonCode::noHum);
    }

    // Noise: compare the room with the singing.
    const double snr = voice - a.noiseFloorDbfs;
    if (a.noiseFloorDbfs > -72.0 && snr < 50.0) {
        plan.denoiseDb = std::clamp(52.0 - snr, 6.0, 18.0);
        say(ReasonCode::denoise, a.noiseFloorDbfs, plan.denoiseDb);
    } else {
        say(ReasonCode::alreadyClean, a.noiseFloorDbfs);
    }

    plan.plosiveDb = 12.0;
    say(ReasonCode::plosives);

    if (breathCount > 0) {
        plan.breathDb = t.breathDb;
        say(ReasonCode::breaths, static_cast<double>(breathCount), plan.breathDb);
    } else {
        say(ReasonCode::noBreaths);
    }

    if (a.levelSpreadDb > 12.0) {
        plan.leveler = true;
        plan.levelerTargetDb = voice;
        plan.levelerRangeDb = 6.0;
        say(ReasonCode::leveler, a.levelSpreadDb);
    } else {
        say(ReasonCode::steadyLevel, a.levelSpreadDb);
    }

    // Corrective EQ: the voice's own spectrum against a typical vocal balance
    // (relative to its 0.7-1.4 kHz band; the expected offsets are heuristics).
    const double reference = bandDb(a, 700.0, 1400.0);
    if (reference > -150.0) {
        const double mud = bandDb(a, 200.0, 400.0) - reference - 4.0;
        if (mud > 1.5) { addEq(dsp::Biquad::Type::peaking, 300.0, 1.0, -std::min(4.0, mud)); say(ReasonCode::mud, std::min(4.0, mud)); }
        const double box = bandDb(a, 500.0, 900.0) - reference - 2.0;
        if (box > 1.5) { addEq(dsp::Biquad::Type::peaking, 700.0, 1.4, -std::min(3.0, box)); say(ReasonCode::boxy, std::min(3.0, box)); }
        const double harsh = bandDb(a, 2500.0, 4500.0) - reference + 6.0;
        if (harsh > 2.0) {
            plan.harshnessOn = true;
            plan.harshness.frequencyHz = 3500.0;
            plan.harshness.q = 1.2;
            plan.harshness.thresholdDb = voice - 12.0;
            plan.harshness.ratio = 3.0;
            plan.harshness.maximumCutDb = std::min(6.0, harsh + 2.0);
            plan.harshness.attackMs = 3.0;
            plan.harshness.releaseMs = 80.0;
            say(ReasonCode::harsh, harsh);
        }
        const double air = bandDb(a, 10000.0, 14000.0) - reference + 24.0;
        if (air < -3.0 && a.sampleRate > 30000.0) {
            const double lift = std::min(3.0, -air - 1.0);
            addEq(dsp::Biquad::Type::highShelf, 10000.0, 0.7071, lift);
            say(ReasonCode::air, lift);
        }

        // Resonances: narrow peaks standing out of the octave around them.
        const std::vector<double> narrow = smoothed(a, 1.0 / 6.0);
        const std::vector<double> broad = smoothed(a, 1.0);
        const double binHz = a.sampleRate / kFrame;
        std::vector<std::pair<double, double>> peaks;  // excess, hz
        for (std::size_t k = 2; k + 2 < kBins; ++k) {
            const double hz = k * binHz;
            if (hz < 250.0 || hz > 5000.0) continue;
            const double excess = narrow[k] - broad[k];
            if (excess > 5.0 && narrow[k] >= narrow[k - 1] && narrow[k] >= narrow[k + 1]) peaks.push_back({excess, hz});
        }
        std::sort(peaks.begin(), peaks.end(), [](const auto& x, const auto& y) { return x.first > y.first; });
        std::vector<double> chosen;
        for (const auto& peak : peaks) {
            const double excess = peak.first;
            const double hz = peak.second;
            if (chosen.size() >= 2) break;
            const bool separate = std::all_of(chosen.begin(), chosen.end(), [&](double other) {
                return std::fabs(std::log2(hz / other)) > 1.0 / 3.0;
            });
            if (!separate) continue;
            chosen.push_back(hz);
            const double cut = std::min(6.0, excess - 2.0);
            addEq(dsp::Biquad::Type::peaking, hz, 6.0, -cut);
            say(ReasonCode::resonance, hz, cut);
        }
    }

    // Tonal colour of the style.
    if (t.warmthDb != 0.0 || t.presenceDb != 0.0 || t.airDb != 0.0) {
        addEq(dsp::Biquad::Type::lowShelf, 150.0, 0.7071, t.warmthDb);
        addEq(dsp::Biquad::Type::peaking, 3500.0, 0.8, t.presenceDb);
        if (a.sampleRate > 30000.0) addEq(dsp::Biquad::Type::highShelf, 11000.0, 0.7071, t.airDb);
        say(ReasonCode::tonal, t.presenceDb, t.airDb);
    }

    // De-essing: always on; firmer when the voice is sibilant.
    plan.deEsserOn = true;
    plan.deEsser.frequencyHz = 6500.0;
    plan.deEsser.q = 1.0;
    plan.deEsser.ratio = 4.0;
    plan.deEsser.attackMs = 1.0;
    plan.deEsser.releaseMs = 60.0;
    if (a.sibilanceDb > -12.0) {
        plan.deEsser.thresholdDb = voice - 14.0;
        plan.deEsser.maximumCutDb = 9.0;
        say(ReasonCode::deEss, a.sibilanceDb);
    } else {
        plan.deEsser.thresholdDb = voice - 8.0;
        plan.deEsser.maximumCutDb = 4.0;
        say(ReasonCode::deEssLight, a.sibilanceDb);
    }

    // Compression, relative to how loud this voice is.
    plan.compressor.thresholdDb = voice - t.depthDb + 6.0;
    plan.compressor.ratio = t.ratio;
    plan.compressor.kneeDb = 6.0;
    plan.compressor.attackMs = t.attackMs;
    plan.compressor.releaseMs = t.releaseMs;
    plan.compressor.makeupDb = 0.0;
    say(ReasonCode::compress, t.ratio, t.depthDb);
    if (t.multiband) {
        plan.multibandOn = true;
        for (std::size_t band = 0; band < 3; ++band) {
            plan.multiband[band].thresholdDb = voice - 6.0 - (band == 1 ? 0.0 : 4.0);
            plan.multiband[band].ratio = band == 1 ? 2.0 : 2.5;
            plan.multiband[band].kneeDb = 6.0;
            plan.multiband[band].attackMs = band == 0 ? 20.0 : 5.0;
            plan.multiband[band].releaseMs = band == 0 ? 200.0 : 100.0;
        }
        say(ReasonCode::multiband);
    }

    plan.saturationDriveDb = t.driveDb;
    plan.saturationMix = t.saturationMix;
    plan.exciterAmount = t.exciter;
    if (t.saturationMix > 0.0 || t.exciter > 0.0) say(ReasonCode::colour, t.driveDb, t.exciter);

    plan.reverbMix = t.reverbMix;
    plan.reverbSize = t.reverbSize;
    plan.reverbDamping = t.reverbDamping;
    plan.reverbPreDelayMs = t.preDelayMs;
    if (t.reverbMix > 0.0) say(ReasonCode::reverb, t.reverbSize, t.reverbMix);
    plan.delayMix = t.delayMix;
    plan.delayMs = t.delayMs > 0.0 ? t.delayMs : 250.0;
    plan.delayFeedback = t.delayFeedback;
    if (t.delayMix > 0.0) say(ReasonCode::delay, t.delayMs, t.delayFeedback);

    plan.loudnessTargetLufs = t.lufs;
    say(ReasonCode::loudness, a.integratedLufs, t.lufs);
    plan.ceilingDb = -1.0;
    say(ReasonCode::limiter, plan.ceilingDb);
    return plan;
}

// --------------------------------------------------------------------- chain

StudioChain::StudioChain(const StudioPlan& plan, const StudioAnalysis& analysis,
                         std::vector<RegionAttenuator::Region> breaths, std::vector<bool> voiced, double firstTime,
                         double hopSeconds, double outputGainDb, bool limit)
    : plan_(plan), sampleRate_(analysis.sampleRate), limit_(limit), outputGain_(dsp::dbToGain(outputGainDb)),
      voiced_(std::move(voiced)), firstTime_(firstTime), hop_(hopSeconds > 0.0 ? hopSeconds : 0.01) {
    const double rate = sampleRate_;
    if (plan.highPassHz > 0.0) {
        highPass_.set(dsp::Biquad::Type::highPass, rate, plan.highPassHz, 0.5412);
        highPass2_.set(dsp::Biquad::Type::highPass, rate, plan.highPassHz, 1.3066);
    }
    if (plan.humHz > 0.0) hum_.prepare(rate, plan.humHz, plan.humHarmonics);
    std::uint64_t shift = 0;
    if (plan.denoiseDb > 0.0 && !analysis.noisePower.empty()) {
        denoiser_ = std::make_unique<SpectralDenoiser>();
        denoiser_->prepare(analysis.noisePower, plan.denoiseDb);
        shift = denoiser_->latency();
    }
    plosives_.prepare(rate, plan.plosiveDb);
    // Later stages see the signal after the denoiser's delay.
    for (RegionAttenuator::Region& region : breaths) { region.start += shift; region.end += shift; }
    breaths_.prepare(rate, plan.breathDb > 0.0 ? std::move(breaths) : std::vector<RegionAttenuator::Region>{}, plan.breathDb);
    leveler_.prepare(rate, plan.levelerTargetDb, plan.levelerRangeDb, plan.levelerRangeDb, plan.levelerTargetDb - 25.0);
    for (int i = 0; i < plan.eqCount; ++i) {
        const EqBand& band = plan.eq[static_cast<std::size_t>(i)];
        eq_[static_cast<std::size_t>(i)].set(band.type, rate, band.frequencyHz, band.q, band.gainDb);
    }
    deEsser_.prepare(rate, plan.deEsser);
    harshness_.prepare(rate, plan.harshness);
    compressor_.prepare(rate, plan.compressor);
    multiband_.prepare(rate, 250.0, 4000.0, plan.multiband);
    saturator_.prepare(plan.saturationDriveDb, plan.saturationMix);
    exciter_.prepare(rate, 3500.0, 6.0, plan.exciterAmount);
    reverb_.prepare(rate, plan.reverbSize, plan.reverbDamping, plan.reverbWidth, plan.reverbPreDelayMs);
    delay_.prepare(rate, plan.delayMs, plan.delayFeedback, plan.delayHighCutHz, plan.delayPingPong);
    limiter_.prepare(rate, plan.ceilingDb, 1.5, 80.0);
}

std::size_t StudioChain::latency() const noexcept {
    return (denoiser_ ? denoiser_->latency() : 0) + (limit_ ? limiter_.latency() : 0);
}

std::size_t StudioChain::tail() const noexcept {
    double seconds = 0.0;
    if (plan_.reverbMix > 0.0) seconds = std::max(seconds, 0.5 + 3.0 * plan_.reverbSize);
    if (plan_.delayMix > 0.0 && plan_.delayFeedback > 0.0) {
        seconds = std::max(seconds, plan_.delayMs / 1000.0 * std::log(0.001) / std::log(plan_.delayFeedback));
    }
    seconds = std::min(seconds, 6.0);
    return latency() + static_cast<std::size_t>(seconds * sampleRate_);
}

void StudioChain::process(const float* mono, float* left, float* right, std::size_t count) noexcept {
    const std::uint64_t shift = denoiser_ ? denoiser_->latency() : 0;
    for (std::size_t i = 0; i < count; ++i) {
        float x = mono[i];
        if (plan_.highPassHz > 0.0) x = highPass2_.process(highPass_.process(x));
        if (plan_.humHz > 0.0) x = hum_.process(x);
        if (denoiser_) x = denoiser_->process(x);
        // The sample now leaving the denoiser entered `shift` samples ago.
        bool sung = false;
        if (position_ >= shift && !voiced_.empty()) {
            const double t = static_cast<double>(position_ - shift) / sampleRate_;
            const long frame = std::lround((t - firstTime_) / hop_);
            sung = frame >= 0 && static_cast<std::size_t>(frame) < voiced_.size() && voiced_[static_cast<std::size_t>(frame)];
        }
        ++position_;
        if (plan_.plosiveDb > 0.0) x = plosives_.process(x, sung);
        if (plan_.breathDb > 0.0) x = breaths_.process(x);
        if (plan_.leveler) x = leveler_.process(x);
        for (int b = 0; b < plan_.eqCount; ++b) x = eq_[static_cast<std::size_t>(b)].process(x);
        if (plan_.deEsserOn) x = deEsser_.process(x);
        if (plan_.harshnessOn) x = harshness_.process(x);
        if (plan_.compressor.ratio > 1.0) x = compressor_.process(x);
        if (plan_.multibandOn) x = multiband_.process(x);
        if (plan_.saturationMix > 0.0) x = saturator_.process(x);
        if (plan_.exciterAmount > 0.0) x = exciter_.process(x);

        // The voice stays in the centre; width comes from the space around it.
        float l = x, r = x;
        if (plan_.reverbMix > 0.0) {
            float wetL = 0.0f, wetR = 0.0f;
            reverb_.process(x, wetL, wetR);
            l += static_cast<float>(plan_.reverbMix) * wetL;
            r += static_cast<float>(plan_.reverbMix) * wetR;
        }
        if (plan_.delayMix > 0.0) {
            float wetL = 0.0f, wetR = 0.0f;
            delay_.process(x, wetL, wetR);
            l += static_cast<float>(plan_.delayMix) * wetL;
            r += static_cast<float>(plan_.delayMix) * wetR;
        }
        l = static_cast<float>(l * outputGain_);
        r = static_cast<float>(r * outputGain_);
        if (limit_) limiter_.process(l, r);
        left[i] = l;
        right[i] = r;
    }
}

}  // namespace maqam
