#include "maqam/encode.hpp"
#include "maqam/export.hpp"
#include "test_support.hpp"
#include "third_party/decoders.hpp"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <random>
#include <string>

using namespace maqam;
using thirdparty::decodeMp3;

namespace {

std::string hex(const std::array<std::uint8_t, 16>& digest) {
    std::string out;
    char buffer[3];
    for (std::uint8_t byte : digest) {
        std::snprintf(buffer, sizeof buffer, "%02x", byte);
        out += buffer;
    }
    return out;
}

std::string md5Of(const std::string& text) {
    Md5 md5;
    md5.update(text.data(), text.size());
    return hex(md5.finish());
}

// A sung-like test signal: a harmonic voice with vibrato over a little noise,
// aperiodic enough to measure delays by correlation. Interleaved.
std::vector<float> voice(double rate, double seconds, int channels, double amplitude = 0.3) {
    const auto frames = static_cast<std::size_t>(rate * seconds);
    std::vector<float> out(frames * static_cast<std::size_t>(channels));
    std::mt19937 rng(11);
    std::normal_distribution<double> noise(0.0, 1.0);
    double phase = 0.0;
    for (std::size_t i = 0; i < frames; ++i) {
        const double t = static_cast<double>(i) / rate;
        const double hz = 220.0 * std::pow(2.0, (300.0 * t + 30.0 * std::sin(2 * synth::kPi * 5.5 * t)) / 1200.0);
        phase += 2 * synth::kPi * hz / rate;
        double s = 0.0;
        for (int h = 1; h <= 8; ++h) s += std::sin(h * phase) / h;
        const double envelope = std::min(1.0, std::min(t, seconds - t) * 20.0);
        for (int c = 0; c < channels; ++c) {
            const double v = amplitude * envelope * s / 2.72 * (c == 1 ? 0.85 : 1.0) + 0.002 * noise(rng);
            out[i * static_cast<std::size_t>(channels) + static_cast<std::size_t>(c)] = static_cast<float>(v);
        }
    }
    return out;
}

// Delay (in frames) of `y` against `x`, channel 0, by cross-correlation.
std::size_t delayOf(const std::vector<float>& x, int xc, const std::vector<float>& y, int yc, std::size_t maxDelay) {
    std::size_t best = 0;
    double bestValue = -1e300;
    const std::size_t frames = x.size() / static_cast<std::size_t>(xc);
    for (std::size_t lag = 0; lag <= maxDelay; ++lag) {
        double sum = 0.0;
        for (std::size_t i = frames / 4; i < frames / 2; ++i) {
            if ((i + lag) * static_cast<std::size_t>(yc) >= y.size()) break;
            sum += static_cast<double>(x[i * static_cast<std::size_t>(xc)]) * y[(i + lag) * static_cast<std::size_t>(yc)];
        }
        if (sum > bestValue) { bestValue = sum; best = lag; }
    }
    return best;
}

struct Comparison {
    double snrDb = 0.0;
    double gain = 0.0;
};

Comparison compare(const std::vector<float>& x, const std::vector<float>& y, int channels, int channel, std::size_t delay) {
    const std::size_t ch = static_cast<std::size_t>(channels);
    const std::size_t frames = x.size() / ch;
    double signal = 0.0, error = 0.0, cross = 0.0;
    for (std::size_t i = frames / 10; i < frames - frames / 10; ++i) {
        const double a = x[i * ch + static_cast<std::size_t>(channel)];
        const double b = y[(i + delay) * ch + static_cast<std::size_t>(channel)];
        signal += a * a;
        error += (a - b) * (a - b);
        cross += a * b;
    }
    return {10.0 * std::log10(signal / std::max(error, 1e-30)), cross / signal};
}

std::vector<std::uint8_t> encodeMp3(const std::vector<float>& x, int rate, int channels, int kbps) {
    MemorySink sink;
    Mp3Encoder encoder(sink, static_cast<std::uint32_t>(rate), channels, kbps);
    const std::size_t frames = x.size() / static_cast<std::size_t>(channels);
    // Odd chunk sizes, as the app's reader delivers.
    for (std::size_t start = 0; start < frames; start += 4093) {
        const std::size_t count = std::min<std::size_t>(4093, frames - start);
        CHECK(encoder.writeFloat(x.data() + start * static_cast<std::size_t>(channels), count));
    }
    CHECK(encoder.finish());
    return sink.bytes();
}

double toneAmplitude(const std::vector<float>& x, int channels, double hz, double rate, std::size_t from, std::size_t to) {
    double re = 0.0, im = 0.0;
    for (std::size_t i = from; i < to; ++i) {
        const double v = x[i * static_cast<std::size_t>(channels)];
        re += v * std::cos(2 * synth::kPi * hz * i / rate);
        im += v * std::sin(2 * synth::kPi * hz * i / rate);
    }
    return 2.0 * std::hypot(re, im) / static_cast<double>(to - from);
}

}  // namespace

TEST_CASE("MD5 gives the published digests") {
    CHECK(md5Of("") == "d41d8cd98f00b204e9800998ecf8427e");
    CHECK(md5Of("abc") == "900150983cd24fb0d6963f7d28e17f72");
    CHECK(md5Of("The quick brown fox jumps over the lazy dog") == "9e107d9d372bb6826bd81d3542a419d6");
    std::string long_(1000, 'a');
    CHECK(md5Of(long_) == "cabe45dcc9ae5b66ba86600cca6b8ba8");
    // Fed in uneven pieces, the same digest.
    Md5 pieces;
    for (std::size_t i = 0; i < long_.size(); i += 37) pieces.update(long_.data() + i, std::min<std::size_t>(37, long_.size() - i));
    CHECK(hex(pieces.finish()) == "cabe45dcc9ae5b66ba86600cca6b8ba8");
}

TEST_CASE("WAV files read back exactly in another decoder, 16 and 24 bit and float") {
    const std::vector<float> x = voice(44100.0, 0.5, 2);
    for (int bits : {16, 24}) {
        MemorySink sink;
        WavEncoder encoder(sink, 44100, 2, bits);
        std::vector<std::int32_t> ints(x.size());
        const double scale = static_cast<double>(1 << (bits - 1));
        for (std::size_t i = 0; i < x.size(); ++i) ints[i] = static_cast<std::int32_t>(std::lround(x[i] * scale));
        ints[0] = static_cast<std::int32_t>(scale) - 1;
        ints[1] = -static_cast<std::int32_t>(scale);
        CHECK(encoder.writeInt(ints.data(), x.size() / 2));
        CHECK(encoder.finish());
        std::vector<std::int32_t> back;
        unsigned channels = 0, rate = 0, depth = 0;
        CHECK(thirdparty::decodeWavInt(sink.bytes(), back, channels, rate, depth));
        CHECK(channels == 2 && rate == 44100 && depth == static_cast<unsigned>(bits));
        CHECK(back.size() == ints.size());
        bool same = back.size() == ints.size();
        for (std::size_t i = 0; same && i < ints.size(); ++i) same = (back[i] >> (32 - bits)) == ints[i];
        CHECK(same);
    }
    MemorySink sink;
    WavEncoder encoder(sink, 48000, 1, 32);
    std::vector<float> mono(x.begin(), x.begin() + 1001);
    CHECK(encoder.writeFloat(mono.data(), mono.size()));
    CHECK(encoder.finish());
    std::vector<float> back;
    unsigned channels = 0, rate = 0;
    CHECK(thirdparty::decodeWavFloat(sink.bytes(), back, channels, rate));
    CHECK(channels == 1 && rate == 48000 && back == mono);
}

TEST_CASE("FLAC is lossless in another decoder and carries the right MD5") {
    std::mt19937 rng(5);
    std::normal_distribution<double> noise(0.0, 1.0);
    for (int bits : {16, 24}) {
        for (int channels : {1, 2}) {
            const std::size_t frames = 48000 + 1234;  // a short last block
            std::vector<std::int32_t> x(frames * static_cast<std::size_t>(channels));
            const double full = static_cast<double>((1 << (bits - 1)) - 1);
            for (std::size_t i = 0; i < frames; ++i) {
                for (int c = 0; c < channels; ++c) {
                    double s = 0.5 * std::sin(2 * synth::kPi * 220.0 * i / 48000.0) + 0.2 * std::sin(2 * synth::kPi * 331.0 * i / 48000.0 + c);
                    s += 0.001 * noise(rng);
                    if (i >= 10000 && i < 14096) s = 0.25;        // a constant block
                    if (i >= 20000 && i < 20100) s = (i & 1) ? 1.0 : -1.0;  // extremes
                    x[i * static_cast<std::size_t>(channels) + static_cast<std::size_t>(c)] =
                        static_cast<std::int32_t>(std::clamp(std::lround(s * full), -static_cast<long>(full) - 1, static_cast<long>(full)));
                }
            }
            MemorySink sink;
            FlacEncoder encoder(sink, 48000, channels, bits);
            for (std::size_t start = 0; start < frames; start += 3000) {
                CHECK(encoder.writeInt(x.data() + start * static_cast<std::size_t>(channels), std::min<std::size_t>(3000, frames - start)));
            }
            CHECK(encoder.finish());
            std::vector<std::int32_t> back;
            unsigned decodedChannels = 0, rate = 0;
            CHECK(thirdparty::decodeFlac(sink.bytes(), back, decodedChannels, rate));
            CHECK(decodedChannels == static_cast<unsigned>(channels) && rate == 48000);
            bool same = back.size() == x.size();
            for (std::size_t i = 0; same && i < x.size(); ++i) same = (back[i] >> (32 - bits)) == x[i];
            CHECK(same);
            // STREAMINFO's MD5 is the MD5 of the samples as little-endian bytes.
            Md5 md5;
            const std::size_t width = static_cast<std::size_t>(bits / 8);
            for (std::int32_t v : x) {
                std::uint8_t bytes[3];
                for (std::size_t b = 0; b < width; ++b) bytes[b] = static_cast<std::uint8_t>(static_cast<std::uint32_t>(v) >> (8 * b));
                md5.update(bytes, width);
            }
            const auto digest = md5.finish();
            CHECK(std::memcmp(sink.bytes().data() + 8 + 18, digest.data(), 16) == 0);
            // And it is smaller than the PCM.
            CHECK(sink.bytes().size() < x.size() * width * 7 / 10);
        }
    }
}

TEST_CASE("MP3 decodes in another decoder at the right level, rate and size") {
    struct Case { int rate, channels, kbps; double minimumSnr; };
    const Case cases[] = {{44100, 1, 128, 30.0}, {44100, 2, 192, 30.0}, {48000, 2, 320, 35.0}, {48000, 1, 256, 38.0}, {32000, 1, 128, 30.0}};
    for (const Case& c : cases) {
        const double seconds = 2.0;
        const std::vector<float> x = voice(c.rate, seconds, c.channels);
        const std::vector<std::uint8_t> bytes = encodeMp3(x, c.rate, c.channels, c.kbps);
        const thirdparty::Mp3Decoded decoded = decodeMp3(bytes);
        CHECK(decoded.framesWithoutAudio == 0);
        CHECK(decoded.sampleRate == c.rate);
        CHECK(decoded.channels == c.channels);
        CHECK(decoded.bitrateKbps == c.kbps);
        // Constant bit rate: the size is the bit rate times the duration (plus the flush frames).
        const double expectedBytes = c.kbps * 1000.0 / 8.0 * (static_cast<double>(decoded.frames) * 1152.0 / c.rate);
        CHECK(std::fabs(static_cast<double>(bytes.size()) - expectedBytes) < 2.0);
        const std::size_t delay = delayOf(x, c.channels, decoded.samples, c.channels, 4000);
        for (int channel = 0; channel < c.channels; ++channel) {
            const Comparison result = compare(x, decoded.samples, c.channels, channel, delay);
            std::printf("      %d Hz %d ch %d kbit/s: delay %zu, gain %.4f, SNR %.1f dB (ch %d)\n", c.rate, c.channels, c.kbps,
                        delay, result.gain, result.snrDb, channel);
            CHECK(result.snrDb > c.minimumSnr);
            CHECK(std::fabs(result.gain - 1.0) < 0.01);
        }
        // Always the same delay: the two filter banks' fixed latency.
        CHECK(delay == 1056);
    }
}

TEST_CASE("MP3 copes with silence, full-scale squares and identical channels") {
    std::vector<float> x(48000 * 2, 0.0f);
    std::vector<std::uint8_t> bytes = encodeMp3(x, 48000, 2, 192);
    thirdparty::Mp3Decoded decoded = decodeMp3(bytes);
    CHECK(decoded.framesWithoutAudio == 0);
    CHECK(std::all_of(decoded.samples.begin(), decoded.samples.end(), [](float v) { return std::fabs(v) < 1e-6f; }));
    // A full-scale square stresses the largest values the format can carry.
    for (std::size_t i = 0; i < x.size(); ++i) x[i] = ((i / 2 / 55) % 2) ? 1.0f : -1.0f;
    bytes = encodeMp3(x, 48000, 2, 128);
    decoded = decodeMp3(bytes);
    CHECK(decoded.framesWithoutAudio == 0);
    CHECK(decoded.samples.size() >= x.size());
    // Identical channels come back identical (mid/side with no side).
    const std::vector<float> mono = voice(44100.0, 1.0, 1);
    std::vector<float> dual(mono.size() * 2);
    for (std::size_t i = 0; i < mono.size(); ++i) dual[2 * i] = dual[2 * i + 1] = mono[i];
    decoded = decodeMp3(encodeMp3(dual, 44100, 2, 128));
    double difference = 0.0;
    for (std::size_t i = 0; i + 1 < decoded.samples.size(); i += 2) difference = std::max<double>(difference, std::fabs(decoded.samples[i] - decoded.samples[i + 1]));
    CHECK(difference < 1e-4);
}

TEST_CASE("the resampler keeps pitch, level and timing, and removes what cannot fit") {
    struct Pair { double from, to; };
    for (const Pair p : {Pair{48000, 44100}, Pair{44100, 48000}, Pair{96000, 48000}, Pair{22050, 48000}}) {
        const std::vector<float> x = synth::tone(1000.0, 1.0, p.from, 0.5);
        Resampler resampler(p.from, p.to, 1, 2);
        CHECK(resampler.ok());
        std::vector<float> y;
        for (std::size_t start = 0; start < x.size(); start += 777) resampler.process(x.data() + start, std::min<std::size_t>(777, x.size() - start), y);
        resampler.flush(y);
        const auto expected = static_cast<std::size_t>(std::ceil(x.size() * p.to / p.from - 1e-9));
        CHECK(y.size() == expected);
        // Same tone, same level, same phase: sample n of the output is input time n/to.
        double worst = 0.0;
        for (std::size_t n = y.size() / 10; n < y.size() * 9 / 10; ++n) {
            const double ideal = 0.5 * std::sin(2 * synth::kPi * 1000.0 * static_cast<double>(n) / p.to);
            worst = std::max(worst, std::fabs(y[n] - ideal));
        }
        CHECK(worst < 1e-3);
    }
    // A 23 kHz tone has no place at 44.1 kHz; it must not fold back to 21.1 kHz.
    const std::vector<float> high = synth::tone(23000.0, 0.5, 48000.0, 0.5);
    Resampler down(48000, 44100, 1, 2);
    std::vector<float> y;
    down.process(high.data(), high.size(), y);
    down.flush(y);
    const double alias = toneAmplitude(y, 1, 21100.0, 44100.0, 2000, y.size() - 2000);
    CHECK(20.0 * std::log10(alias / 0.5 + 1e-12) < -70.0);
    // Equal rates pass straight through.
    Resampler same(48000, 48000, 2, 2);
    CHECK(same.passthrough());
}

TEST_CASE("true peak sees the peak between samples") {
    // A quarter-rate sine at 45 degrees: every sample is at 0.707 of the real peak.
    TruePeak meter(1);
    double peak = 0.0;
    for (int i = 0; i < 4800; ++i) {
        const float s = static_cast<float>(0.5 * std::sin(synth::kPi / 2 * i + synth::kPi / 4));
        float delayed;
        peak = std::max(peak, meter.process(&s, &delayed));
    }
    CHECK_NEAR(20.0 * std::log10(peak / 0.5), 0.0, 0.2);  // reads the real peak, 3 dB over the samples
}

TEST_CASE("export reaches a loudness target under a true-peak ceiling") {
    const std::vector<float> x = voice(48000.0, 4.0, 2, 0.6);
    ExportSettings settings;
    settings.format = ExportSettings::Format::wav;
    settings.sampleRate = 44100.0;
    settings.channels = 2;
    settings.bits = 24;
    settings.limit = false;
    ExportStats measured;
    Exporter measure(settings, 48000.0, 2, nullptr);
    CHECK(measure.push(x.data(), x.size() / 2));
    CHECK(measure.finish(measured));
    CHECK(measured.frames == static_cast<std::uint64_t>(std::ceil(x.size() / 2 * 44100.0 / 48000.0)));

    settings.gainDb = -3.0 - measured.integratedLufs;  // loud: the limiter has to work
    settings.limit = true;
    settings.ceilingDb = -1.0;
    MemorySink sink;
    Exporter exporter(settings, 48000.0, 2, &sink);
    for (std::size_t start = 0; start < x.size() / 2; start += 4096) {
        CHECK(exporter.push(x.data() + start * 2, std::min<std::size_t>(4096, x.size() / 2 - start)));
    }
    ExportStats stats;
    CHECK(exporter.finish(stats));
    std::printf("      loudness %.2f LUFS, true peak %.2f dBTP, limiting %.1f dB\n", stats.integratedLufs, stats.truePeakDbtp,
                stats.maximumReductionDb);
    CHECK(stats.frames == measured.frames);
    CHECK(stats.truePeakDbtp <= -1.0 + 0.1);
    // Limiting this hard costs some loudness; what is reported is what was measured.
    CHECK(stats.integratedLufs > -6.0 && stats.integratedLufs < -3.0);
    CHECK(stats.maximumReductionDb > 0.5);
    CHECK(stats.clippedSamples == 0);
    // The file holds exactly what was measured.
    std::vector<std::int32_t> back;
    unsigned channels = 0, rate = 0, bits = 0;
    CHECK(thirdparty::decodeWavInt(sink.bytes(), back, channels, rate, bits));
    CHECK(channels == 2 && rate == 44100 && bits == 24);
    CHECK(back.size() == stats.frames * 2);
    CHECK(stats.bytes == sink.bytes().size());
}

TEST_CASE("export mixes to mono, writes FLAC and MP3 that decode") {
    const std::vector<float> x = voice(48000.0, 2.0, 2);
    ExportSettings settings;
    settings.channels = 1;
    settings.sampleRate = 48000.0;
    settings.limit = false;
    for (auto format : {ExportSettings::Format::flac, ExportSettings::Format::mp3}) {
        settings.format = format;
        settings.bits = 16;
        settings.mp3Kbps = 192;
        MemorySink sink;
        Exporter exporter(settings, 48000.0, 2, &sink);
        CHECK(exporter.ok());
        CHECK(exporter.push(x.data(), x.size() / 2));
        ExportStats stats;
        CHECK(exporter.finish(stats));
        if (format == ExportSettings::Format::flac) {
            std::vector<std::int32_t> back;
            unsigned channels = 0, rate = 0;
            CHECK(thirdparty::decodeFlac(sink.bytes(), back, channels, rate));
            CHECK(channels == 1 && back.size() == x.size() / 2);
            // The mono mix is the average of the two channels, within dither.
            double worst = 0.0;
            for (std::size_t i = 0; i < back.size(); ++i) {
                const double mix = 0.5 * (x[2 * i] + x[2 * i + 1]);
                worst = std::max(worst, std::fabs(back[i] / 2147483648.0 - mix));
            }
            CHECK(worst < 2.5 / 32768.0);
        } else {
            const thirdparty::Mp3Decoded decoded = decodeMp3(sink.bytes());
            CHECK(decoded.channels == 1 && decoded.sampleRate == 48000 && decoded.framesWithoutAudio == 0);
        }
    }
}

TEST_CASE("dither keeps a tone quieter than one step alive in 16 bits") {
    // -100 dBFS is a third of a 16-bit step: plain rounding erases it.
    const std::vector<float> tone = synth::tone(1000.0, 1.0, 48000.0, std::pow(10.0, -100.0 / 20.0));
    for (bool dither : {false, true}) {
        ExportSettings settings;
        settings.format = ExportSettings::Format::wav;
        settings.channels = 1;
        settings.bits = 16;
        settings.limit = false;
        settings.dither = dither;
        MemorySink sink;
        Exporter exporter(settings, 48000.0, 1, &sink);
        CHECK(exporter.push(tone.data(), tone.size()));
        ExportStats stats;
        CHECK(exporter.finish(stats));
        std::vector<std::int32_t> back;
        unsigned channels = 0, rate = 0, bits = 0;
        CHECK(thirdparty::decodeWavInt(sink.bytes(), back, channels, rate, bits));
        std::vector<float> asFloat(back.size());
        for (std::size_t i = 0; i < back.size(); ++i) asFloat[i] = static_cast<float>(back[i] / 2147483648.0);
        const double level = 20.0 * std::log10(toneAmplitude(asFloat, 1, 1000.0, 48000.0, 0, asFloat.size()) + 1e-20);
        if (dither) CHECK_NEAR(level, -100.0, 1.5);
        else CHECK(level < -130.0);
    }
}

TEST_CASE("export refuses settings it cannot honour") {
    ExportSettings s;
    s.format = ExportSettings::Format::mp3;
    s.sampleRate = 96000.0;
    CHECK(validate(s, 48000.0, 2) == ExportProblem::mp3SampleRate);
    s.sampleRate = 48000.0;
    s.mp3Kbps = 100;
    CHECK(validate(s, 48000.0, 2) == ExportProblem::bitrate);
    s.format = ExportSettings::Format::flac;
    s.bits = 32;
    CHECK(validate(s, 48000.0, 2) == ExportProblem::bits);
    s.bits = 24;
    s.channels = 3;
    CHECK(validate(s, 48000.0, 2) == ExportProblem::channels);
    s.channels = 2;
    CHECK(validate(s, 48000.0, 2) == ExportProblem::none);
    Exporter refused(ExportSettings{ExportSettings::Format::flac, 48000.0, 2, 8}, 48000.0, 2, nullptr);
    CHECK(!refused.ok());
}
