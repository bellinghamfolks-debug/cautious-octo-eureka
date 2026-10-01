#include "maqam/fft.hpp"

#include <cmath>
#include <stdexcept>

namespace maqam {

std::size_t FFT::nextPowerOfTwo(std::size_t value) noexcept {
    std::size_t result = 1;
    while (result < value) result <<= 1;
    return result;
}

FFT::FFT(std::size_t size) : size_(size), bitReversed_(size), twiddles_(size / 2) {
    if (!isPowerOfTwo(size)) throw std::invalid_argument("FFT size must be a power of two");
    std::size_t bits = 0;
    while ((std::size_t{1} << bits) < size) ++bits;
    for (std::size_t i = 0; i < size; ++i) {
        std::size_t reversed = 0;
        for (std::size_t b = 0; b < bits; ++b) {
            if (i & (std::size_t{1} << b)) reversed |= std::size_t{1} << (bits - 1 - b);
        }
        bitReversed_[i] = reversed;
    }
    for (std::size_t k = 0; k < size / 2; ++k) {
        const double angle = -2.0 * 3.14159265358979323846 * static_cast<double>(k) / static_cast<double>(size);
        twiddles_[k] = {std::cos(angle), std::sin(angle)};
    }
}

void FFT::transform(std::complex<double>* data, bool inverse) const noexcept {
    for (std::size_t i = 0; i < size_; ++i) {
        const std::size_t j = bitReversed_[i];
        if (i < j) std::swap(data[i], data[j]);
    }
    for (std::size_t length = 2; length <= size_; length <<= 1) {
        const std::size_t half = length / 2;
        const std::size_t step = size_ / length;
        for (std::size_t start = 0; start < size_; start += length) {
            for (std::size_t k = 0; k < half; ++k) {
                std::complex<double> w = twiddles_[k * step];
                if (inverse) w = std::conj(w);
                const std::complex<double> odd = data[start + k + half] * w;
                data[start + k + half] = data[start + k] - odd;
                data[start + k] += odd;
            }
        }
    }
    if (inverse) {
        const double scale = 1.0 / static_cast<double>(size_);
        for (std::size_t i = 0; i < size_; ++i) data[i] *= scale;
    }
}

}  // namespace maqam
