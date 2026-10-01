#include "maqam_core.h"

#include "maqam/levels.hpp"
#include "maqam/maqam.hpp"
#include "maqam/notes.hpp"
#include "maqam/pitch_detector.hpp"
#include "maqam/tuning.hpp"

#include <cstring>
#include <new>
#include <vector>

struct MQLevelAccumulator {
    MQLevelAccumulator(int channels, double sampleRate) : accumulator(channels, sampleRate) {}
    maqam::LevelAccumulator accumulator;
};

struct MQWaveformAccumulator {
    MQWaveformAccumulator(std::size_t total, int channels, std::size_t buckets) : accumulator(total, channels, buckets) {}
    maqam::WaveformAccumulator accumulator;
};

struct MQPitchTracker {
    MQPitchTracker(const maqam::PitchDetectorConfig& config, std::size_t hop) : tracker(config, hop) {}
    maqam::PitchTracker tracker;
};

struct MQPitchDetector {
    explicit MQPitchDetector(const maqam::PitchDetectorConfig& config) : detector(config) {}
    maqam::PitchDetector detector;
};

namespace {

maqam::PitchDetectorConfig toConfig(const MQPitchConfig& config) {
    maqam::PitchDetectorConfig result;
    result.sampleRate = config.sample_rate;
    result.frameSize = config.frame_size;
    result.minimumHz = config.minimum_hz;
    result.maximumHz = config.maximum_hz;
    result.threshold = config.threshold;
    result.silenceDbfs = config.silence_dbfs;
    return result;
}

MQPitchEstimate toEstimate(const maqam::PitchEstimate& estimate) {
    return {estimate.frequencyHz, estimate.confidence, estimate.rmsDbfs, estimate.voiced ? 1 : 0};
}

maqam::Scale toScale(const MQScale& scale) {
    maqam::Scale result;
    result.octaveEquivalent = scale.octave_equivalent != 0;
    const int32_t count = scale.degree_count < 0 ? 0 : (scale.degree_count > MQ_MAX_DEGREES ? MQ_MAX_DEGREES : scale.degree_count);
    result.degreeCount = static_cast<std::size_t>(count);
    for (int32_t i = 0; i < count; ++i) {
        result.degrees[static_cast<std::size_t>(i)].cents = scale.degrees[i].cents;
        const int32_t alternates = scale.degrees[i].alternate_count < 0 ? 0
            : (scale.degrees[i].alternate_count > MQ_MAX_ALTERNATES ? MQ_MAX_ALTERNATES : scale.degrees[i].alternate_count);
        result.degrees[static_cast<std::size_t>(i)].alternateCount = static_cast<std::size_t>(alternates);
        for (int32_t a = 0; a < alternates; ++a) {
            result.degrees[static_cast<std::size_t>(i)].alternates[static_cast<std::size_t>(a)] = scale.degrees[i].alternates[a];
        }
    }
    return result;
}

maqam::NoteSegmentConfig toNoteConfig(const MQNoteConfig& config) {
    maqam::NoteSegmentConfig result;
    result.minimumNoteSeconds = config.minimum_note_seconds;
    result.maximumGapSeconds = config.maximum_gap_seconds;
    result.splitCents = config.split_cents;
    result.splitHoldSeconds = config.split_hold_seconds;
    result.shiftCents = config.shift_cents;
    result.shiftWindowSeconds = config.shift_window_seconds;
    result.trimCents = config.trim_cents;
    result.minimumConfidence = config.minimum_confidence;
    result.referenceHz = config.reference_hz;
    return result;
}

MQTargetMatch toMatch(const maqam::TargetMatch& match) {
    return {match.targetCents, match.deviationCents, match.degreeIndex, match.alternate ? 1 : 0};
}

void copyName(char* destination, std::size_t capacity, const std::string& source) {
    std::strncpy(destination, source.c_str(), capacity - 1);
    destination[capacity - 1] = '\0';
}

}  // namespace

extern "C" {

MQStatus mq_analyze_levels(const float* interleaved, size_t frames, int32_t channels,
                           double sample_rate, MQLevelReport* out) {
    if (!interleaved || !out || channels <= 0 || !(sample_rate > 0.0)) return MQ_ERROR_INVALID_ARGUMENT;
    try {
        const maqam::LevelReport report = maqam::analyzeLevels(interleaved, frames, channels, sample_rate);
        out->peak = report.peak;
        out->peak_dbfs = report.peakDbfs;
        out->rms_dbfs = report.rmsDbfs;
        out->crest_db = report.crestDb;
        out->dc_offset = report.dcOffset;
        out->noise_floor_dbfs = report.noiseFloorDbfs;
        out->dynamic_range_db = report.dynamicRangeDb;
        out->clipped_samples = report.clippedSamples;
        out->frames = report.frames;
        out->channels = report.channels;
        return MQ_OK;
    } catch (const std::bad_alloc&) {
        return MQ_ERROR_OUT_OF_MEMORY;
    }
}

MQStatus mq_waveform_peaks(const float* interleaved, size_t frames, int32_t channels,
                           size_t buckets, float* out_min, float* out_max) {
    if (!out_min || !out_max || buckets == 0 || channels <= 0) return MQ_ERROR_INVALID_ARGUMENT;
    maqam::waveformPeaks(interleaved, frames, channels, buckets, out_min, out_max);
    return MQ_OK;
}

MQStatus mq_waveform_rms_dbfs(const float* interleaved, size_t frames, int32_t channels,
                              size_t buckets, float* out_dbfs) {
    if (!out_dbfs || buckets == 0 || channels <= 0) return MQ_ERROR_INVALID_ARGUMENT;
    maqam::waveformRmsDbfs(interleaved, frames, channels, buckets, out_dbfs);
    return MQ_OK;
}

MQLevelAccumulator* mq_level_accumulator_create(int32_t channels, double sample_rate) {
    if (channels <= 0 || !(sample_rate > 0.0)) return nullptr;
    try { return new MQLevelAccumulator(channels, sample_rate); } catch (...) { return nullptr; }
}

void mq_level_accumulator_push(MQLevelAccumulator* accumulator, const float* interleaved, size_t frames) {
    if (!accumulator || !interleaved) return;
    try { accumulator->accumulator.push(interleaved, frames); } catch (...) { /* out of memory: keep what we have */ }
}

MQStatus mq_level_accumulator_finish(const MQLevelAccumulator* accumulator, MQLevelReport* out) {
    if (!accumulator || !out) return MQ_ERROR_INVALID_ARGUMENT;
    try {
        const maqam::LevelReport report = accumulator->accumulator.finish();
        out->peak = report.peak;
        out->peak_dbfs = report.peakDbfs;
        out->rms_dbfs = report.rmsDbfs;
        out->crest_db = report.crestDb;
        out->dc_offset = report.dcOffset;
        out->noise_floor_dbfs = report.noiseFloorDbfs;
        out->dynamic_range_db = report.dynamicRangeDb;
        out->clipped_samples = report.clippedSamples;
        out->frames = report.frames;
        out->channels = report.channels;
        return MQ_OK;
    } catch (const std::bad_alloc&) {
        return MQ_ERROR_OUT_OF_MEMORY;
    }
}

void mq_level_accumulator_destroy(MQLevelAccumulator* accumulator) { delete accumulator; }

MQWaveformAccumulator* mq_waveform_accumulator_create(uint64_t total_frames, int32_t channels, size_t buckets) {
    if (total_frames == 0 || channels <= 0 || buckets == 0) return nullptr;
    try { return new MQWaveformAccumulator(static_cast<std::size_t>(total_frames), channels, buckets); } catch (...) { return nullptr; }
}

void mq_waveform_accumulator_push(MQWaveformAccumulator* accumulator, const float* interleaved, size_t frames) {
    if (accumulator && interleaved) accumulator->accumulator.push(interleaved, frames);
}

MQStatus mq_waveform_accumulator_read(const MQWaveformAccumulator* accumulator, float* out_min, float* out_max,
                                      float* out_rms_dbfs) {
    if (!accumulator || !out_min || !out_max || !out_rms_dbfs) return MQ_ERROR_INVALID_ARGUMENT;
    const std::size_t buckets = accumulator->accumulator.buckets();
    std::memcpy(out_min, accumulator->accumulator.minimum(), buckets * sizeof(float));
    std::memcpy(out_max, accumulator->accumulator.maximum(), buckets * sizeof(float));
    accumulator->accumulator.rmsDbfs(out_rms_dbfs);
    return MQ_OK;
}

void mq_waveform_accumulator_destroy(MQWaveformAccumulator* accumulator) { delete accumulator; }

double mq_hz_to_cents(double hz, double reference_hz) { return maqam::hzToCents(hz, reference_hz); }
double mq_cents_to_hz(double cents, double reference_hz) { return maqam::centsToHz(cents, reference_hz); }

MQQuarterToneName mq_name_quarter_tone(double hz, double a4_hz) {
    const maqam::QuarterToneName name = maqam::nameQuarterTone(hz, a4_hz);
    return {name.octave, name.step, name.remainderCents};
}

int32_t mq_maqam_count(void) { return static_cast<int32_t>(maqam::builtinMaqamat().size()); }

MQStatus mq_maqam_info(int32_t index, MQMaqamInfo* out) {
    const auto& all = maqam::builtinMaqamat();
    if (!out || index < 0 || static_cast<std::size_t>(index) >= all.size()) return MQ_ERROR_NOT_FOUND;
    const maqam::MaqamDefinition& definition = all[static_cast<std::size_t>(index)];
    std::memset(out, 0, sizeof(*out));
    copyName(out->id, sizeof(out->id), definition.id);
    copyName(out->family, sizeof(out->family), definition.family);
    copyName(out->arabic_name, sizeof(out->arabic_name), definition.arabicName);
    copyName(out->english_name, sizeof(out->english_name), definition.englishName);
    copyName(out->lower_jins, sizeof(out->lower_jins), definition.lowerJins);
    copyName(out->upper_jins, sizeof(out->upper_jins), definition.upperJins);
    out->typical_tonic_hz = definition.typicalTonicHz;
    out->scale.degree_count = static_cast<int32_t>(definition.scale.degreeCount);
    out->scale.octave_equivalent = definition.scale.octaveEquivalent ? 1 : 0;
    for (std::size_t i = 0; i < definition.scale.degreeCount; ++i) {
        out->scale.degrees[i].cents = definition.scale.degrees[i].cents;
        out->scale.degrees[i].alternate_count = static_cast<int32_t>(definition.scale.degrees[i].alternateCount);
        for (std::size_t a = 0; a < definition.scale.degrees[i].alternateCount; ++a) {
            out->scale.degrees[i].alternates[a] = definition.scale.degrees[i].alternates[a];
        }
    }
    return MQ_OK;
}

MQTargetMatch mq_nearest_target(const MQScale* scale, double cents_from_tonic) {
    MQTargetMatch result{0.0, 0.0, -1, 0};
    if (!scale) return result;
    const maqam::TargetMatch match = maqam::nearestTarget(toScale(*scale), cents_from_tonic);
    result.target_cents = match.targetCents;
    result.deviation_cents = match.deviationCents;
    result.degree_index = match.degreeIndex;
    result.alternate = match.alternate ? 1 : 0;
    return result;
}

MQPitchConfig mq_pitch_default_config(double sample_rate) {
    maqam::PitchDetectorConfig defaults;
    defaults.sampleRate = sample_rate;
    defaults.frameSize = maqam::frameSizeFor(sample_rate, defaults.minimumHz);
    return {defaults.sampleRate, static_cast<uint32_t>(defaults.frameSize), defaults.minimumHz,
            defaults.maximumHz, defaults.threshold, defaults.silenceDbfs};
}

MQPitchDetector* mq_pitch_create(const MQPitchConfig* config) {
    if (!config) return nullptr;
    try {
        return new MQPitchDetector(toConfig(*config));
    } catch (...) {
        return nullptr;
    }
}

void mq_pitch_destroy(MQPitchDetector* detector) { delete detector; }

MQStatus mq_pitch_detect(MQPitchDetector* detector, const float* samples, MQPitchEstimate* out) {
    if (!detector || !samples || !out) return MQ_ERROR_INVALID_ARGUMENT;
    *out = toEstimate(detector->detector.detect(samples));
    return MQ_OK;
}

MQStatus mq_pitch_track(const float* mono, size_t frames, const MQPitchConfig* config, uint32_t hop_size,
                        double* out_times, MQPitchEstimate* out_estimates, size_t capacity, size_t* out_count) {
    if (!mono || !config || hop_size == 0 || !out_count) return MQ_ERROR_INVALID_ARGUMENT;
    if (frames < config->frame_size) { *out_count = 0; return MQ_OK; }
    const size_t needed = (frames - config->frame_size) / hop_size + 1;
    if (capacity == 0) { *out_count = needed; return MQ_OK; }
    if (!out_times || !out_estimates) return MQ_ERROR_INVALID_ARGUMENT;
    try {
        const auto track = maqam::trackPitch(mono, frames, toConfig(*config), hop_size);
        const size_t written = track.size() < capacity ? track.size() : capacity;
        for (size_t i = 0; i < written; ++i) {
            out_times[i] = track[i].timeSeconds;
            out_estimates[i] = toEstimate(track[i].estimate);
        }
        *out_count = written;
        return MQ_OK;
    } catch (const std::bad_alloc&) {
        return MQ_ERROR_OUT_OF_MEMORY;
    } catch (...) {
        return MQ_ERROR_INVALID_ARGUMENT;
    }
}

uint32_t mq_pitch_frame_size_for(double sample_rate, double minimum_hz) {
    return static_cast<uint32_t>(maqam::frameSizeFor(sample_rate, minimum_hz));
}

MQPitchTracker* mq_pitch_tracker_create(const MQPitchConfig* config, uint32_t hop_size) {
    if (!config || hop_size == 0) return nullptr;
    try {
        return new MQPitchTracker(toConfig(*config), hop_size);
    } catch (...) {
        return nullptr;
    }
}

void mq_pitch_tracker_push(MQPitchTracker* tracker, const float* mono, size_t frames) {
    if (!tracker || !mono) return;
    try {
        tracker->tracker.push(mono, frames);
    } catch (...) {
        // Out of memory: the track simply stops growing; the caller sees the count.
    }
}

size_t mq_pitch_tracker_count(const MQPitchTracker* tracker) {
    return tracker ? tracker->tracker.frames().size() : 0;
}

size_t mq_pitch_tracker_read(const MQPitchTracker* tracker, double* out_times, MQPitchEstimate* out_estimates,
                             size_t capacity) {
    if (!tracker || !out_times || !out_estimates) return 0;
    const auto& frames = tracker->tracker.frames();
    const size_t count = frames.size() < capacity ? frames.size() : capacity;
    for (size_t i = 0; i < count; ++i) {
        out_times[i] = frames[i].timeSeconds;
        out_estimates[i] = toEstimate(frames[i].estimate);
    }
    return count;
}

void mq_pitch_tracker_destroy(MQPitchTracker* tracker) { delete tracker; }

MQNoteConfig mq_note_default_config(double reference_hz) {
    maqam::NoteSegmentConfig d;
    return {d.minimumNoteSeconds, d.maximumGapSeconds, d.splitCents, d.splitHoldSeconds, d.shiftCents,
            d.shiftWindowSeconds, d.trimCents, d.minimumConfidence, reference_hz > 0.0 ? reference_hz : d.referenceHz};
}

MQStatus mq_segment_notes(const double* times, const MQPitchEstimate* estimates, size_t count,
                          const MQNoteConfig* config, MQSungNote* out_notes, size_t capacity, size_t* out_count) {
    if (!config || !out_count || (count > 0 && (!times || !estimates))) return MQ_ERROR_INVALID_ARGUMENT;
    if (capacity > 0 && !out_notes) return MQ_ERROR_INVALID_ARGUMENT;
    try {
        std::vector<maqam::PitchFrame> track(count);
        for (size_t i = 0; i < count; ++i) {
            track[i].timeSeconds = times[i];
            track[i].estimate.frequencyHz = estimates[i].frequency_hz;
            track[i].estimate.confidence = estimates[i].confidence;
            track[i].estimate.rmsDbfs = estimates[i].rms_dbfs;
            track[i].estimate.voiced = estimates[i].voiced != 0;
        }
        const auto notes = maqam::segmentNotes(track, toNoteConfig(*config));
        if (capacity == 0) { *out_count = notes.size(); return MQ_OK; }
        const size_t written = notes.size() < capacity ? notes.size() : capacity;
        for (size_t i = 0; i < written; ++i) {
            const maqam::SungNote& n = notes[i];
            out_notes[i] = {n.startSeconds, n.endSeconds, n.hz, n.cents, n.spreadCents, n.vibratoRateHz,
                            n.vibratoExtentCents, n.firstFrame, n.frameCount};
        }
        *out_count = written;
        return MQ_OK;
    } catch (const std::bad_alloc&) {
        return MQ_ERROR_OUT_OF_MEMORY;
    } catch (...) {
        return MQ_ERROR_INVALID_ARGUMENT;
    }
}

MQStatus mq_evaluate_intonation(const MQScale* scale, double tonic_hz, const MQSungNote* notes, size_t count,
                                double tolerance_cents, MQNoteMatch* out_matches, MQIntonationSummary* out_summary) {
    if (!scale || !out_summary || !(tonic_hz > 0.0) || (count > 0 && !notes)) return MQ_ERROR_INVALID_ARGUMENT;
    try {
        std::vector<maqam::SungNote> sung(count);
        for (size_t i = 0; i < count; ++i) {
            sung[i].startSeconds = notes[i].start_seconds;
            sung[i].endSeconds = notes[i].end_seconds;
            sung[i].hz = notes[i].hz;
            sung[i].cents = notes[i].cents;
        }
        std::vector<maqam::NoteMatch> matches;
        const maqam::IntonationSummary summary =
            maqam::evaluateIntonation(toScale(*scale), tonic_hz, sung, tolerance_cents, &matches);
        if (out_matches) {
            for (size_t i = 0; i < matches.size(); ++i) {
                out_matches[i] = {toMatch(matches[i].target), matches[i].inTune ? 1 : 0};
            }
        }
        *out_summary = MQIntonationSummary{};
        out_summary->note_count = summary.noteCount;
        out_summary->in_tune_count = summary.inTuneCount;
        out_summary->total_seconds = summary.totalSeconds;
        out_summary->in_tune_fraction = summary.inTuneFraction;
        out_summary->mean_absolute_deviation_cents = summary.meanAbsoluteDeviationCents;
        for (size_t d = 0; d < MQ_MAX_DEGREES; ++d) {
            out_summary->degrees[d] = {summary.degrees[d].noteCount, summary.degrees[d].seconds,
                                       summary.degrees[d].meanDeviationCents};
        }
        return MQ_OK;
    } catch (const std::bad_alloc&) {
        return MQ_ERROR_OUT_OF_MEMORY;
    } catch (...) {
        return MQ_ERROR_INVALID_ARGUMENT;
    }
}

}  // extern "C"
