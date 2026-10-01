#include "maqam/levels.hpp"

#include <algorithm>
#include <cmath>

namespace maqam {

double linearToDbfs(double linear) noexcept {
    if (!(linear > 0.0)) return kSilenceDbfs;
    return std::max(kSilenceDbfs, 20.0 * std::log10(linear));
}

namespace {

double percentile(std::vector<double> values, double fraction) {
    if (values.empty()) return 0.0;
    const std::size_t index = static_cast<std::size_t>(
        std::clamp(fraction, 0.0, 1.0) * static_cast<double>(values.size() - 1));
    std::nth_element(values.begin(), values.begin() + static_cast<std::ptrdiff_t>(index), values.end());
    return values[index];
}

}  // namespace

LevelAccumulator::LevelAccumulator(int channels, double sampleRate)
    : channels_(channels),
      sampleRate_(sampleRate),
      blockFrames_(std::max<std::size_t>(1, static_cast<std::size_t>(sampleRate > 0.0 ? sampleRate * 0.05 : 1.0))) {}

void LevelAccumulator::push(const float* interleaved, std::size_t frames) {
    if (!interleaved || channels_ <= 0) return;
    const std::size_t stride = static_cast<std::size_t>(channels_);
    for (std::size_t frame = 0; frame < frames; ++frame) {
        double frameSquares = 0.0;
        for (std::size_t c = 0; c < stride; ++c) {
            const double x = interleaved[frame * stride + c];
            const double magnitude = std::fabs(x);
            if (magnitude > peak_) peak_ = magnitude;
            if (magnitude >= kClipThreshold) ++clipped_;
            sum_ += x;
            sumSquares_ += x * x;
            frameSquares += x * x;
        }
        blockSquares_ += frameSquares / static_cast<double>(stride);
        if (++blockCount_ == blockFrames_) {
            blockRms_.push_back(std::sqrt(blockSquares_ / static_cast<double>(blockCount_)));
            blockSquares_ = 0.0;
            blockCount_ = 0;
        }
    }
    frames_ += frames;
}

LevelReport LevelAccumulator::finish() const {
    LevelReport report;
    report.frames = frames_;
    report.channels = channels_;
    if (frames_ == 0 || channels_ <= 0 || !(sampleRate_ > 0.0)) return report;
    std::vector<double> blocks = blockRms_;
    if (blockCount_ > blockFrames_ / 4) blocks.push_back(std::sqrt(blockSquares_ / static_cast<double>(blockCount_)));
    const long double total = static_cast<long double>(frames_) * channels_;
    const double rms = std::sqrt(static_cast<double>(sumSquares_ / total));
    report.peak = peak_;
    report.peakDbfs = linearToDbfs(peak_);
    report.rmsDbfs = linearToDbfs(rms);
    report.crestDb = (peak_ > 0.0 && rms > 0.0) ? report.peakDbfs - report.rmsDbfs : 0.0;
    report.dcOffset = static_cast<double>(sum_ / total);
    report.clippedSamples = clipped_;
    if (!blocks.empty()) {
        const double floor = percentile(blocks, 0.10);
        const double loud = percentile(blocks, 0.95);
        report.noiseFloorDbfs = linearToDbfs(floor);
        report.dynamicRangeDb = linearToDbfs(loud) - report.noiseFloorDbfs;
    }
    return report;
}

LevelReport analyzeLevels(const float* interleaved, std::size_t frames, int channels,
                          double sampleRate) {
    LevelAccumulator accumulator(channels, sampleRate);
    accumulator.push(interleaved, frames);
    return accumulator.finish();
}

WaveformAccumulator::WaveformAccumulator(std::size_t totalFrames, int channels, std::size_t buckets)
    : totalFrames_(totalFrames),
      channels_(channels),
      min_(buckets, 0.0f),
      max_(buckets, 0.0f),
      squares_(buckets, 0.0),
      counts_(buckets, 0) {}

void WaveformAccumulator::push(const float* interleaved, std::size_t frames) {
    const std::size_t buckets = min_.size();
    if (!interleaved || channels_ <= 0 || buckets == 0 || totalFrames_ == 0) return;
    const std::size_t stride = static_cast<std::size_t>(channels_);
    for (std::size_t frame = 0; frame < frames; ++frame, ++position_) {
        if (position_ >= totalFrames_) return;  // the header under-reported; ignore the excess
        const std::size_t bucket = std::min(buckets - 1, position_ * buckets / totalFrames_);
        for (std::size_t c = 0; c < stride; ++c) {
            const float x = interleaved[frame * stride + c];
            if (counts_[bucket] == 0 && c == 0) { min_[bucket] = max_[bucket] = x; }
            min_[bucket] = std::min(min_[bucket], x);
            max_[bucket] = std::max(max_[bucket], x);
            squares_[bucket] += static_cast<double>(x) * x;
        }
        ++counts_[bucket];
    }
}

void WaveformAccumulator::rmsDbfs(float* out) const noexcept {
    if (!out) return;
    for (std::size_t b = 0; b < min_.size(); ++b) {
        out[b] = counts_[b] == 0 ? static_cast<float>(kSilenceDbfs)
            : static_cast<float>(linearToDbfs(std::sqrt(squares_[b] / (static_cast<double>(counts_[b]) * channels_))));
    }
}

void waveformPeaks(const float* interleaved, std::size_t frames, int channels,
                   std::size_t buckets, float* outMin, float* outMax) noexcept {
    if (!outMin || !outMax || buckets == 0) return;
    std::fill(outMin, outMin + buckets, 0.0f);
    std::fill(outMax, outMax + buckets, 0.0f);
    if (!interleaved || frames == 0 || channels <= 0) return;
    for (std::size_t b = 0; b < buckets; ++b) {
        const std::size_t start = b * frames / buckets;
        std::size_t end = (b + 1) * frames / buckets;
        if (end <= start) end = std::min(frames, start + 1);
        float low = 0.0f, high = 0.0f;
        bool first = true;
        for (std::size_t frame = start; frame < end; ++frame) {
            for (int c = 0; c < channels; ++c) {
                const float x = interleaved[frame * static_cast<std::size_t>(channels) + static_cast<std::size_t>(c)];
                if (first) { low = high = x; first = false; }
                low = std::min(low, x);
                high = std::max(high, x);
            }
        }
        outMin[b] = low;
        outMax[b] = high;
    }
}

void waveformRmsDbfs(const float* interleaved, std::size_t frames, int channels,
                     std::size_t buckets, float* outDbfs) noexcept {
    if (!outDbfs || buckets == 0) return;
    std::fill(outDbfs, outDbfs + buckets, static_cast<float>(kSilenceDbfs));
    if (!interleaved || frames == 0 || channels <= 0) return;
    for (std::size_t b = 0; b < buckets; ++b) {
        const std::size_t start = b * frames / buckets;
        std::size_t end = (b + 1) * frames / buckets;
        if (end <= start) end = std::min(frames, start + 1);
        double squares = 0.0;
        for (std::size_t frame = start; frame < end; ++frame) {
            for (int c = 0; c < channels; ++c) {
                const double x = interleaved[frame * static_cast<std::size_t>(channels) + static_cast<std::size_t>(c)];
                squares += x * x;
            }
        }
        const double count = static_cast<double>((end - start) * static_cast<std::size_t>(channels));
        outDbfs[b] = static_cast<float>(linearToDbfs(std::sqrt(squares / count)));
    }
}

}  // namespace maqam
