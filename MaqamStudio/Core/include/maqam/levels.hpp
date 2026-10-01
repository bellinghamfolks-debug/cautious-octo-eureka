// Offline level analysis and waveform summaries.
//
// Input is interleaved 32-bit float PCM. Everything here is O(n), single-pass
// where possible, and safe to run on a background thread over long files.
#pragma once

#include <cstddef>
#include <vector>

namespace maqam {

struct LevelReport {
    double peak = 0.0;              // absolute sample peak, linear (1.0 == 0 dBFS)
    double peakDbfs = -160.0;
    double rmsDbfs = -160.0;        // whole-signal RMS
    double crestDb = 0.0;           // peak-to-RMS ratio
    double dcOffset = 0.0;          // mean sample value
    double noiseFloorDbfs = -160.0; // 10th percentile of 50 ms block RMS
    double dynamicRangeDb = 0.0;    // 95th minus 10th percentile of block RMS
    std::size_t clippedSamples = 0; // samples with |x| >= clipThreshold
    std::size_t frames = 0;
    int channels = 0;
};

constexpr double kClipThreshold = 0.999;
constexpr double kSilenceDbfs = -160.0;

double linearToDbfs(double linear) noexcept;

LevelReport analyzeLevels(const float* interleaved, std::size_t frames, int channels,
                          double sampleRate);

// Streaming form of analyzeLevels for files read in chunks. Memory grows by one
// double per 50 ms block, so a one-hour take needs about 600 KB.
class LevelAccumulator {
public:
    LevelAccumulator(int channels, double sampleRate);
    void push(const float* interleaved, std::size_t frames);
    LevelReport finish() const;

private:
    int channels_;
    double sampleRate_;
    std::size_t blockFrames_;
    std::vector<double> blockRms_;
    long double sum_ = 0.0L;
    long double sumSquares_ = 0.0L;
    double blockSquares_ = 0.0;
    std::size_t blockCount_ = 0;
    double peak_ = 0.0;
    std::size_t clipped_ = 0;
    std::size_t frames_ = 0;
};

// Streaming waveform summary: the total length must be known up front (it is,
// from the file header), so each frame can be assigned to its bucket as it arrives.
class WaveformAccumulator {
public:
    WaveformAccumulator(std::size_t totalFrames, int channels, std::size_t buckets);
    void push(const float* interleaved, std::size_t frames);
    std::size_t buckets() const noexcept { return min_.size(); }
    const float* minimum() const noexcept { return min_.data(); }
    const float* maximum() const noexcept { return max_.data(); }
    // RMS per bucket in dBFS; buckets that received no frames read as silence.
    void rmsDbfs(float* out) const noexcept;

private:
    std::size_t totalFrames_;
    int channels_;
    std::size_t position_ = 0;
    std::vector<float> min_;
    std::vector<float> max_;
    std::vector<double> squares_;
    std::vector<std::size_t> counts_;
};

// Min/max per bucket across all channels, for drawing and for the accessible
// audio graph. `buckets` values are written to each of outMin and outMax.
void waveformPeaks(const float* interleaved, std::size_t frames, int channels,
                   std::size_t buckets, float* outMin, float* outMax) noexcept;

// RMS per bucket in dBFS, used to describe the waveform in words.
void waveformRmsDbfs(const float* interleaved, std::size_t frames, int channels,
                     std::size_t buckets, float* outDbfs) noexcept;

}  // namespace maqam
