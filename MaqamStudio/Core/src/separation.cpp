#include "maqam/separation.hpp"

#include <algorithm>
#include <array>
#include <cmath>

namespace maqam {

namespace {

constexpr double kPi = 3.14159265358979323846;
// Frames are found from a 30 s neighbourhood; frames closer than 1 s are the
// same note or beat continuing, not a repetition of it.
constexpr double kSearchSeconds = 30.0;
constexpr double kMinimumGapSeconds = 1.0;
constexpr std::size_t kNeighbours = 30;

std::uint16_t quantize(double magnitude) {
    const double db = 20.0 * std::log10(magnitude + 1e-12);
    return static_cast<std::uint16_t>(std::clamp(std::lround((db + 160.0) * 100.0), 0L, 65535L));
}

float dequantize(std::uint16_t q) {
    static const std::vector<float> table = [] {
        std::vector<float> t(65536);
        for (std::size_t i = 0; i < t.size(); ++i) t[i] = static_cast<float>(std::pow(10.0, (static_cast<double>(i) / 100.0 - 160.0) / 20.0));
        t[0] = 0.0f;
        return t;
    }();
    return table[q];
}

}  // namespace

Separation::Separation(double sampleRate) : sampleRate_(sampleRate) {
    window_.resize(kFrameSize);
    for (std::size_t i = 0; i < kFrameSize; ++i) window_[i] = 0.5 - 0.5 * std::cos(2.0 * kPi * static_cast<double>(i) / kFrameSize);
    left_.resize(kFrameSize);
    right_.resize(kFrameSize);
    ringLeft_.assign(kFrameSize, 0.0f);
    ringRight_.assign(kFrameSize, 0.0f);
    // Fingerprint bands: logarithmic from 60 Hz to 8 kHz (or Nyquist).
    const double top = std::min(8000.0, 0.45 * sampleRate);
    for (std::size_t b = 0; b <= kFeatureBands; ++b) {
        const double hz = 60.0 * std::pow(top / 60.0, static_cast<double>(b) / kFeatureBands);
        bandEdges_.push_back(std::clamp<std::size_t>(static_cast<std::size_t>(hz / sampleRate * kFrameSize), 1, kBins - 1));
    }
    for (std::size_t b = 1; b <= kFeatureBands; ++b) bandEdges_[b] = std::max(bandEdges_[b], bandEdges_[b - 1] + 1);
}

void Separation::analyse(const float* stereo, std::size_t frames) {
    if (analysed_) return;
    for (std::size_t i = 0; i < frames; ++i) {
        ringLeft_[ringPosition_] = stereo[2 * i];
        ringRight_[ringPosition_] = stereo[2 * i + 1];
        ringPosition_ = (ringPosition_ + 1) % kFrameSize;
        ++inputLength_;
        if (++sinceFrame_ == kHop) {
            sinceFrame_ = 0;
            analyseFrame();
        }
    }
}

void Separation::finishAnalysis() {
    if (analysed_) return;
    // Zeros until every frame that touches the input exists.
    const std::size_t total = static_cast<std::size_t>((inputLength_ + kFrameSize - kHop + kHop - 1) / kHop);
    while (frames_ < total) {
        ringLeft_[ringPosition_] = 0.0f;
        ringRight_[ringPosition_] = 0.0f;
        ringPosition_ = (ringPosition_ + 1) % kFrameSize;
        if (++sinceFrame_ == kHop) {
            sinceFrame_ = 0;
            analyseFrame();
        }
    }
    masks_.assign(frames_ * kBins, 0);
    analysed_ = true;
}

void Separation::analyseFrame() {
    for (std::size_t i = 0; i < kFrameSize; ++i) {
        const std::size_t at = (ringPosition_ + i) % kFrameSize;  // oldest first
        left_[i] = {ringLeft_[at] * window_[i], 0.0};
        right_[i] = {ringRight_[at] * window_[i], 0.0};
    }
    fft_.transform(left_.data(), false);
    fft_.transform(right_.data(), false);
    magnitude_.resize((frames_ + 1) * kBins);
    centre_.resize((frames_ + 1) * kBins);
    std::uint16_t* magnitudes = magnitude_.data() + frames_ * kBins;
    std::uint8_t* centre = centre_.data() + frames_ * kBins;
    for (std::size_t k = 0; k < kBins; ++k) {
        const std::complex<double> l = left_[k], r = right_[k];
        magnitudes[k] = quantize(std::abs(0.5 * (l + r)));
        // 1 when left and right agree in level and phase (centre), 0 when one
        // side is silent or they are opposed.
        const double power = std::norm(l) + std::norm(r);
        const double agreement = power > 1e-20 ? 2.0 * (l * std::conj(r)).real() / power : 1.0;
        centre[k] = static_cast<std::uint8_t>(std::lround(255.0 * std::clamp((agreement - 0.5) / 0.45, 0.0, 1.0)));
    }
    features_.resize((frames_ + 1) * kFeatureBands);
    float* feature = features_.data() + frames_ * kFeatureBands;
    double norm = 0.0;
    for (std::size_t b = 0; b < kFeatureBands; ++b) {
        double energy = 0.0;
        for (std::size_t k = bandEdges_[b]; k < bandEdges_[b + 1]; ++k) {
            const double m = dequantize(magnitudes[k]);
            energy += m * m;
        }
        feature[b] = static_cast<float>(std::pow(energy, 0.25));
        norm += static_cast<double>(feature[b]) * feature[b];
    }
    if (norm > 0.0) {
        const double scale = 1.0 / std::sqrt(norm);
        for (std::size_t b = 0; b < kFeatureBands; ++b) feature[b] = static_cast<float>(feature[b] * scale);
    }
    ++frames_;
}

float Separation::magnitude(std::size_t frame, std::size_t bin) const noexcept {
    return dequantize(magnitude_[frame * kBins + bin]);
}

void Separation::estimateClassical(std::size_t firstFrame, std::size_t count) {
    if (!analysed_) return;
    const double framesPerSecond = sampleRate_ / kHop;
    const auto search = static_cast<std::ptrdiff_t>(kSearchSeconds * framesPerSecond);
    const auto gap = static_cast<std::ptrdiff_t>(kMinimumGapSeconds * framesPerSecond);
    const auto total = static_cast<std::ptrdiff_t>(frames_);
    std::vector<std::pair<float, std::size_t>> candidates;
    std::vector<std::uint16_t> values;
    std::vector<std::size_t> neighbours;
    const double lowEdge = 80.0, lowFull = 160.0;
    for (std::size_t frame = firstFrame; frame < std::min(frames_, firstFrame + count); ++frame) {
        const auto t = static_cast<std::ptrdiff_t>(frame);
        const float* own = features_.data() + frame * kFeatureBands;
        candidates.clear();
        for (std::ptrdiff_t u = std::max<std::ptrdiff_t>(0, t - search); u <= std::min(total - 1, t + search); ++u) {
            if (std::abs(u - t) < gap) continue;
            const float* other = features_.data() + static_cast<std::size_t>(u) * kFeatureBands;
            float similarity = 0.0f;
            for (std::size_t b = 0; b < kFeatureBands; ++b) similarity += own[b] * other[b];
            candidates.emplace_back(-similarity, static_cast<std::size_t>(u));
        }
        std::uint8_t* masks = masks_.data() + frame * kBins;
        if (candidates.size() < 3) {
            // Too little song to find repetition in: decide on position alone.
            for (std::size_t k = 0; k < kBins; ++k) masks[k] = centre_[frame * kBins + k];
            continue;
        }
        const std::size_t keep = std::min(kNeighbours, candidates.size());
        std::nth_element(candidates.begin(), candidates.begin() + static_cast<std::ptrdiff_t>(keep - 1), candidates.end());
        neighbours.clear();
        for (std::size_t i = 0; i < keep; ++i) neighbours.push_back(candidates[i].second);
        values.resize(keep);
        for (std::size_t k = 0; k < kBins; ++k) {
            // What repeats here: the median of this bin over the similar frames
            // (taken on the log scale, which keeps the order).
            for (std::size_t i = 0; i < keep; ++i) values[i] = magnitude_[neighbours[i] * kBins + k];
            std::nth_element(values.begin(), values.begin() + static_cast<std::ptrdiff_t>(keep / 2), values.end());
            const double v = magnitude(frame, k);
            const double repeating = std::min<double>(dequantize(values[keep / 2]), v);
            const double hz = static_cast<double>(k) * sampleRate_ / kFrameSize;
            const double range = hz <= lowEdge ? 0.0 : hz >= lowFull ? 1.0 : (hz - lowEdge) / (lowFull - lowEdge);
            const double voice = (v - repeating) * (centre_[frame * kBins + k] / 255.0) * range;
            const double rest = v - voice;
            const double denominator = voice * voice + rest * rest;
            const double mask = denominator > 1e-24 ? voice * voice / denominator : 0.0;
            masks[k] = static_cast<std::uint8_t>(std::lround(255.0 * std::clamp(mask, 0.0, 1.0)));
        }
    }
}

void Separation::magnitudes(std::size_t firstFrame, std::size_t count, float* out) const {
    for (std::size_t f = 0; f < count; ++f) {
        const std::size_t frame = firstFrame + f;
        for (std::size_t k = 0; k < kBins; ++k) out[f * kBins + k] = frame < frames_ ? magnitude(frame, k) : 0.0f;
    }
}

void Separation::setMasks(std::size_t firstFrame, std::size_t count, const float* masks) {
    if (!analysed_) return;
    for (std::size_t f = 0; f < count && firstFrame + f < frames_; ++f) {
        for (std::size_t k = 0; k < kBins; ++k) {
            const float m = masks[f * kBins + k];
            masks_[(firstFrame + f) * kBins + k] =
                static_cast<std::uint8_t>(std::lround(255.0 * std::clamp(std::isfinite(m) ? static_cast<double>(m) : 0.0, 0.0, 1.0)));
        }
    }
}

float Separation::mask(std::size_t frame, std::size_t bin) const noexcept {
    if (!analysed_ || frame >= frames_ || bin >= kBins) return 0.0f;
    return masks_[frame * kBins + bin] / 255.0f;
}

void Separation::render(const float* stereo, std::size_t frames) {
    if (!analysed_) return;
    if (renderLeft_.empty()) {
        renderLeft_.assign(kFrameSize, 0.0f);
        renderRight_.assign(kFrameSize, 0.0f);
        overlap_.assign(4 * kFrameSize, 0.0);
    }
    for (std::size_t i = 0; i < frames; ++i) {
        if (renderInput_ >= inputLength_) return;  // the same song as analysed, no more
        renderLeft_[renderPosition_] = stereo[2 * i];
        renderRight_[renderPosition_] = stereo[2 * i + 1];
        renderPosition_ = (renderPosition_ + 1) % kFrameSize;
        ++renderInput_;
        if (++renderSince_ == kHop) {
            renderSince_ = 0;
            renderFrame();
        }
    }
}

void Separation::finishRender() {
    if (!analysed_) return;
    if (renderLeft_.empty()) render(nullptr, 0);
    while (renderFrame_ < frames_) {
        renderLeft_[renderPosition_] = 0.0f;
        renderRight_[renderPosition_] = 0.0f;
        renderPosition_ = (renderPosition_ + 1) % kFrameSize;
        if (++renderSince_ == kHop) {
            renderSince_ = 0;
            renderFrame();
        }
    }
}

void Separation::renderFrame() {
    for (std::size_t i = 0; i < kFrameSize; ++i) {
        const std::size_t at = (renderPosition_ + i) % kFrameSize;
        left_[i] = {renderLeft_[at] * window_[i], 0.0};
        right_[i] = {renderRight_[at] * window_[i], 0.0};
    }
    fft_.transform(left_.data(), false);
    fft_.transform(right_.data(), false);
    // Two real signals per inverse transform: vocal L + i vocal R, and the
    // same for the accompaniment.
    std::vector<std::complex<double>>& vocal = left_;
    std::vector<std::complex<double>>& rest = right_;
    for (std::size_t k = 0; k < kFrameSize; ++k) {
        const std::size_t bin = k < kBins ? k : kFrameSize - k;
        const double m = mask(renderFrame_, bin);
        const std::complex<double> l = left_[k], r = right_[k];
        const std::complex<double> vl = m * l, vr = m * r;
        vocal[k] = vl + std::complex<double>(0.0, 1.0) * vr;
        rest[k] = (l - vl) + std::complex<double>(0.0, 1.0) * (r - vr);
    }
    fft_.transform(vocal.data(), true);
    fft_.transform(rest.data(), true);
    constexpr double kScale = 1.0 / 1.5;  // Hann analysis and synthesis at 75% overlap
    double* vocalLeft = overlap_.data();
    double* vocalRight = vocalLeft + kFrameSize;
    double* restLeft = vocalRight + kFrameSize;
    double* restRight = restLeft + kFrameSize;
    for (std::size_t i = 0; i < kFrameSize; ++i) {
        const double w = window_[i] * kScale;
        vocalLeft[i] += vocal[i].real() * w;
        vocalRight[i] += vocal[i].imag() * w;
        restLeft[i] += rest[i].real() * w;
        restRight[i] += rest[i].imag() * w;
    }
    // The first hop is now complete. Frame t starts kFrameSize - kHop samples
    // before input sample t * kHop, so the first three hops are padding.
    const std::uint64_t virtualStart = static_cast<std::uint64_t>(renderFrame_) * kHop;
    for (std::size_t i = 0; i < kHop; ++i) {
        const std::uint64_t position = virtualStart + i;
        if (position < kFrameSize - kHop) continue;
        if (emitted_ >= inputLength_) break;
        outVocals_.push_back(static_cast<float>(vocalLeft[i]));
        outVocals_.push_back(static_cast<float>(vocalRight[i]));
        outAccompaniment_.push_back(static_cast<float>(restLeft[i]));
        outAccompaniment_.push_back(static_cast<float>(restRight[i]));
        ++emitted_;
    }
    for (double* channel : {vocalLeft, vocalRight, restLeft, restRight}) {
        std::copy(channel + kHop, channel + kFrameSize, channel);
        std::fill(channel + kFrameSize - kHop, channel + kFrameSize, 0.0);
    }
    ++renderFrame_;
}

std::size_t Separation::pull(float* vocals, float* accompaniment, std::size_t capacity) {
    const std::size_t available = outVocals_.size() / 2 - outRead_;
    const std::size_t count = std::min(available, capacity);
    std::copy(outVocals_.begin() + static_cast<std::ptrdiff_t>(2 * outRead_),
              outVocals_.begin() + static_cast<std::ptrdiff_t>(2 * (outRead_ + count)), vocals);
    std::copy(outAccompaniment_.begin() + static_cast<std::ptrdiff_t>(2 * outRead_),
              outAccompaniment_.begin() + static_cast<std::ptrdiff_t>(2 * (outRead_ + count)), accompaniment);
    outRead_ += count;
    if (outRead_ > 65536 && outRead_ == outVocals_.size() / 2) {
        outVocals_.clear();
        outAccompaniment_.clear();
        outRead_ = 0;
    }
    return count;
}

}  // namespace maqam
