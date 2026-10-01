#include "maqam/correction.hpp"

#include "maqam/tuning.hpp"

#include <algorithm>
#include <cmath>
#include <limits>

namespace maqam {
namespace {

constexpr double kTwoPi = 6.283185307179586;

}  // namespace

CorrectionResult computeCorrection(const std::vector<PitchFrame>& track, const std::vector<SungNote>& notes,
                                   const Scale& scale, double tonicHz, const CorrectionSettings& settings,
                                   const std::vector<NoteOverride>& overrides) {
    CorrectionResult result;
    const std::size_t count = track.size();
    result.shiftCents.assign(count, 0.0);
    result.noteTargetCents.assign(notes.size(), std::numeric_limits<double>::quiet_NaN());
    if (count == 0 || !(tonicHz > 0.0) || scale.degreeCount == 0) return result;
    const double hop = count > 1 ? (track.back().timeSeconds - track.front().timeSeconds) / static_cast<double>(count - 1) : 0.01;
    if (!(hop > 0.0)) return result;

    std::vector<bool> voiced(count, false);
    std::vector<double> cents(count, 0.0);
    for (std::size_t i = 0; i < count; ++i) {
        voiced[i] = track[i].estimate.voiced && track[i].estimate.frequencyHz > 0.0;
        if (voiced[i]) cents[i] = hzToCents(track[i].estimate.frequencyHz, tonicHz);
    }

    // Frames owned by a note get a value; the rest are filled in afterwards.
    std::vector<bool> decided(count, false);
    std::vector<bool> locked(count, false);  // bypassed notes: exactly as sung
    const double strength = std::clamp(settings.strength, 0.0, 1.0);
    const double drift = std::clamp(settings.driftCorrection, 0.0, 1.0);
    const double humanize = std::clamp(settings.humanize, 0.0, 1.0);
    const auto halfCycle = static_cast<std::size_t>(std::max(1.0, std::round(0.125 / hop)));

    for (std::size_t k = 0; k < notes.size(); ++k) {
        const SungNote& note = notes[k];
        // Frames of this note, by time.
        std::vector<std::size_t> frames;
        for (std::size_t i = 0; i < count; ++i) {
            const double t = track[i].timeSeconds;
            if (t >= note.startSeconds && t < note.endSeconds && voiced[i]) frames.push_back(i);
        }
        if (frames.empty()) continue;
        const NoteOverride override = k < overrides.size() ? overrides[k] : NoteOverride{};
        if (override.bypass) {
            for (std::size_t i : frames) { decided[i] = true; locked[i] = true; }  // shift stays 0
            continue;
        }
        const double centre = hzToCents(note.hz, tonicHz);
        double target = 0.0;
        if (override.hasTarget) {
            target = override.targetCentsFromTonic;
        } else {
            const TargetMatch match = nearestTarget(scale, centre);
            if (match.degreeIndex < 0) continue;
            target = match.targetCents;
        }
        result.noteTargetCents[k] = target;

        // The note's moving centre (vibrato averaged out over exactly one of its
        // cycles when it has vibrato) and the residue around it.
        const std::size_t reach = note.vibratoRateHz > 0.0
            ? static_cast<std::size_t>(std::max(1.0, std::round(0.5 / note.vibratoRateHz / hop)))
            : halfCycle;
        std::vector<double> moving(frames.size(), 0.0);
        for (std::size_t a = 0; a < frames.size(); ++a) {
            double sum = 0.0;
            std::size_t used = 0;
            for (std::size_t b = 0; b < frames.size(); ++b) {
                const std::size_t distance = frames[a] > frames[b] ? frames[a] - frames[b] : frames[b] - frames[a];
                if (distance <= reach) { sum += cents[frames[b]]; ++used; }
            }
            moving[a] = sum / static_cast<double>(used);
        }
        double residueEnergy = 0.0;
        for (std::size_t a = 0; a < frames.size(); ++a) {
            const double residue = cents[frames[a]] - moving[a];
            residueEnergy += residue * residue;
        }
        const double vibratoAmplitude = std::sqrt(2.0 * residueEnergy / static_cast<double>(frames.size()));

        const double duration = note.endSeconds - note.startSeconds;
        const bool held = duration > 0.3;
        // Humanize: on held notes, keep part of what strong settings would remove,
        // and let the correction arrive more slowly.
        double keepVibrato = std::max(0.0, settings.vibratoAmount);
        if (held && keepVibrato < 1.0) keepVibrato += humanize * (1.0 - keepVibrato);
        const double keepDrift = 1.0 - drift * (held ? 1.0 - 0.5 * humanize : 1.0);
        const double tau = std::max(0.0, settings.retuneMs) / 1000.0 * (held ? 1.0 + 2.0 * humanize : 1.0);

        for (std::size_t a = 0; a < frames.size(); ++a) {
            const std::size_t i = frames[a];
            const double t = track[i].timeSeconds;
            double residue = cents[i] - moving[a];
            if (settings.vibratoRateHz > 0.0) {
                residue = vibratoAmplitude * std::sin(kTwoPi * settings.vibratoRateHz * (t - note.startSeconds));
            }
            const double desired = target + keepDrift * (moving[a] - centre) + keepVibrato * residue;
            const double ramp = tau > 0.0 ? 1.0 - std::exp(-(t - note.startSeconds) / tau) : 1.0;
            result.shiftCents[i] = strength * ramp * (desired - cents[i]);
            decided[i] = true;
        }
    }

    // Between notes: interpolate the neighbouring notes' shifts, so slides and
    // ornaments keep their shape; optionally pull voiced frames to the scale.
    const double sensitivity = std::clamp(settings.transitionSensitivity, 0.0, 1.0);
    std::size_t i = 0;
    while (i < count) {
        if (decided[i]) { ++i; continue; }
        const std::size_t gapStart = i;
        while (i < count && !decided[i]) ++i;
        const std::size_t gapEnd = i;  // exclusive
        const bool hasBefore = gapStart > 0;
        const bool hasAfter = gapEnd < count;
        const double before = hasBefore ? result.shiftCents[gapStart - 1] : (hasAfter ? result.shiftCents[gapEnd] : 0.0);
        const double after = hasAfter ? result.shiftCents[gapEnd] : before;
        for (std::size_t j = gapStart; j < gapEnd; ++j) {
            const double fraction = static_cast<double>(j - gapStart + 1) / static_cast<double>(gapEnd - gapStart + 1);
            double shift = before + (after - before) * fraction;
            if (voiced[j] && sensitivity > 0.0) {
                const TargetMatch match = nearestTarget(scale, cents[j]);
                if (match.degreeIndex >= 0) {
                    shift = (1.0 - sensitivity) * shift + sensitivity * strength * (match.targetCents - cents[j]);
                }
            }
            result.shiftCents[j] = shift;
        }
    }

    // Final smoothing against zipper noise, then the safety clamp.
    const auto half = static_cast<std::size_t>(std::round(std::max(0.0, settings.smoothingMs) / 1000.0 / hop / 2.0));
    if (half > 0) {
        std::vector<double> smoothed(count, 0.0);
        for (std::size_t j = 0; j < count; ++j) {
            const std::size_t from = j >= half ? j - half : 0;
            const std::size_t to = std::min(count - 1, j + half);
            double sum = 0.0;
            for (std::size_t m = from; m <= to; ++m) sum += result.shiftCents[m];
            smoothed[j] = sum / static_cast<double>(to - from + 1);
        }
        result.shiftCents.swap(smoothed);
        for (std::size_t j = 0; j < count; ++j) {
            if (locked[j]) result.shiftCents[j] = 0.0;
        }
    }
    const double limit = std::max(0.0, settings.maximumShiftCents);
    for (double& shift : result.shiftCents) shift = std::clamp(shift, -limit, limit);
    return result;
}

}  // namespace maqam
