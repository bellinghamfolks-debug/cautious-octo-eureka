// Vocal separation: a mixed song in, a vocal stem and an accompaniment stem out.
//
// Two passes over the song, with the masks in between:
//   1. analyse(): a 4096-point STFT (hop 1024, Hann) of both channels. For each
//      frame it keeps the magnitude of the mid channel, how centred each bin is
//      between left and right, and a compact spectral fingerprint.
//   2. Masks, 0..1 per bin and frame, saying how much of each bin is voice.
//      Either estimateClassical() computes them here, or an external model
//      (Core ML in the app) reads magnitudes() and supplies setMasks().
//   3. render(): the STFT again; each bin of each channel is split into
//      mask * X (vocal) and X - mask * X (accompaniment), so the two stems
//      always add back up to the original mix exactly.
//
// The classical estimate is signal processing, not machine learning:
//   * repetition (REPET-SIM, Rafii & Pardo 2012): accompaniment repeats. Each
//     frame's most similar frames elsewhere in the song give, by their median,
//     what repeats there; the rest is the candidate voice.
//   * position: a lead voice is almost always mixed to the centre, so bins
//     that differ between left and right are accompaniment.
//   * range: nothing below 80 Hz is voice.
// It works best on stereo mixes with a centred voice over repeating music;
// expect some leakage either way. An ML model can replace it through the
// same masks.
#pragma once

#include "maqam/fft.hpp"

#include <complex>
#include <cstddef>
#include <cstdint>
#include <vector>

namespace maqam {

class Separation {
public:
    static constexpr std::size_t kFrameSize = 4096;
    static constexpr std::size_t kHop = 1024;
    static constexpr std::size_t kBins = kFrameSize / 2 + 1;
    static constexpr std::size_t kFeatureBands = 64;

    explicit Separation(double sampleRate);

    // Pass 1: interleaved stereo (callers duplicate mono).
    void analyse(const float* stereo, std::size_t frames);
    void finishAnalysis();
    std::size_t frameCount() const noexcept { return frames_; }
    std::uint64_t inputFrames() const noexcept { return inputLength_; }

    // Masks. Frames are processed in any order; unset masks are 0 (all music).
    void estimateClassical(std::size_t firstFrame, std::size_t count);
    void magnitudes(std::size_t firstFrame, std::size_t count, float* out) const;  // [count][kBins]
    void setMasks(std::size_t firstFrame, std::size_t count, const float* masks);   // [count][kBins], 0..1
    float mask(std::size_t frame, std::size_t bin) const noexcept;

    // Pass 2: the same input again; stems come out kFrameSize - kHop frames
    // later. pull() takes what is ready (interleaved stereo each).
    void render(const float* stereo, std::size_t frames);
    void finishRender();
    std::size_t pull(float* vocals, float* accompaniment, std::size_t capacity);
    std::size_t ready() const noexcept { return outVocals_.size() / 2 - outRead_; }

private:
    void analyseFrame();
    void renderFrame();
    float magnitude(std::size_t frame, std::size_t bin) const noexcept;

    double sampleRate_;
    FFT fft_{kFrameSize};
    std::vector<double> window_;
    std::vector<std::complex<double>> left_, right_;

    // Pass 1 state.
    std::vector<float> ringLeft_, ringRight_;
    std::size_t ringPosition_ = 0, sinceFrame_ = 0;
    std::uint64_t inputLength_ = 0;
    std::size_t frames_ = 0;
    bool analysed_ = false;
    std::vector<std::uint16_t> magnitude_;  // [frame][bin], log scale, 0.01 dB steps
    std::vector<std::uint8_t> centre_;      // [frame][bin], 0..255
    std::vector<float> features_;           // [frame][kFeatureBands], unit length
    std::vector<std::size_t> bandEdges_;
    std::vector<std::uint8_t> masks_;       // [frame][bin], 0..255

    // Pass 2 state.
    std::vector<float> renderLeft_, renderRight_;
    std::size_t renderPosition_ = 0, renderSince_ = 0, renderFrame_ = 0;
    std::uint64_t renderInput_ = 0, emitted_ = 0;
    std::vector<double> overlap_;           // vocal L, vocal R, accompaniment L, accompaniment R
    std::vector<float> outVocals_, outAccompaniment_;
    std::size_t outRead_ = 0;
};

}  // namespace maqam
