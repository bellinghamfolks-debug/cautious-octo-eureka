#include "maqam/levels.hpp"
#include "test_support.hpp"

using namespace maqam;

TEST_CASE("a half-scale sine peaks at -6 dBFS and has -9 dBFS RMS") {
    const auto sine = signal::tone(1000.0, 1.0, 48000.0, 0.5);
    const LevelReport report = analyzeLevels(sine.data(), sine.size(), 1, 48000.0);
    CHECK_NEAR(report.peakDbfs, -6.0206, 0.01);
    CHECK_NEAR(report.rmsDbfs, -9.0309, 0.01);
    CHECK_NEAR(report.crestDb, 3.0103, 0.02);
    CHECK(report.clippedSamples == 0);
    CHECK_NEAR(report.dcOffset, 0.0, 1e-4);
}

TEST_CASE("clipped samples are counted") {
    auto sine = signal::tone(200.0, 0.5, 44100.0, 1.4);
    for (float& x : sine) x = std::fmax(-1.0f, std::fmin(1.0f, x));
    const LevelReport report = analyzeLevels(sine.data(), sine.size(), 1, 44100.0);
    CHECK(report.clippedSamples > 1000);
    CHECK_NEAR(report.peakDbfs, 0.0, 0.01);
}

TEST_CASE("silence reports the floor rather than infinities") {
    std::vector<float> silence(48000, 0.0f);
    const LevelReport report = analyzeLevels(silence.data(), silence.size(), 1, 48000.0);
    CHECK(report.peakDbfs == kSilenceDbfs);
    CHECK(report.rmsDbfs == kSilenceDbfs);
    CHECK(std::isfinite(report.crestDb));
}

TEST_CASE("noise floor comes from the quiet part and dynamic range spans both") {
    auto quiet = signal::noise(48000, 0.001);   // about -65 dBFS RMS
    auto loud = signal::tone(300.0, 2.0, 48000.0, 0.5);
    std::vector<float> take(quiet);
    take.insert(take.end(), loud.begin(), loud.end());
    const LevelReport report = analyzeLevels(take.data(), take.size(), 1, 48000.0);
    CHECK(report.noiseFloorDbfs < -60.0);
    CHECK(report.noiseFloorDbfs > -70.0);
    CHECK(report.dynamicRangeDb > 50.0);
}

TEST_CASE("stereo is analysed across both channels") {
    std::vector<float> stereo(2 * 4800);
    for (std::size_t i = 0; i < 4800; ++i) { stereo[2 * i] = 0.25f; stereo[2 * i + 1] = -0.75f; }
    const LevelReport report = analyzeLevels(stereo.data(), 4800, 2, 48000.0);
    CHECK_NEAR(report.peak, 0.75, 1e-6);
    CHECK_NEAR(report.dcOffset, -0.25, 1e-6);
}

TEST_CASE("waveform peaks keep the extremes of every bucket") {
    std::vector<float> ramp(1000);
    for (std::size_t i = 0; i < ramp.size(); ++i) ramp[i] = static_cast<float>(i) / 1000.0f - 0.5f;
    std::vector<float> low(10), high(10);
    waveformPeaks(ramp.data(), ramp.size(), 1, 10, low.data(), high.data());
    CHECK_NEAR(low[0], -0.5, 1e-6);
    CHECK_NEAR(high[0], -0.401, 1e-6);
    CHECK_NEAR(high[9], 0.499, 1e-6);
    for (std::size_t b = 1; b < 10; ++b) CHECK(low[b] > low[b - 1]);
}

TEST_CASE("waveform handles more buckets than frames") {
    std::vector<float> three = {0.1f, -0.2f, 0.3f};
    std::vector<float> low(8), high(8), rms(8);
    waveformPeaks(three.data(), 3, 1, 8, low.data(), high.data());
    waveformRmsDbfs(three.data(), 3, 1, 8, rms.data());
    CHECK_NEAR(high[7], 0.3, 1e-6);
    for (float value : rms) CHECK(std::isfinite(value));
}

TEST_CASE("streaming level analysis in odd-sized chunks equals one pass") {
    auto quiet = signal::noise(30000, 0.002, 7);
    auto loud = signal::tone(220.0, 1.5, 44100.0, 0.6, 4);
    std::vector<float> take(quiet);
    take.insert(take.end(), loud.begin(), loud.end());
    const LevelReport whole = analyzeLevels(take.data(), take.size(), 1, 44100.0);
    LevelAccumulator streaming(1, 44100.0);
    for (std::size_t offset = 0; offset < take.size(); offset += 4093) {
        streaming.push(take.data() + offset, std::min<std::size_t>(4093, take.size() - offset));
    }
    const LevelReport chunked = streaming.finish();
    CHECK_NEAR(chunked.peakDbfs, whole.peakDbfs, 1e-9);
    CHECK_NEAR(chunked.rmsDbfs, whole.rmsDbfs, 1e-9);
    CHECK_NEAR(chunked.noiseFloorDbfs, whole.noiseFloorDbfs, 1e-9);
    CHECK_NEAR(chunked.dynamicRangeDb, whole.dynamicRangeDb, 1e-9);
    CHECK(chunked.frames == whole.frames);
}

TEST_CASE("streaming waveform in chunks equals the one-pass summary") {
    const auto tone = signal::tone(110.0, 2.0, 48000.0, 0.7, 3);
    const std::size_t buckets = 300;
    std::vector<float> low(buckets), high(buckets), rms(buckets);
    waveformPeaks(tone.data(), tone.size(), 1, buckets, low.data(), high.data());
    waveformRmsDbfs(tone.data(), tone.size(), 1, buckets, rms.data());
    WaveformAccumulator streaming(tone.size(), 1, buckets);
    for (std::size_t offset = 0; offset < tone.size(); offset += 10007) {
        streaming.push(tone.data() + offset, std::min<std::size_t>(10007, tone.size() - offset));
    }
    std::vector<float> streamRms(buckets);
    streaming.rmsDbfs(streamRms.data());
    for (std::size_t b = 0; b < buckets; ++b) {
        CHECK_NEAR(streaming.minimum()[b], low[b], 1e-7);
        CHECK_NEAR(streaming.maximum()[b], high[b], 1e-7);
        CHECK_NEAR(streamRms[b], rms[b], 1e-3);
    }
}

TEST_CASE("a waveform fed more frames than the header promised stays in bounds") {
    const auto tone = signal::tone(300.0, 0.2, 48000.0, 0.5);
    WaveformAccumulator streaming(tone.size() / 2, 1, 50);
    streaming.push(tone.data(), tone.size());
    CHECK(streaming.buckets() == 50);
    CHECK(streaming.maximum()[49] > 0.0f);
}
