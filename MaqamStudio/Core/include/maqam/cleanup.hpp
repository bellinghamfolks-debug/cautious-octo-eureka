// Cleanup stages: noise, hum, plosives. Same rule as dsp.hpp: allocate in
// prepare(), never in process().
#pragma once

#include "maqam/dsp.hpp"
#include "maqam/fft.hpp"

#include <array>
#include <complex>
#include <cstddef>
#include <cstdint>
#include <vector>

namespace maqam {

// Short-time spectral noise reduction against a measured noise profile.
//
// Each 2048-sample frame (75% overlap) is compared bin by bin with the noise
// power; bins near the noise are lowered towards a floor, bins well above it
// pass. Gains fall slowly and rise quickly, and are smoothed across
// neighbouring bins, which is what keeps the residue from "chirping" (musical
// noise). Output is delayed by latency() samples.
class SpectralDenoiser {
public:
    static constexpr std::size_t kFrameSize = 2048;
    static constexpr std::size_t kHop = kFrameSize / 4;

    // `noisePower` holds kFrameSize/2+1 bins of Hann-windowed frame power.
    void prepare(const std::vector<float>& noisePower, double reductionDb, double oversubtraction = 3.0);
    void reset() noexcept;
    float process(float x) noexcept;
    std::size_t latency() const noexcept { return kFrameSize; }

private:
    void processFrame() noexcept;

    FFT fft_{kFrameSize};
    std::vector<double> window_;
    std::vector<double> noise_;
    std::vector<double> gains_, smoothed_;
    std::vector<std::complex<double>> spectrum_;
    std::vector<float> input_;      // last kFrameSize input samples (ring)
    std::vector<double> output_;    // overlap-add accumulator
    std::size_t inputIndex_ = 0, sinceFrame_ = 0, outputIndex_ = 0;
    double floor_ = 0.1, oversubtraction_ = 3.0;
};

// Notches on a mains hum and its harmonics.
class HumRemover {
public:
    void prepare(double sampleRate, double fundamentalHz, int harmonics) noexcept;
    void reset() noexcept;
    float process(float x) noexcept;

private:
    std::array<dsp::Biquad, 6> notches_{};
    int count_ = 0;
};

// Plosives ("p", "b" bursts of air on the microphone) are sudden low-frequency
// jumps with no sung pitch. While one lasts, the signal is blended towards a
// high-passed copy. `voiced` (from the pitch track) keeps a low male note's
// onset, which also jumps in the low end, from being mistaken for one.
class PlosiveTamer {
public:
    void prepare(double sampleRate, double maximumCutDb) noexcept;
    void reset() noexcept;
    float process(float x, bool voiced) noexcept;
    std::size_t events() const noexcept { return events_; }

private:
    dsp::Biquad detector_, upper_, highPass_, highPass2_;
    dsp::Envelope fast_, slow_, upperFast_, blend_;
    double depth_ = 1.0;
    bool active_ = false;
    std::size_t events_ = 0;
};

// Gains an offline list of time regions down (breaths), with short fades.
class RegionAttenuator {
public:
    struct Region { std::uint64_t start, end; };
    void prepare(double sampleRate, std::vector<Region> regions, double cutDb);
    void reset() noexcept { index_ = 0; position_ = 0; }
    float process(float x) noexcept;

private:
    std::vector<Region> regions_;
    std::size_t index_ = 0;
    std::uint64_t position_ = 0;
    std::uint64_t fade_ = 480;
    double cut_ = 1.0;
};

}  // namespace maqam
