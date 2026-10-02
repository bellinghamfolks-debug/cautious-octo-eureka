// FLAC encoder (RFC 9639). Each block of 4096 samples is coded per channel
// with the best of: constant, verbatim, fixed predictors of order 0-4, and
// LPC of order 1-8 from a Tukey-windowed autocorrelation; residuals use
// partitioned Rice codes. Stereo tries left/right, left/side, side/right and
// mid/side and keeps the smallest.
#include "maqam/encode.hpp"

#include "bitwriter.hpp"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <limits>

namespace maqam {

namespace {

constexpr int kMaxLpcOrder = 8;
constexpr int kMaxPartitionOrder = 8;

std::uint8_t crc8(const std::uint8_t* data, std::size_t size) {
    std::uint8_t crc = 0;
    for (std::size_t i = 0; i < size; ++i) {
        crc ^= data[i];
        for (int b = 0; b < 8; ++b) crc = static_cast<std::uint8_t>(crc & 0x80 ? (crc << 1) ^ 0x07 : crc << 1);
    }
    return crc;
}

std::uint16_t crc16(const std::uint8_t* data, std::size_t size) {
    std::uint32_t crc = 0;
    for (std::size_t i = 0; i < size; ++i) {
        crc ^= static_cast<std::uint32_t>(data[i]) << 8;
        for (int b = 0; b < 8; ++b) crc = (crc & 0x8000) ? ((crc << 1) ^ 0x8005) & 0xFFFF : (crc << 1) & 0xFFFF;
    }
    return static_cast<std::uint16_t>(crc);
}

std::uint32_t zigzag(std::int32_t r) { return (static_cast<std::uint32_t>(r) << 1) ^ static_cast<std::uint32_t>(r >> 31); }

int sampleRateCode(std::uint32_t rate) {
    switch (rate) {
    case 88200: return 1; case 176400: return 2; case 192000: return 3; case 8000: return 4;
    case 16000: return 5; case 22050: return 6; case 24000: return 7; case 32000: return 8;
    case 44100: return 9; case 48000: return 10; case 96000: return 11;
    default: return 0;  // taken from STREAMINFO
    }
}

// How a channel's block is coded.
struct Subframe {
    enum Kind { constant, verbatim, fixed, lpc } kind = verbatim;
    int order = 0;
    int precision = 0;
    int shift = 0;
    std::array<std::int32_t, kMaxLpcOrder> coefficients{};
    int partitionOrder = 0;
    std::size_t estimatedBits = 0;
};

class SubframeCoder {
public:
    void prepare(std::size_t blockSize) {
        residual_.resize(blockSize);
        window_.resize(blockSize);
        windowed_.resize(blockSize);
        windowSize_ = 0;
    }

    // Chooses the cheapest coding for x[0..n) at `bps` bits per sample.
    Subframe choose(const std::int32_t* x, std::size_t n, int bps) {
        Subframe best;
        best.kind = Subframe::verbatim;
        best.estimatedBits = 8 + n * static_cast<std::size_t>(bps);
        if (std::all_of(x, x + n, [&](std::int32_t v) { return v == x[0]; })) {
            best.kind = Subframe::constant;
            best.estimatedBits = 8 + static_cast<std::size_t>(bps);
            return best;
        }
        for (int order = 0; order <= 4 && static_cast<std::size_t>(order) < n; ++order) {
            Subframe candidate;
            candidate.kind = Subframe::fixed;
            candidate.order = order;
            if (!fixedResidual(x, n, order)) continue;
            const std::size_t bits = 8 + static_cast<std::size_t>(order * bps) + residualBits(n, order, &candidate.partitionOrder);
            candidate.estimatedBits = bits;
            if (bits < best.estimatedBits) best = candidate;
        }
        if (n > static_cast<std::size_t>(4 * kMaxLpcOrder)) tryLpc(x, n, bps, best);
        return best;
    }

    // Writes a chosen subframe; recomputes the residual and picks exact Rice parameters.
    void write(BitWriter& out, const Subframe& s, const std::int32_t* x, std::size_t n, int bps) {
        switch (s.kind) {
        case Subframe::constant:
            out.put(0, 8);
            out.putSigned(x[0], bps);
            return;
        case Subframe::verbatim:
            out.put(0x02, 8);
            for (std::size_t i = 0; i < n; ++i) out.putSigned(x[i], bps);
            return;
        case Subframe::fixed:
            out.put(static_cast<std::uint32_t>((0x08 | s.order) << 1), 8);
            for (int i = 0; i < s.order; ++i) out.putSigned(x[i], bps);
            fixedResidual(x, n, s.order);
            break;
        case Subframe::lpc:
            out.put(static_cast<std::uint32_t>((0x20 | (s.order - 1)) << 1), 8);
            for (int i = 0; i < s.order; ++i) out.putSigned(x[i], bps);
            out.put(static_cast<std::uint32_t>(s.precision - 1), 4);
            out.putSigned(s.shift, 5);
            for (int i = 0; i < s.order; ++i) out.putSigned(s.coefficients[static_cast<std::size_t>(i)], s.precision);
            lpcResidual(x, n, s);
            break;
        }
        writeResidual(out, n, s.order, s.partitionOrder);
    }

private:
    bool fixedResidual(const std::int32_t* x, std::size_t n, int order) {
        for (std::size_t i = static_cast<std::size_t>(order); i < n; ++i) {
            std::int64_t r;
            switch (order) {
            case 0: r = x[i]; break;
            case 1: r = static_cast<std::int64_t>(x[i]) - x[i - 1]; break;
            case 2: r = static_cast<std::int64_t>(x[i]) - 2ll * x[i - 1] + x[i - 2]; break;
            case 3: r = static_cast<std::int64_t>(x[i]) - 3ll * x[i - 1] + 3ll * x[i - 2] - x[i - 3]; break;
            default: r = static_cast<std::int64_t>(x[i]) - 4ll * x[i - 1] + 6ll * x[i - 2] - 4ll * x[i - 3] + x[i - 4]; break;
            }
            if (r > (1ll << 30) || r < -(1ll << 30)) return false;
            residual_[i] = static_cast<std::int32_t>(r);
        }
        return true;
    }

    bool lpcResidual(const std::int32_t* x, std::size_t n, const Subframe& s) {
        for (std::size_t i = static_cast<std::size_t>(s.order); i < n; ++i) {
            std::int64_t sum = 0;
            for (int j = 0; j < s.order; ++j) sum += static_cast<std::int64_t>(s.coefficients[static_cast<std::size_t>(j)]) * x[i - 1 - static_cast<std::size_t>(j)];
            const std::int64_t r = static_cast<std::int64_t>(x[i]) - (sum >> s.shift);
            if (r > (1ll << 30) || r < -(1ll << 30)) return false;
            residual_[i] = static_cast<std::int32_t>(r);
        }
        return true;
    }

    void tryLpc(const std::int32_t* x, std::size_t n, int bps, Subframe& best) {
        if (windowSize_ != n) {
            // Tukey window with 50% taper, as most FLAC encoders use.
            const double taper = 0.25 * static_cast<double>(n);
            for (std::size_t i = 0; i < n; ++i) {
                const double t = static_cast<double>(i);
                double w = 1.0;
                if (t < taper) w = 0.5 - 0.5 * std::cos(M_PI * t / taper);
                else if (t > n - 1 - taper) w = 0.5 - 0.5 * std::cos(M_PI * (static_cast<double>(n - 1) - t) / taper);
                window_[i] = w;
            }
            windowSize_ = n;
        }
        for (std::size_t i = 0; i < n; ++i) windowed_[i] = x[i] * window_[i];
        double r[kMaxLpcOrder + 1];
        for (int lag = 0; lag <= kMaxLpcOrder; ++lag) {
            double sum = 0.0;
            for (std::size_t i = static_cast<std::size_t>(lag); i < n; ++i) sum += windowed_[i] * windowed_[i - static_cast<std::size_t>(lag)];
            r[lag] = sum;
        }
        if (r[0] <= 0.0) return;
        r[0] *= 1.0 + 1e-9;  // a hair of white noise keeps the recursion stable
        // Levinson-Durbin; predictors[p] predicts x[i] as sum a_j x[i-1-j] with p+1 taps.
        double a[kMaxLpcOrder] = {}, error = r[0];
        double predictors[kMaxLpcOrder][kMaxLpcOrder] = {};
        int orders = 0;
        for (int i = 0; i < kMaxLpcOrder; ++i) {
            double k = r[i + 1];
            for (int j = 0; j < i; ++j) k -= a[j] * r[i - j];
            k /= error;
            double next[kMaxLpcOrder];
            for (int j = 0; j < i; ++j) next[j] = a[j] - k * a[i - 1 - j];
            next[i] = k;
            std::copy(next, next + i + 1, a);
            error *= 1.0 - k * k;
            std::copy(a, a + i + 1, predictors[i]);
            orders = i + 1;
            if (error <= 0.0) break;
        }
        const int precision = bps <= 16 ? 14 : 15;
        for (int order = 1; order <= orders; ++order) {
            Subframe candidate;
            candidate.kind = Subframe::lpc;
            candidate.order = order;
            candidate.precision = precision;
            if (!quantize(predictors[order - 1], order, precision, candidate)) continue;
            if (!lpcResidual(x, n, candidate)) continue;
            const std::size_t bits = 8 + static_cast<std::size_t>(order * bps) + 9 +
                                     static_cast<std::size_t>(order * precision) +
                                     residualBits(n, order, &candidate.partitionOrder);
            candidate.estimatedBits = bits;
            if (bits < best.estimatedBits) best = candidate;
        }
    }

    static bool quantize(const double* lp, int order, int precision, Subframe& s) {
        double cmax = 0.0;
        for (int i = 0; i < order; ++i) cmax = std::max(cmax, std::fabs(lp[i]));
        if (cmax <= 0.0 || !std::isfinite(cmax)) return false;
        int exponent;
        std::frexp(cmax, &exponent);
        // cmax < 2^exponent; one bit of the precision is the sign.
        int shift = precision - 1 - exponent;
        shift = std::clamp(shift, 0, 15);
        const std::int32_t qmax = (1 << (precision - 1)) - 1, qmin = -qmax - 1;
        double error = 0.0;
        for (int i = 0; i < order; ++i) {
            error += lp[i] * static_cast<double>(1 << shift);
            const auto q = static_cast<std::int32_t>(std::clamp<long>(std::lround(error), qmin, qmax));
            error -= q;
            s.coefficients[static_cast<std::size_t>(i)] = q;
        }
        s.shift = shift;
        return true;
    }

    // Estimated residual bits for the best partition order (Rice parameter from the mean).
    std::size_t residualBits(std::size_t n, int order, int* bestOrder) {
        int maxOrder = 0;
        while (maxOrder < kMaxPartitionOrder && (n % (std::size_t{1} << (maxOrder + 1))) == 0 &&
               (n >> (maxOrder + 1)) > static_cast<std::size_t>(order)) {
            ++maxOrder;
        }
        // Sums over the finest partitions, merged pairwise for coarser orders.
        const std::size_t finest = std::size_t{1} << maxOrder;
        sums_.assign(finest, 0);
        const std::size_t length = n >> maxOrder;
        for (std::size_t p = 0; p < finest; ++p) {
            std::uint64_t sum = 0;
            const std::size_t start = p == 0 ? static_cast<std::size_t>(order) : p * length;
            for (std::size_t i = start; i < (p + 1) * length; ++i) sum += zigzag(residual_[i]);
            sums_[p] = sum;
        }
        std::size_t best = std::numeric_limits<std::size_t>::max();
        for (int partitionOrder = maxOrder; partitionOrder >= 0; --partitionOrder) {
            const std::size_t partitions = std::size_t{1} << partitionOrder;
            const std::size_t size = n >> partitionOrder;
            std::size_t bits = 6;
            for (std::size_t p = 0; p < partitions; ++p) {
                const std::size_t count = p == 0 ? size - static_cast<std::size_t>(order) : size;
                bits += 5 + estimate(sums_[p], count);
            }
            if (bits < best) { best = bits; *bestOrder = partitionOrder; }
            if (partitionOrder > 0) {
                for (std::size_t p = 0; p < partitions / 2; ++p) sums_[p] = sums_[2 * p] + sums_[2 * p + 1];
            }
        }
        return best;
    }

    static int parameterFor(std::uint64_t sum, std::size_t count) {
        if (count == 0 || sum == 0) return 0;
        const double mean = static_cast<double>(sum) / static_cast<double>(count);
        return std::clamp(static_cast<int>(std::floor(std::log2(mean * 0.6931 + 1.0))), 0, 30);
    }

    static std::size_t estimate(std::uint64_t sum, std::size_t count) {
        const int k = parameterFor(sum, count);
        return count * static_cast<std::size_t>(k + 1) + static_cast<std::size_t>(sum >> k);
    }

    void writeResidual(BitWriter& out, std::size_t n, int order, int partitionOrder) {
        const std::size_t partitions = std::size_t{1} << partitionOrder;
        const std::size_t size = n >> partitionOrder;
        // Exact best parameter per partition, around the estimate.
        parameters_.assign(partitions, 0);
        bool wide = false;
        for (std::size_t p = 0; p < partitions; ++p) {
            const std::size_t start = p == 0 ? static_cast<std::size_t>(order) : p * size;
            const std::size_t end = (p + 1) * size;
            std::uint64_t sum = 0;
            for (std::size_t i = start; i < end; ++i) sum += zigzag(residual_[i]);
            const int guess = parameterFor(sum, end - start);
            int bestK = guess;
            std::uint64_t bestBits = std::numeric_limits<std::uint64_t>::max();
            for (int k = std::max(0, guess - 1); k <= std::min(30, guess + 1); ++k) {
                std::uint64_t bits = (end - start) * static_cast<std::uint64_t>(k + 1);
                for (std::size_t i = start; i < end; ++i) bits += zigzag(residual_[i]) >> k;
                if (bits < bestBits) { bestBits = bits; bestK = k; }
            }
            parameters_[p] = bestK;
            if (bestK > 14) wide = true;
        }
        out.put(wide ? 1 : 0, 2);
        out.put(static_cast<std::uint32_t>(partitionOrder), 4);
        for (std::size_t p = 0; p < partitions; ++p) {
            const int k = parameters_[p];
            out.put(static_cast<std::uint32_t>(k), wide ? 5 : 4);
            const std::size_t start = p == 0 ? static_cast<std::size_t>(order) : p * size;
            for (std::size_t i = start; i < (p + 1) * size; ++i) {
                const std::uint32_t u = zigzag(residual_[i]);
                out.putUnary(u >> k);
                if (k > 0) out.put(u & ((1u << k) - 1u), k);
            }
        }
    }

    std::vector<std::int32_t> residual_;
    std::vector<double> window_, windowed_;
    std::size_t windowSize_ = 0;
    std::vector<std::uint64_t> sums_;
    std::vector<int> parameters_;
};

}  // namespace

struct FlacEncoder::Impl {
    ByteSink& sink;
    std::uint32_t sampleRate;
    int channels, bits;
    bool ok = true;
    std::uint64_t streamInfoOffset = 0;
    std::vector<std::vector<std::int32_t>> block;  // per channel
    std::vector<std::int32_t> mid, side;
    std::size_t filled = 0;
    std::uint64_t frameNumber = 0, totalSamples = 0;
    std::uint32_t minFrame = 0xFFFFFF, maxFrame = 0;
    Md5 md5;
    std::vector<std::uint8_t> md5Bytes;
    SubframeCoder coder;
    BitWriter frame;

    Impl(ByteSink& s, std::uint32_t rate, int ch, int b) : sink(s), sampleRate(rate), channels(ch), bits(b) {}

    bool writeStreamInfo(bool last, const std::array<std::uint8_t, 16>& digest) {
        BitWriter w;
        w.put(last ? 0x80 : 0x00, 8);  // STREAMINFO; a VORBIS_COMMENT block follows
        w.put(34, 24);
        w.put(static_cast<std::uint32_t>(kBlockSize), 16);
        w.put(static_cast<std::uint32_t>(kBlockSize), 16);
        w.put(totalSamples ? minFrame : 0, 24);
        w.put(maxFrame, 24);
        w.put(sampleRate, 20);
        w.put(static_cast<std::uint32_t>(channels - 1), 3);
        w.put(static_cast<std::uint32_t>(bits - 1), 5);
        w.put(static_cast<std::uint32_t>(totalSamples >> 32) & 0xF, 4);
        w.put(static_cast<std::uint32_t>(totalSamples), 32);
        for (std::uint8_t byte : digest) w.put(byte, 8);
        return sink.write(w.bytes().data(), w.bytes().size());
    }

    bool start() {
        if (!sink.write("fLaC", 4)) return false;
        streamInfoOffset = sink.position();
        if (!writeStreamInfo(false, {})) return false;
        static const char vendor[] = "Maqam Studio";
        const std::uint32_t vendorLength = sizeof(vendor) - 1;
        std::uint8_t comment[4 + 4 + sizeof(vendor) - 1 + 4] = {};
        const std::uint32_t length = sizeof(comment) - 4;
        comment[0] = 0x80 | 4;
        comment[1] = static_cast<std::uint8_t>(length >> 16);
        comment[2] = static_cast<std::uint8_t>(length >> 8);
        comment[3] = static_cast<std::uint8_t>(length);
        putLE32(comment + 4, vendorLength);
        std::memcpy(comment + 8, vendor, vendorLength);
        putLE32(comment + 8 + vendorLength, 0);
        return sink.write(comment, sizeof(comment));
    }

    static void putLE32(std::uint8_t* p, std::uint32_t v) {
        for (int i = 0; i < 4; ++i) p[i] = static_cast<std::uint8_t>(v >> (8 * i));
    }

    void utf8(std::uint64_t value) {
        if (value < 0x80) { frame.put(static_cast<std::uint32_t>(value), 8); return; }
        int extra = 1;
        while (extra < 6 && value >= (1ull << (5 * extra + 6))) ++extra;
        const std::uint32_t leadBits = static_cast<std::uint32_t>(value >> (6 * extra));
        const std::uint32_t lead = (0xFF00u >> (extra + 1)) & 0xFF;
        frame.put(lead | leadBits, 8);
        for (int i = extra - 1; i >= 0; --i) frame.put(0x80 | static_cast<std::uint32_t>((value >> (6 * i)) & 0x3F), 8);
    }

    bool encodeBlock(std::size_t n) {
        coder.prepare(kBlockSize);
        // Channel assignment: 1 = independent (or mono 0), 8 left/side, 9 side/right, 10 mid/side.
        int assignment = channels == 1 ? 0 : 1;
        Subframe first, second;
        const std::int32_t* a = block[0].data();
        const std::int32_t* b = channels > 1 ? block[1].data() : nullptr;
        int bpsA = bits, bpsB = bits;
        if (channels == 2) {
            for (std::size_t i = 0; i < n; ++i) {
                const std::int64_t l = block[0][i], r = block[1][i];
                side[i] = static_cast<std::int32_t>(l - r);
                mid[i] = static_cast<std::int32_t>((l + r) >> 1);
            }
            const Subframe left = coder.choose(block[0].data(), n, bits);
            const Subframe right = coder.choose(block[1].data(), n, bits);
            const Subframe sideCoded = coder.choose(side.data(), n, bits + 1);
            const Subframe midCoded = coder.choose(mid.data(), n, bits);
            const std::size_t costs[4] = {left.estimatedBits + right.estimatedBits, left.estimatedBits + sideCoded.estimatedBits,
                                          sideCoded.estimatedBits + right.estimatedBits, midCoded.estimatedBits + sideCoded.estimatedBits};
            const int choice = static_cast<int>(std::min_element(costs, costs + 4) - costs);
            switch (choice) {
            case 0: assignment = 1; first = left; second = right; break;
            case 1: assignment = 8; first = left; second = sideCoded; b = side.data(); bpsB = bits + 1; break;
            case 2: assignment = 9; first = sideCoded; second = right; a = side.data(); bpsA = bits + 1; break;
            default: assignment = 10; first = midCoded; second = sideCoded; a = mid.data(); b = side.data(); bpsB = bits + 1; break;
            }
        } else {
            first = coder.choose(a, n, bits);
        }

        frame.clear();
        frame.put(0xFFF8, 16);
        int sizeCode = 12;  // 4096
        if (n != kBlockSize) sizeCode = n <= 256 ? 6 : 7;
        frame.put(static_cast<std::uint32_t>(sizeCode), 4);
        frame.put(static_cast<std::uint32_t>(sampleRateCode(sampleRate)), 4);
        frame.put(static_cast<std::uint32_t>(assignment), 4);
        frame.put(bits == 16 ? 4 : 6, 3);
        frame.put(0, 1);
        utf8(frameNumber);
        if (sizeCode == 6) frame.put(static_cast<std::uint32_t>(n - 1), 8);
        if (sizeCode == 7) frame.put(static_cast<std::uint32_t>(n - 1), 16);
        frame.put(crc8(frame.bytes().data(), frame.bytes().size()), 8);
        coder.write(frame, first, a, n, bpsA);
        if (channels == 2) coder.write(frame, second, b, n, bpsB);
        frame.alignToByte();
        const std::uint16_t crc = crc16(frame.bytes().data(), frame.bytes().size());
        frame.put(crc, 16);
        const auto& bytes = frame.bytes();
        minFrame = std::min(minFrame, static_cast<std::uint32_t>(bytes.size()));
        maxFrame = std::max(maxFrame, static_cast<std::uint32_t>(bytes.size()));
        ++frameNumber;
        totalSamples += n;
        return sink.write(bytes.data(), bytes.size());
    }
};

FlacEncoder::FlacEncoder(ByteSink& sink, std::uint32_t sampleRate, int channels, int bits)
    : impl_(std::make_unique<Impl>(sink, sampleRate, channels, bits)) {
    Impl& s = *impl_;
    s.ok = (bits == 16 || bits == 24) && (channels == 1 || channels == 2) && sampleRate > 0 && sampleRate < (1u << 20);
    s.block.assign(static_cast<std::size_t>(channels), std::vector<std::int32_t>(kBlockSize));
    s.mid.resize(kBlockSize);
    s.side.resize(kBlockSize);
    s.frame.reserve(kBlockSize * 8);
    if (s.ok) s.ok = s.start();
}

FlacEncoder::~FlacEncoder() = default;

bool FlacEncoder::writeInt(const std::int32_t* interleaved, std::size_t frames) {
    Impl& s = *impl_;
    if (!s.ok) return false;
    const std::size_t width = static_cast<std::size_t>(s.bits / 8);
    const std::int32_t limit = 1 << (s.bits - 1);
    s.md5Bytes.resize(frames * static_cast<std::size_t>(s.channels) * width);
    for (std::size_t i = 0; i < frames; ++i) {
        for (int c = 0; c < s.channels; ++c) {
            const std::int32_t v = std::clamp(interleaved[i * static_cast<std::size_t>(s.channels) + static_cast<std::size_t>(c)], -limit, limit - 1);
            s.block[static_cast<std::size_t>(c)][s.filled] = v;
            const std::size_t at = (i * static_cast<std::size_t>(s.channels) + static_cast<std::size_t>(c)) * width;
            for (std::size_t b = 0; b < width; ++b) s.md5Bytes[at + b] = static_cast<std::uint8_t>(static_cast<std::uint32_t>(v) >> (8 * b));
        }
        if (++s.filled == kBlockSize) {
            if (!s.encodeBlock(kBlockSize)) return s.ok = false;
            s.filled = 0;
        }
    }
    s.md5.update(s.md5Bytes.data(), s.md5Bytes.size());
    return true;
}

bool FlacEncoder::finish() {
    Impl& s = *impl_;
    if (!s.ok) return false;
    if (s.filled > 0 && !s.encodeBlock(s.filled)) return false;
    s.filled = 0;
    const std::uint64_t end = s.sink.position();
    const auto digest = s.md5.finish();
    return s.sink.seek(s.streamInfoOffset) && s.writeStreamInfo(false, digest) && s.sink.seek(end);
}

}  // namespace maqam
