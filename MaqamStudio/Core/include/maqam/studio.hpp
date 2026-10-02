// Auto Studio: listen to the voice, decide a chain for it, run the chain.
//
// 1. StudioAnalyzer streams the (mono) voice once and measures what the
//    decisions need: loudness, peaks, noise floor and its spectrum, mains hum,
//    the long-term spectrum, sibilance, level spread, and per-frame features
//    for finding breaths.
// 2. planStudio turns those measurements and a genre profile into concrete
//    settings for every stage, and records why each stage is on, off, or set
//    the way it is (StudioReason), so the app can say it in words.
// 3. StudioChain runs the plan sample by sample: mono cleanup and tone, then
//    stereo space, loudness gain and a limiter. It allocates only when built.
//
// Profiles are starting points chosen by ear for each style, not measured
// standards; every value they set can be changed afterwards.
#pragma once

#include "maqam/cleanup.hpp"
#include "maqam/dsp.hpp"

#include <array>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <vector>

namespace maqam {

enum class GenreProfile : int {
    khaleeji = 0, arabicPop, tarab, shilat, iraqi, egyptian, levantine, acoustic,
    cleanStudio, modernCommercial, natural, heavyAutoTune, count
};

struct StudioFrame {
    float levelDb = -160.0f;    // RMS of the 2048-sample frame
    float flatness = 0.0f;      // 0 tonal .. 1 noise-like, 300 Hz - 8 kHz
    float highRatioDb = -60.0f; // 5-9 kHz against 300 Hz - 3 kHz
    float lowRatio = 0.0f;      // share of power below 90 Hz
};

struct StudioAnalysis {
    double sampleRate = 48000.0;
    std::uint64_t samples = 0;
    std::size_t hop = SpectralDenoiser::kHop;
    double integratedLufs = -200.0;
    double peakDbfs = -160.0;
    std::uint64_t clippedSamples = 0;
    double noiseFloorDbfs = -160.0;   // 10th percentile of frame levels
    double voiceLevelDbfs = -160.0;   // median of frames well above the floor
    double levelSpreadDb = 0.0;       // 90th minus 10th percentile of those frames
    double humHz = 0.0;               // 0, 50 or 60
    double humStrengthDb = 0.0;       // harmonics over their neighbourhood
    double sibilanceDb = -60.0;       // 95th percentile of highRatioDb over voice frames
    double rumbleRatio = 0.0;         // mean share of power below 90 Hz
    std::vector<float> noisePower;    // per bin, Hann-windowed 2048-sample frames
    std::vector<float> spectrumDb;    // long-term average spectrum of voice frames
    std::vector<StudioFrame> frames;  // one per hop
};

class StudioAnalyzer {
public:
    explicit StudioAnalyzer(double sampleRate);
    void push(const float* mono, std::size_t count);
    StudioAnalysis finish();

private:
    void analyseFrame();
    void analyseHumBlock();

    StudioAnalysis result_;
    FFT fft_{SpectralDenoiser::kFrameSize};
    std::vector<double> window_;
    std::vector<float> ring_;
    std::size_t ringIndex_ = 0, sinceFrame_ = 0, seen_ = 0;
    std::vector<std::complex<double>> bins_;
    // Spectra summed per 1 dB level step, so the quietest and the loudest
    // frames can be averaged after one pass without keeping every spectrum.
    std::vector<std::vector<double>> spectraByLevel_;
    std::vector<std::size_t> framesByLevel_;
    std::vector<float> humBlock_;
    std::vector<double> humWindow_;
    std::size_t humFill_ = 0;
    struct HumBlock { double levelDb, ratio50Db, ratio60Db; };
    std::vector<HumBlock> humBlocks_;
    dsp::LoudnessMeter loudness_;
};

// Breaths: unvoiced, noisy, quieter than singing but above the room, between
// 0.12 and 0.8 s. `voiced(frameIndex)` tells which analysis frames the pitch
// track heard as sung.
std::vector<RegionAttenuator::Region> findBreaths(const StudioAnalysis& analysis, const std::vector<bool>& voiced);

enum class ReasonCode : int {
    highPass = 1, rumble, hum, noHum, denoise, alreadyClean, plosives, breaths, noBreaths, leveler,
    steadyLevel, mud, boxy, harsh, air, resonance, deEss, deEssLight, compress, multiband, colour,
    reverb, delay, loudness, limiter, clippedInput, tonal
};

struct StudioReason {
    ReasonCode code;
    double a = 0.0;
    double b = 0.0;
};

struct EqBand {
    dsp::Biquad::Type type = dsp::Biquad::Type::peaking;
    double frequencyHz = 1000.0;
    double q = 1.0;
    double gainDb = 0.0;
};

struct StudioPlan {
    GenreProfile profile = GenreProfile::natural;
    double highPassHz = 80.0;
    double humHz = 0.0;
    int humHarmonics = 0;
    double denoiseDb = 0.0;          // 0 = off
    double plosiveDb = 12.0;         // 0 = off
    double breathDb = 0.0;           // 0 = off
    bool leveler = false;
    double levelerTargetDb = -20.0;
    double levelerRangeDb = 6.0;
    std::array<EqBand, 8> eq{};
    int eqCount = 0;
    dsp::DynamicBandSettings deEsser{};
    bool deEsserOn = true;
    dsp::DynamicBandSettings harshness{};
    bool harshnessOn = false;
    dsp::CompressorSettings compressor{};
    bool multibandOn = false;
    std::array<dsp::CompressorSettings, 3> multiband{};
    double saturationDriveDb = 0.0;
    double saturationMix = 0.0;
    double exciterAmount = 0.0;
    double reverbMix = 0.0, reverbSize = 0.5, reverbDamping = 0.4, reverbWidth = 1.0, reverbPreDelayMs = 20.0;
    double delayMix = 0.0, delayMs = 250.0, delayFeedback = 0.25, delayHighCutHz = 6000.0;
    bool delayPingPong = true;
    double loudnessTargetLufs = -14.0;
    double ceilingDb = -1.0;
};

// The tuning style each profile pairs with (0 natural, 1 strong, 2 robotic).
int profileTuningPreset(GenreProfile profile);

StudioPlan planStudio(const StudioAnalysis& analysis, std::size_t breathCount, GenreProfile profile,
                      std::vector<StudioReason>* reasons);

class StudioChain {
public:
    // `voiced` is per pitch frame (`firstTime`, `hopSeconds`); breaths in samples.
    StudioChain(const StudioPlan& plan, const StudioAnalysis& analysis, std::vector<RegionAttenuator::Region> breaths,
                std::vector<bool> voiced, double firstTime, double hopSeconds, double outputGainDb, bool limit);

    void process(const float* mono, float* left, float* right, std::size_t count) noexcept;
    // Output lags input by latency(); feed tail() more samples of silence (they
    // include the latency) to finish the reverb and delay.
    std::size_t latency() const noexcept;
    std::size_t tail() const noexcept;

private:
    StudioPlan plan_;
    double sampleRate_;
    dsp::Biquad highPass_, highPass2_;
    HumRemover hum_;
    std::unique_ptr<SpectralDenoiser> denoiser_;
    PlosiveTamer plosives_;
    RegionAttenuator breaths_;
    dsp::Leveler leveler_;
    std::array<dsp::Biquad, 8> eq_{};
    dsp::DynamicBand deEsser_, harshness_;
    dsp::Compressor compressor_;
    dsp::MultibandCompressor multiband_;
    dsp::Saturator saturator_;
    dsp::Exciter exciter_;
    dsp::Reverb reverb_;
    dsp::Delay delay_;
    dsp::Limiter limiter_;
    bool limit_ = true;
    double outputGain_ = 1.0;
    std::vector<bool> voiced_;
    double firstTime_ = 0.0, hop_ = 0.01;
    std::uint64_t position_ = 0;
};

}  // namespace maqam
