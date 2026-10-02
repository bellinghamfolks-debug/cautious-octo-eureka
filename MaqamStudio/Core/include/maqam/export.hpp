// Export: sample-rate conversion, true-peak measurement and limiting, dither,
// and the chain that takes a rendered take to a finished file.
//
//   input -> channels (mono mix or copy) -> gain -> resampler
//         -> true-peak limiter (optional) -> meters -> dither and quantize
//         -> WAV / FLAC / MP3
//
// The meters read exactly what is written (before MP3's own coding), so the
// loudness and peaks reported are measured, not intended.
#pragma once

#include "maqam/dsp.hpp"
#include "maqam/encode.hpp"

#include <cstddef>
#include <cstdint>
#include <memory>
#include <vector>

namespace maqam {

// Windowed-sinc polyphase resampler for a rational ratio out/in = L/M.
// Output sample n sits exactly at input time n * in/out (no added delay); the
// total output is ceil(inputFrames * out / in) frames once flushed.
class Resampler {
public:
    // quality 0 (fast: 32 taps per phase), 1 (good: 64), 2 (best: 128).
    Resampler(double inputRate, double outputRate, int channels, int quality);
    bool ok() const noexcept { return ok_; }
    bool passthrough() const noexcept { return l_ == 1 && m_ == 1; }
    // Appends output frames (interleaved) for `frames` more input frames.
    void process(const float* interleaved, std::size_t frames, std::vector<float>& out);
    void flush(std::vector<float>& out);
    // The rate actually produced; equal to outputRate unless the ratio had to
    // be approximated (only for unusual rates; the error is under 0.1 cent).
    double actualOutputRate() const noexcept { return actualRate_; }

private:
    bool produce(std::vector<float>& out, bool final);
    bool ok_ = false;
    int channels_;
    std::uint64_t l_ = 1, m_ = 1;
    int taps_ = 0;
    std::vector<float> filter_;           // [phase][tap]
    std::vector<float> history_;          // interleaved input not yet consumed
    std::uint64_t historyStart_ = 0;      // absolute index of history_[0]
    std::uint64_t inputCount_ = 0, outputCount_ = 0;
    double actualRate_ = 0.0;
};

// 4x oversampled peak detection (ITU-R BS.1770-4 annex 2 method; our own
// 48-tap interpolation filter). Delays its output by kDelay samples.
class TruePeak {
public:
    static constexpr std::size_t kDelay = 6;
    explicit TruePeak(int channels);
    // Feeds one frame; returns the largest interpolated magnitude around the
    // frame that left the delay line, written to `delayed`.
    double process(const float* frame, float* delayed) noexcept;

private:
    int channels_;
    std::vector<float> ring_;  // [channel][12]
    std::size_t position_ = 0;
    float coefficients_[4][12] = {};
};

struct ExportSettings {
    enum class Format { wav = 0, flac = 1, mp3 = 2 } format = Format::wav;
    double sampleRate = 48000.0;
    int channels = 2;
    int bits = 24;              // WAV 16/24/32 (float); FLAC 16/24; ignored for MP3
    int mp3Kbps = 256;
    int resamplerQuality = 2;
    double gainDb = 0.0;
    bool limit = true;
    double ceilingDb = -1.0;    // dBTP
    bool dither = true;
};

struct ExportStats {
    std::uint64_t frames = 0;
    double integratedLufs = -200.0;
    double truePeakDbtp = -200.0;
    double samplePeakDbfs = -200.0;
    std::uint64_t clippedSamples = 0;   // samples beyond full scale before quantizing
    double maximumReductionDb = 0.0;    // deepest limiting
    std::uint64_t bytes = 0;
};

enum class ExportProblem { none, sampleRate, channels, bits, bitrate, mp3SampleRate };
ExportProblem validate(const ExportSettings& settings, double inputRate, int inputChannels);

class Exporter {
public:
    // With a null sink nothing is written, only measured.
    Exporter(const ExportSettings& settings, double inputRate, int inputChannels, ByteSink* sink);
    ~Exporter();
    bool ok() const noexcept { return ok_; }
    bool push(const float* interleaved, std::size_t frames);
    bool finish(ExportStats& stats);

private:
    bool consume(const std::vector<float>& frames);
    bool deliver(const float* frame);

    ExportSettings settings_;
    int inputChannels_;
    ByteSink* sink_;
    bool ok_ = false;
    double gain_ = 1.0;
    std::unique_ptr<Resampler> resampler_;
    std::unique_ptr<Encoder> encoder_;
    std::unique_ptr<TruePeak> limiterDetector_, meterPeak_;
    dsp::Limiter limiter_;
    std::size_t latency_ = 0, skipped_ = 0;
    dsp::LoudnessMeter loudness_;
    std::vector<float> mapped_, resampled_, left_, right_, floats_;
    std::vector<std::int32_t> ints_;
    std::uint64_t frames_ = 0, clipped_ = 0;
    double samplePeak_ = 0.0, truePeak_ = 0.0;
    std::uint64_t dither_ = 0x9E3779B97F4A7C15ull;
};

}  // namespace maqam
