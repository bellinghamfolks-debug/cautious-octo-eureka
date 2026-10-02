#include "maqam/cleanup.hpp"

#include <algorithm>
#include <cmath>
#include <cstdint>

namespace maqam {

void SpectralDenoiser::prepare(const std::vector<float>& noisePower, double reductionDb, double oversubtraction) {
    const std::size_t bins = kFrameSize / 2 + 1;
    window_.resize(kFrameSize);
    for (std::size_t i = 0; i < kFrameSize; ++i) window_[i] = 0.5 - 0.5 * std::cos(2.0 * dsp::kPi * i / kFrameSize);
    noise_.assign(bins, 0.0);
    for (std::size_t k = 0; k < bins && k < noisePower.size(); ++k) noise_[k] = noisePower[k];
    gains_.assign(bins, 1.0);
    smoothed_.assign(bins, 1.0);
    spectrum_.assign(kFrameSize, {0.0, 0.0});
    input_.assign(kFrameSize, 0.0f);
    output_.assign(2 * kFrameSize, 0.0);
    floor_ = dsp::dbToGain(-std::fabs(reductionDb));
    oversubtraction_ = oversubtraction;
    reset();
}

void SpectralDenoiser::reset() noexcept {
    std::fill(input_.begin(), input_.end(), 0.0f);
    std::fill(output_.begin(), output_.end(), 0.0);
    std::fill(smoothed_.begin(), smoothed_.end(), 1.0);
    inputIndex_ = sinceFrame_ = outputIndex_ = 0;
}

float SpectralDenoiser::process(float x) noexcept {
    input_[inputIndex_] = x;
    inputIndex_ = (inputIndex_ + 1) % kFrameSize;
    // The output for this sample was completed by frames that have already run.
    const double y = output_[outputIndex_];
    output_[outputIndex_] = 0.0;
    outputIndex_ = (outputIndex_ + 1) % output_.size();
    if (++sinceFrame_ >= kHop) {
        sinceFrame_ = 0;
        processFrame();
    }
    return static_cast<float>(y);
}

void SpectralDenoiser::processFrame() noexcept {
    // Oldest sample first.
    for (std::size_t i = 0; i < kFrameSize; ++i) {
        spectrum_[i] = {input_[(inputIndex_ + i) % kFrameSize] * window_[i], 0.0};
    }
    fft_.transform(spectrum_.data(), false);
    const std::size_t bins = kFrameSize / 2 + 1;
    for (std::size_t k = 0; k < bins; ++k) {
        const double power = std::norm(spectrum_[k]);
        const double clean = power - oversubtraction_ * noise_[k];
        gains_[k] = power > 0.0 ? std::max(floor_, std::sqrt(std::max(0.0, clean) / power)) : floor_;
    }
    for (std::size_t k = 0; k < bins; ++k) {
        // Across frequency: a three-bin average; across time: rise fast, fall slowly.
        const double across = (gains_[k > 0 ? k - 1 : k] + gains_[k] + gains_[k + 1 < bins ? k + 1 : k]) / 3.0;
        smoothed_[k] = across > smoothed_[k] ? across : 0.7 * smoothed_[k] + 0.3 * across;
        spectrum_[k] *= smoothed_[k];
        if (k > 0 && k < kFrameSize / 2) spectrum_[kFrameSize - k] = std::conj(spectrum_[k]);
    }
    fft_.transform(spectrum_.data(), true);
    // Hann analysis and synthesis at 75% overlap sum to 1.5.
    constexpr double kScale = 1.0 / 1.5;
    // This frame ends at the newest input; its output lands kFrameSize ahead of
    // the read position, which fixes the latency at exactly kFrameSize.
    for (std::size_t i = 0; i < kFrameSize; ++i) {
        const std::size_t slot = (outputIndex_ + i) % output_.size();
        output_[slot] += spectrum_[i].real() * window_[i] * kScale;
    }
}

void HumRemover::prepare(double sampleRate, double fundamentalHz, int harmonics) noexcept {
    count_ = 0;
    for (int h = 1; h <= harmonics && h <= static_cast<int>(notches_.size()); ++h) {
        const double hz = fundamentalHz * h;
        if (hz >= sampleRate * 0.45) break;
        // Narrow enough to leave a low voice's own partials alone.
        notches_[static_cast<std::size_t>(count_)].set(dsp::Biquad::Type::notch, sampleRate, hz, 30.0);
        ++count_;
    }
    reset();
}

void HumRemover::reset() noexcept {
    for (dsp::Biquad& notch : notches_) notch.reset();
}

float HumRemover::process(float x) noexcept {
    for (int i = 0; i < count_; ++i) x = notches_[static_cast<std::size_t>(i)].process(x);
    return x;
}

void PlosiveTamer::prepare(double sampleRate, double maximumCutDb) noexcept {
    detector_.set(dsp::Biquad::Type::lowPass, sampleRate, 90.0, 0.7071);
    upper_.set(dsp::Biquad::Type::highPass, sampleRate, 300.0, 0.7071);
    highPass_.set(dsp::Biquad::Type::highPass, sampleRate, 180.0, 0.7071);
    highPass2_.set(dsp::Biquad::Type::highPass, sampleRate, 180.0, 0.7071);
    fast_.set(sampleRate, 2.0, 30.0);
    slow_.set(sampleRate, 300.0, 300.0);
    upperFast_.set(sampleRate, 2.0, 30.0);
    blend_.set(sampleRate, 1.0, 60.0);
    depth_ = 1.0 - dsp::dbToGain(-std::fabs(maximumCutDb));
    reset();
}

void PlosiveTamer::reset() noexcept {
    detector_.reset(); upper_.reset(); highPass_.reset(); highPass2_.reset();
    fast_.reset(0.0); slow_.reset(0.0); upperFast_.reset(0.0); blend_.reset(0.0);
    active_ = false;
    events_ = 0;
}

float PlosiveTamer::process(float x, bool voiced) noexcept {
    const double low = std::fabs(detector_.process(x));
    const double fast = fast_.process(low);
    const double slow = slow_.process(low);
    const double upper = upperFast_.process(std::fabs(upper_.process(x)));
    // A plosive is a burst of air: the sub-90 Hz band jumps well over its
    // recent level and outweighs the voice above 300 Hz. A sung note's onset
    // jumps too, but its energy is above, not below.
    const bool burst = !voiced && fast > 0.03 && fast > 3.0 * slow + 1e-6 && fast > 1.2 * upper;
    if (burst && !active_) ++events_;
    active_ = burst;
    const double amount = blend_.process(burst ? depth_ : 0.0);
    const float filtered = highPass2_.process(highPass_.process(x));
    return static_cast<float>((1.0 - amount) * x + amount * filtered);
}

void RegionAttenuator::prepare(double sampleRate, std::vector<Region> regions, double cutDb) {
    regions_ = std::move(regions);
    std::sort(regions_.begin(), regions_.end(), [](const Region& a, const Region& b) { return a.start < b.start; });
    fade_ = std::max<std::uint64_t>(1, static_cast<std::uint64_t>(0.01 * sampleRate));
    cut_ = dsp::dbToGain(-std::fabs(cutDb));
    reset();
}

float RegionAttenuator::process(float x) noexcept {
    const std::uint64_t t = position_++;
    while (index_ < regions_.size() && regions_[index_].end + fade_ <= t) ++index_;
    if (index_ >= regions_.size()) return x;
    const Region& region = regions_[index_];
    if (t + fade_ <= region.start) return x;
    // Fade in to the cut over 10 ms before the region, out over 10 ms after it.
    double depth = 1.0;
    if (t < region.start) depth = 1.0 - static_cast<double>(region.start - t) / static_cast<double>(fade_);
    else if (t >= region.end) depth = 1.0 - static_cast<double>(t - region.end) / static_cast<double>(fade_);
    return static_cast<float>(x * (1.0 - depth * (1.0 - cut_)));
}

}  // namespace maqam
