#include "maqam/detection.hpp"

#include "maqam/tuning.hpp"

#include <algorithm>
#include <cmath>

namespace maqam {
namespace {

struct Sung {
    double absoluteCents;  // from A4 = 440
    double pitchClass;     // [0, 1200)
    double weight;         // seconds, capped
    double start;
};

struct PitchClass {
    double centre = 0.0;   // [0, 1200)
    double weight = 0.0;
    std::vector<std::size_t> members;
};

double wrap(double cents) {
    double value = std::fmod(cents, 1200.0);
    if (value < 0.0) value += 1200.0;
    return value;
}

double circularDistance(double a, double b) {
    const double difference = std::fabs(wrap(a) - wrap(b));
    return std::min(difference, 1200.0 - difference);
}

// Weighted circular mean of pitch classes around `reference`.
double circularMean(const std::vector<Sung>& sung, const std::vector<std::size_t>& members, double reference) {
    double sum = 0.0;
    double weight = 0.0;
    for (std::size_t i : members) {
        double offset = sung[i].pitchClass - reference;
        if (offset > 600.0) offset -= 1200.0;
        if (offset < -600.0) offset += 1200.0;
        sum += offset * sung[i].weight;
        weight += sung[i].weight;
    }
    return wrap(reference + (weight > 0.0 ? sum / weight : 0.0));
}

std::vector<PitchClass> clusterPitchClasses(const std::vector<Sung>& sung, double clusterCents) {
    std::vector<std::size_t> order(sung.size());
    for (std::size_t i = 0; i < order.size(); ++i) order[i] = i;
    std::sort(order.begin(), order.end(), [&](std::size_t a, std::size_t b) { return sung[a].pitchClass < sung[b].pitchClass; });
    std::vector<PitchClass> classes;
    for (std::size_t i : order) {
        if (!classes.empty() && circularDistance(sung[i].pitchClass, classes.back().centre) <= clusterCents) {
            classes.back().members.push_back(i);
        } else {
            classes.push_back({});
            classes.back().members.push_back(i);
        }
        PitchClass& current = classes.back();
        current.centre = circularMean(sung, current.members, sung[current.members.front()].pitchClass);
    }
    // The circle closes: the last class may continue the first.
    if (classes.size() > 1 && circularDistance(classes.front().centre, classes.back().centre) <= clusterCents) {
        for (std::size_t i : classes.back().members) classes.front().members.push_back(i);
        classes.pop_back();
        classes.front().centre = circularMean(sung, classes.front().members, classes.front().centre);
    }
    for (PitchClass& pitchClass : classes) {
        pitchClass.weight = 0.0;
        for (std::size_t i : pitchClass.members) pitchClass.weight += sung[i].weight;
    }
    return classes;
}

// The tonic as sung: the lowest octave in which this pitch class carries a
// real share of its time, averaged there.
double tonicHzOf(const std::vector<Sung>& sung, const PitchClass& pitchClass) {
    std::vector<std::pair<long, double>> octaves;  // octave index, weight
    for (std::size_t i : pitchClass.members) {
        const long octave = std::lround((sung[i].absoluteCents - pitchClass.centre) / 1200.0);
        auto found = std::find_if(octaves.begin(), octaves.end(), [&](const auto& entry) { return entry.first == octave; });
        if (found == octaves.end()) octaves.push_back({octave, sung[i].weight}); else found->second += sung[i].weight;
    }
    std::sort(octaves.begin(), octaves.end());
    long chosen = octaves.front().first;
    for (const auto& [octave, weight] : octaves) {
        if (weight >= 0.25 * pitchClass.weight) { chosen = octave; break; }
    }
    double sum = 0.0;
    double weight = 0.0;
    for (std::size_t i : pitchClass.members) {
        if (std::lround((sung[i].absoluteCents - pitchClass.centre) / 1200.0) != chosen) continue;
        sum += sung[i].absoluteCents * sung[i].weight;
        weight += sung[i].weight;
    }
    return centsToHz(sum / weight, 440.0);
}

}  // namespace

MaqamDetection detectMaqam(const std::vector<SungNote>& notes, const std::vector<Scale>& scales,
                           const DetectionConfig& config) {
    MaqamDetection detection;
    std::vector<Sung> sung;
    for (const SungNote& note : notes) {
        const double seconds = note.endSeconds - note.startSeconds;
        if (!(note.hz > 0.0) || !(seconds > 0.0)) continue;
        const double absolute = hzToCents(note.hz, 440.0);
        sung.push_back({absolute, wrap(absolute), std::min(seconds, config.noteWeightCapSeconds), note.startSeconds});
        detection.sungSeconds += seconds;
    }
    if (sung.empty() || scales.empty()) return detection;
    double totalWeight = 0.0;
    for (const Sung& note : sung) totalWeight += note.weight;

    std::vector<PitchClass> classes = clusterPitchClasses(sung, config.clusterCents);
    classes.erase(std::remove_if(classes.begin(), classes.end(),
                                 [&](const PitchClass& c) { return c.weight < 0.02 * totalWeight; }),
                  classes.end());
    detection.pitchClasses = classes.size();
    detection.enoughData = detection.sungSeconds >= config.minimumSeconds
        && detection.pitchClasses >= config.minimumPitchClasses;
    if (classes.empty()) return detection;

    // Tonic evidence, shared by every scale.
    const auto last = std::max_element(sung.begin(), sung.end(),
                                       [](const Sung& a, const Sung& b) { return a.start < b.start; });
    std::vector<double> sortedCents;
    for (const Sung& note : sung) sortedCents.push_back(note.absoluteCents);
    std::sort(sortedCents.begin(), sortedCents.end());
    const double lowCents = sortedCents[sortedCents.size() / 10];

    struct Scored {
        std::size_t scale;
        std::size_t tonicClass;
        double score;
        double deviation;
    };
    std::vector<Scored> scored;
    const double twoSigmaSquared = 2.0 * config.sigmaCents * config.sigmaCents;
    for (std::size_t t = 0; t < classes.size(); ++t) {
        const PitchClass& tonic = classes[t];
        if (tonic.weight < 0.04 * totalWeight) continue;
        double evidence = config.tonicShareBonus * tonic.weight / totalWeight;
        if (circularDistance(last->pitchClass, tonic.centre) <= config.clusterCents) evidence += config.finalNoteBonus;
        if (circularDistance(wrap(lowCents), tonic.centre) <= config.clusterCents) evidence += config.lowNoteBonus;

        for (std::size_t s = 0; s < scales.size(); ++s) {
            const Scale& scale = scales[s];
            if (scale.degreeCount == 0) continue;
            double fit = 0.0;
            double deviation = 0.0;
            for (const Sung& note : sung) {
                const double relative = wrap(note.pitchClass - tonic.centre);
                double nearest = 1200.0;
                for (std::size_t d = 0; d < scale.degreeCount; ++d) {
                    const ScaleDegree& degree = scale.degrees[d];
                    nearest = std::min(nearest, circularDistance(relative, degree.cents));
                    for (std::size_t a = 0; a < degree.alternateCount; ++a) {
                        nearest = std::min(nearest, circularDistance(relative, degree.alternates[a]));
                    }
                }
                fit += note.weight * std::max(config.floorLogLikelihood, -nearest * nearest / twoSigmaSquared);
                deviation += note.weight * nearest;
            }
            scored.push_back({s, t, fit + evidence, deviation / totalWeight});
        }
    }
    if (scored.empty()) return detection;

    const double best = std::max_element(scored.begin(), scored.end(),
                                         [](const Scored& a, const Scored& b) { return a.score < b.score; })->score;
    double normaliser = 0.0;
    for (const Scored& entry : scored) normaliser += std::exp(entry.score - best);
    std::vector<double> tonicHz(classes.size(), 0.0);
    for (const Scored& entry : scored) {
        if (tonicHz[entry.tonicClass] == 0.0) tonicHz[entry.tonicClass] = tonicHzOf(sung, classes[entry.tonicClass]);
        detection.ranked.push_back({entry.scale, tonicHz[entry.tonicClass], std::exp(entry.score - best) / normaliser,
                                    entry.deviation});
    }
    std::vector<std::size_t> classOf(detection.ranked.size());
    for (std::size_t i = 0; i < scored.size(); ++i) classOf[i] = scored[i].tonicClass;
    std::vector<std::size_t> order(detection.ranked.size());
    for (std::size_t i = 0; i < order.size(); ++i) order[i] = i;
    std::stable_sort(order.begin(), order.end(), [&](std::size_t a, std::size_t b) {
        return detection.ranked[a].probability > detection.ranked[b].probability;
    });
    std::vector<MaqamCandidate> ranked;
    for (std::size_t i : order) ranked.push_back(detection.ranked[i]);
    const std::size_t bestClass = classOf[order.front()];
    for (std::size_t i = 0; i < scored.size(); ++i) {
        if (classOf[i] == bestClass) detection.tonicConfidence += detection.ranked[i].probability;
    }
    detection.ranked = std::move(ranked);
    detection.tonicHz = detection.ranked.front().tonicHz;
    return detection;
}

}  // namespace maqam
