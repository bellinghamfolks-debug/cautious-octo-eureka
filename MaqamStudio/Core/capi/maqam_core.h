/*
 * Maqam Studio core: the C interface the Swift app calls.
 *
 * Plain C types only, so the header imports into Swift through the bridging
 * header without any C++ interop. Functions marked REALTIME-SAFE neither
 * allocate nor lock and may be called from the audio render thread.
 */
#ifndef MAQAM_CORE_H
#define MAQAM_CORE_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define MQ_MAX_DEGREES 12
#define MQ_MAX_ALTERNATES 2
#define MQ_NAME_CAPACITY 48

typedef enum {
    MQ_OK = 0,
    MQ_ERROR_INVALID_ARGUMENT = 1,
    MQ_ERROR_OUT_OF_MEMORY = 2,
    MQ_ERROR_NOT_FOUND = 3,
} MQStatus;

/* ---------------------------------------------------------------- levels */

typedef struct {
    double peak;
    double peak_dbfs;
    double rms_dbfs;
    double crest_db;
    double dc_offset;
    double noise_floor_dbfs;
    double dynamic_range_db;
    uint64_t clipped_samples;
    uint64_t frames;
    int32_t channels;
} MQLevelReport;

MQStatus mq_analyze_levels(const float *interleaved, size_t frames, int32_t channels,
                           double sample_rate, MQLevelReport *out_report);

MQStatus mq_waveform_peaks(const float *interleaved, size_t frames, int32_t channels,
                           size_t buckets, float *out_min, float *out_max);

MQStatus mq_waveform_rms_dbfs(const float *interleaved, size_t frames, int32_t channels,
                              size_t buckets, float *out_dbfs);

/* Streaming analysis for long files read in chunks (never on the audio thread). */
typedef struct MQLevelAccumulator MQLevelAccumulator;
MQLevelAccumulator *mq_level_accumulator_create(int32_t channels, double sample_rate);
void mq_level_accumulator_push(MQLevelAccumulator *accumulator, const float *interleaved, size_t frames);
MQStatus mq_level_accumulator_finish(const MQLevelAccumulator *accumulator, MQLevelReport *out_report);
void mq_level_accumulator_destroy(MQLevelAccumulator *accumulator);

typedef struct MQWaveformAccumulator MQWaveformAccumulator;
MQWaveformAccumulator *mq_waveform_accumulator_create(uint64_t total_frames, int32_t channels, size_t buckets);
void mq_waveform_accumulator_push(MQWaveformAccumulator *accumulator, const float *interleaved, size_t frames);
/* Each output array must hold `buckets` values. */
MQStatus mq_waveform_accumulator_read(const MQWaveformAccumulator *accumulator, float *out_min,
                                      float *out_max, float *out_rms_dbfs);
void mq_waveform_accumulator_destroy(MQWaveformAccumulator *accumulator);

/* ---------------------------------------------------------------- tuning */

double mq_hz_to_cents(double hz, double reference_hz);
double mq_cents_to_hz(double cents, double reference_hz);

typedef struct {
    int32_t octave;
    int32_t step;            /* quarter tones above C, 0..23 */
    double remainder_cents;  /* left over after naming, [-25, 25) */
} MQQuarterToneName;

MQQuarterToneName mq_name_quarter_tone(double hz, double a4_hz);

/* ----------------------------------------------------------------- maqam */

typedef struct {
    double cents;
    double alternates[MQ_MAX_ALTERNATES];
    int32_t alternate_count;
} MQDegree;

typedef struct {
    MQDegree degrees[MQ_MAX_DEGREES];
    int32_t degree_count;
    int32_t octave_equivalent; /* 0 for scales such as Saba */
} MQScale;

typedef struct {
    char id[MQ_NAME_CAPACITY];
    char family[MQ_NAME_CAPACITY];
    char arabic_name[MQ_NAME_CAPACITY * 2];
    char english_name[MQ_NAME_CAPACITY];
    char lower_jins[MQ_NAME_CAPACITY];
    char upper_jins[MQ_NAME_CAPACITY];
    double typical_tonic_hz;
    MQScale scale;
} MQMaqamInfo;

typedef struct {
    double target_cents;
    double deviation_cents;
    int32_t degree_index; /* -1 when the scale is empty */
    int32_t alternate;
} MQTargetMatch;

int32_t mq_maqam_count(void);
MQStatus mq_maqam_info(int32_t index, MQMaqamInfo *out_info);

/* REALTIME-SAFE */
MQTargetMatch mq_nearest_target(const MQScale *scale, double cents_from_tonic);

/* ----------------------------------------------------------------- pitch */

typedef struct {
    double sample_rate;
    uint32_t frame_size;
    double minimum_hz;
    double maximum_hz;
    double threshold;
    double silence_dbfs;
} MQPitchConfig;

typedef struct {
    double frequency_hz;
    double confidence;
    double rms_dbfs;
    int32_t voiced;
} MQPitchEstimate;

typedef struct MQPitchDetector MQPitchDetector;

MQPitchConfig mq_pitch_default_config(double sample_rate);
MQPitchDetector *mq_pitch_create(const MQPitchConfig *config);
void mq_pitch_destroy(MQPitchDetector *detector);
/* REALTIME-SAFE: `samples` holds config.frame_size mono samples. */
MQStatus mq_pitch_detect(MQPitchDetector *detector, const float *samples, MQPitchEstimate *out);

/* Offline track: writes at most `capacity` frames, returns how many were produced
 * through `out_count`. Pass capacity = 0 to learn the required count. */
MQStatus mq_pitch_track(const float *mono, size_t frames, const MQPitchConfig *config,
                        uint32_t hop_size, double *out_times, MQPitchEstimate *out_estimates,
                        size_t capacity, size_t *out_count);

#ifdef __cplusplus
}
#endif

#endif /* MAQAM_CORE_H */
