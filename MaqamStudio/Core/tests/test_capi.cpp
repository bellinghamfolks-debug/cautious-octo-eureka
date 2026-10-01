#include "maqam_core.h"
#include "test_support.hpp"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <string>

TEST_CASE("the C interface lists every built-in maqam with its Arabic name") {
    const int32_t count = mq_maqam_count();
    CHECK(count >= 15);
    bool foundRast = false;
    for (int32_t i = 0; i < count; ++i) {
        MQMaqamInfo info;
        CHECK(mq_maqam_info(i, &info) == MQ_OK);
        CHECK(std::strlen(info.arabic_name) > 0);
        if (std::strcmp(info.id, "rast") == 0) {
            foundRast = true;
            CHECK(std::strcmp(info.arabic_name, "راست") == 0);
            CHECK(info.scale.degrees[2].cents == 350.0);
            CHECK(info.scale.degrees[6].alternate_count == 1);
            const MQTargetMatch match = mq_nearest_target(&info.scale, 344.0);
            CHECK(match.degree_index == 2);
            CHECK_NEAR(match.deviation_cents, -6.0, 1e-9);
        }
    }
    CHECK(foundRast);
    MQMaqamInfo unused;
    CHECK(mq_maqam_info(count, &unused) == MQ_ERROR_NOT_FOUND);
}

TEST_CASE("the C pitch tracker reports its size and then fills it") {
    const auto tone = synth::tone(196.0, 1.0, 48000.0, 0.4, 4);
    MQPitchConfig config = mq_pitch_default_config(48000.0);
    size_t needed = 0;
    CHECK(mq_pitch_track(tone.data(), tone.size(), &config, 512, nullptr, nullptr, 0, &needed) == MQ_OK);
    CHECK(needed > 80);
    std::vector<double> times(needed);
    std::vector<MQPitchEstimate> estimates(needed);
    size_t written = 0;
    CHECK(mq_pitch_track(tone.data(), tone.size(), &config, 512, times.data(), estimates.data(), needed, &written) == MQ_OK);
    CHECK(written == needed);
    CHECK_NEAR(mq_hz_to_cents(estimates[needed / 2].frequency_hz, 196.0), 0.0, 3.0);
}

TEST_CASE("the C interface refuses bad arguments instead of crashing") {
    MQLevelReport report;
    CHECK(mq_analyze_levels(nullptr, 10, 1, 48000.0, &report) == MQ_ERROR_INVALID_ARGUMENT);
    float sample = 0.0f;
    CHECK(mq_analyze_levels(&sample, 1, 0, 48000.0, &report) == MQ_ERROR_INVALID_ARGUMENT);
    MQPitchConfig bad = mq_pitch_default_config(48000.0);
    bad.maximum_hz = 10.0;
    CHECK(mq_pitch_create(&bad) == nullptr);
    CHECK(mq_pitch_detect(nullptr, &sample, nullptr) == MQ_ERROR_INVALID_ARGUMENT);
    mq_pitch_destroy(nullptr);
}

TEST_CASE("a detector created through the C interface runs frame by frame") {
    MQPitchConfig config = mq_pitch_default_config(44100.0);
    MQPitchDetector* detector = mq_pitch_create(&config);
    CHECK(detector != nullptr);
    const auto tone = synth::tone(329.63, 0.3, 44100.0, 0.4, 3);
    MQPitchEstimate estimate;
    CHECK(mq_pitch_detect(detector, tone.data() + 2000, &estimate) == MQ_OK);
    CHECK(estimate.voiced == 1);
    CHECK_NEAR(mq_hz_to_cents(estimate.frequency_hz, 329.63), 0.0, 3.0);
    mq_pitch_destroy(detector);
}

TEST_CASE("the streaming C accumulators agree with the one-shot calls") {
    const auto tone = synth::tone(261.63, 1.0, 48000.0, 0.5, 2);
    MQLevelReport whole;
    CHECK(mq_analyze_levels(tone.data(), tone.size(), 1, 48000.0, &whole) == MQ_OK);
    MQLevelAccumulator* levels = mq_level_accumulator_create(1, 48000.0);
    MQWaveformAccumulator* waveform = mq_waveform_accumulator_create(tone.size(), 1, 64);
    CHECK(levels != nullptr);
    CHECK(waveform != nullptr);
    for (size_t offset = 0; offset < tone.size(); offset += 8192) {
        const size_t count = std::min<size_t>(8192, tone.size() - offset);
        mq_level_accumulator_push(levels, tone.data() + offset, count);
        mq_waveform_accumulator_push(waveform, tone.data() + offset, count);
    }
    MQLevelReport streamed;
    CHECK(mq_level_accumulator_finish(levels, &streamed) == MQ_OK);
    CHECK_NEAR(streamed.rms_dbfs, whole.rms_dbfs, 1e-9);
    std::vector<float> low(64), high(64), rms(64);
    CHECK(mq_waveform_accumulator_read(waveform, low.data(), high.data(), rms.data()) == MQ_OK);
    std::vector<float> wholeLow(64), wholeHigh(64);
    CHECK(mq_waveform_peaks(tone.data(), tone.size(), 1, 64, wholeLow.data(), wholeHigh.data()) == MQ_OK);
    for (size_t b = 0; b < 64; ++b) {
        CHECK_NEAR(high[b], wholeHigh[b], 1e-7);
        CHECK_NEAR(low[b], wholeLow[b], 1e-7);
    }
    CHECK(mq_waveform_accumulator_create(0, 1, 64) == nullptr);
    mq_level_accumulator_destroy(levels);
    mq_waveform_accumulator_destroy(waveform);
}

TEST_CASE("the C tracker, note segmenter and intonation judge work end to end") {
    const double rate = 48000.0;
    const double d4 = 293.6648;
    std::vector<float> audio;
    for (double cents : {0.0, 150.0, 320.0}) {  // third degree 20 cents sharp
        const auto note = synth::tone(d4 * std::exp2(cents / 1200.0), 0.5, rate, 0.4, 4);
        audio.insert(audio.end(), note.begin(), note.end());
        audio.insert(audio.end(), static_cast<std::size_t>(0.1 * rate), 0.0f);
    }
    MQPitchConfig config = mq_pitch_default_config(rate);
    MQPitchTracker* tracker = mq_pitch_tracker_create(&config, 480);
    CHECK(tracker != nullptr);
    for (std::size_t start = 0; start < audio.size(); start += 1000) {
        mq_pitch_tracker_push(tracker, audio.data() + start, std::min<std::size_t>(1000, audio.size() - start));
    }
    const size_t frames = mq_pitch_tracker_count(tracker);
    std::size_t expected = 0;
    CHECK(mq_pitch_track(audio.data(), audio.size(), &config, 480, nullptr, nullptr, 0, &expected) == MQ_OK);
    CHECK(frames == expected);
    std::vector<double> times(frames);
    std::vector<MQPitchEstimate> estimates(frames);
    CHECK(mq_pitch_tracker_read(tracker, times.data(), estimates.data(), frames) == frames);
    mq_pitch_tracker_destroy(tracker);

    const MQNoteConfig noteConfig = mq_note_default_config(440.0);
    size_t count = 0;
    CHECK(mq_segment_notes(times.data(), estimates.data(), frames, &noteConfig, nullptr, 0, &count) == MQ_OK);
    CHECK(count == 3);
    std::vector<MQSungNote> notes(count);
    CHECK(mq_segment_notes(times.data(), estimates.data(), frames, &noteConfig, notes.data(), count, &count) == MQ_OK);

    MQMaqamInfo bayati{};
    for (int32_t i = 0; i < mq_maqam_count(); ++i) {
        mq_maqam_info(i, &bayati);
        if (std::string(bayati.id) == "bayati") break;
    }
    std::vector<MQNoteMatch> matches(count);
    MQIntonationSummary summary{};
    CHECK(mq_evaluate_intonation(&bayati.scale, d4, notes.data(), count, 15.0, matches.data(), &summary) == MQ_OK);
    CHECK(summary.note_count == 3);
    CHECK(summary.in_tune_count == 2);
    if (count == 3) {
        CHECK(matches[2].target.degree_index == 2);
        CHECK_NEAR(matches[2].target.deviation_cents, 20.0, 3.0);
        CHECK(matches[2].in_tune == 0);
    }
    CHECK_NEAR(summary.degrees[2].mean_deviation_cents, 20.0, 3.0);

    CHECK(mq_evaluate_intonation(nullptr, d4, notes.data(), count, 15.0, nullptr, &summary) == MQ_ERROR_INVALID_ARGUMENT);
    CHECK(mq_evaluate_intonation(&bayati.scale, 0.0, notes.data(), count, 15.0, nullptr, &summary) == MQ_ERROR_INVALID_ARGUMENT);
    CHECK(mq_segment_notes(nullptr, nullptr, 5, &noteConfig, nullptr, 0, &count) == MQ_ERROR_INVALID_ARGUMENT);
    CHECK(mq_pitch_tracker_create(&config, 0) == nullptr);
    CHECK(mq_pitch_default_config(96000.0).frame_size == 4096);
}
