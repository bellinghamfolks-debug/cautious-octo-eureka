// Time-domain pitch-synchronous overlap-add (TD-PSOLA) pitch shifting.
//
// Why PSOLA for an Arabic vocal tuner: each grain is one period of the voice,
// so the spectral envelope (the formants, the vowel, the singer's colour) is
// carried over unchanged while the period spacing changes. Formants can still
// be moved on purpose by resampling the grains.
//
// Three steps, each usable on long files without holding them in memory:
//  1. MarkFinder streams the (mono) signal and places one analysis mark per
//     period in voiced regions, on the waveform's main peak, and every 5 ms in
//     unvoiced regions.
//  2. planGrains turns marks and a shift curve into synthesis grains.
//  3. renderBlock produces any block of output from the matching block of
//     input, sample-identical to rendering the whole file at once.
//
// Windows are asymmetric Hann halves spanning the neighbouring marks, so with
// no shift consecutive grains sum to exactly one: an unshifted file comes back
// unchanged.
#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

namespace maqam {

struct PitchMark {
    double position = 0.0;  // sample index
    bool voiced = false;
};

class MarkFinder {
public:
    // `trackHz` is the pitch track (0 = unvoiced), frames `hopSeconds` apart
    // starting at `firstTime`.
    MarkFinder(double sampleRate, double firstTime, double hopSeconds, std::vector<float> trackHz);

    void push(const float* mono, std::size_t frames);
    // Places the remaining marks and closes the sequence at the end of the signal.
    std::vector<PitchMark> finish();

private:
    double hzAt(double samplePosition) const;
    bool advance(bool final);

    double sampleRate_;
    double firstTime_;
    double hop_;
    std::vector<float> track_;
    std::vector<float> buffer_;
    std::uint64_t bufferStart_ = 0;  // absolute index of buffer_[0]
    std::uint64_t total_ = 0;        // samples pushed so far
    std::vector<PitchMark> marks_;
};

struct Grain {
    double outputCentre = 0.0;  // where the grain is placed
    double inputCentre = 0.0;   // the analysis mark it is taken from
    double inputLeft = 0.0;     // window reach before the mark, in input samples
    double inputRight = 0.0;    // and after it
    double factor = 1.0;        // input samples per output sample inside the grain (formant scaling)
    double gain = 1.0;
};

struct GrainPlan {
    std::vector<Grain> grains;  // sorted by outputCentre
    double maximumOutputReach = 0.0;
    std::uint64_t totalSamples = 0;
};

// `shiftCents` is per pitch frame (same timing as the track the marks used).
// `formantShiftCents` moves formants on purpose; with `preserveFormants`
// false they move with the pitch (the "chipmunk" sound).
GrainPlan planGrains(const std::vector<PitchMark>& marks, const std::vector<double>& shiftCents, double firstTime,
                     double hopSeconds, double sampleRate, std::uint64_t totalSamples, double formantShiftCents,
                     bool preserveFormants);

// The input range [start, end) that output block [outputStart, outputStart + outputFrames) needs.
void inputRangeFor(const GrainPlan& plan, std::int64_t outputStart, std::size_t outputFrames, std::int64_t& start,
                   std::int64_t& end);

// Renders one output block. `input[c]` holds `inputFrames` samples of channel c
// starting at absolute sample `inputStart` (it should cover inputRangeFor;
// anything outside it reads as silence). `output[c]` receives `outputFrames`.
void renderBlock(const GrainPlan& plan, const float* const* input, int channels, std::int64_t inputStart,
                 std::size_t inputFrames, std::int64_t outputStart, std::size_t outputFrames, float* const* output);

}  // namespace maqam
