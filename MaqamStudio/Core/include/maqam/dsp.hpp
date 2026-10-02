// Building blocks for the studio chain.
//
// Every processor follows the same rule as the pitch detector: construct or
// prepare() off the audio thread (that is where memory is allocated), then
// process() never allocates, locks or does I/O, so the same code can later run
// live. State is per instance; one instance per channel.
#pragma once

#include <array>
#include <cmath>
#include <cstddef>
#include <vector>

namespace maqam::dsp {

constexpr double kPi = 3.14159265358979323846;

inline double dbToGain(double db) { return std::pow(10.0, db / 20.0); }
inline double gainToDb(double gain) { return gain > 1e-12 ? 20.0 * std::log10(gain) : -240.0; }

// ------------------------------------------------------------------ filters

// RBJ "Audio EQ Cookbook" biquad, transposed direct form II.
class Biquad {
public:
    enum class Type { lowPass, highPass, bandPass, notch, peaking, lowShelf, highShelf };

    void set(Type type, double sampleRate, double frequency, double q, double gainDb = 0.0) noexcept;
    void setIdentity() noexcept;
    // Raw normalised coefficients (a0 = 1).
    void setCoefficients(double b0, double b1, double b2, double a1, double a2) noexcept {
        b0_ = b0; b1_ = b1; b2_ = b2; a1_ = a1; a2_ = a2;
    }
    void reset() noexcept { z1_ = z2_ = 0.0; }

    float process(float x) noexcept {
        const double y = b0_ * x + z1_;
        z1_ = b1_ * x - a1_ * y + z2_;
        z2_ = b2_ * x - a2_ * y;
        return static_cast<float>(y);
    }
    void process(float* data, std::size_t count) noexcept {
        for (std::size_t i = 0; i < count; ++i) data[i] = process(data[i]);
    }
    // |H| at `frequency`, for tests and for describing what an EQ does.
    double magnitudeAt(double frequency, double sampleRate) const noexcept;

private:
    double b0_ = 1, b1_ = 0, b2_ = 0, a1_ = 0, a2_ = 0;
    double z1_ = 0, z2_ = 0;
};

// Linkwitz-Riley 4th-order split: low + high sum to an all-pass (flat magnitude).
class Crossover {
public:
    void set(double sampleRate, double frequency) noexcept;
    void reset() noexcept;
    void process(float x, float& low, float& high) noexcept {
        low = lp2_.process(lp1_.process(x));
        high = hp2_.process(hp1_.process(x));
    }

private:
    Biquad lp1_, lp2_, hp1_, hp2_;
};

// --------------------------------------------------------------- envelopes

// One-pole follower with separate attack and release, on a linear level.
class Envelope {
public:
    void set(double sampleRate, double attackMs, double releaseMs) noexcept;
    void reset(double value = 0.0) noexcept { value_ = value; }
    double process(double input) noexcept {
        const double coefficient = input > value_ ? attack_ : release_;
        value_ = input + coefficient * (value_ - input);
        return value_;
    }
    double value() const noexcept { return value_; }

private:
    double attack_ = 0.0, release_ = 0.0, value_ = 0.0;
};

// --------------------------------------------------------------- dynamics

struct CompressorSettings {
    double thresholdDb = -18.0;
    double ratio = 3.0;
    double kneeDb = 6.0;
    double attackMs = 10.0;
    double releaseMs = 120.0;
    double makeupDb = 0.0;
};

// Feed-forward compressor on a smoothed RMS-ish level (soft knee).
class Compressor {
public:
    void prepare(double sampleRate, const CompressorSettings& settings) noexcept;
    void reset() noexcept;
    // Gain to apply for this detector input (linear); call per sample.
    double gainFor(float detector) noexcept;
    float process(float x) noexcept { return static_cast<float>(x * gainFor(x)); }
    double lastReductionDb() const noexcept { return lastReductionDb_; }
    static double staticCurveDb(double inputDb, const CompressorSettings& settings) noexcept;

private:
    CompressorSettings settings_;
    Envelope level_;
    Envelope gain_;
    double lastReductionDb_ = 0.0;
};

// Three bands (two LR4 crossovers), a compressor per band, summed.
class MultibandCompressor {
public:
    void prepare(double sampleRate, double lowSplitHz, double highSplitHz,
                 const std::array<CompressorSettings, 3>& bands) noexcept;
    void reset() noexcept;
    float process(float x) noexcept;

private:
    Crossover low_, high_;
    // The low band is delayed through matching all-pass phase by passing the
    // high split through it too; the bands still sum to a flat magnitude.
    Crossover lowPhase_;
    std::array<Compressor, 3> bands_{};
};

// Dynamic EQ: a peaking (or high-shelf) band that cuts only while its own
// band-passed signal is above threshold. A de-esser is one of these.
struct DynamicBandSettings {
    double frequencyHz = 7000.0;
    double q = 1.5;
    bool shelf = false;
    double thresholdDb = -30.0;
    double ratio = 4.0;
    double maximumCutDb = 8.0;
    double attackMs = 1.0;
    double releaseMs = 60.0;
};

class DynamicBand {
public:
    void prepare(double sampleRate, const DynamicBandSettings& settings) noexcept;
    void reset() noexcept;
    float process(float x) noexcept;
    double lastCutDb() const noexcept { return lastCutDb_; }

private:
    DynamicBandSettings settings_;
    double sampleRate_ = 48000.0;
    Biquad detector_;
    Biquad cut_;
    Envelope envelope_;
    double appliedCutDb_ = 0.0;
    double lastCutDb_ = 0.0;
    int counter_ = 0;
};

// Lookahead sample-peak limiter: the output never exceeds the ceiling.
class Limiter {
public:
    void prepare(double sampleRate, double ceilingDb, double lookaheadMs, double releaseMs);
    void reset() noexcept;
    // Processes a stereo pair in place; both channels share one gain.
    void process(float& left, float& right) noexcept;
    // The same, limiting on `detectorPeak` too: the true (inter-sample) peak
    // around this sample, so the output stays under the ceiling between samples.
    void process(float& left, float& right, double detectorPeak) noexcept;
    std::size_t latency() const noexcept { return delay_; }
    double maximumReductionDb() const noexcept { return maximumReductionDb_; }

private:
    double ceiling_ = 1.0;
    std::size_t delay_ = 0;
    std::vector<float> left_, right_;
    std::vector<double> peaks_;  // per-sample required gain, for the lookahead minimum
    std::size_t position_ = 0;
    double gain_ = 1.0;
    double release_ = 0.0;
    double maximumReductionDb_ = 0.0;
};

// Slow automatic gain ("vocal rider"): keeps phrases near a target level,
// within a bounded range, without touching silence.
class Leveler {
public:
    void prepare(double sampleRate, double targetDb, double maximumBoostDb, double maximumCutDb,
                 double gateDb) noexcept;
    void reset() noexcept;
    float process(float x) noexcept;

private:
    double target_ = -20.0, maximumBoost_ = 6.0, maximumCut_ = 6.0, gate_ = -50.0;
    Envelope power_;
    Envelope gain_;
};

// -------------------------------------------------------------- colour

// Smooth tanh saturation with drive, level-compensated, mixed with the dry signal.
class Saturator {
public:
    void prepare(double driveDb, double mix) noexcept;
    float process(float x) const noexcept;

private:
    double drive_ = 1.0, mix_ = 0.0;
};

// Exciter: generates harmonics from the top end only and adds a little back.
class Exciter {
public:
    void prepare(double sampleRate, double fromHz, double driveDb, double amount) noexcept;
    void reset() noexcept;
    float process(float x) noexcept;

private:
    Biquad highPass_;
    Biquad highPass2_;
    double drive_ = 1.0, amount_ = 0.0;
};

// --------------------------------------------------------------- space

// Freeverb-style stereo reverb (8 combs, 4 all-passes per side). Wet only.
class Reverb {
public:
    void prepare(double sampleRate, double roomSize, double damping, double widthAmount, double preDelayMs);
    void reset() noexcept;
    void process(float input, float& left, float& right) noexcept;

private:
    struct Comb {
        std::vector<float> buffer;
        std::size_t index = 0;
        float store = 0.0f;
        float process(float input, float feedback, float damp) noexcept;
    };
    struct AllPass {
        std::vector<float> buffer;
        std::size_t index = 0;
        float process(float input) noexcept;
    };
    std::array<Comb, 8> combsL_{}, combsR_{};
    std::array<AllPass, 4> allpassL_{}, allpassR_{};
    std::vector<float> preDelay_;
    std::size_t preIndex_ = 0;
    float feedback_ = 0.84f, damp_ = 0.2f, wet1_ = 1.0f, wet2_ = 0.0f;
};

// Stereo (ping-pong) delay with a darkening feedback path. Wet only.
class Delay {
public:
    void prepare(double sampleRate, double timeMs, double feedback, double highCutHz, bool pingPong);
    void reset() noexcept;
    void process(float input, float& left, float& right) noexcept;

private:
    std::vector<float> left_, right_;
    std::size_t index_ = 0;
    std::size_t length_ = 1;
    double feedback_ = 0.3;
    bool pingPong_ = true;
    Biquad toneL_, toneR_;
};

// ------------------------------------------------------------- loudness

// ITU-R BS.1770-4 integrated loudness (K-weighting, 400 ms blocks with 75%
// overlap, absolute gate -70 LUFS, relative gate -10 LU). Mono or stereo.
class LoudnessMeter {
public:
    explicit LoudnessMeter(double sampleRate = 48000.0, int channels = 2);
    void push(const float* left, const float* right, std::size_t frames);  // right may be null (mono)
    double integratedLufs() const;
    double maximumMomentaryLufs() const noexcept { return maximumMomentary_; }

private:
    int channels_;
    std::array<Biquad, 2> stage1_{}, stage2_{};
    std::size_t blockLength_ = 0, hop_ = 0;
    std::vector<double> squares_;   // per-sample weighted power sum over channels, ring
    std::size_t filled_ = 0, sinceBlock_ = 0;
    double running_ = 0.0;
    std::vector<double> blocks_;    // mean-square power of each 400 ms block
    double maximumMomentary_ = -200.0;
};

}  // namespace maqam::dsp
