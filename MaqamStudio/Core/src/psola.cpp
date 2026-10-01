#include "maqam/psola.hpp"

#include <algorithm>
#include <cmath>

namespace maqam {
namespace {

constexpr double kPi = 3.14159265358979323846;
constexpr double kUnvoicedStepSeconds = 0.005;

}  // namespace

MarkFinder::MarkFinder(double sampleRate, double firstTime, double hopSeconds, std::vector<float> trackHz)
    : sampleRate_(sampleRate > 0.0 ? sampleRate : 48000.0),
      firstTime_(firstTime),
      hop_(hopSeconds > 0.0 ? hopSeconds : 0.01),
      track_(std::move(trackHz)) {}

double MarkFinder::hzAt(double samplePosition) const {
    if (track_.empty()) return 0.0;
    const double frame = (samplePosition / sampleRate_ - firstTime_) / hop_;
    const auto index = static_cast<std::size_t>(std::clamp(std::llround(frame), 0LL, static_cast<long long>(track_.size() - 1)));
    return track_[index];
}

void MarkFinder::push(const float* mono, std::size_t frames) {
    if (!mono || frames == 0) return;
    buffer_.insert(buffer_.end(), mono, mono + frames);
    total_ += frames;
    while (advance(false)) {}
    // Keep only what the next search can reach: from just before the last mark.
    if (!marks_.empty()) {
        const auto keepFrom = static_cast<std::uint64_t>(std::max(0.0, std::floor(marks_.back().position) - 2.0));
        if (keepFrom > bufferStart_) {
            const std::uint64_t drop = std::min<std::uint64_t>(keepFrom - bufferStart_, buffer_.size());
            buffer_.erase(buffer_.begin(), buffer_.begin() + static_cast<std::ptrdiff_t>(drop));
            bufferStart_ += drop;
        }
    }
}

bool MarkFinder::advance(bool /*final*/) {
    if (marks_.empty()) {
        if (total_ == 0) return false;
        marks_.push_back({0.0, false});
        return true;
    }
    const PitchMark last = marks_.back();
    const double hz = hzAt(last.position);
    if (!(hz > 0.0)) {
        const double next = last.position + std::round(kUnvoicedStepSeconds * sampleRate_);
        if (next >= static_cast<double>(total_)) return false;
        marks_.push_back({next, false});
        return true;
    }
    const double period = sampleRate_ / hz;
    // Entering voicing: the first peak within one period. Inside it: the peak
    // nearest one period on, which keeps every grain on the same part of the cycle.
    const double low = last.voiced ? last.position + 0.85 * period : last.position + 1.0;
    const double high = last.voiced ? last.position + 1.15 * period : last.position + period;
    const auto first = static_cast<std::uint64_t>(std::ceil(low));
    const auto end = static_cast<std::uint64_t>(std::floor(high));
    // Wait for the whole search window; at the end of the signal, stop here
    // and let finish() close the sequence.
    if (end >= total_) return false;
    std::uint64_t best = first;
    float bestValue = -1e30f;
    for (std::uint64_t j = std::max(first, bufferStart_); j <= end; ++j) {
        const float value = buffer_[static_cast<std::size_t>(j - bufferStart_)];
        if (value > bestValue) { bestValue = value; best = j; }
    }
    marks_.push_back({static_cast<double>(best), true});
    return true;
}

std::vector<PitchMark> MarkFinder::finish() {
    while (advance(true)) {}
    if (total_ > 0 && (marks_.empty() || marks_.back().position < static_cast<double>(total_))) {
        marks_.push_back({static_cast<double>(total_), false});
    }
    return marks_;
}

GrainPlan planGrains(const std::vector<PitchMark>& marks, const std::vector<double>& shiftCents, double firstTime,
                     double hopSeconds, double sampleRate, std::uint64_t totalSamples, double formantShiftCents,
                     bool preserveFormants) {
    GrainPlan plan;
    plan.totalSamples = totalSamples;
    if (marks.size() < 2 || !(sampleRate > 0.0)) return plan;
    const double hop = hopSeconds > 0.0 ? hopSeconds : 0.01;

    auto shiftAt = [&](double position) {
        if (shiftCents.empty()) return 0.0;
        const double frame = (position / sampleRate - firstTime) / hop;
        if (frame <= 0.0) return shiftCents.front();
        const auto lower = static_cast<std::size_t>(frame);
        if (lower + 1 >= shiftCents.size()) return shiftCents.back();
        const double weight = frame - static_cast<double>(lower);
        return shiftCents[lower] * (1.0 - weight) + shiftCents[lower + 1] * weight;
    };
    const double formant = std::exp2(formantShiftCents / 1200.0);
    const std::size_t count = marks.size();

    double position = marks.front().position;
    std::size_t nearest = 0;
    while (position <= static_cast<double>(totalSamples)) {
        // The analysis mark nearest in time: the output keeps the input's timing.
        while (nearest + 1 < count && std::fabs(marks[nearest + 1].position - position) <= std::fabs(marks[nearest].position - position)) {
            ++nearest;
        }
        while (nearest > 0 && std::fabs(marks[nearest - 1].position - position) < std::fabs(marks[nearest].position - position)) {
            --nearest;
        }
        const std::size_t i = nearest;
        const double right = i + 1 < count ? marks[i + 1].position - marks[i].position : marks[i].position - marks[i - 1].position;
        const double left = i > 0 ? marks[i].position - marks[i - 1].position : right;
        const bool voiced = marks[i].voiced && i + 1 < count;
        const double ratio = voiced ? std::exp2(shiftAt(position) / 1200.0) : 1.0;
        const double factor = voiced ? (preserveFormants ? formant : ratio * formant) : 1.0;

        Grain grain;
        grain.outputCentre = position;
        grain.inputCentre = marks[i].position;
        grain.inputLeft = std::max(1.0, left);
        grain.inputRight = std::max(1.0, right);
        grain.factor = factor;
        // Grain density grows with the pitch ratio and shrinks with formant
        // stretching; this keeps the level where it was.
        grain.gain = factor / ratio;
        plan.grains.push_back(grain);
        plan.maximumOutputReach = std::max(plan.maximumOutputReach, std::max(grain.inputLeft, grain.inputRight) / factor);
        position += std::max(1.0, voiced ? right / ratio : right);
    }
    return plan;
}

namespace {

std::pair<std::size_t, std::size_t> grainsOverlapping(const GrainPlan& plan, double from, double to) {
    const double reach = plan.maximumOutputReach + 1.0;
    const auto begin = std::lower_bound(plan.grains.begin(), plan.grains.end(), from - reach,
                                        [](const Grain& grain, double value) { return grain.outputCentre < value; });
    const auto end = std::upper_bound(begin, plan.grains.end(), to + reach,
                                      [](double value, const Grain& grain) { return value < grain.outputCentre; });
    return {static_cast<std::size_t>(begin - plan.grains.begin()), static_cast<std::size_t>(end - plan.grains.begin())};
}

}  // namespace

void inputRangeFor(const GrainPlan& plan, std::int64_t outputStart, std::size_t outputFrames, std::int64_t& start,
                   std::int64_t& end) {
    const auto [first, last] = grainsOverlapping(plan, static_cast<double>(outputStart),
                                                 static_cast<double>(outputStart) + static_cast<double>(outputFrames));
    if (first >= last) { start = end = outputStart; return; }
    double low = 1e300;
    double high = -1e300;
    for (std::size_t g = first; g < last; ++g) {
        low = std::min(low, plan.grains[g].inputCentre - plan.grains[g].inputLeft);
        high = std::max(high, plan.grains[g].inputCentre + plan.grains[g].inputRight);
    }
    start = std::max<std::int64_t>(0, static_cast<std::int64_t>(std::floor(low)) - 1);
    end = static_cast<std::int64_t>(std::ceil(high)) + 2;
}

void renderBlock(const GrainPlan& plan, const float* const* input, int channels, std::int64_t inputStart,
                 std::size_t inputFrames, std::int64_t outputStart, std::size_t outputFrames, float* const* output) {
    if (!output || channels <= 0) return;
    for (int c = 0; c < channels; ++c) std::fill(output[c], output[c] + outputFrames, 0.0f);
    if (!input || outputFrames == 0) return;
    const double blockStart = static_cast<double>(outputStart);
    const double blockEnd = blockStart + static_cast<double>(outputFrames);
    const auto [first, last] = grainsOverlapping(plan, blockStart, blockEnd);
    const auto inputLength = static_cast<std::int64_t>(inputFrames);

    for (std::size_t g = first; g < last; ++g) {
        const Grain& grain = plan.grains[g];
        const double reachLeft = grain.inputLeft / grain.factor;
        const double reachRight = grain.inputRight / grain.factor;
        const auto from = static_cast<std::int64_t>(std::max(blockStart, std::ceil(grain.outputCentre - reachLeft)));
        const auto to = static_cast<std::int64_t>(std::min(blockEnd - 1.0, std::floor(grain.outputCentre + reachRight)));
        for (std::int64_t o = from; o <= to; ++o) {
            const double offset = (static_cast<double>(o) - grain.outputCentre) * grain.factor;
            const double shape = offset < 0.0 ? offset / grain.inputLeft : offset / grain.inputRight;
            if (shape <= -1.0 || shape >= 1.0) continue;
            const double window = 0.5 * (1.0 + std::cos(kPi * shape)) * grain.gain;
            const double source = grain.inputCentre + offset - static_cast<double>(inputStart);
            const auto index = static_cast<std::int64_t>(std::floor(source));
            const double fraction = source - static_cast<double>(index);
            const std::size_t out = static_cast<std::size_t>(o - outputStart);
            for (int c = 0; c < channels; ++c) {
                const float a = index >= 0 && index < inputLength ? input[c][index] : 0.0f;
                const float b = index + 1 >= 0 && index + 1 < inputLength ? input[c][index + 1] : 0.0f;
                output[c][out] += static_cast<float>(window * (a + (b - a) * fraction));
            }
        }
    }
}

}  // namespace maqam
