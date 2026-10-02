#include "maqam_core.h"
#include "test_support.hpp"
#include "third_party/decoders.hpp"
#include <cstdio>
#include <vector>

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

TEST_CASE("the C correction and PSOLA path tunes a sharp quarter-tone note") {
    const double rate = 48000.0;
    const double d4 = 293.6648;
    const auto audio = synth::tone(d4 * std::exp2(175.0 / 1200.0), 1.0, rate, 0.3, 5);  // Bayati's 150, sung at 175
    MQPitchConfig config = mq_pitch_default_config(rate);
    size_t frames = 0;
    mq_pitch_track(audio.data(), audio.size(), &config, 480, nullptr, nullptr, 0, &frames);
    std::vector<double> times(frames);
    std::vector<MQPitchEstimate> estimates(frames);
    mq_pitch_track(audio.data(), audio.size(), &config, 480, times.data(), estimates.data(), frames, &frames);
    const MQNoteConfig noteConfig = mq_note_default_config(d4);
    size_t noteCount = 0;
    mq_segment_notes(times.data(), estimates.data(), frames, &noteConfig, nullptr, 0, &noteCount);
    std::vector<MQSungNote> notes(noteCount);
    mq_segment_notes(times.data(), estimates.data(), frames, &noteConfig, notes.data(), noteCount, &noteCount);
    CHECK(noteCount == 1);

    MQMaqamInfo bayati{};
    for (int32_t i = 0; i < mq_maqam_count(); ++i) {
        mq_maqam_info(i, &bayati);
        if (std::string(bayati.id) == "bayati") break;
    }
    MQCorrectionSettings robotic = mq_correction_preset(MQ_CORRECTION_ROBOTIC);
    std::vector<double> shift(frames);
    std::vector<double> targets(noteCount);
    CHECK(mq_compute_correction(times.data(), estimates.data(), frames, notes.data(), noteCount, nullptr,
                                &bayati.scale, d4, &robotic, shift.data(), targets.data()) == MQ_OK);
    if (noteCount == 1) CHECK_NEAR(targets[0], 150.0, 1e-9);

    std::vector<float> hz(frames);
    for (size_t i = 0; i < frames; ++i) hz[i] = estimates[i].voiced ? static_cast<float>(estimates[i].frequency_hz) : 0.0f;
    MQMarkFinder* finder = mq_marks_create(rate, times[0], 0.01, hz.data(), frames);
    mq_marks_push(finder, audio.data(), audio.size());
    MQGrainPlan* plan = mq_grain_plan_create(finder, shift.data(), frames, times[0], 0.01, rate, audio.size(), 0.0, 1);
    mq_marks_destroy(finder);
    CHECK(plan != nullptr);
    std::vector<float> out(audio.size());
    const float* in[] = {audio.data()};
    float* outs[] = {out.data()};
    CHECK(mq_grain_plan_render(plan, in, 1, 0, audio.size(), 0, out.size(), outs) == MQ_OK);
    int64_t from = -1, to = -1;
    mq_grain_plan_input_range(plan, 1000, 500, &from, &to);
    CHECK(from >= 0 && from <= 1000 && to >= 1500);
    mq_grain_plan_destroy(plan);

    MQPitchDetector* detector = mq_pitch_create(&config);
    MQPitchEstimate estimate{};
    mq_pitch_detect(detector, out.data() + out.size() / 2, &estimate);
    mq_pitch_destroy(detector);
    CHECK(estimate.voiced != 0);
    CHECK_NEAR(1200.0 * std::log2(estimate.frequency_hz / d4), 150.0, 3.0);

    CHECK(mq_compute_correction(times.data(), estimates.data(), frames, notes.data(), noteCount, nullptr,
                                &bayati.scale, 0.0, &robotic, shift.data(), nullptr) == MQ_ERROR_INVALID_ARGUMENT);
    CHECK(mq_grain_plan_create(nullptr, shift.data(), frames, 0, 0.01, rate, 10, 0, 1) == nullptr);
}

TEST_CASE("the C detector ranks maqamat and reports when it cannot tell") {
    std::vector<MQScale> scales;
    int32_t saba = -1;
    for (int32_t i = 0; i < mq_maqam_count(); ++i) {
        MQMaqamInfo info{};
        mq_maqam_info(i, &info);
        if (std::string(info.id) == "saba") saba = i;
        scales.push_back(info.scale);
    }
    const double d4 = 293.6648;
    std::vector<MQSungNote> notes;
    double t = 0.0;
    for (double cents : {0.0, 150.0, 300.0, 400.0, 700.0, 800.0, 1000.0, 800.0, 700.0, 400.0, 300.0, 150.0, 0.0}) {
        MQSungNote note{};
        note.start_seconds = t;
        note.end_seconds = t + 0.45;
        note.hz = d4 * std::exp2(cents / 1200.0);
        notes.push_back(note);
        t += 0.5;
    }
    notes.back().end_seconds += 0.8;
    MQDetectionSummary summary{};
    size_t count = 0;
    CHECK(mq_detect_maqam(notes.data(), notes.size(), scales.data(), scales.size(), &summary, nullptr, 0, &count) == MQ_OK);
    CHECK(count > 0);
    CHECK(summary.enough_data == 1);
    std::vector<MQMaqamCandidate> ranked(count);
    CHECK(mq_detect_maqam(notes.data(), notes.size(), scales.data(), scales.size(), &summary, ranked.data(), count, &count) == MQ_OK);
    CHECK(static_cast<int32_t>(ranked[0].scale_index) == saba);
    CHECK_NEAR(1200.0 * std::log2(ranked[0].tonic_hz / d4), 0.0, 2.0);
    CHECK(ranked[0].probability >= ranked[1].probability);

    CHECK(mq_detect_maqam(notes.data(), 1, scales.data(), scales.size(), &summary, nullptr, 0, &count) == MQ_OK);
    CHECK(summary.enough_data == 0);
    CHECK(mq_detect_maqam(nullptr, 3, scales.data(), scales.size(), &summary, nullptr, 0, &count) == MQ_ERROR_INVALID_ARGUMENT);
}

TEST_CASE("the C studio path analyses, plans with reasons, and renders to target") {
    const double rate = 48000.0;
    std::vector<float> voice;
    for (int note = 0; note < 6; ++note) {
        const auto part = synth::tone(220.0 * std::exp2(note * 150.0 / 1200.0), 0.6, rate, 0.25, 6);
        voice.insert(voice.end(), part.begin(), part.end());
        voice.insert(voice.end(), 12000, 0.0f);
    }
    const auto noise = synth::noise(voice.size(), 0.003, 5);
    for (std::size_t i = 0; i < voice.size(); ++i) voice[i] += noise[i];

    MQStudioAnalyzer* analyzer = mq_studio_analyzer_create(rate);
    mq_studio_analyzer_push(analyzer, voice.data(), voice.size());
    MQPitchConfig config = mq_pitch_default_config(rate);
    size_t frames = 0;
    mq_pitch_track(voice.data(), voice.size(), &config, 480, nullptr, nullptr, 0, &frames);
    std::vector<double> times(frames);
    std::vector<MQPitchEstimate> estimates(frames);
    mq_pitch_track(voice.data(), voice.size(), &config, 480, times.data(), estimates.data(), frames, &frames);
    std::vector<float> hz(frames);
    for (size_t i = 0; i < frames; ++i) hz[i] = estimates[i].voiced ? static_cast<float>(estimates[i].frequency_hz) : 0.0f;
    MQStudioSession* session = mq_studio_session_create(analyzer, hz.data(), frames, times[0], 0.01);
    mq_studio_analyzer_destroy(analyzer);
    CHECK(session != nullptr);

    MQStudioMeasurements measured{};
    mq_studio_measurements(session, &measured);
    CHECK(measured.duration_seconds > 5.0);
    CHECK(measured.noise_floor_dbfs < -40.0);

    MQStudioPlan plan{};
    MQStudioReason reasons[64];
    size_t count = 0;
    CHECK(mq_studio_plan(session, MQ_PROFILE_KHALEEJI, &plan, reasons, 64, &count) == MQ_OK);
    CHECK(count > 5);
    CHECK(plan.denoise_db > 0.0);
    CHECK(mq_studio_plan(session, 99, &plan, nullptr, 0, nullptr) == MQ_ERROR_INVALID_ARGUMENT);
    CHECK(mq_studio_profile_tuning(MQ_PROFILE_HEAVY_AUTOTUNE) == MQ_CORRECTION_ROBOTIC);

    auto renderAt = [&](double gain, int32_t limit, std::vector<float>& left, std::vector<float>& right) {
        MQStudioChain* chain = mq_studio_chain_create(session, &plan, gain, limit);
        std::vector<float> input = voice;
        input.resize(voice.size() + mq_studio_chain_tail(chain), 0.0f);
        left.assign(input.size(), 0.0f);
        right.assign(input.size(), 0.0f);
        mq_studio_chain_process(chain, input.data(), left.data(), right.data(), input.size());
        const auto latency = static_cast<std::ptrdiff_t>(mq_studio_chain_latency(chain));
        left.erase(left.begin(), left.begin() + latency);
        right.erase(right.begin(), right.begin() + latency);
        mq_studio_chain_destroy(chain);
        MQLoudnessMeter* meter = mq_loudness_create(rate, 2);
        mq_loudness_push(meter, left.data(), right.data(), left.size());
        const double lufs = mq_loudness_integrated(meter);
        mq_loudness_destroy(meter);
        return lufs;
    };
    std::vector<float> left, right;
    const double before = renderAt(0.0, 0, left, right);
    const double after = renderAt(plan.loudness_target_lufs - before, 1, left, right);
    CHECK_NEAR(after, plan.loudness_target_lufs, 0.8);
    float peak = 0.0f;
    for (std::size_t i = 0; i < left.size(); ++i) peak = std::max({peak, std::fabs(left[i]), std::fabs(right[i])});
    CHECK(peak <= std::pow(10.0f, static_cast<float>(plan.ceiling_db) / 20.0f) + 1e-6f);
    mq_studio_session_destroy(session);
}

TEST_CASE("the C export path writes a file another decoder reads, and refuses bad settings") {
    MQExportSettings settings;
    mq_export_default_settings(&settings);
    settings.format = MQ_EXPORT_FLAC;
    settings.bits = 16;
    settings.sample_rate = 44100.0;
    CHECK(mq_export_check(&settings, 48000.0, 1) == MQ_EXPORT_OK);
    const std::string path = "/tmp/maqam_capi_export_test.flac";
    MQExporter* exporter = mq_exporter_create(&settings, 48000.0, 1, path.c_str());
    CHECK(exporter != nullptr);
    const std::vector<float> tone = synth::tone(440.0, 1.0, 48000.0, 0.25);
    CHECK(mq_exporter_push(exporter, tone.data(), tone.size()) == MQ_OK);
    MQExportStats stats;
    CHECK(mq_exporter_finish(exporter, &stats) == MQ_OK);
    CHECK(mq_exporter_finish(exporter, &stats) == MQ_ERROR_INVALID_ARGUMENT);  // only once
    mq_exporter_destroy(exporter);
    CHECK(stats.frames == 44100);
    CHECK_NEAR(stats.sample_peak_dbfs, 20.0 * std::log10(0.25), 0.2);
    std::FILE* file = std::fopen(path.c_str(), "rb");
    CHECK(file != nullptr);
    std::vector<std::uint8_t> bytes;
    if (file) {
        std::uint8_t buffer[4096];
        std::size_t read;
        while ((read = std::fread(buffer, 1, sizeof buffer, file)) > 0) bytes.insert(bytes.end(), buffer, buffer + read);
        std::fclose(file);
    }
    std::remove(path.c_str());
    CHECK(bytes.size() == stats.bytes);
    std::vector<std::int32_t> samples;
    unsigned channels = 0, rate = 0;
    CHECK(thirdparty::decodeFlac(bytes, samples, channels, rate));
    CHECK(channels == 2 && rate == 44100 && samples.size() == 2 * 44100);

    settings.format = MQ_EXPORT_MP3;
    settings.sample_rate = 22050.0;
    CHECK(mq_export_check(&settings, 48000.0, 1) == MQ_EXPORT_MP3_SAMPLE_RATE);
    CHECK(mq_exporter_create(&settings, 48000.0, 1, nullptr) == nullptr);
    settings.sample_rate = 48000.0;
    CHECK(mq_exporter_create(&settings, 48000.0, 1, "/nonexistent-folder/x.mp3") == nullptr);
}

TEST_CASE("the C separation path renders stems that add up to the mix") {
    CHECK(mq_separation_create(0.0) == nullptr);
    MQSeparation* separation = mq_separation_create(44100.0);
    CHECK(separation != nullptr);
    const std::vector<float> tone = synth::tone(330.0, 2.0, 44100.0, 0.3);
    std::vector<float> stereo(2 * tone.size());
    for (std::size_t i = 0; i < tone.size(); ++i) { stereo[2 * i] = tone[i]; stereo[2 * i + 1] = 0.5f * tone[i]; }
    CHECK(mq_separation_analyse(separation, stereo.data(), tone.size()) == MQ_OK);
    CHECK(mq_separation_finish_analysis(separation) == MQ_OK);
    const std::size_t frames = mq_separation_frame_count(separation);
    CHECK(frames == (tone.size() + 3 * 1024 + 1023) / 1024);
    std::vector<float> magnitudes(10 * mq_separation_bins());
    CHECK(mq_separation_magnitudes(separation, 0, 10, magnitudes.data()) == MQ_OK);
    CHECK(mq_separation_estimate_classical(separation, 0, frames) == MQ_OK);
    CHECK(mq_separation_render(separation, stereo.data(), tone.size()) == MQ_OK);
    CHECK(mq_separation_finish_render(separation) == MQ_OK);
    std::vector<float> vocals(stereo.size()), rest(stereo.size());
    CHECK(mq_separation_pull(separation, vocals.data(), rest.data(), tone.size()) == tone.size());
    double worst = 0.0;
    for (std::size_t i = 0; i < stereo.size(); ++i) worst = std::max(worst, std::fabs(static_cast<double>(vocals[i]) + rest[i] - stereo[i]));
    CHECK(worst < 1e-5);
    mq_separation_destroy(separation);
}
