// Monophonic fundamental-frequency estimation (YIN, de Cheveigné & Kawahara 2002).
//
// The difference function is computed from an FFT autocorrelation, so a
// 2048-sample frame costs two 4096-point transforms instead of ~2M multiplies.
// Sub-sample lag is refined by parabolic interpolation, which is what keeps a
// quarter tone (50 cents, about 3% in frequency) clearly resolvable.
//
// Real-time rule: construct off the audio thread; detect() never allocates.
#pragma once

#include "maqam/fft.hpp"

#include <complex>
#include <cstddef>
#include <cstdint>
#include <vector>

namespace maqam {

struct PitchEstimate {
    double frequencyHz = 0.0;  // 0 when unvoiced
    double confidence = 0.0;   // 1 - aperiodicity, in [0, 1]
    double rmsDbfs = -160.0;   // frame level, for voicing decisions
    bool voiced = false;
};

struct PitchDetectorConfig {
    double sampleRate = 48000.0;
    std::size_t frameSize = 2048;   // analysis window, samples
    double minimumHz = 60.0;        // lowest male chest voice
    double maximumHz = 1400.0;      // high female head voice and mawwal ornaments
    double threshold = 0.12;        // YIN absolute threshold
    double silenceDbfs = -55.0;     // frames quieter than this are unvoiced
};

class PitchDetector {
public:
    explicit PitchDetector(const PitchDetectorConfig& config);

    const PitchDetectorConfig& config() const noexcept { return config_; }

    // `samples` must hold config().frameSize mono samples.
    PitchEstimate detect(const float* samples) noexcept;

private:
    PitchDetectorConfig config_;
    std::size_t minLag_;
    std::size_t maxLag_;
    FFT fft_;
    std::vector<std::complex<double>> spectrum_;
    std::vector<double> squares_;     // prefix sums of x^2
    std::vector<double> difference_;  // cumulative-mean-normalized difference
};

struct PitchFrame {
    double timeSeconds = 0.0;
    PitchEstimate estimate;
};

// Offline pitch track over a whole mono signal, one frame every `hopSize`.
std::vector<PitchFrame> trackPitch(const float* mono, std::size_t frames,
                                   const PitchDetectorConfig& config, std::size_t hopSize);

// The smallest power-of-two frame that fits two periods of `minimumHz` at
// `sampleRate` (2048 at 44.1/48 kHz, 4096 at 88.2/96 kHz).
std::size_t frameSizeFor(double sampleRate, double minimumHz);

// The same track as trackPitch, fed in chunks of any size, so a long file is
// analysed while it is decoded instead of after it is loaded whole. Offline
// only: push() allocates as frames accumulate.
class PitchTracker {
public:
    PitchTracker(const PitchDetectorConfig& config, std::size_t hopSize);

    void push(const float* mono, std::size_t frames);
    const std::vector<PitchFrame>& frames() const noexcept { return track_; }

private:
    PitchDetector detector_;
    std::size_t hop_;
    std::vector<float> pending_;
    std::size_t readPosition_ = 0;   // next frame start within pending_
    std::uint64_t pendingOffset_ = 0;  // absolute sample index of pending_[0]
    std::vector<PitchFrame> track_;
};

}  // namespace maqam
