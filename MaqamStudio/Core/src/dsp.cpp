#include "maqam/dsp.hpp"

#include <algorithm>
#include <complex>
#include <numeric>

namespace maqam::dsp {

// ------------------------------------------------------------------ filters

void Biquad::set(Type type, double sampleRate, double frequency, double q, double gainDb) noexcept {
    const double nyquist = sampleRate * 0.5;
    frequency = std::clamp(frequency, 1.0, nyquist * 0.98);
    q = std::max(q, 0.01);
    const double w0 = 2.0 * kPi * frequency / sampleRate;
    const double cosW = std::cos(w0);
    const double sinW = std::sin(w0);
    const double alpha = sinW / (2.0 * q);
    const double a = std::pow(10.0, gainDb / 40.0);
    double b0 = 1, b1 = 0, b2 = 0, a0 = 1, a1 = 0, a2 = 0;
    switch (type) {
    case Type::lowPass:
        b0 = (1 - cosW) / 2; b1 = 1 - cosW; b2 = (1 - cosW) / 2;
        a0 = 1 + alpha; a1 = -2 * cosW; a2 = 1 - alpha;
        break;
    case Type::highPass:
        b0 = (1 + cosW) / 2; b1 = -(1 + cosW); b2 = (1 + cosW) / 2;
        a0 = 1 + alpha; a1 = -2 * cosW; a2 = 1 - alpha;
        break;
    case Type::bandPass:
        b0 = alpha; b1 = 0; b2 = -alpha;
        a0 = 1 + alpha; a1 = -2 * cosW; a2 = 1 - alpha;
        break;
    case Type::notch:
        b0 = 1; b1 = -2 * cosW; b2 = 1;
        a0 = 1 + alpha; a1 = -2 * cosW; a2 = 1 - alpha;
        break;
    case Type::peaking:
        b0 = 1 + alpha * a; b1 = -2 * cosW; b2 = 1 - alpha * a;
        a0 = 1 + alpha / a; a1 = -2 * cosW; a2 = 1 - alpha / a;
        break;
    case Type::lowShelf: {
        const double s = 2 * std::sqrt(a) * alpha;
        b0 = a * ((a + 1) - (a - 1) * cosW + s);
        b1 = 2 * a * ((a - 1) - (a + 1) * cosW);
        b2 = a * ((a + 1) - (a - 1) * cosW - s);
        a0 = (a + 1) + (a - 1) * cosW + s;
        a1 = -2 * ((a - 1) + (a + 1) * cosW);
        a2 = (a + 1) + (a - 1) * cosW - s;
        break;
    }
    case Type::highShelf: {
        const double s = 2 * std::sqrt(a) * alpha;
        b0 = a * ((a + 1) + (a - 1) * cosW + s);
        b1 = -2 * a * ((a - 1) + (a + 1) * cosW);
        b2 = a * ((a + 1) + (a - 1) * cosW - s);
        a0 = (a + 1) - (a - 1) * cosW + s;
        a1 = 2 * ((a - 1) - (a + 1) * cosW);
        a2 = (a + 1) - (a - 1) * cosW - s;
        break;
    }
    }
    b0_ = b0 / a0; b1_ = b1 / a0; b2_ = b2 / a0; a1_ = a1 / a0; a2_ = a2 / a0;
}

void Biquad::setIdentity() noexcept {
    b0_ = 1; b1_ = b2_ = a1_ = a2_ = 0;
}

double Biquad::magnitudeAt(double frequency, double sampleRate) const noexcept {
    const double w = 2.0 * kPi * frequency / sampleRate;
    const std::complex<double> z1 = std::polar(1.0, -w);
    const std::complex<double> z2 = z1 * z1;
    return std::abs((b0_ + b1_ * z1 + b2_ * z2) / (1.0 + a1_ * z1 + a2_ * z2));
}

void Crossover::set(double sampleRate, double frequency) noexcept {
    const double butterworthQ = 0.7071067811865476;
    lp1_.set(Biquad::Type::lowPass, sampleRate, frequency, butterworthQ);
    lp2_.set(Biquad::Type::lowPass, sampleRate, frequency, butterworthQ);
    hp1_.set(Biquad::Type::highPass, sampleRate, frequency, butterworthQ);
    hp2_.set(Biquad::Type::highPass, sampleRate, frequency, butterworthQ);
    reset();
}

void Crossover::reset() noexcept {
    lp1_.reset(); lp2_.reset(); hp1_.reset(); hp2_.reset();
}

// --------------------------------------------------------------- envelopes

void Envelope::set(double sampleRate, double attackMs, double releaseMs) noexcept {
    attack_ = attackMs > 0.0 ? std::exp(-1.0 / (attackMs * 0.001 * sampleRate)) : 0.0;
    release_ = releaseMs > 0.0 ? std::exp(-1.0 / (releaseMs * 0.001 * sampleRate)) : 0.0;
}

// --------------------------------------------------------------- dynamics

double Compressor::staticCurveDb(double inputDb, const CompressorSettings& s) noexcept {
    const double ratio = std::max(1.0, s.ratio);
    const double over = inputDb - s.thresholdDb;
    const double knee = std::max(0.0, s.kneeDb);
    if (knee > 0.0 && std::fabs(over) <= knee / 2.0) {
        const double x = over + knee / 2.0;
        return inputDb + (1.0 / ratio - 1.0) * x * x / (2.0 * knee);
    }
    if (over > 0.0) return s.thresholdDb + over / ratio;
    return inputDb;
}

void Compressor::prepare(double sampleRate, const CompressorSettings& settings) noexcept {
    settings_ = settings;
    // The level detector is a short RMS window; the gain follows attack/release.
    level_.set(sampleRate, 5.0, 5.0);
    gain_.set(sampleRate, settings.attackMs, settings.releaseMs);
    reset();
}

void Compressor::reset() noexcept {
    level_.reset(0.0);
    gain_.reset(0.0);
    lastReductionDb_ = 0.0;
}

double Compressor::gainFor(float detector) noexcept {
    const double power = level_.process(static_cast<double>(detector) * detector);
    const double levelDb = 10.0 * std::log10(power + 1e-24) + 3.0103;  // RMS of a sine reads as its peak
    const double reduction = levelDb - staticCurveDb(levelDb, settings_);  // >= 0
    // Attack when the reduction grows, release when it shrinks.
    const double smoothed = gain_.process(reduction);
    lastReductionDb_ = smoothed;
    return dbToGain(settings_.makeupDb - smoothed);
}

void MultibandCompressor::prepare(double sampleRate, double lowSplitHz, double highSplitHz,
                                  const std::array<CompressorSettings, 3>& bands) noexcept {
    low_.set(sampleRate, lowSplitHz);
    high_.set(sampleRate, highSplitHz);
    lowPhase_.set(sampleRate, highSplitHz);
    for (std::size_t i = 0; i < 3; ++i) bands_[i].prepare(sampleRate, bands[i]);
}

void MultibandCompressor::reset() noexcept {
    low_.reset(); high_.reset(); lowPhase_.reset();
    for (Compressor& band : bands_) band.reset();
}

float MultibandCompressor::process(float x) noexcept {
    float low = 0.0f, rest = 0.0f, mid = 0.0f, top = 0.0f, a = 0.0f, b = 0.0f;
    low_.process(x, low, rest);
    high_.process(rest, mid, top);
    lowPhase_.process(low, a, b);  // same phase shift the other bands went through
    low = a + b;
    return bands_[0].process(low) + bands_[1].process(mid) + bands_[2].process(top);
}

void DynamicBand::prepare(double sampleRate, const DynamicBandSettings& settings) noexcept {
    settings_ = settings;
    sampleRate_ = sampleRate;
    detector_.set(Biquad::Type::bandPass, sampleRate, settings.frequencyHz, settings.q);
    envelope_.set(sampleRate, settings.attackMs, settings.releaseMs);
    reset();
}

void DynamicBand::reset() noexcept {
    detector_.reset();
    cut_.setIdentity();
    cut_.reset();
    envelope_.reset(0.0);
    appliedCutDb_ = 0.0;
    lastCutDb_ = 0.0;
    counter_ = 0;
}

float DynamicBand::process(float x) noexcept {
    const float band = detector_.process(x);
    const double level = envelope_.process(std::fabs(band));
    const double levelDb = gainToDb(level * 1.4142135623730951);
    const double over = levelDb - settings_.thresholdDb;
    const double wanted = over > 0.0 ? std::min(settings_.maximumCutDb, over * (1.0 - 1.0 / std::max(1.0, settings_.ratio))) : 0.0;
    // Coefficients are recomputed every 16 samples, and only on a real change.
    if (++counter_ >= 16) {
        counter_ = 0;
        if (std::fabs(wanted - appliedCutDb_) > 0.05) {
            appliedCutDb_ = wanted;
            if (appliedCutDb_ <= 0.01) {
                cut_.setIdentity();
            } else {
                cut_.set(settings_.shelf ? Biquad::Type::highShelf : Biquad::Type::peaking, sampleRate_,
                         settings_.frequencyHz, settings_.shelf ? 0.7071 : settings_.q, -appliedCutDb_);
            }
        }
    }
    lastCutDb_ = appliedCutDb_;
    return cut_.process(x);
}

void Limiter::prepare(double sampleRate, double ceilingDb, double lookaheadMs, double releaseMs) {
    ceiling_ = dbToGain(ceilingDb);
    delay_ = std::max<std::size_t>(1, static_cast<std::size_t>(lookaheadMs * 0.001 * sampleRate));
    left_.assign(delay_ + 1, 0.0f);
    right_.assign(delay_ + 1, 0.0f);
    peaks_.assign(2 * (delay_ + 1), 1.0);
    release_ = std::exp(-1.0 / (releaseMs * 0.001 * sampleRate));
    reset();
}

void Limiter::reset() noexcept {
    std::fill(left_.begin(), left_.end(), 0.0f);
    std::fill(right_.begin(), right_.end(), 0.0f);
    std::fill(peaks_.begin(), peaks_.end(), 1.0);
    position_ = 0;
    gain_ = 1.0;
    maximumReductionDb_ = 0.0;
}

void Limiter::process(float& left, float& right) noexcept {
    const std::size_t size = delay_ + 1;
    // Gain each incoming sample needs; the minimum over the lookahead window,
    // then averaged over it, reaches every peak in time and never overshoots.
    const double peak = std::max(std::fabs(left), std::fabs(right));
    const double required = peak > ceiling_ ? ceiling_ / peak : 1.0;
    double* need = peaks_.data();
    double* windowMin = peaks_.data() + size;
    need[position_] = required;
    double minimum = 1.0;
    for (std::size_t i = 0; i < size; ++i) minimum = std::min(minimum, need[i]);
    windowMin[position_] = minimum;
    double average = 0.0;
    for (std::size_t i = 0; i < size; ++i) average += windowMin[i];
    average /= static_cast<double>(size);
    const double target = average;
    gain_ = target < gain_ ? target : target + release_ * (gain_ - target);

    const std::size_t oldest = (position_ + 1) % size;
    const float outLeft = left_[oldest];
    const float outRight = right_[oldest];
    left_[position_] = left;
    right_[position_] = right;
    position_ = oldest;
    // Gain of the sample leaving now: the window it was inside is fully known.
    const double applied = std::min(gain_, 1.0);
    left = static_cast<float>(outLeft * applied);
    right = static_cast<float>(outRight * applied);
    // Safety: never let a rounding error exceed the ceiling.
    left = std::clamp(left, static_cast<float>(-ceiling_), static_cast<float>(ceiling_));
    right = std::clamp(right, static_cast<float>(-ceiling_), static_cast<float>(ceiling_));
    maximumReductionDb_ = std::max(maximumReductionDb_, -gainToDb(applied));
}

void Leveler::prepare(double sampleRate, double targetDb, double maximumBoostDb, double maximumCutDb,
                      double gateDb) noexcept {
    target_ = targetDb;
    maximumBoost_ = maximumBoostDb;
    maximumCut_ = maximumCutDb;
    gate_ = gateDb;
    power_.set(sampleRate, 300.0, 300.0);
    gain_.set(sampleRate, 400.0, 400.0);
    reset();
}

void Leveler::reset() noexcept {
    power_.reset(0.0);
    gain_.reset(0.0);
}

float Leveler::process(float x) noexcept {
    const double levelDb = 10.0 * std::log10(power_.process(static_cast<double>(x) * x) + 1e-24) + 3.0103;
    // Below the gate (pauses, breaths, room) the gain holds instead of pumping up.
    double wanted = gain_.value();
    if (levelDb > gate_) wanted = std::clamp(target_ - levelDb, -maximumCut_, maximumBoost_);
    return static_cast<float>(x * dbToGain(gain_.process(wanted)));
}

// -------------------------------------------------------------- colour

void Saturator::prepare(double driveDb, double mix) noexcept {
    drive_ = dbToGain(driveDb);
    mix_ = std::clamp(mix, 0.0, 1.0);
}

float Saturator::process(float x) const noexcept {
    // tanh(d x) / d: unity for quiet signals, rounding off as it gets louder.
    const double shaped = std::tanh(drive_ * x) / drive_;
    return static_cast<float>((1.0 - mix_) * x + mix_ * shaped);
}

void Exciter::prepare(double sampleRate, double fromHz, double driveDb, double amount) noexcept {
    highPass_.set(Biquad::Type::highPass, sampleRate, fromHz, 0.7071);
    highPass2_.set(Biquad::Type::highPass, sampleRate, fromHz, 0.7071);
    drive_ = dbToGain(driveDb);
    amount_ = std::max(0.0, amount);
    reset();
}

void Exciter::reset() noexcept {
    highPass_.reset();
    highPass2_.reset();
}

float Exciter::process(float x) noexcept {
    const double top = highPass_.process(x);
    // Asymmetric shaping adds both even and odd harmonics; the second filter
    // keeps the generated low products out.
    const double shaped = std::tanh(drive_ * top + 0.3 * drive_ * top * top) / drive_;
    return static_cast<float>(x + amount_ * highPass2_.process(static_cast<float>(shaped)));
}

// --------------------------------------------------------------- space

float Reverb::Comb::process(float input, float feedback, float damp) noexcept {
    const float output = buffer[index];
    store = output * (1.0f - damp) + store * damp;
    buffer[index] = input + store * feedback;
    if (++index >= buffer.size()) index = 0;
    return output;
}

float Reverb::AllPass::process(float input) noexcept {
    const float buffered = buffer[index];
    const float output = -input + buffered;
    buffer[index] = input + buffered * 0.5f;
    if (++index >= buffer.size()) index = 0;
    return output;
}

void Reverb::prepare(double sampleRate, double roomSize, double damping, double widthAmount, double preDelayMs) {
    static constexpr std::array<int, 8> kCombs{1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617};
    static constexpr std::array<int, 4> kAllPasses{556, 441, 341, 225};
    constexpr int kSpread = 23;
    const double scale = sampleRate / 44100.0;
    for (std::size_t i = 0; i < 8; ++i) {
        combsL_[i].buffer.assign(static_cast<std::size_t>(kCombs[i] * scale), 0.0f);
        combsR_[i].buffer.assign(static_cast<std::size_t>((kCombs[i] + kSpread) * scale), 0.0f);
    }
    for (std::size_t i = 0; i < 4; ++i) {
        allpassL_[i].buffer.assign(static_cast<std::size_t>(kAllPasses[i] * scale), 0.0f);
        allpassR_[i].buffer.assign(static_cast<std::size_t>((kAllPasses[i] + kSpread) * scale), 0.0f);
    }
    preDelay_.assign(std::max<std::size_t>(1, static_cast<std::size_t>(preDelayMs * 0.001 * sampleRate)), 0.0f);
    feedback_ = static_cast<float>(std::clamp(roomSize, 0.0, 1.0) * 0.28 + 0.7);
    damp_ = static_cast<float>(std::clamp(damping, 0.0, 1.0) * 0.4);
    const double width = std::clamp(widthAmount, 0.0, 1.0);
    // Freeverb's wet scale (3), so that a mix of 1 is about as loud as the dry voice.
    wet1_ = static_cast<float>(3.0 * (width / 2.0 + 0.5));
    wet2_ = static_cast<float>(3.0 * (1.0 - width) / 2.0);
    reset();
}

void Reverb::reset() noexcept {
    for (auto* set : {&combsL_, &combsR_}) {
        for (Comb& comb : *set) { std::fill(comb.buffer.begin(), comb.buffer.end(), 0.0f); comb.index = 0; comb.store = 0.0f; }
    }
    for (auto* set : {&allpassL_, &allpassR_}) {
        for (AllPass& pass : *set) { std::fill(pass.buffer.begin(), pass.buffer.end(), 0.0f); pass.index = 0; }
    }
    std::fill(preDelay_.begin(), preDelay_.end(), 0.0f);
    preIndex_ = 0;
}

void Reverb::process(float input, float& left, float& right) noexcept {
    const float delayed = preDelay_[preIndex_];
    preDelay_[preIndex_] = input;
    if (++preIndex_ >= preDelay_.size()) preIndex_ = 0;
    const float feed = delayed * 0.015f;
    float outL = 0.0f, outR = 0.0f;
    for (std::size_t i = 0; i < 8; ++i) {
        outL += combsL_[i].process(feed, feedback_, damp_);
        outR += combsR_[i].process(feed, feedback_, damp_);
    }
    for (std::size_t i = 0; i < 4; ++i) {
        outL = allpassL_[i].process(outL);
        outR = allpassR_[i].process(outR);
    }
    left = outL * wet1_ + outR * wet2_;
    right = outR * wet1_ + outL * wet2_;
}

void Delay::prepare(double sampleRate, double timeMs, double feedback, double highCutHz, bool pingPong) {
    length_ = std::max<std::size_t>(1, static_cast<std::size_t>(timeMs * 0.001 * sampleRate));
    left_.assign(length_, 0.0f);
    right_.assign(length_, 0.0f);
    feedback_ = std::clamp(feedback, 0.0, 0.95);
    pingPong_ = pingPong;
    toneL_.set(Biquad::Type::lowPass, sampleRate, highCutHz, 0.7071);
    toneR_.set(Biquad::Type::lowPass, sampleRate, highCutHz, 0.7071);
    reset();
}

void Delay::reset() noexcept {
    std::fill(left_.begin(), left_.end(), 0.0f);
    std::fill(right_.begin(), right_.end(), 0.0f);
    index_ = 0;
    toneL_.reset();
    toneR_.reset();
}

void Delay::process(float input, float& left, float& right) noexcept {
    const float outL = left_[index_];
    const float outR = right_[index_];
    const float darkL = toneL_.process(outL);
    const float darkR = toneR_.process(outR);
    const auto fb = static_cast<float>(feedback_);
    if (pingPong_) {
        left_[index_] = input + darkR * fb;   // the echo crosses sides each repeat
        right_[index_] = darkL * fb;
    } else {
        left_[index_] = input + darkL * fb;
        right_[index_] = input + darkR * fb;
    }
    if (++index_ >= length_) index_ = 0;
    left = outL;
    right = outR;
}

// ------------------------------------------------------------- loudness

LoudnessMeter::LoudnessMeter(double sampleRate, int channels) : channels_(std::clamp(channels, 1, 2)) {
    // BS.1770 K-weighting at any rate, by the bilinear derivation (Brecht De
    // Man) that reproduces the standard's 48 kHz coefficients exactly: a high
    // shelf (+4 dB above ~1.7 kHz) and a high-pass at ~38 Hz.
    double k = std::tan(kPi * 1681.9744509555319 / sampleRate);
    double q = 0.7071752369554193;
    const double vh = std::pow(10.0, 3.99984385397 / 20.0);
    const double vb = std::pow(vh, 0.4996667741545416);
    double a0 = 1.0 + k / q + k * k;
    for (Biquad& stage : stage1_) {
        stage.setCoefficients((vh + vb * k / q + k * k) / a0, 2.0 * (k * k - vh) / a0, (vh - vb * k / q + k * k) / a0,
                              2.0 * (k * k - 1.0) / a0, (1.0 - k / q + k * k) / a0);
    }
    k = std::tan(kPi * 38.13547087613982 / sampleRate);
    q = 0.5003270373238773;
    a0 = 1.0 + k / q + k * k;
    for (Biquad& stage : stage2_) {
        stage.setCoefficients(1.0, -2.0, 1.0, 2.0 * (k * k - 1.0) / a0, (1.0 - k / q + k * k) / a0);
    }
    blockLength_ = static_cast<std::size_t>(std::round(0.4 * sampleRate));
    hop_ = static_cast<std::size_t>(std::round(0.1 * sampleRate));
    squares_.assign(blockLength_, 0.0);
}

void LoudnessMeter::push(const float* left, const float* right, std::size_t frames) {
    for (std::size_t i = 0; i < frames; ++i) {
        double power = 0.0;
        const float l = stage2_[0].process(stage1_[0].process(left[i]));
        power += static_cast<double>(l) * l;
        if (channels_ == 2 && right) {
            const float r = stage2_[1].process(stage1_[1].process(right[i]));
            power += static_cast<double>(r) * r;
        }
        const std::size_t slot = filled_ % blockLength_;
        running_ += power - squares_[slot];
        squares_[slot] = power;
        ++filled_;
        if (filled_ < blockLength_) continue;
        // A 400 ms block when the first one fills, then every 100 ms.
        if (filled_ == blockLength_ || ++sinceBlock_ >= hop_) {
            sinceBlock_ = 0;
            const double meanSquare = std::max(0.0, running_ / static_cast<double>(blockLength_));
            blocks_.push_back(meanSquare);
            maximumMomentary_ = std::max(maximumMomentary_, -0.691 + 10.0 * std::log10(meanSquare + 1e-30));
        }
    }
}

double LoudnessMeter::integratedLufs() const {
    auto loudness = [](double meanSquare) { return -0.691 + 10.0 * std::log10(meanSquare + 1e-30); };
    double sum = 0.0;
    std::size_t count = 0;
    for (double block : blocks_) {
        if (loudness(block) > -70.0) { sum += block; ++count; }
    }
    if (count == 0) return -200.0;
    const double relativeGate = loudness(sum / static_cast<double>(count)) - 10.0;
    sum = 0.0;
    count = 0;
    for (double block : blocks_) {
        const double l = loudness(block);
        if (l > -70.0 && l > relativeGate) { sum += block; ++count; }
    }
    return count == 0 ? -200.0 : loudness(sum / static_cast<double>(count));
}

}  // namespace maqam::dsp
