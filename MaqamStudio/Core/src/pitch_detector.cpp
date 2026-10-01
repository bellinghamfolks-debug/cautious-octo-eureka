#include "maqam/pitch_detector.hpp"

#include "maqam/levels.hpp"

#include <algorithm>
#include <cmath>
#include <stdexcept>

namespace maqam {

PitchDetector::PitchDetector(const PitchDetectorConfig& config)
    : config_(config),
      minLag_(0),
      maxLag_(0),
      fft_(FFT::nextPowerOfTwo(config.frameSize * 2)) {
    if (config.frameSize < 64) throw std::invalid_argument("frame size too small");
    if (!(config.sampleRate > 0.0) || !(config.minimumHz > 0.0) || !(config.maximumHz > config.minimumHz)) {
        throw std::invalid_argument("invalid pitch range");
    }
    minLag_ = std::max<std::size_t>(2, static_cast<std::size_t>(std::floor(config.sampleRate / config.maximumHz)));
    maxLag_ = static_cast<std::size_t>(std::ceil(config.sampleRate / config.minimumHz));
    // The lowest pitch needs its period to fit twice in the frame. Clamping it
    // quietly would make a low voice read as an octave error, so refuse instead.
    if (maxLag_ > config.frameSize / 2) throw std::invalid_argument("frame too short for the requested lowest pitch");
    if (maxLag_ <= minLag_ + 2) throw std::invalid_argument("pitch range is empty");
    spectrum_.resize(fft_.size());
    squares_.resize(config.frameSize + 1);
    difference_.resize(maxLag_ + 2);
}

PitchEstimate PitchDetector::detect(const float* samples) noexcept {
    PitchEstimate estimate;
    const std::size_t n = config_.frameSize;

    // Prefix sums of energy for the two windowed energy terms of d(tau).
    squares_[0] = 0.0;
    for (std::size_t i = 0; i < n; ++i) squares_[i + 1] = squares_[i] + static_cast<double>(samples[i]) * samples[i];
    const double rms = std::sqrt(squares_[n] / static_cast<double>(n));
    estimate.rmsDbfs = linearToDbfs(rms);
    if (estimate.rmsDbfs < config_.silenceDbfs) return estimate;

    // Autocorrelation r(tau) = IFFT(|FFT(x)|^2), zero-padded to avoid wrap-around.
    std::fill(spectrum_.begin(), spectrum_.end(), std::complex<double>(0.0, 0.0));
    for (std::size_t i = 0; i < n; ++i) spectrum_[i] = {static_cast<double>(samples[i]), 0.0};
    fft_.transform(spectrum_.data(), false);
    for (std::complex<double>& bin : spectrum_) bin = {std::norm(bin), 0.0};
    fft_.transform(spectrum_.data(), true);

    // d(tau) = sum_{j<n-tau} (x_j - x_{j+tau})^2 = E[0, n-tau) + E[tau, n) - 2 r(tau),
    // then the cumulative mean normalisation that makes the threshold meaningful.
    difference_[0] = 1.0;
    double runningSum = 0.0;
    for (std::size_t tau = 1; tau <= maxLag_ + 1; ++tau) {
        const double head = squares_[n - tau];
        const double tail = squares_[n] - squares_[tau];
        const double d = std::max(0.0, head + tail - 2.0 * spectrum_[tau].real());
        runningSum += d;
        difference_[tau] = runningSum > 0.0 ? d * static_cast<double>(tau) / runningSum : 1.0;
    }

    // First dip below the threshold, followed down to its local minimum; if
    // none qualifies, the global minimum in range (reported with low confidence).
    std::size_t lag = 0;
    for (std::size_t tau = minLag_; tau <= maxLag_; ++tau) {
        if (difference_[tau] < config_.threshold) {
            while (tau + 1 <= maxLag_ && difference_[tau + 1] < difference_[tau]) ++tau;
            lag = tau;
            break;
        }
    }
    if (lag == 0) {
        lag = minLag_;
        for (std::size_t tau = minLag_ + 1; tau <= maxLag_; ++tau) {
            if (difference_[tau] < difference_[lag]) lag = tau;
        }
    }

    // Parabolic interpolation around the chosen lag.
    double refined = static_cast<double>(lag);
    if (lag > minLag_ && lag < maxLag_) {
        const double left = difference_[lag - 1];
        const double centre = difference_[lag];
        const double right = difference_[lag + 1];
        const double denominator = left - 2.0 * centre + right;
        if (std::fabs(denominator) > 1e-12) refined += 0.5 * (left - right) / denominator;
    }

    const double aperiodicity = std::clamp(difference_[lag], 0.0, 1.0);
    estimate.confidence = 1.0 - aperiodicity;
    estimate.voiced = difference_[lag] < config_.threshold * 2.0;
    estimate.frequencyHz = estimate.voiced && refined > 0.0 ? config_.sampleRate / refined : 0.0;
    if (!estimate.voiced) estimate.confidence = std::min(estimate.confidence, 0.5);
    return estimate;
}

std::vector<PitchFrame> trackPitch(const float* mono, std::size_t frames,
                                   const PitchDetectorConfig& config, std::size_t hopSize) {
    std::vector<PitchFrame> track;
    if (!mono || hopSize == 0 || frames < config.frameSize) return track;
    PitchDetector detector(config);
    track.reserve((frames - config.frameSize) / hopSize + 1);
    for (std::size_t start = 0; start + config.frameSize <= frames; start += hopSize) {
        PitchFrame frame;
        // Time stamps the centre of the analysis window.
        frame.timeSeconds = (static_cast<double>(start) + config.frameSize / 2.0) / config.sampleRate;
        frame.estimate = detector.detect(mono + start);
        track.push_back(frame);
    }
    return track;
}

std::size_t frameSizeFor(double sampleRate, double minimumHz) {
    if (!(sampleRate > 0.0) || !(minimumHz > 0.0)) return 2048;
    const auto period = static_cast<std::size_t>(std::ceil(sampleRate / minimumHz));
    return std::max<std::size_t>(1024, FFT::nextPowerOfTwo(period * 2));
}

PitchTracker::PitchTracker(const PitchDetectorConfig& config, std::size_t hopSize)
    : detector_(config), hop_(hopSize) {
    if (hopSize == 0) throw std::invalid_argument("hop size must be positive");
}

void PitchTracker::push(const float* mono, std::size_t frames) {
    if (!mono || frames == 0) return;
    pending_.insert(pending_.end(), mono, mono + frames);
    const std::size_t size = detector_.config().frameSize;
    const double sampleRate = detector_.config().sampleRate;
    while (readPosition_ + size <= pending_.size()) {
        PitchFrame frame;
        const double start = static_cast<double>(pendingOffset_ + readPosition_);
        frame.timeSeconds = (start + size / 2.0) / sampleRate;
        frame.estimate = detector_.detect(pending_.data() + readPosition_);
        track_.push_back(frame);
        readPosition_ += hop_;
    }
    // Drop what no future frame can reach, once per push rather than per hop.
    const std::size_t consumed = std::min(readPosition_, pending_.size());
    pending_.erase(pending_.begin(), pending_.begin() + static_cast<std::ptrdiff_t>(consumed));
    pendingOffset_ += consumed;
    readPosition_ -= consumed;
}

}  // namespace maqam
