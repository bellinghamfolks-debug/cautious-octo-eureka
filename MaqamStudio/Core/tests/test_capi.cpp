#include "maqam_core.h"
#include "test_support.hpp"

#include <algorithm>
#include <cstring>

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
    const auto tone = signal::tone(196.0, 1.0, 48000.0, 0.4, 4);
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
    const auto tone = signal::tone(329.63, 0.3, 44100.0, 0.4, 3);
    MQPitchEstimate estimate;
    CHECK(mq_pitch_detect(detector, tone.data() + 2000, &estimate) == MQ_OK);
    CHECK(estimate.voiced == 1);
    CHECK_NEAR(mq_hz_to_cents(estimate.frequency_hz, 329.63), 0.0, 3.0);
    mq_pitch_destroy(detector);
}

TEST_CASE("the streaming C accumulators agree with the one-shot calls") {
    const auto tone = signal::tone(261.63, 1.0, 48000.0, 0.5, 2);
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
