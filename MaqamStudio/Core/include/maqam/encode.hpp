// Audio file encoders for export: WAV, FLAC and MP3, written from scratch so
// export works offline on every platform the core builds for.
//
// * WAV: 16- or 24-bit PCM, or 32-bit float.
// * FLAC: lossless, 16 or 24 bits; fixed and LPC prediction, partitioned Rice
//   residuals, stereo decorrelation, and the MD5 of the audio in STREAMINFO.
// * MP3: MPEG-1 Layer III, 32/44.1/48 kHz, constant bit rate 128-320 kbit/s,
//   mono, stereo or mid/side stereo, with a bit reservoir. Long blocks only:
//   a very sharp attack can smear slightly ahead of itself (pre-echo), which
//   is rare in sung voice at these bit rates.
//
// Each encoder writes to a ByteSink and seeks back once at the end to fill in
// lengths and checksums. Nothing here allocates per sample.
#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <memory>
#include <string>
#include <vector>

namespace maqam {

class ByteSink {
public:
    virtual ~ByteSink() = default;
    virtual bool write(const void* data, std::size_t size) = 0;
    virtual bool seek(std::uint64_t offset) = 0;
    virtual std::uint64_t position() const = 0;
};

class FileSink final : public ByteSink {
public:
    explicit FileSink(const char* path);
    ~FileSink() override;
    bool ok() const noexcept { return file_ != nullptr; }
    bool write(const void* data, std::size_t size) override;
    bool seek(std::uint64_t offset) override;
    std::uint64_t position() const override { return position_; }
    bool close();

private:
    std::FILE* file_ = nullptr;
    std::uint64_t position_ = 0;
};

class MemorySink final : public ByteSink {
public:
    bool write(const void* data, std::size_t size) override;
    bool seek(std::uint64_t offset) override;
    std::uint64_t position() const override { return position_; }
    const std::vector<std::uint8_t>& bytes() const noexcept { return bytes_; }

private:
    std::vector<std::uint8_t> bytes_;
    std::uint64_t position_ = 0;
};

class Md5 {
public:
    Md5();
    void update(const void* data, std::size_t size);
    std::array<std::uint8_t, 16> finish();

private:
    void block(const std::uint8_t* data);
    std::array<std::uint32_t, 4> state_;
    std::array<std::uint8_t, 64> buffer_{};
    std::uint64_t length_ = 0;
};

class Encoder {
public:
    virtual ~Encoder() = default;
    // Integer formats take samples already quantized to their bit depth;
    // float formats and MP3 take samples in -1..1. Interleaved.
    virtual bool writeInt(const std::int32_t* interleaved, std::size_t frames);
    virtual bool writeFloat(const float* interleaved, std::size_t frames);
    virtual bool wantsFloat() const noexcept = 0;
    virtual bool finish() = 0;
};

class WavEncoder final : public Encoder {
public:
    // bits: 16 or 24 (integer PCM) or 32 (IEEE float).
    WavEncoder(ByteSink& sink, std::uint32_t sampleRate, int channels, int bits);
    bool writeInt(const std::int32_t* interleaved, std::size_t frames) override;
    bool writeFloat(const float* interleaved, std::size_t frames) override;
    bool wantsFloat() const noexcept override { return bits_ == 32; }
    bool finish() override;

private:
    bool header(std::uint64_t dataBytes);
    ByteSink& sink_;
    std::uint32_t sampleRate_;
    int channels_, bits_;
    std::uint64_t dataBytes_ = 0;
    std::vector<std::uint8_t> scratch_;
    bool ok_ = true;
};

class FlacEncoder final : public Encoder {
public:
    static constexpr std::size_t kBlockSize = 4096;
    FlacEncoder(ByteSink& sink, std::uint32_t sampleRate, int channels, int bits);
    ~FlacEncoder() override;
    bool writeInt(const std::int32_t* interleaved, std::size_t frames) override;
    bool wantsFloat() const noexcept override { return false; }
    bool finish() override;

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};

class Mp3Encoder final : public Encoder {
public:
    static bool supports(std::uint32_t sampleRate, int bitrateKbps) noexcept;
    // Channels 1 or 2. Stereo is coded as mid/side when the channels are alike.
    Mp3Encoder(ByteSink& sink, std::uint32_t sampleRate, int channels, int bitrateKbps);
    ~Mp3Encoder() override;
    bool writeFloat(const float* interleaved, std::size_t frames) override;
    bool wantsFloat() const noexcept override { return true; }
    bool finish() override;

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};

}  // namespace maqam
