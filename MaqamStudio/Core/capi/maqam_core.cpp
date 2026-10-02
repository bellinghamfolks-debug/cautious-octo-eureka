#include "maqam_core.h"

#include "maqam/levels.hpp"
#include "maqam/maqam.hpp"
#include "maqam/notes.hpp"
#include "maqam/correction.hpp"
#include "maqam/detection.hpp"
#include "maqam/studio.hpp"
#include "maqam/psola.hpp"
#include "maqam/pitch_detector.hpp"
#include "maqam/tuning.hpp"

#include <cstring>
#include <algorithm>
#include <cmath>
#include <limits>
#include <memory>
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

struct MQMarkFinder {
    MQMarkFinder(double rate, double first, double hop, std::vector<float> track) : finder(rate, first, hop, std::move(track)) {}
    maqam::MarkFinder finder;
};

struct MQGrainPlan {
    maqam::GrainPlan plan;
};

struct MQStudioAnalyzer {
    explicit MQStudioAnalyzer(double rate) : analyzer(rate) {}
    maqam::StudioAnalyzer analyzer;
};

struct MQStudioSession {
    maqam::StudioAnalysis analysis;
    std::vector<bool> voiced;  // per analysis frame
    std::vector<maqam::RegionAttenuator::Region> breaths;
};

struct MQStudioChain {
    std::unique_ptr<maqam::StudioChain> chain;
};

struct MQLoudnessMeter {
    MQLoudnessMeter(double rate, int channels) : meter(rate, channels) {}
    maqam::dsp::LoudnessMeter meter;
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

maqam::dsp::Biquad::Type eqType(int32_t type) {
    switch (type) {
    case 1: return maqam::dsp::Biquad::Type::lowShelf;
    case 2: return maqam::dsp::Biquad::Type::highShelf;
    default: return maqam::dsp::Biquad::Type::peaking;
    }
}

int32_t eqTypeCode(maqam::dsp::Biquad::Type type) {
    if (type == maqam::dsp::Biquad::Type::lowShelf) return 1;
    if (type == maqam::dsp::Biquad::Type::highShelf) return 2;
    return 0;
}

MQStudioPlan toCPlan(const maqam::StudioPlan& p) {
    MQStudioPlan c{};
    c.profile = static_cast<int32_t>(p.profile);
    c.high_pass_hz = p.highPassHz;
    c.hum_hz = p.humHz;
    c.hum_harmonics = p.humHarmonics;
    c.denoise_db = p.denoiseDb;
    c.plosive_db = p.plosiveDb;
    c.breath_db = p.breathDb;
    c.leveler = p.leveler ? 1 : 0;
    c.leveler_target_db = p.levelerTargetDb;
    c.leveler_range_db = p.levelerRangeDb;
    c.eq_count = p.eqCount;
    for (int i = 0; i < p.eqCount; ++i) {
        const auto& band = p.eq[static_cast<std::size_t>(i)];
        c.eq[i] = {eqTypeCode(band.type), band.frequencyHz, band.q, band.gainDb};
    }
    c.de_esser = {p.deEsserOn ? 1 : 0, p.deEsser.frequencyHz, p.deEsser.thresholdDb, p.deEsser.maximumCutDb};
    c.harshness = {p.harshnessOn ? 1 : 0, p.harshness.frequencyHz, p.harshness.thresholdDb, p.harshness.maximumCutDb};
    c.compressor = {p.compressor.thresholdDb, p.compressor.ratio, p.compressor.attackMs, p.compressor.releaseMs};
    c.multiband = p.multibandOn ? 1 : 0;
    for (std::size_t b = 0; b < 3; ++b) {
        c.bands[b] = {p.multiband[b].thresholdDb, p.multiband[b].ratio, p.multiband[b].attackMs, p.multiband[b].releaseMs};
    }
    c.saturation_drive_db = p.saturationDriveDb;
    c.saturation_mix = p.saturationMix;
    c.exciter_amount = p.exciterAmount;
    c.reverb_mix = p.reverbMix;
    c.reverb_size = p.reverbSize;
    c.reverb_damping = p.reverbDamping;
    c.reverb_pre_delay_ms = p.reverbPreDelayMs;
    c.delay_mix = p.delayMix;
    c.delay_ms = p.delayMs;
    c.delay_feedback = p.delayFeedback;
    c.loudness_target_lufs = p.loudnessTargetLufs;
    c.ceiling_db = p.ceilingDb;
    return c;
}

maqam::StudioPlan fromCPlan(const MQStudioPlan& c) {
    maqam::StudioPlan p;
    p.profile = static_cast<maqam::GenreProfile>(std::clamp(c.profile, 0, static_cast<int32_t>(maqam::GenreProfile::count) - 1));
    p.highPassHz = std::clamp(c.high_pass_hz, 0.0, 400.0);
    p.humHz = (c.hum_hz == 50.0 || c.hum_hz == 60.0) ? c.hum_hz : 0.0;
    p.humHarmonics = std::clamp(c.hum_harmonics, 0, 6);
    p.denoiseDb = std::clamp(c.denoise_db, 0.0, 30.0);
    p.plosiveDb = std::clamp(c.plosive_db, 0.0, 30.0);
    p.breathDb = std::clamp(c.breath_db, 0.0, 40.0);
    p.leveler = c.leveler != 0;
    p.levelerTargetDb = std::clamp(c.leveler_target_db, -60.0, 0.0);
    p.levelerRangeDb = std::clamp(c.leveler_range_db, 0.0, 12.0);
    p.eqCount = std::clamp(c.eq_count, 0, 8);
    for (int i = 0; i < p.eqCount; ++i) {
        p.eq[static_cast<std::size_t>(i)] = {eqType(c.eq[i].type), std::clamp(c.eq[i].frequency_hz, 20.0, 20000.0),
                                             std::clamp(c.eq[i].q, 0.1, 20.0), std::clamp(c.eq[i].gain_db, -24.0, 24.0)};
    }
    p.deEsserOn = c.de_esser.enabled != 0;
    p.deEsser.frequencyHz = std::clamp(c.de_esser.frequency_hz, 2000.0, 16000.0);
    p.deEsser.thresholdDb = c.de_esser.threshold_db;
    p.deEsser.maximumCutDb = std::clamp(c.de_esser.maximum_cut_db, 0.0, 24.0);
    p.deEsser.q = 1.0;
    p.deEsser.ratio = 4.0;
    p.deEsser.attackMs = 1.0;
    p.deEsser.releaseMs = 60.0;
    p.harshnessOn = c.harshness.enabled != 0;
    p.harshness.frequencyHz = std::clamp(c.harshness.frequency_hz, 1000.0, 8000.0);
    p.harshness.thresholdDb = c.harshness.threshold_db;
    p.harshness.maximumCutDb = std::clamp(c.harshness.maximum_cut_db, 0.0, 18.0);
    p.harshness.q = 1.2;
    p.harshness.ratio = 3.0;
    p.harshness.attackMs = 3.0;
    p.harshness.releaseMs = 80.0;
    p.compressor.thresholdDb = c.compressor.threshold_db;
    p.compressor.ratio = std::clamp(c.compressor.ratio, 1.0, 20.0);
    p.compressor.attackMs = std::clamp(c.compressor.attack_ms, 0.1, 200.0);
    p.compressor.releaseMs = std::clamp(c.compressor.release_ms, 5.0, 2000.0);
    p.compressor.kneeDb = 6.0;
    p.multibandOn = c.multiband != 0;
    for (std::size_t b = 0; b < 3; ++b) {
        p.multiband[b].thresholdDb = c.bands[b].threshold_db;
        p.multiband[b].ratio = std::clamp(c.bands[b].ratio, 1.0, 20.0);
        p.multiband[b].attackMs = std::clamp(c.bands[b].attack_ms, 0.1, 200.0);
        p.multiband[b].releaseMs = std::clamp(c.bands[b].release_ms, 5.0, 2000.0);
        p.multiband[b].kneeDb = 6.0;
    }
    p.saturationDriveDb = std::clamp(c.saturation_drive_db, 0.0, 24.0);
    p.saturationMix = std::clamp(c.saturation_mix, 0.0, 1.0);
    p.exciterAmount = std::clamp(c.exciter_amount, 0.0, 1.0);
    p.reverbMix = std::clamp(c.reverb_mix, 0.0, 1.0);
    p.reverbSize = std::clamp(c.reverb_size, 0.0, 1.0);
    p.reverbDamping = std::clamp(c.reverb_damping, 0.0, 1.0);
    p.reverbPreDelayMs = std::clamp(c.reverb_pre_delay_ms, 0.0, 200.0);
    p.delayMix = std::clamp(c.delay_mix, 0.0, 1.0);
    p.delayMs = std::clamp(c.delay_ms, 10.0, 2000.0);
    p.delayFeedback = std::clamp(c.delay_feedback, 0.0, 0.9);
    p.loudnessTargetLufs = std::clamp(c.loudness_target_lufs, -30.0, -6.0);
    p.ceilingDb = std::clamp(c.ceiling_db, -12.0, -0.1);
    return p;
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

MQCorrectionSettings mq_correction_preset(MQCorrectionPreset preset) {
    switch (preset) {
    case MQ_CORRECTION_STRONG:
        return {25.0, 1.0, 0.15, 0.6, 0.0, 0.35, 0.8, 10.0, 1200.0};
    case MQ_CORRECTION_ROBOTIC:
        return {0.0, 1.0, 0.0, 0.0, 0.0, 1.0, 1.0, 0.0, 1200.0};
    case MQ_CORRECTION_NATURAL:
    default: {
        const maqam::CorrectionSettings d;
        return {d.retuneMs, d.strength, d.humanize, d.vibratoAmount, d.vibratoRateHz, d.transitionSensitivity,
                d.driftCorrection, d.smoothingMs, d.maximumShiftCents};
    }
    }
}

MQStatus mq_compute_correction(const double* times, const MQPitchEstimate* estimates, size_t frame_count,
                               const MQSungNote* notes, size_t note_count, const MQNoteOverride* overrides,
                               const MQScale* scale, double tonic_hz, const MQCorrectionSettings* settings,
                               double* out_shift_cents, double* out_note_targets) {
    if (!scale || !settings || !out_shift_cents || !(tonic_hz > 0.0)) return MQ_ERROR_INVALID_ARGUMENT;
    if ((frame_count > 0 && (!times || !estimates)) || (note_count > 0 && !notes)) return MQ_ERROR_INVALID_ARGUMENT;
    try {
        std::vector<maqam::PitchFrame> track(frame_count);
        for (size_t i = 0; i < frame_count; ++i) {
            track[i].timeSeconds = times[i];
            track[i].estimate.frequencyHz = estimates[i].frequency_hz;
            track[i].estimate.confidence = estimates[i].confidence;
            track[i].estimate.voiced = estimates[i].voiced != 0;
        }
        std::vector<maqam::SungNote> sung(note_count);
        for (size_t i = 0; i < note_count; ++i) {
            sung[i].startSeconds = notes[i].start_seconds;
            sung[i].endSeconds = notes[i].end_seconds;
            sung[i].hz = notes[i].hz;
            sung[i].cents = notes[i].cents;
            sung[i].vibratoRateHz = notes[i].vibrato_rate_hz;
            sung[i].vibratoExtentCents = notes[i].vibrato_extent_cents;
        }
        std::vector<maqam::NoteOverride> manual;
        if (overrides) {
            manual.resize(note_count);
            for (size_t i = 0; i < note_count; ++i) {
                manual[i].bypass = overrides[i].bypass != 0;
                manual[i].hasTarget = overrides[i].has_target != 0;
                manual[i].targetCentsFromTonic = overrides[i].target_cents_from_tonic;
            }
        }
        maqam::CorrectionSettings s;
        s.retuneMs = settings->retune_ms;
        s.strength = settings->strength;
        s.humanize = settings->humanize;
        s.vibratoAmount = settings->vibrato_amount;
        s.vibratoRateHz = settings->vibrato_rate_hz;
        s.transitionSensitivity = settings->transition_sensitivity;
        s.driftCorrection = settings->drift_correction;
        s.smoothingMs = settings->smoothing_ms;
        s.maximumShiftCents = settings->maximum_shift_cents;
        const auto result = maqam::computeCorrection(track, sung, toScale(*scale), tonic_hz, s, manual);
        std::copy(result.shiftCents.begin(), result.shiftCents.end(), out_shift_cents);
        if (out_note_targets) std::copy(result.noteTargetCents.begin(), result.noteTargetCents.end(), out_note_targets);
        return MQ_OK;
    } catch (const std::bad_alloc&) {
        return MQ_ERROR_OUT_OF_MEMORY;
    } catch (...) {
        return MQ_ERROR_INVALID_ARGUMENT;
    }
}

MQMarkFinder* mq_marks_create(double sample_rate, double first_time, double hop_seconds, const float* track_hz,
                              size_t frame_count) {
    if (!(sample_rate > 0.0) || (frame_count > 0 && !track_hz)) return nullptr;
    try {
        return new MQMarkFinder(sample_rate, first_time, hop_seconds,
                                std::vector<float>(track_hz, track_hz + frame_count));
    } catch (...) {
        return nullptr;
    }
}

void mq_marks_push(MQMarkFinder* finder, const float* mono, size_t frames) {
    if (!finder || !mono) return;
    try {
        finder->finder.push(mono, frames);
    } catch (...) {
    }
}

void mq_marks_destroy(MQMarkFinder* finder) { delete finder; }

MQGrainPlan* mq_grain_plan_create(MQMarkFinder* finder, const double* shift_cents, size_t frame_count,
                                  double first_time, double hop_seconds, double sample_rate, uint64_t total_samples,
                                  double formant_shift_cents, int32_t preserve_formants) {
    if (!finder || (frame_count > 0 && !shift_cents)) return nullptr;
    try {
        auto* plan = new MQGrainPlan();
        plan->plan = maqam::planGrains(finder->finder.finish(), std::vector<double>(shift_cents, shift_cents + frame_count),
                                       first_time, hop_seconds, sample_rate, total_samples, formant_shift_cents,
                                       preserve_formants != 0);
        return plan;
    } catch (...) {
        return nullptr;
    }
}

void mq_grain_plan_input_range(const MQGrainPlan* plan, int64_t output_start, size_t output_frames,
                               int64_t* out_input_start, int64_t* out_input_end) {
    if (!plan || !out_input_start || !out_input_end) return;
    int64_t start = 0, end = 0;
    maqam::inputRangeFor(plan->plan, output_start, output_frames, start, end);
    *out_input_start = start;
    *out_input_end = end;
}

MQStatus mq_grain_plan_render(const MQGrainPlan* plan, const float* const* input, int32_t channels, int64_t input_start,
                              size_t input_frames, int64_t output_start, size_t output_frames, float* const* output) {
    if (!plan || !input || !output || channels <= 0) return MQ_ERROR_INVALID_ARGUMENT;
    maqam::renderBlock(plan->plan, input, channels, input_start, input_frames, output_start, output_frames, output);
    return MQ_OK;
}

void mq_grain_plan_destroy(MQGrainPlan* plan) { delete plan; }

MQStatus mq_detect_maqam(const MQSungNote* notes, size_t note_count, const MQScale* scales, size_t scale_count,
                         MQDetectionSummary* out_summary, MQMaqamCandidate* out_candidates, size_t capacity,
                         size_t* out_count) {
    if (!out_summary || !out_count || (note_count > 0 && !notes) || (scale_count > 0 && !scales)) {
        return MQ_ERROR_INVALID_ARGUMENT;
    }
    if (capacity > 0 && !out_candidates) return MQ_ERROR_INVALID_ARGUMENT;
    try {
        std::vector<maqam::SungNote> sung(note_count);
        for (size_t i = 0; i < note_count; ++i) {
            sung[i].startSeconds = notes[i].start_seconds;
            sung[i].endSeconds = notes[i].end_seconds;
            sung[i].hz = notes[i].hz;
        }
        std::vector<maqam::Scale> converted(scale_count);
        for (size_t i = 0; i < scale_count; ++i) converted[i] = toScale(scales[i]);
        const maqam::MaqamDetection detection = maqam::detectMaqam(sung, converted);
        *out_summary = {detection.enoughData ? 1 : 0, detection.sungSeconds, detection.pitchClasses,
                        detection.tonicHz, detection.tonicConfidence};
        if (capacity == 0) { *out_count = detection.ranked.size(); return MQ_OK; }
        const size_t written = detection.ranked.size() < capacity ? detection.ranked.size() : capacity;
        for (size_t i = 0; i < written; ++i) {
            const maqam::MaqamCandidate& c = detection.ranked[i];
            out_candidates[i] = {c.scaleIndex, c.tonicHz, c.probability, c.meanDeviationCents};
        }
        *out_count = written;
        return MQ_OK;
    } catch (const std::bad_alloc&) {
        return MQ_ERROR_OUT_OF_MEMORY;
    } catch (...) {
        return MQ_ERROR_INVALID_ARGUMENT;
    }
}

MQStudioAnalyzer* mq_studio_analyzer_create(double sample_rate) {
    if (!(sample_rate > 0.0)) return nullptr;
    try { return new MQStudioAnalyzer(sample_rate); } catch (...) { return nullptr; }
}

void mq_studio_analyzer_push(MQStudioAnalyzer* analyzer, const float* mono, size_t frames) {
    if (!analyzer || !mono) return;
    try { analyzer->analyzer.push(mono, frames); } catch (...) {}
}

void mq_studio_analyzer_destroy(MQStudioAnalyzer* analyzer) { delete analyzer; }

MQStudioSession* mq_studio_session_create(MQStudioAnalyzer* analyzer, const float* track_hz, size_t track_count,
                                          double track_first_time, double track_hop_seconds) {
    if (!analyzer || (track_count > 0 && !track_hz)) return nullptr;
    try {
        auto* session = new MQStudioSession();
        session->analysis = analyzer->analyzer.finish();
        const auto& a = session->analysis;
        session->voiced.assign(a.frames.size(), false);
        if (track_count > 0 && track_hop_seconds > 0.0) {
            for (size_t i = 0; i < a.frames.size(); ++i) {
                const double centre = (static_cast<double>(i * a.hop) + maqam::SpectralDenoiser::kFrameSize / 2.0) / a.sampleRate;
                const long frame = std::lround((centre - track_first_time) / track_hop_seconds);
                session->voiced[i] = frame >= 0 && static_cast<size_t>(frame) < track_count && track_hz[frame] > 0.0f;
            }
        }
        session->breaths = maqam::findBreaths(a, session->voiced);
        return session;
    } catch (...) {
        return nullptr;
    }
}

void mq_studio_session_destroy(MQStudioSession* session) { delete session; }

void mq_studio_measurements(const MQStudioSession* session, MQStudioMeasurements* out) {
    if (!session || !out) return;
    const auto& a = session->analysis;
    *out = {static_cast<double>(a.samples) / a.sampleRate, a.integratedLufs, a.peakDbfs, a.noiseFloorDbfs,
            a.voiceLevelDbfs, a.levelSpreadDb, a.humHz, a.humStrengthDb, a.sibilanceDb, a.clippedSamples,
            session->breaths.size()};
}

MQStatus mq_studio_plan(const MQStudioSession* session, int32_t profile, MQStudioPlan* out_plan,
                        MQStudioReason* out_reasons, size_t capacity, size_t* out_count) {
    if (!session || !out_plan || profile < 0 || profile >= MQ_PROFILE_COUNT) return MQ_ERROR_INVALID_ARGUMENT;
    try {
        std::vector<maqam::StudioReason> reasons;
        const maqam::StudioPlan plan = maqam::planStudio(session->analysis, session->breaths.size(),
                                                         static_cast<maqam::GenreProfile>(profile), &reasons);
        *out_plan = toCPlan(plan);
        if (out_count) *out_count = reasons.size();
        if (out_reasons) {
            const size_t written = reasons.size() < capacity ? reasons.size() : capacity;
            for (size_t i = 0; i < written; ++i) out_reasons[i] = {static_cast<int32_t>(reasons[i].code), reasons[i].a, reasons[i].b};
            if (out_count) *out_count = written;
        }
        return MQ_OK;
    } catch (...) {
        return MQ_ERROR_INVALID_ARGUMENT;
    }
}

int32_t mq_studio_profile_tuning(int32_t profile) {
    if (profile < 0 || profile >= MQ_PROFILE_COUNT) return MQ_CORRECTION_NATURAL;
    return maqam::profileTuningPreset(static_cast<maqam::GenreProfile>(profile));
}

MQStudioChain* mq_studio_chain_create(const MQStudioSession* session, const MQStudioPlan* plan, double output_gain_db,
                                      int32_t limit) {
    if (!session || !plan) return nullptr;
    try {
        const auto& a = session->analysis;
        auto* chain = new MQStudioChain();
        chain->chain = std::make_unique<maqam::StudioChain>(
            fromCPlan(*plan), a, session->breaths, session->voiced,
            maqam::SpectralDenoiser::kFrameSize / 2.0 / a.sampleRate, static_cast<double>(a.hop) / a.sampleRate,
            output_gain_db, limit != 0);
        return chain;
    } catch (...) {
        return nullptr;
    }
}

void mq_studio_chain_process(MQStudioChain* chain, const float* mono, float* left, float* right, size_t frames) {
    if (!chain || !mono || !left || !right) return;
    chain->chain->process(mono, left, right, frames);
}

uint64_t mq_studio_chain_latency(const MQStudioChain* chain) { return chain ? chain->chain->latency() : 0; }
uint64_t mq_studio_chain_tail(const MQStudioChain* chain) { return chain ? chain->chain->tail() : 0; }
void mq_studio_chain_destroy(MQStudioChain* chain) { delete chain; }

MQLoudnessMeter* mq_loudness_create(double sample_rate, int32_t channels) {
    if (!(sample_rate > 0.0)) return nullptr;
    try { return new MQLoudnessMeter(sample_rate, channels); } catch (...) { return nullptr; }
}

void mq_loudness_push(MQLoudnessMeter* meter, const float* left, const float* right, size_t frames) {
    if (!meter || !left) return;
    try { meter->meter.push(left, right, frames); } catch (...) {}
}

double mq_loudness_integrated(const MQLoudnessMeter* meter) { return meter ? meter->meter.integratedLufs() : -200.0; }
void mq_loudness_destroy(MQLoudnessMeter* meter) { delete meter; }

}  // extern "C"
