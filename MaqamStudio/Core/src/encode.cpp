#include "maqam/encode.hpp"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <limits>

namespace maqam {

// MARK: Sinks

FileSink::FileSink(const char* path) : file_(path ? std::fopen(path, "wb") : nullptr) {}

FileSink::~FileSink() { close(); }

bool FileSink::write(const void* data, std::size_t size) {
    if (!file_) return false;
    if (size == 0) return true;
    if (std::fwrite(data, 1, size, file_) != size) return false;
    position_ += size;
    return true;
}

bool FileSink::seek(std::uint64_t offset) {
    if (!file_ || offset > static_cast<std::uint64_t>(std::numeric_limits<long>::max())) return false;
    if (std::fseek(file_, static_cast<long>(offset), SEEK_SET) != 0) return false;
    position_ = offset;
    return true;
}

bool FileSink::close() {
    if (!file_) return true;
    const bool ok = std::fclose(file_) == 0;
    file_ = nullptr;
    return ok;
}

bool MemorySink::write(const void* data, std::size_t size) {
    const auto* bytes = static_cast<const std::uint8_t*>(data);
    const std::uint64_t end = position_ + size;
    if (end > bytes_.size()) bytes_.resize(static_cast<std::size_t>(end));
    if (size > 0) std::memcpy(bytes_.data() + position_, bytes, size);
    position_ = end;
    return true;
}

bool MemorySink::seek(std::uint64_t offset) {
    if (offset > bytes_.size()) return false;
    position_ = offset;
    return true;
}

// MARK: MD5 (RFC 1321)

namespace {

struct Md5Constants {
    std::array<std::uint32_t, 64> k{};
    Md5Constants() {
        // K[i] = floor(|sin(i + 1)| * 2^32); checked against known digests in the tests.
        for (int i = 0; i < 64; ++i) {
            k[static_cast<std::size_t>(i)] =
                static_cast<std::uint32_t>(std::floor(std::fabs(std::sin(static_cast<long double>(i + 1))) * 4294967296.0L));
        }
    }
};

const Md5Constants& md5Constants() {
    static const Md5Constants constants;
    return constants;
}

constexpr int kMd5Shifts[64] = {7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
                                5, 9,  14, 20, 5, 9,  14, 20, 5, 9,  14, 20, 5, 9,  14, 20,
                                4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
                                6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21};

std::uint32_t rotateLeft(std::uint32_t x, int c) { return (x << c) | (x >> (32 - c)); }

void putLE16(std::uint8_t* p, std::uint32_t v) { p[0] = static_cast<std::uint8_t>(v); p[1] = static_cast<std::uint8_t>(v >> 8); }
void putLE32(std::uint8_t* p, std::uint32_t v) { putLE16(p, v & 0xFFFF); putLE16(p + 2, v >> 16); }

}  // namespace

Md5::Md5() : state_{0x67452301u, 0xefcdab89u, 0x98badcfeu, 0x10325476u} { md5Constants(); }

void Md5::update(const void* data, std::size_t size) {
    const auto* bytes = static_cast<const std::uint8_t*>(data);
    std::size_t used = static_cast<std::size_t>(length_ % 64);
    length_ += size;
    if (used > 0) {
        const std::size_t take = std::min(size, 64 - used);
        std::memcpy(buffer_.data() + used, bytes, take);
        bytes += take;
        size -= take;
        used += take;
        if (used < 64) return;
        block(buffer_.data());
    }
    while (size >= 64) {
        block(bytes);
        bytes += 64;
        size -= 64;
    }
    if (size > 0) std::memcpy(buffer_.data(), bytes, size);
}

std::array<std::uint8_t, 16> Md5::finish() {
    const std::uint64_t bits = length_ * 8;
    const std::uint8_t one = 0x80, zero = 0;
    update(&one, 1);
    while (length_ % 64 != 56) update(&zero, 1);
    std::uint8_t tail[8];
    for (int i = 0; i < 8; ++i) tail[i] = static_cast<std::uint8_t>(bits >> (8 * i));
    update(tail, 8);
    std::array<std::uint8_t, 16> digest{};
    for (int i = 0; i < 4; ++i) putLE32(digest.data() + 4 * i, state_[static_cast<std::size_t>(i)]);
    return digest;
}

void Md5::block(const std::uint8_t* data) {
    const auto& k = md5Constants().k;
    std::uint32_t m[16];
    for (int i = 0; i < 16; ++i) {
        m[i] = static_cast<std::uint32_t>(data[4 * i]) | static_cast<std::uint32_t>(data[4 * i + 1]) << 8 |
               static_cast<std::uint32_t>(data[4 * i + 2]) << 16 | static_cast<std::uint32_t>(data[4 * i + 3]) << 24;
    }
    std::uint32_t a = state_[0], b = state_[1], c = state_[2], d = state_[3];
    for (int i = 0; i < 64; ++i) {
        std::uint32_t f;
        int g;
        if (i < 16) { f = (b & c) | (~b & d); g = i; }
        else if (i < 32) { f = (d & b) | (~d & c); g = (5 * i + 1) % 16; }
        else if (i < 48) { f = b ^ c ^ d; g = (3 * i + 5) % 16; }
        else { f = c ^ (b | ~d); g = (7 * i) % 16; }
        const std::uint32_t next = d;
        d = c;
        c = b;
        b = b + rotateLeft(a + f + k[static_cast<std::size_t>(i)] + m[g], kMd5Shifts[i]);
        a = next;
    }
    state_[0] += a; state_[1] += b; state_[2] += c; state_[3] += d;
}

// MARK: Encoder defaults

bool Encoder::writeInt(const std::int32_t*, std::size_t) { return false; }
bool Encoder::writeFloat(const float*, std::size_t) { return false; }

// MARK: WAV

WavEncoder::WavEncoder(ByteSink& sink, std::uint32_t sampleRate, int channels, int bits)
    : sink_(sink), sampleRate_(sampleRate), channels_(channels), bits_(bits) {
    ok_ = (bits == 16 || bits == 24 || bits == 32) && channels >= 1 && channels <= 8 && sampleRate > 0 && header(0);
}

bool WavEncoder::header(std::uint64_t dataBytes) {
    const bool isFloat = bits_ == 32;
    const std::uint32_t frameBytes = static_cast<std::uint32_t>(channels_ * (bits_ / 8));
    std::uint8_t h[58] = {};
    std::size_t size = 0;
    auto tag = [&](const char* text) { std::memcpy(h + size, text, 4); size += 4; };
    auto u32 = [&](std::uint32_t v) { putLE32(h + size, v); size += 4; };
    auto u16 = [&](std::uint32_t v) { putLE16(h + size, v); size += 2; };
    const std::uint32_t data32 = static_cast<std::uint32_t>(dataBytes);
    const std::uint32_t headerSize = isFloat ? 58 : 44;
    tag("RIFF");
    u32(headerSize - 8 + data32 + (data32 & 1));
    tag("WAVE");
    tag("fmt ");
    u32(isFloat ? 18 : 16);
    u16(isFloat ? 3 : 1);
    u16(static_cast<std::uint32_t>(channels_));
    u32(sampleRate_);
    u32(sampleRate_ * frameBytes);
    u16(frameBytes);
    u16(static_cast<std::uint32_t>(bits_));
    if (isFloat) {
        u16(0);
        tag("fact");
        u32(4);
        u32(frameBytes ? data32 / frameBytes : 0);
    }
    tag("data");
    u32(data32);
    return sink_.write(h, size);
}

bool WavEncoder::writeInt(const std::int32_t* interleaved, std::size_t frames) {
    if (!ok_ || bits_ == 32) return false;
    const std::size_t count = frames * static_cast<std::size_t>(channels_);
    const std::size_t width = static_cast<std::size_t>(bits_ / 8);
    scratch_.resize(count * width);
    for (std::size_t i = 0; i < count; ++i) {
        const std::uint32_t v = static_cast<std::uint32_t>(interleaved[i]);
        for (std::size_t b = 0; b < width; ++b) scratch_[i * width + b] = static_cast<std::uint8_t>(v >> (8 * b));
    }
    dataBytes_ += scratch_.size();
    if (dataBytes_ > 0xFFFFFFFFull - 64) return ok_ = false;
    return ok_ = sink_.write(scratch_.data(), scratch_.size());
}

bool WavEncoder::writeFloat(const float* interleaved, std::size_t frames) {
    if (!ok_ || bits_ != 32) return false;
    const std::size_t count = frames * static_cast<std::size_t>(channels_);
    scratch_.resize(count * 4);
    for (std::size_t i = 0; i < count; ++i) {
        std::uint32_t v;
        std::memcpy(&v, &interleaved[i], 4);
        putLE32(scratch_.data() + 4 * i, v);
    }
    dataBytes_ += scratch_.size();
    if (dataBytes_ > 0xFFFFFFFFull - 64) return ok_ = false;
    return ok_ = sink_.write(scratch_.data(), scratch_.size());
}

bool WavEncoder::finish() {
    if (!ok_) return false;
    if (dataBytes_ & 1) {
        const std::uint8_t pad = 0;
        if (!sink_.write(&pad, 1)) return false;
    }
    const std::uint64_t end = sink_.position();
    return sink_.seek(0) && header(dataBytes_) && sink_.seek(end);
}

}  // namespace maqam
