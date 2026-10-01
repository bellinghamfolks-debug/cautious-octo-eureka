#include "maqam/notes.hpp"

#include "maqam/tuning.hpp"

#include <algorithm>
#include <cmath>

namespace maqam {
namespace {

double median(std::vector<double> values) {
    if (values.empty()) return 0.0;
    const std::size_t middle = values.size() / 2;
    std::nth_element(values.begin(), values.begin() + static_cast<std::ptrdiff_t>(middle), values.end());
    double upper = values[middle];
    if (values.size() % 2 == 1) return upper;
    const double lower = *std::max_element(values.begin(), values.begin() + static_cast<std::ptrdiff_t>(middle));
    return 0.5 * (lower + upper);
}

double percentile(std::vector<double> values, double fraction) {
    if (values.empty()) return 0.0;
    std::sort(values.begin(), values.end());
    const double position = fraction * static_cast<double>(values.size() - 1);
    const auto below = static_cast<std::size_t>(std::floor(position));
    const std::size_t above = std::min(values.size() - 1, below + 1);
    const double weight = position - static_cast<double>(below);
    return values[below] * (1.0 - weight) + values[above] * weight;
}

struct Builder {
    const std::vector<PitchFrame>& track;
    const NoteSegmentConfig& config;
    const std::vector<bool>& valid;
    const std::vector<double>& raw;
    const std::vector<double>& smoothed;
    double hop;
    std::vector<SungNote>& notes;

    // Turns frames [first, last] into a note, if a steady enough part remains.
    void close(std::size_t first, std::size_t last) {
        std::vector<std::size_t> frames;
        for (std::size_t i = first; i <= last && i < track.size(); ++i) {
            if (valid[i]) frames.push_back(i);
        }
        if (frames.empty()) return;
        std::vector<double> values;
        values.reserve(frames.size());
        for (std::size_t i : frames) values.push_back(smoothed[i]);
        const double centre = median(values);

        // The slide into and out of a note belongs to the transition.
        std::size_t head = 0;
        std::size_t tail = frames.size();
        while (head < tail && std::fabs(smoothed[frames[head]] - centre) > config.trimCents) ++head;
        while (tail > head && std::fabs(smoothed[frames[tail - 1]] - centre) > config.trimCents) --tail;
        if (head >= tail) return;
        const std::size_t firstFrame = frames[head];
        const std::size_t lastFrame = frames[tail - 1];
        const double start = track[firstFrame].timeSeconds - hop / 2.0;
        const double end = track[lastFrame].timeSeconds + hop / 2.0;
        if (end - start < config.minimumNoteSeconds) return;

        std::vector<double> held;
        std::vector<double> unsmoothed;
        std::vector<double> times;
        for (std::size_t k = head; k < tail; ++k) {
            held.push_back(smoothed[frames[k]]);
            unsmoothed.push_back(raw[frames[k]]);
            times.push_back(track[frames[k]].timeSeconds);
        }

        SungNote note;
        note.startSeconds = std::max(0.0, start);
        note.endSeconds = end;
        note.cents = median(held);
        note.hz = centsToHz(note.cents, config.referenceHz);
        note.spreadCents = 0.5 * (percentile(held, 0.9) - percentile(held, 0.1));
        note.firstFrame = firstFrame;
        note.frameCount = lastFrame - firstFrame + 1;
        // The median filter flattens vibrato peaks, so vibrato is measured raw.
        measureVibrato(unsmoothed, times, note);
        notes.push_back(note);
    }

    // Vibrato is what is left after removing the note's linear drift: its rate
    // from how often that residue changes sign, its extent from its RMS.
    static void measureVibrato(const std::vector<double>& cents, const std::vector<double>& times, SungNote& note) {
        const std::size_t count = cents.size();
        const double duration = note.endSeconds - note.startSeconds;
        if (count < 8 || duration < 0.35) return;
        double meanTime = 0.0;
        double meanCents = 0.0;
        for (std::size_t i = 0; i < count; ++i) { meanTime += times[i]; meanCents += cents[i]; }
        meanTime /= static_cast<double>(count);
        meanCents /= static_cast<double>(count);
        double covariance = 0.0;
        double variance = 0.0;
        for (std::size_t i = 0; i < count; ++i) {
            covariance += (times[i] - meanTime) * (cents[i] - meanCents);
            variance += (times[i] - meanTime) * (times[i] - meanTime);
        }
        const double slope = variance > 0.0 ? covariance / variance : 0.0;
        double sumSquares = 0.0;
        int sign = 0;
        std::size_t crossings = 0;
        constexpr double kHysteresisCents = 3.0;
        for (std::size_t i = 0; i < count; ++i) {
            const double residual = cents[i] - (meanCents + slope * (times[i] - meanTime));
            sumSquares += residual * residual;
            const int now = residual > kHysteresisCents ? 1 : (residual < -kHysteresisCents ? -1 : 0);
            if (now != 0) {
                if (sign != 0 && now != sign) ++crossings;
                sign = now;
            }
        }
        const double extent = std::sqrt(2.0 * sumSquares / static_cast<double>(count));
        const double span = times.back() - times.front();
        const double rate = span > 0.0 ? 0.5 * static_cast<double>(crossings) / span : 0.0;
        if (rate >= 3.5 && rate <= 9.0 && extent >= 12.0) {
            note.vibratoRateHz = rate;
            note.vibratoExtentCents = extent;
        }
    }
};

}  // namespace

std::vector<SungNote> segmentNotes(const std::vector<PitchFrame>& track, const NoteSegmentConfig& config) {
    std::vector<SungNote> notes;
    const std::size_t count = track.size();
    if (count < 2 || !(config.referenceHz > 0.0)) return notes;
    const double hop = (track.back().timeSeconds - track.front().timeSeconds) / static_cast<double>(count - 1);
    if (!(hop > 0.0)) return notes;

    std::vector<bool> valid(count, false);
    std::vector<double> cents(count, 0.0);
    for (std::size_t i = 0; i < count; ++i) {
        const PitchEstimate& estimate = track[i].estimate;
        valid[i] = estimate.voiced && estimate.frequencyHz > 0.0 && estimate.confidence >= config.minimumConfidence;
        if (valid[i]) cents[i] = hzToCents(estimate.frequencyHz, config.referenceHz);
    }
    // A five-frame median removes single-frame octave slips without blurring slides.
    std::vector<double> smoothed(count, 0.0);
    std::vector<double> window;
    for (std::size_t i = 0; i < count; ++i) {
        if (!valid[i]) continue;
        window.clear();
        const std::size_t from = i >= 2 ? i - 2 : 0;
        const std::size_t to = std::min(count - 1, i + 2);
        for (std::size_t j = from; j <= to; ++j) {
            if (valid[j]) window.push_back(cents[j]);
        }
        smoothed[i] = median(window);
    }

    // Where notes split is decided on the centre of the pitch. Where the pitch
    // is locally flat (a held note, however short, as in a fast melisma) the
    // centre is the pitch itself; where it keeps moving, it is the mean over
    // one vibrato cycle, from which vibrato averages out and a step does not.
    std::vector<double> centre(count, 0.0);
    const auto halfCycle = static_cast<std::size_t>(std::max(1.0, std::round(0.1 / hop)));
    const auto flatReach = static_cast<std::size_t>(std::max(1.0, std::round(0.025 / hop)));
    constexpr double kFlatCents = 10.0;
    for (std::size_t i = 0; i < count; ++i) {
        if (!valid[i]) continue;
        if (i >= flatReach && i + flatReach < count) {
            double low = smoothed[i];
            double high = smoothed[i];
            bool flat = true;
            for (std::size_t j = i - flatReach; flat && j <= i + flatReach; ++j) {
                flat = valid[j];
                low = std::min(low, smoothed[j]);
                high = std::max(high, smoothed[j]);
            }
            if (flat && high - low < kFlatCents) {
                centre[i] = smoothed[i];
                continue;
            }
        }
        double sum = 0.0;
        std::size_t used = 0;
        const std::size_t from = i >= halfCycle ? i - halfCycle : 0;
        const std::size_t to = std::min(count - 1, i + halfCycle);
        for (std::size_t j = from; j <= to; ++j) {
            if (valid[j]) { sum += smoothed[j]; ++used; }
        }
        centre[i] = sum / static_cast<double>(used);
    }

    Builder builder{track, config, valid, cents, smoothed, hop, notes};
    const auto anchorFrames = static_cast<std::size_t>(std::max(3.0, std::round(0.3 / hop)));
    const auto holdFrames = static_cast<std::size_t>(std::max(1.0, std::round(config.splitHoldSeconds / hop)));
    const auto shiftFrames = static_cast<std::size_t>(std::max(3.0, std::round(config.shiftWindowSeconds / hop)));
    const auto shiftAnchorFrames = static_cast<std::size_t>(std::max(3.0, std::round(0.4 / hop)));

    bool inNote = false;
    std::size_t segmentStart = 0;
    std::size_t lastValid = 0;
    std::size_t outsideStart = 0;
    std::size_t outsideCount = 0;
    std::vector<double> recent;        // centre cents of the current note, newest last
    std::vector<std::size_t> recentAt;  // the frame each of those came from

    for (std::size_t i = 0; i < count; ++i) {
        if (!valid[i]) {
            if (inNote && track[i].timeSeconds - track[lastValid].timeSeconds > config.maximumGapSeconds) {
                builder.close(segmentStart, lastValid);
                inNote = false;
            }
            continue;
        }
        if (!inNote) {
            inNote = true;
            segmentStart = i;
            outsideCount = 0;
            recent.assign(1, centre[i]);
            recentAt.assign(1, i);
            lastValid = i;
            continue;
        }
        const std::size_t tailSize = std::min(recent.size(), anchorFrames);
        const double anchor = median(std::vector<double>(recent.end() - static_cast<std::ptrdiff_t>(tailSize), recent.end()));
        if (std::fabs(centre[i] - anchor) > config.splitCents) {
            if (outsideCount == 0) outsideStart = i;
            ++outsideCount;
            if (outsideCount >= holdFrames) {
                // The voice moved and stayed: the old note ends where it left.
                builder.close(segmentStart, outsideStart - 1);
                segmentStart = outsideStart;
                recent.clear();
                recentAt.clear();
                for (std::size_t j = outsideStart; j <= i; ++j) {
                    if (valid[j]) { recent.push_back(centre[j]); recentAt.push_back(j); }
                }
                outsideCount = 0;
                lastValid = i;
                continue;
            }
        } else {
            outsideCount = 0;
        }
        recent.push_back(centre[i]);
        recentAt.push_back(i);
        lastValid = i;

        // Sustained small step: the last window's mean has left the mean held
        // before it. Means over windows longer than a vibrato cycle cancel the
        // vibrato; a median over a part-cycle would not.
        if (recent.size() >= shiftFrames + shiftAnchorFrames) {
            const std::size_t windowStart = recent.size() - shiftFrames;
            double windowMean = 0.0;
            for (std::size_t k = windowStart; k < recent.size(); ++k) windowMean += recent[k];
            windowMean /= static_cast<double>(shiftFrames);
            double before = 0.0;
            for (std::size_t k = windowStart - shiftAnchorFrames; k < windowStart; ++k) before += recent[k];
            before /= static_cast<double>(shiftAnchorFrames);
            const double shift = windowMean - before;
            if (std::fabs(shift) > config.shiftCents) {
                // The new note starts at the first frame past half the step.
                std::size_t split = windowStart;
                while (split + 1 < recent.size() && (recent[split] - before) * (shift > 0 ? 1.0 : -1.0) < std::fabs(shift) / 2.0) {
                    ++split;
                }
                const std::size_t splitFrame = recentAt[split];
                builder.close(segmentStart, splitFrame - 1);
                segmentStart = splitFrame;
                recent.erase(recent.begin(), recent.begin() + static_cast<std::ptrdiff_t>(split));
                recentAt.erase(recentAt.begin(), recentAt.begin() + static_cast<std::ptrdiff_t>(split));
                outsideCount = 0;
            }
        }
    }
    if (inNote) builder.close(segmentStart, lastValid);
    return notes;
}

IntonationSummary evaluateIntonation(const Scale& scale, double tonicHz, const std::vector<SungNote>& notes,
                                     double toleranceCents, std::vector<NoteMatch>* matches) {
    IntonationSummary summary;
    if (matches) matches->clear();
    if (!(tonicHz > 0.0) || scale.degreeCount == 0) return summary;
    double inTuneSeconds = 0.0;
    double absoluteDeviation = 0.0;
    std::array<double, kMaxDegrees> weightedDeviation{};
    for (const SungNote& note : notes) {
        const double seconds = std::max(0.0, note.endSeconds - note.startSeconds);
        NoteMatch match;
        match.target = nearestTarget(scale, hzToCents(note.hz, tonicHz));
        match.inTune = std::fabs(match.target.deviationCents) <= toleranceCents;
        if (matches) matches->push_back(match);
        ++summary.noteCount;
        summary.totalSeconds += seconds;
        absoluteDeviation += std::fabs(match.target.deviationCents) * seconds;
        if (match.inTune) {
            ++summary.inTuneCount;
            inTuneSeconds += seconds;
        }
        if (match.target.degreeIndex >= 0 && static_cast<std::size_t>(match.target.degreeIndex) < kMaxDegrees) {
            const auto degree = static_cast<std::size_t>(match.target.degreeIndex);
            ++summary.degrees[degree].noteCount;
            summary.degrees[degree].seconds += seconds;
            weightedDeviation[degree] += match.target.deviationCents * seconds;
        }
    }
    if (summary.totalSeconds > 0.0) {
        summary.inTuneFraction = inTuneSeconds / summary.totalSeconds;
        summary.meanAbsoluteDeviationCents = absoluteDeviation / summary.totalSeconds;
    }
    for (std::size_t degree = 0; degree < kMaxDegrees; ++degree) {
        if (summary.degrees[degree].seconds > 0.0) {
            summary.degrees[degree].meanDeviationCents = weightedDeviation[degree] / summary.degrees[degree].seconds;
        }
    }
    return summary;
}

}  // namespace maqam
