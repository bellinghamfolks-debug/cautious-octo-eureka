// MSB-first bit writer shared by the FLAC and MP3 encoders.
#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

namespace maqam {

class BitWriter {
public:
    void clear() noexcept { bytes_.clear(); accumulator_ = 0; filled_ = 0; }
    void reserve(std::size_t bytes) { bytes_.reserve(bytes); }

    // Writes the low `count` bits of `value` (count <= 32), most significant first.
    void put(std::uint32_t value, int count) {
        while (count > 0) {
            const int take = count < 24 ? count : 24;
            count -= take;
            const std::uint32_t part = (value >> count) & ((1u << take) - 1u);
            accumulator_ = (accumulator_ << take) | part;
            filled_ += take;
            while (filled_ >= 8) {
                filled_ -= 8;
                bytes_.push_back(static_cast<std::uint8_t>(accumulator_ >> filled_));
            }
            accumulator_ &= (1ull << filled_) - 1ull;
        }
    }

    void putSigned(std::int64_t value, int count) { put(static_cast<std::uint32_t>(value) & mask(count), count); }

    // `zeros` zero bits then a one.
    void putUnary(std::uint32_t zeros) {
        while (zeros >= 24) { put(0, 24); zeros -= 24; }
        put(1, static_cast<int>(zeros) + 1);
    }

    void alignToByte() {
        if (filled_ > 0) put(0, 8 - filled_);
    }

    std::size_t bitCount() const noexcept { return bytes_.size() * 8 + static_cast<std::size_t>(filled_); }
    // Complete bytes only; call alignToByte() first to include the tail.
    const std::vector<std::uint8_t>& bytes() const noexcept { return bytes_; }

private:
    static std::uint32_t mask(int count) noexcept { return count >= 32 ? 0xFFFFFFFFu : (1u << count) - 1u; }
    std::vector<std::uint8_t> bytes_;
    std::uint64_t accumulator_ = 0;
    int filled_ = 0;
};

}  // namespace maqam
