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

/* Smallest power-of-two frame holding two periods of `minimum_hz`. The default
 * configuration already uses it, so 88.2 and 96 kHz files work. */
uint32_t mq_pitch_frame_size_for(double sample_rate, double minimum_hz);

/* Streaming offline tracker: the same frames as mq_pitch_track, fed in chunks.
 * Allocates as it goes; never use it on the audio thread. */
typedef struct MQPitchTracker MQPitchTracker;
MQPitchTracker *mq_pitch_tracker_create(const MQPitchConfig *config, uint32_t hop_size);
void mq_pitch_tracker_push(MQPitchTracker *tracker, const float *mono, size_t frames);
size_t mq_pitch_tracker_count(const MQPitchTracker *tracker);
/* Copies up to `capacity` frames from the start; returns how many were copied. */
size_t mq_pitch_tracker_read(const MQPitchTracker *tracker, double *out_times, MQPitchEstimate *out_estimates,
                             size_t capacity);
void mq_pitch_tracker_destroy(MQPitchTracker *tracker);

/* ---------------------------------------------------------------- notes */

typedef struct {
    double minimum_note_seconds;
    double maximum_gap_seconds;
    double split_cents;
    double split_hold_seconds;
    double shift_cents;
    double shift_window_seconds;
    double trim_cents;
    double minimum_confidence;
    double reference_hz;
} MQNoteConfig;

typedef struct {
    double start_seconds;
    double end_seconds;
    double hz;
    double cents;               /* above reference_hz */
    double spread_cents;
    double vibrato_rate_hz;     /* 0 when none */
    double vibrato_extent_cents;
    uint64_t first_frame;
    uint64_t frame_count;
} MQSungNote;

MQNoteConfig mq_note_default_config(double reference_hz);

/* Segments a pitch track into notes. Pass capacity = 0 to learn the count. */
MQStatus mq_segment_notes(const double *times, const MQPitchEstimate *estimates, size_t count,
                          const MQNoteConfig *config, MQSungNote *out_notes, size_t capacity, size_t *out_count);

typedef struct {
    MQTargetMatch target;
    int32_t in_tune;
} MQNoteMatch;

typedef struct {
    uint64_t note_count;
    double seconds;
    double mean_deviation_cents; /* positive: sung sharp */
} MQDegreeTendency;

typedef struct {
    uint64_t note_count;
    uint64_t in_tune_count;
    double total_seconds;
    double in_tune_fraction;
    double mean_absolute_deviation_cents;
    MQDegreeTendency degrees[MQ_MAX_DEGREES];
} MQIntonationSummary;

/* Judges notes against `scale` on `tonic_hz`. `out_matches` may be NULL;
 * otherwise it must hold `count` entries. */
MQStatus mq_evaluate_intonation(const MQScale *scale, double tonic_hz, const MQSungNote *notes, size_t count,
                                double tolerance_cents, MQNoteMatch *out_matches, MQIntonationSummary *out_summary);

/* ----------------------------------------------------------- correction */

typedef enum {
    MQ_CORRECTION_NATURAL = 0,
    MQ_CORRECTION_STRONG = 1,
    MQ_CORRECTION_ROBOTIC = 2,
} MQCorrectionPreset;

typedef struct {
    double retune_ms;
    double strength;
    double humanize;
    double vibrato_amount;
    double vibrato_rate_hz;          /* 0 keeps the singer's rate */
    double transition_sensitivity;
    double drift_correction;
    double smoothing_ms;
    double maximum_shift_cents;
} MQCorrectionSettings;

typedef struct {
    int32_t bypass;
    int32_t has_target;
    double target_cents_from_tonic;
} MQNoteOverride;

MQCorrectionSettings mq_correction_preset(MQCorrectionPreset preset);

/* Per-frame shift in cents for a pitch track and its notes. `overrides` may be
 * NULL or hold `note_count` entries; `out_note_targets` may be NULL or hold
 * `note_count` entries (NaN for a bypassed note). */
MQStatus mq_compute_correction(const double *times, const MQPitchEstimate *estimates, size_t frame_count,
                               const MQSungNote *notes, size_t note_count, const MQNoteOverride *overrides,
                               const MQScale *scale, double tonic_hz, const MQCorrectionSettings *settings,
                               double *out_shift_cents, double *out_note_targets);

/* -------------------------------------------------- PSOLA pitch shifting */

typedef struct MQMarkFinder MQMarkFinder;
MQMarkFinder *mq_marks_create(double sample_rate, double first_time, double hop_seconds, const float *track_hz,
                              size_t frame_count);
void mq_marks_push(MQMarkFinder *finder, const float *mono, size_t frames);
void mq_marks_destroy(MQMarkFinder *finder);

typedef struct MQGrainPlan MQGrainPlan;
/* Finishes `finder` (it can no longer be pushed to) and plans grains for the
 * per-frame shift curve. */
MQGrainPlan *mq_grain_plan_create(MQMarkFinder *finder, const double *shift_cents, size_t frame_count,
                                  double first_time, double hop_seconds, double sample_rate, uint64_t total_samples,
                                  double formant_shift_cents, int32_t preserve_formants);
void mq_grain_plan_input_range(const MQGrainPlan *plan, int64_t output_start, size_t output_frames,
                               int64_t *out_input_start, int64_t *out_input_end);
/* `input[c]` holds `input_frames` samples of channel c from `input_start`;
 * `output[c]` receives `output_frames` samples from `output_start`. */
MQStatus mq_grain_plan_render(const MQGrainPlan *plan, const float *const *input, int32_t channels, int64_t input_start,
                              size_t input_frames, int64_t output_start, size_t output_frames, float *const *output);
void mq_grain_plan_destroy(MQGrainPlan *plan);

/* ------------------------------------------------- maqam and tonic detection */

typedef struct {
    uint64_t scale_index;        /* into the `scales` passed in */
    double tonic_hz;             /* as sung, not snapped to A = 440 */
    double probability;
    double mean_deviation_cents;
} MQMaqamCandidate;

typedef struct {
    int32_t enough_data;         /* 0: too little or too uniform singing to make a claim */
    double sung_seconds;
    uint64_t pitch_classes;
    double tonic_hz;
    double tonic_confidence;
} MQDetectionSummary;

/* Ranks every (scale, tonic) pair, most probable first. Pass capacity = 0 to
 * learn the count; the summary is filled either way. */
MQStatus mq_detect_maqam(const MQSungNote *notes, size_t note_count, const MQScale *scales, size_t scale_count,
                         MQDetectionSummary *out_summary, MQMaqamCandidate *out_candidates, size_t capacity,
                         size_t *out_count);

/* ------------------------------------------------------------ Auto Studio */

enum {
    MQ_PROFILE_KHALEEJI = 0, MQ_PROFILE_ARABIC_POP, MQ_PROFILE_TARAB, MQ_PROFILE_SHILAT, MQ_PROFILE_IRAQI,
    MQ_PROFILE_EGYPTIAN, MQ_PROFILE_LEVANTINE, MQ_PROFILE_ACOUSTIC, MQ_PROFILE_CLEAN_STUDIO,
    MQ_PROFILE_MODERN_COMMERCIAL, MQ_PROFILE_NATURAL, MQ_PROFILE_HEAVY_AUTOTUNE, MQ_PROFILE_COUNT
};

/* Why a stage is on, off or set as it is; `a` and `b` carry the numbers. */
enum {
    MQ_REASON_HIGH_PASS = 1, MQ_REASON_RUMBLE, MQ_REASON_HUM, MQ_REASON_NO_HUM, MQ_REASON_DENOISE,
    MQ_REASON_ALREADY_CLEAN, MQ_REASON_PLOSIVES, MQ_REASON_BREATHS, MQ_REASON_NO_BREATHS, MQ_REASON_LEVELER,
    MQ_REASON_STEADY_LEVEL, MQ_REASON_MUD, MQ_REASON_BOXY, MQ_REASON_HARSH, MQ_REASON_AIR, MQ_REASON_RESONANCE,
    MQ_REASON_DE_ESS, MQ_REASON_DE_ESS_LIGHT, MQ_REASON_COMPRESS, MQ_REASON_MULTIBAND, MQ_REASON_COLOUR,
    MQ_REASON_REVERB, MQ_REASON_DELAY, MQ_REASON_LOUDNESS, MQ_REASON_LIMITER, MQ_REASON_CLIPPED_INPUT,
    MQ_REASON_TONAL
};

typedef struct { int32_t code; double a; double b; } MQStudioReason;

typedef struct {
    int32_t type;  /* 0 peaking, 1 low shelf, 2 high shelf */
    double frequency_hz;
    double q;
    double gain_db;
} MQEqBand;

typedef struct {
    int32_t enabled;
    double frequency_hz;
    double threshold_db;
    double maximum_cut_db;
} MQDynamicBand;

typedef struct {
    double threshold_db;
    double ratio;
    double attack_ms;
    double release_ms;
} MQCompressorBand;

typedef struct {
    int32_t profile;
    double high_pass_hz;          /* 0 = off */
    double hum_hz;                /* 0 = off */
    int32_t hum_harmonics;
    double denoise_db;            /* 0 = off */
    double plosive_db;            /* 0 = off */
    double breath_db;             /* 0 = off */
    int32_t leveler;
    double leveler_target_db;
    double leveler_range_db;
    MQEqBand eq[8];
    int32_t eq_count;
    MQDynamicBand de_esser;
    MQDynamicBand harshness;
    MQCompressorBand compressor;  /* ratio 1 = off */
    int32_t multiband;
    MQCompressorBand bands[3];
    double saturation_drive_db;
    double saturation_mix;
    double exciter_amount;
    double reverb_mix;
    double reverb_size;
    double reverb_damping;
    double reverb_pre_delay_ms;
    double delay_mix;
    double delay_ms;
    double delay_feedback;
    double loudness_target_lufs;
    double ceiling_db;
} MQStudioPlan;

typedef struct {
    double duration_seconds;
    double integrated_lufs;
    double peak_dbfs;
    double noise_floor_dbfs;
    double voice_level_dbfs;
    double level_spread_db;
    double hum_hz;
    double hum_strength_db;
    double sibilance_db;
    uint64_t clipped_samples;
    uint64_t breath_count;
} MQStudioMeasurements;

typedef struct MQStudioAnalyzer MQStudioAnalyzer;
typedef struct MQStudioSession MQStudioSession;
typedef struct MQStudioChain MQStudioChain;
typedef struct MQLoudnessMeter MQLoudnessMeter;

MQStudioAnalyzer *mq_studio_analyzer_create(double sample_rate);
void mq_studio_analyzer_push(MQStudioAnalyzer *analyzer, const float *mono, size_t frames);
void mq_studio_analyzer_destroy(MQStudioAnalyzer *analyzer);

/* Finishes the analysis. The pitch track (Hz per frame, 0 = unvoiced) tells
 * where the singing is, for breath and plosive handling. */
MQStudioSession *mq_studio_session_create(MQStudioAnalyzer *analyzer, const float *track_hz, size_t track_count,
                                          double track_first_time, double track_hop_seconds);
void mq_studio_session_destroy(MQStudioSession *session);
void mq_studio_measurements(const MQStudioSession *session, MQStudioMeasurements *out);

/* The adaptive plan for a profile, and the reasons behind it. */
MQStatus mq_studio_plan(const MQStudioSession *session, int32_t profile, MQStudioPlan *out_plan,
                        MQStudioReason *out_reasons, size_t capacity, size_t *out_count);
/* The tuning style a profile pairs with: an MQCorrectionPreset value. */
int32_t mq_studio_profile_tuning(int32_t profile);

MQStudioChain *mq_studio_chain_create(const MQStudioSession *session, const MQStudioPlan *plan, double output_gain_db,
                                      int32_t limit);
void mq_studio_chain_process(MQStudioChain *chain, const float *mono, float *left, float *right, size_t frames);
uint64_t mq_studio_chain_latency(const MQStudioChain *chain);
uint64_t mq_studio_chain_tail(const MQStudioChain *chain);
void mq_studio_chain_destroy(MQStudioChain *chain);

/* ITU-R BS.1770-4 integrated loudness. `right` may be NULL for mono. */
MQLoudnessMeter *mq_loudness_create(double sample_rate, int32_t channels);
void mq_loudness_push(MQLoudnessMeter *meter, const float *left, const float *right, size_t frames);
double mq_loudness_integrated(const MQLoudnessMeter *meter);
void mq_loudness_destroy(MQLoudnessMeter *meter);

/* ---- Export ------------------------------------------------------------- */

typedef enum {
    MQ_EXPORT_WAV = 0,
    MQ_EXPORT_FLAC = 1,
    MQ_EXPORT_MP3 = 2,
} MQExportFormat;

typedef enum {
    MQ_EXPORT_OK = 0,
    MQ_EXPORT_BAD_SAMPLE_RATE = 1,
    MQ_EXPORT_BAD_CHANNELS = 2,
    MQ_EXPORT_BAD_BITS = 3,
    MQ_EXPORT_BAD_BITRATE = 4,
    MQ_EXPORT_MP3_SAMPLE_RATE = 5,
} MQExportProblem;

typedef struct {
    int32_t format;             /* MQExportFormat */
    double sample_rate;
    int32_t channels;           /* 1 or 2 */
    int32_t bits;               /* WAV 16/24/32 (float); FLAC 16/24; ignored for MP3 */
    int32_t mp3_kbps;           /* 128, 160, 192, 224, 256 or 320 */
    int32_t resampler_quality;  /* 0 fast, 1 good, 2 best */
    double gain_db;
    int32_t limit;              /* true-peak limiter at ceiling_db */
    double ceiling_db;
    int32_t dither;             /* TPDF dither for integer formats */
} MQExportSettings;

typedef struct {
    uint64_t frames;
    double integrated_lufs;
    double true_peak_dbtp;
    double sample_peak_dbfs;
    uint64_t clipped_samples;
    double maximum_reduction_db;
    uint64_t bytes;
} MQExportStats;

typedef struct MQExporter MQExporter;

void mq_export_default_settings(MQExportSettings *out);
/* Why these settings cannot be used for this input (MQ_EXPORT_OK if they can). */
int32_t mq_export_check(const MQExportSettings *settings, double input_rate, int32_t input_channels);
/* `path` NULL measures without writing. Returns NULL if the settings are not
 * usable or the file cannot be created. */
MQExporter *mq_exporter_create(const MQExportSettings *settings, double input_rate, int32_t input_channels,
                               const char *path);
/* Interleaved input frames. */
MQStatus mq_exporter_push(MQExporter *exporter, const float *interleaved, size_t frames);
/* Flushes, finishes the file (lengths, checksums) and reports what was written. */
MQStatus mq_exporter_finish(MQExporter *exporter, MQExportStats *out);
void mq_exporter_destroy(MQExporter *exporter);

#ifdef __cplusplus
}
#endif

#endif /* MAQAM_CORE_H */
