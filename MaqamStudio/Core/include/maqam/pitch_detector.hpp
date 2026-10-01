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

}  // namespace maqam
