// Iterative radix-2 FFT used by the pitch detector's autocorrelation.
//
// Real-time rule: all memory is allocated in the constructor; transform()
// never allocates, locks or throws, so it may run on the audio thread.
#pragma once

#include <complex>
#include <cstddef>
#include <vector>

namespace maqam {

class FFT {
public:
    explicit FFT(std::size_t size);  // size must be a power of two

    std::size_t size() const noexcept { return size_; }

    // In-place forward (inverse == false) or inverse transform of `data`,
    // which must hold exactly size() values. The inverse is scaled by 1/N.
    void transform(std::complex<double>* data, bool inverse) const noexcept;

    static bool isPowerOfTwo(std::size_t value) noexcept { return value && !(value & (value - 1)); }
    static std::size_t nextPowerOfTwo(std::size_t value) noexcept;

private:
    std::size_t size_;
    std::vector<std::size_t> bitReversed_;
    std::vector<std::complex<double>> twiddles_;
};

}  // namespace maqam
