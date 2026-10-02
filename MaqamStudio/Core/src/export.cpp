#include "maqam/export.hpp"

#include <algorithm>
#include <cmath>
#include <numeric>

namespace maqam {

namespace {

double besselI0(double x) {
    double sum = 1.0, term = 1.0;
    for (int k = 1; k < 50; ++k) {
        term *= (x / (2.0 * k)) * (x / (2.0 * k));
        sum += term;
        if (term < sum * 1e-17) break;
    }
    return sum;
}

double kaiser(double position, double halfWidth, double beta) {
    const double r = position / halfWidth;
    if (std::fabs(r) >= 1.0) return 0.0;
    return besselI0(beta * std::sqrt(1.0 - r * r)) / besselI0(beta);
}

double sinc(double x) { return std::fabs(x) < 1e-12 ? 1.0 : std::sin(dsp::kPi * x) / (dsp::kPi * x); }

// Best rational approximation p/q of `value` with q <= limit (continued fractions).
void approximate(double value, std::uint64_t limit, std::uint64_t& p, std::uint64_t& q) {
    std::uint64_t p0 = 0, q0 = 1, p1 = 1, q1 = 0;
    double x = value;
    for (int i = 0; i < 64; ++i) {
        const double a = std::floor(x);
        const auto ai = static_cast<std::uint64_t>(a);
        const std::uint64_t p2 = ai * p1 + p0, q2 = ai * q1 + q0;
        if (q2 > limit || p2 > limit) break;
        p0 = p1; q0 = q1; p1 = p2; q1 = q2;
        if (x - a < 1e-12) break;
        x = 1.0 / (x - a);
    }
    p = std::max<std::uint64_t>(p1, 1);
    q = std::max<std::uint64_t>(q1, 1);
}

}  // namespace

// MARK: Resampler

Resampler::Resampler(double inputRate, double outputRate, int channels, int quality) : channels_(channels) {
    if (!(inputRate > 0.0) || !(outputRate > 0.0) || channels < 1) return;
    const double ri = std::round(inputRate), ro = std::round(outputRate);
    if (std::fabs(ri - inputRate) < 1e-6 && std::fabs(ro - outputRate) < 1e-6) {
        const auto a = static_cast<std::uint64_t>(ri), b = static_cast<std::uint64_t>(ro);
        const std::uint64_t g = std::gcd(a, b);
        l_ = b / g;
        m_ = a / g;
    } else {
        l_ = 0;
    }
    if (l_ == 0 || l_ > 2048 || m_ > 2048) approximate(outputRate / inputRate, 2048, l_, m_);
    actualRate_ = inputRate * static_cast<double>(l_) / static_cast<double>(m_);
    ok_ = true;
    if (passthrough()) return;

    // Passband edge (fraction of the lower Nyquist frequency) and stopband depth
    // grow with quality; "best" is flat to about 20 kHz at 44.1 kHz and keeps
    // aliases more than 90 dB down.
    static const int kTaps[3] = {32, 64, 128};
    static const double kRolloff[3] = {0.85, 0.90, 0.92};
    static const double kBeta[3] = {6.5, 8.0, 9.5};
    const int q = std::clamp(quality, 0, 2);
    taps_ = kTaps[q];
    const std::uint64_t length = static_cast<std::uint64_t>(taps_) * l_;
    const double centre = static_cast<double>(length) / 2.0;  // a whole number: no half-sample shift
    const double cutoff = kRolloff[q] / (2.0 * static_cast<double>(std::max(l_, m_)));
    filter_.assign(static_cast<std::size_t>(length), 0.0f);
    std::vector<double> h(static_cast<std::size_t>(length));
    for (std::uint64_t k = 0; k < length; ++k) {
        const double x = static_cast<double>(k) - centre;
        h[k] = 2.0 * cutoff * sinc(2.0 * cutoff * x) * kaiser(x, centre, kBeta[q]);
    }
    // Each phase sums to one, so a constant passes unchanged whatever the phase.
    for (std::uint64_t p = 0; p < l_; ++p) {
        double sum = 0.0;
        for (int j = 0; j < taps_; ++j) sum += h[p + static_cast<std::uint64_t>(j) * l_];
        for (int j = 0; j < taps_; ++j) {
            filter_[p * static_cast<std::uint64_t>(taps_) + static_cast<std::uint64_t>(j)] =
                static_cast<float>(sum != 0.0 ? h[p + static_cast<std::uint64_t>(j) * l_] / sum : 0.0);
        }
    }
}

void Resampler::process(const float* interleaved, std::size_t frames, std::vector<float>& out) {
    if (!ok_) return;
    if (passthrough()) {
        out.insert(out.end(), interleaved, interleaved + frames * static_cast<std::size_t>(channels_));
        inputCount_ += frames;
        outputCount_ += frames;
        return;
    }
    history_.insert(history_.end(), interleaved, interleaved + frames * static_cast<std::size_t>(channels_));
    inputCount_ += frames;
    produce(out, false);
}

void Resampler::flush(std::vector<float>& out) {
    if (!ok_ || passthrough()) return;
    produce(out, true);
}

bool Resampler::produce(std::vector<float>& out, bool final) {
    const std::uint64_t centre = static_cast<std::uint64_t>(taps_) * l_ / 2;
    const std::uint64_t total = (inputCount_ * l_ + m_ - 1) / m_;
    const std::size_t ch = static_cast<std::size_t>(channels_);
    while (true) {
        const std::uint64_t n = outputCount_;
        if (final && n >= total) break;
        const std::uint64_t t = n * m_ + centre;
        const std::uint64_t base = t / l_;
        if (!final && base >= inputCount_) break;
        const std::uint64_t phase = t % l_;
        const float* coefficients = filter_.data() + phase * static_cast<std::uint64_t>(taps_);
        const std::size_t at = out.size();
        out.resize(at + ch, 0.0f);
        for (int j = 0; j < taps_; ++j) {
            if (base < static_cast<std::uint64_t>(j)) break;
            const std::uint64_t index = base - static_cast<std::uint64_t>(j);
            if (index >= inputCount_ || index < historyStart_) continue;
            const float* frame = history_.data() + (index - historyStart_) * ch;
            for (std::size_t c = 0; c < ch; ++c) out[at + c] += coefficients[j] * frame[c];
        }
        ++outputCount_;
    }
    // Drop input no later output can reach.
    const std::uint64_t nextBase = (outputCount_ * m_ + centre) / l_;
    const std::uint64_t keepFrom = nextBase >= static_cast<std::uint64_t>(taps_) ? nextBase - static_cast<std::uint64_t>(taps_) + 1 : 0;
    if (keepFrom > historyStart_ + 4096) {
        const std::uint64_t drop = std::min<std::uint64_t>(keepFrom - historyStart_, history_.size() / ch);
        history_.erase(history_.begin(), history_.begin() + static_cast<std::ptrdiff_t>(drop * ch));
        historyStart_ += drop;
    }
    return true;
}

// MARK: True peak

TruePeak::TruePeak(int channels) : channels_(channels), ring_(static_cast<std::size_t>(channels) * 12, 0.0f) {
    // 48 taps at 4x: a Kaiser-windowed sinc at the original Nyquist frequency.
    double h[48];
    for (int k = 0; k < 48; ++k) {
        const double x = k - 23.5;
        h[k] = 0.24 * sinc(0.24 * x) * kaiser(x, 24.0, 7.0);
    }
    for (int phase = 0; phase < 4; ++phase) {
        double sum = 0.0;
        for (int j = 0; j < 12; ++j) sum += h[phase + 4 * j];
        for (int j = 0; j < 12; ++j) coefficients_[phase][j] = static_cast<float>(h[phase + 4 * j] / sum);
    }
}

double TruePeak::process(const float* frame, float* delayed) noexcept {
    double peak = 0.0;
    for (int c = 0; c < channels_; ++c) {
        float* ring = ring_.data() + static_cast<std::size_t>(c) * 12;
        ring[position_] = frame[c];
        for (int phase = 0; phase < 4; ++phase) {
            double sum = 0.0;
            for (int j = 0; j < 12; ++j) sum += coefficients_[phase][j] * ring[(position_ + 12 - static_cast<std::size_t>(j)) % 12];
            peak = std::max(peak, std::fabs(sum));
        }
        delayed[c] = ring[(position_ + 12 - kDelay) % 12];
    }
    position_ = (position_ + 1) % 12;
    return peak;
}

// MARK: Exporter

ExportProblem validate(const ExportSettings& s, double inputRate, int inputChannels) {
    if (!(inputRate >= 8000.0 && inputRate <= 384000.0) || !(s.sampleRate >= 8000.0 && s.sampleRate <= 192000.0)) {
        return ExportProblem::sampleRate;
    }
    if (inputChannels < 1 || inputChannels > 8 || s.channels < 1 || s.channels > 2) return ExportProblem::channels;
    switch (s.format) {
    case ExportSettings::Format::wav:
        if (s.bits != 16 && s.bits != 24 && s.bits != 32) return ExportProblem::bits;
        break;
    case ExportSettings::Format::flac:
        if (s.bits != 16 && s.bits != 24) return ExportProblem::bits;
        break;
    case ExportSettings::Format::mp3:
        if (s.sampleRate != 32000.0 && s.sampleRate != 44100.0 && s.sampleRate != 48000.0) return ExportProblem::mp3SampleRate;
        if (!Mp3Encoder::supports(static_cast<std::uint32_t>(s.sampleRate), s.mp3Kbps)) return ExportProblem::bitrate;
        break;
    }
    return ExportProblem::none;
}

Exporter::Exporter(const ExportSettings& settings, double inputRate, int inputChannels, ByteSink* sink)
    : settings_(settings), inputChannels_(inputChannels), sink_(sink), loudness_(settings.sampleRate, settings.channels) {
    if (validate(settings, inputRate, inputChannels) != ExportProblem::none) return;
    gain_ = dsp::dbToGain(settings.gainDb);
    resampler_ = std::make_unique<Resampler>(inputRate, settings.sampleRate, settings.channels, settings.resamplerQuality);
    if (!resampler_->ok()) return;
    const auto rate = static_cast<std::uint32_t>(std::lround(settings.sampleRate));
    if (sink_) {
        switch (settings.format) {
        case ExportSettings::Format::wav: encoder_ = std::make_unique<WavEncoder>(*sink_, rate, settings.channels, settings.bits); break;
        case ExportSettings::Format::flac: encoder_ = std::make_unique<FlacEncoder>(*sink_, rate, settings.channels, settings.bits); break;
        case ExportSettings::Format::mp3: encoder_ = std::make_unique<Mp3Encoder>(*sink_, rate, settings.channels, settings.mp3Kbps); break;
        }
    }
    meterPeak_ = std::make_unique<TruePeak>(settings.channels);
    if (settings.limit) {
        limiterDetector_ = std::make_unique<TruePeak>(settings.channels);
        limiter_.prepare(settings.sampleRate, settings.ceilingDb, 2.0, 80.0);
        latency_ = TruePeak::kDelay + limiter_.latency();
    }
    ok_ = true;
}

Exporter::~Exporter() = default;

bool Exporter::push(const float* interleaved, std::size_t frames) {
    if (!ok_) return false;
    const std::size_t in = static_cast<std::size_t>(inputChannels_), out = static_cast<std::size_t>(settings_.channels);
    mapped_.resize(frames * out);
    for (std::size_t i = 0; i < frames; ++i) {
        const float* frame = interleaved + i * in;
        if (out == 1) {
            double sum = 0.0;
            for (std::size_t c = 0; c < in; ++c) sum += frame[c];
            mapped_[i] = static_cast<float>(gain_ * sum / static_cast<double>(in));
        } else {
            mapped_[i * 2] = static_cast<float>(gain_ * frame[0]);
            mapped_[i * 2 + 1] = static_cast<float>(gain_ * frame[in > 1 ? 1 : 0]);
        }
    }
    resampled_.clear();
    resampler_->process(mapped_.data(), frames, resampled_);
    return consume(resampled_);
}

bool Exporter::consume(const std::vector<float>& frames) {
    const std::size_t ch = static_cast<std::size_t>(settings_.channels);
    const std::size_t count = frames.size() / ch;
    floats_.clear();
    for (std::size_t i = 0; i < count; ++i) {
        float frame[2] = {frames[i * ch], ch > 1 ? frames[i * ch + 1] : 0.0f};
        if (settings_.limit) {
            float delayed[2] = {0.0f, 0.0f};
            const double peak = limiterDetector_->process(frame, delayed);
            float l = delayed[0], r = ch > 1 ? delayed[1] : delayed[0];
            limiter_.process(l, r, peak);
            frame[0] = l;
            frame[1] = r;
            if (skipped_ < latency_) { ++skipped_; continue; }
        }
        deliver(frame);
    }
    if (floats_.empty()) return true;
    const std::size_t produced = floats_.size() / ch;
    left_.resize(produced);
    right_.resize(produced);
    for (std::size_t i = 0; i < produced; ++i) {
        left_[i] = floats_[i * ch];
        right_[i] = ch > 1 ? floats_[i * ch + 1] : 0.0f;
    }
    loudness_.push(left_.data(), ch > 1 ? right_.data() : nullptr, produced);
    frames_ += produced;
    if (!encoder_) return true;
    if (encoder_->wantsFloat()) return encoder_->writeFloat(floats_.data(), produced);
    // Integer formats: TPDF dither of one LSB, then round.
    const double scale = static_cast<double>(1u << (settings_.bits - 1));
    const auto top = static_cast<std::int32_t>(scale) - 1, bottom = -static_cast<std::int32_t>(scale);
    ints_.resize(floats_.size());
    for (std::size_t i = 0; i < floats_.size(); ++i) {
        double v = floats_[i] * scale;
        if (settings_.dither) {
            auto next = [&] {
                dither_ ^= dither_ << 13; dither_ ^= dither_ >> 7; dither_ ^= dither_ << 17;
                return static_cast<double>(dither_ >> 11) * (1.0 / 9007199254740992.0);
            };
            v += next() - next();
        }
        ints_[i] = static_cast<std::int32_t>(std::clamp<long long>(std::llround(v), bottom, top));
    }
    return encoder_->writeInt(ints_.data(), produced);
}

bool Exporter::deliver(const float* frame) {
    const std::size_t ch = static_cast<std::size_t>(settings_.channels);
    float ignored[2];
    truePeak_ = std::max(truePeak_, meterPeak_->process(frame, ignored));
    for (std::size_t c = 0; c < ch; ++c) {
        const double magnitude = std::fabs(frame[c]);
        samplePeak_ = std::max(samplePeak_, magnitude);
        if (magnitude > 1.0) ++clipped_;
        floats_.push_back(frame[c]);
    }
    return true;
}

bool Exporter::finish(ExportStats& stats) {
    if (!ok_) return false;
    resampled_.clear();
    resampler_->flush(resampled_);
    if (!consume(resampled_)) return false;
    if (settings_.limit) {
        // Silence drains the detector and the limiter's lookahead.
        const std::vector<float> silence(latency_ * static_cast<std::size_t>(settings_.channels), 0.0f);
        if (!consume(silence)) return false;
    }
    // Let the peak meter see the last samples too.
    const float zero[2] = {0.0f, 0.0f};
    float ignored[2];
    for (std::size_t i = 0; i < TruePeak::kDelay; ++i) truePeak_ = std::max(truePeak_, meterPeak_->process(zero, ignored));
    if (encoder_ && !encoder_->finish()) return false;
    stats.frames = frames_;
    stats.integratedLufs = loudness_.integratedLufs();
    stats.samplePeakDbfs = dsp::gainToDb(samplePeak_);
    stats.truePeakDbtp = dsp::gainToDb(std::max(truePeak_, samplePeak_));
    stats.clippedSamples = clipped_;
    stats.maximumReductionDb = settings_.limit ? limiter_.maximumReductionDb() : 0.0;
    stats.bytes = sink_ ? sink_->position() : 0;
    return true;
}

}  // namespace maqam
