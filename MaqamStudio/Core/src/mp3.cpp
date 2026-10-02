// MPEG-1 Layer III encoder (ISO/IEC 11172-3), long blocks only.
//
// Per frame of 1152 samples and per channel: the 32-band polyphase analysis
// filter bank, an 18-line MDCT in each band, and the encoder side of the
// decoder's alias-reduction butterflies give 576 spectral lines per granule.
// A simple masking model (band energies spread across neighbouring bands,
// plus the threshold of hearing) says how much noise each scale-factor band
// can take. The rate loop finds the finest global step that fits the bits;
// the distortion loop amplifies (raises the scale factor of) bands whose
// noise is above what is allowed and tries again. When a granule is already
// under its masking threshold everywhere, it is coded more coarsely and the
// saved bits go to the bit reservoir for harder granules.
#include "maqam/encode.hpp"

#include "bitwriter.hpp"
#include "mp3_tables.hpp"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <deque>
#include <limits>

namespace maqam {

namespace {

using mp3::HuffCode;

constexpr int kBitrates[15] = {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320};
constexpr int kGranule = 576;
constexpr int kFrame = 1152;
constexpr int kMaxValue = 8191 + 15;

// Long-block scale-factor band widths for 44.1, 48 and 32 kHz.
constexpr int kBandWidths[3][22] = {
    {4, 4, 4, 4, 4, 4, 6, 6, 8, 8, 10, 12, 16, 20, 24, 28, 34, 42, 50, 54, 76, 158},
    {4, 4, 4, 4, 4, 4, 6, 6, 6, 8, 10, 12, 16, 18, 22, 28, 34, 40, 46, 54, 54, 192},
    {4, 4, 4, 4, 4, 4, 6, 6, 8, 10, 12, 16, 20, 24, 30, 38, 46, 56, 68, 84, 102, 26}};

// (slen1, slen2) for each scalefac_compress value.
constexpr int kSlen[16][2] = {{0, 0}, {0, 1}, {0, 2}, {0, 3}, {3, 0}, {1, 1}, {1, 2}, {1, 3},
                              {2, 1}, {2, 2}, {2, 3}, {3, 1}, {3, 2}, {3, 3}, {4, 2}, {4, 3}};

constexpr double kAliasCoefficients[8] = {-0.6, -0.535, -0.33, -0.185, -0.095, -0.041, -0.0142, -0.0037};

struct PairTable {
    const HuffCode* codes;
    int size;     // values 0..size-1 (15 means "15 or more" when linbits > 0)
    int linbits;
};

PairTable pairTable(int index) {
    using namespace mp3;
    static const int kLinbits[32] = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                                     1, 2, 3, 4, 6, 8, 10, 13, 4, 5, 6, 7, 8, 9, 11, 13};
    switch (index) {
    case 1: return {kTable1, 2, 0};
    case 2: return {kTable2, 3, 0};
    case 3: return {kTable3, 3, 0};
    case 5: return {kTable5, 4, 0};
    case 6: return {kTable6, 4, 0};
    case 7: return {kTable7, 6, 0};
    case 8: return {kTable8, 6, 0};
    case 9: return {kTable9, 6, 0};
    case 10: return {kTable10, 8, 0};
    case 11: return {kTable11, 8, 0};
    case 12: return {kTable12, 8, 0};
    case 13: return {kTable13, 16, 0};
    case 15: return {kTable15, 16, 0};
    default:
        if (index >= 16 && index < 24) return {kTable16, 16, kLinbits[index]};
        if (index >= 24 && index < 32) return {kTable24, 16, kLinbits[index]};
        return {nullptr, 0, 0};
    }
}

// Largest value table `index` can code.
int capacity(int index) {
    const PairTable t = pairTable(index);
    if (!t.codes) return 0;
    return t.linbits ? 15 + (1 << t.linbits) - 1 : t.size - 1;
}

// Bits for pairs [begin, end) of `ix` (absolute values) with table `index`.
int pairBits(const int* ix, int begin, int end, int index) {
    if (index == 0) return 0;
    const PairTable t = pairTable(index);
    int bits = 0;
    for (int i = begin; i < end; i += 2) {
        const int x = ix[i], y = ix[i + 1];
        if (t.linbits) {
            const int cx = std::min(x, 15), cy = std::min(y, 15);
            bits += t.codes[cx * 16 + cy].length;
            if (x >= 15) bits += t.linbits;
            if (y >= 15) bits += t.linbits;
        } else {
            bits += t.codes[x * t.size + y].length;
        }
        bits += (x != 0) + (y != 0);
    }
    return bits;
}

// The cheapest table for a region and its cost.
std::pair<int, int> bestTable(const int* ix, int begin, int end) {
    if (begin >= end) return {0, 0};
    const int largest = *std::max_element(ix + begin, ix + end);
    if (largest == 0) return {0, 0};
    int candidates[6];
    int count = 0;
    auto add = [&](int index) { candidates[count++] = index; };
    if (largest <= 15) {
        switch (largest) {
        case 1: add(1); break;
        case 2: add(2); add(3); break;
        case 3: add(5); add(6); break;
        case 4: case 5: add(7); add(8); add(9); break;
        case 6: case 7: add(10); add(11); add(12); break;
        default: add(13); add(15); add(16); add(24); break;
        }
    } else {
        for (int index = 16; index < 24; ++index) if (capacity(index) >= largest) { add(index); break; }
        for (int index = 24; index < 32; ++index) if (capacity(index) >= largest) { add(index); break; }
    }
    std::pair<int, int> best{0, std::numeric_limits<int>::max()};
    for (int i = 0; i < count; ++i) {
        const int bits = pairBits(ix, begin, end, candidates[i]);
        if (bits < best.second) best = {candidates[i], bits};
    }
    return best;
}

int count1Bits(const int* ix, int begin, int end, bool tableB) {
    int bits = 0;
    for (int i = begin; i < end; i += 4) {
        const int value = 8 * ix[i] + 4 * ix[i + 1] + 2 * ix[i + 2] + ix[i + 3];
        bits += (tableB ? mp3::kCount1B : mp3::kCount1A)[value].length;
        bits += ix[i] + ix[i + 1] + ix[i + 2] + ix[i + 3];
    }
    return bits;
}

// Threshold of hearing (Terhardt), dB SPL.
double thresholdOfHearing(double hz) {
    const double f = std::max(hz, 20.0) / 1000.0;
    return 3.64 * std::pow(f, -0.8) - 6.5 * std::exp(-0.6 * (f - 3.3) * (f - 3.3)) + 1e-3 * f * f * f * f;
}

struct GranuleInfo {
    int part23 = 0;       // part2_3_length
    int bigValues = 0;
    int globalGain = 210;
    int scalefacCompress = 0;
    int tableSelect[3] = {0, 0, 0};
    int region0 = 0, region1 = 0;
    bool count1B = false;
    int count1 = 0;       // quadruples
    int scalefac[22] = {};
    int ix[kGranule] = {};      // absolute values
    bool negative[kGranule] = {};
};

}  // namespace

struct Mp3Encoder::Impl {
    ByteSink& sink;
    int sampleRate, channels, kbps;
    int rateIndex = 0, bitrateIndex = 0;
    bool ok = true;
    int bands[23] = {};  // band start lines, bands[22] = 576

    // Input buffering.
    std::vector<float> input;  // planar, kFrame per channel
    std::size_t filled = 0;

    // Analysis state per channel.
    std::vector<std::array<double, 512>> history;
    std::vector<int> historyIndex;
    std::vector<std::array<double, kGranule>> previousSubbands;  // [band * 18 + t] of the last granule
    double window[512] = {};
    double matrix[32][64] = {};
    double mdct[18][36] = {};
    double cs[8] = {}, ca[8] = {};
    double transformGain = 1.0;  // energy of a full-scale sine's lines, for the hearing threshold

    // Spectra of the current frame: [granule][channel][line].
    double xr[2][2][kGranule] = {};
    GranuleInfo info[2][2];
    double athLine[kGranule] = {};
    double snrDb = 20.0;
    double pow43[kMaxValue + 1] = {};

    // Frame sizing and the reservoir.
    long long slotRemainder = 0;
    int reservoirBytes = 0;
    struct Pending {
        std::vector<std::uint8_t> bytes;  // header + side info + slot
        std::size_t used;                 // bytes of the slot filled so far
        std::size_t headerSize;
    };
    std::deque<Pending> pending;
    BitWriter main;
    std::uint64_t framesWritten = 0;
    int lastGain[2] = {180, 180};

    Impl(ByteSink& s, int rate, int ch, int rateKbps) : sink(s), sampleRate(rate), channels(ch), kbps(rateKbps) {}

    void setUp() {
        rateIndex = sampleRate == 44100 ? 0 : sampleRate == 48000 ? 1 : 2;
        for (int i = 1; i < 15; ++i) if (kBitrates[i] == kbps) bitrateIndex = i;
        bands[0] = 0;
        for (int b = 0; b < 22; ++b) bands[b + 1] = bands[b] + kBandWidths[rateIndex][b];
        input.assign(static_cast<std::size_t>(kFrame * channels), 0.0f);
        history.assign(static_cast<std::size_t>(channels), {});
        historyIndex.assign(static_cast<std::size_t>(channels), 0);
        previousSubbands.assign(static_cast<std::size_t>(channels), {});
        // Analysis window: the synthesis prototype reversed in time, with the sign
        // of every other 64-tap block folded in (see the derivation in the tests).
        for (int m = 0; m < 512; ++m) {
            const int block = m / 64;
            const double sign = (block % 2 == 0) ? -1.0 : 1.0;
            window[m] = sign * mp3::kWindow[511 - m] / 65536.0 / 32.0;
        }
        for (int k = 0; k < 32; ++k)
            for (int i = 0; i < 64; ++i) matrix[k][i] = std::cos((2 * k + 1) * (i - 15) * M_PI / 64.0);
        for (int m = 0; m < 18; ++m)
            for (int n = 0; n < 36; ++n)
                mdct[m][n] = std::sin(M_PI / 36.0 * (n + 0.5)) * std::cos(M_PI / 72.0 * (2 * n + 19) * (2 * m + 1)) / 9.0;
        for (int i = 0; i < 8; ++i) {
            const double root = std::sqrt(1.0 + kAliasCoefficients[i] * kAliasCoefficients[i]);
            cs[i] = 1.0 / root;
            ca[i] = -kAliasCoefficients[i] / root;
        }
        for (int i = 0; i <= kMaxValue; ++i) pow43[i] = std::pow(static_cast<double>(i), 4.0 / 3.0);
        snrDb = kbps >= 320 ? 26.0 : kbps >= 256 ? 23.0 : kbps >= 192 ? 20.0 : kbps >= 160 ? 18.0 : 16.0;
        measureTransformGain();
        // Threshold of hearing per line, taking a full-scale sine as 96 dB SPL and
        // staying 10 dB below it, since the playback level is unknown.
        for (int i = 0; i < kGranule; ++i) {
            const double hz = (i + 0.5) * sampleRate / (2.0 * kGranule);
            athLine[i] = transformGain * std::pow(10.0, (thresholdOfHearing(hz) - 96.0 - 10.0) / 10.0);
        }
    }

    // Runs a full-scale 1 kHz sine through the transform once to learn the
    // energy it produces, which anchors the hearing threshold.
    void measureTransformGain() {
        std::array<double, 512> h{};
        int index = 0;
        std::array<double, kGranule> previous{};
        double energy = 0.0;
        double lines[kGranule];
        for (int g = 0; g < 8; ++g) {
            double subbands[kGranule];
            for (int t = 0; t < 18; ++t) {
                double samples[32];
                for (int i = 0; i < 32; ++i) samples[i] = std::sin(2 * M_PI * 1000.0 * ((g * 18 + t) * 32 + i) / sampleRate);
                double out[32];
                analyse(h, index, samples, out);
                for (int k = 0; k < 32; ++k) subbands[k * 18 + t] = out[k];
            }
            transform(subbands, previous, lines);
            if (g >= 4) for (double v : lines) energy += v * v;
        }
        transformGain = std::max(energy / 4.0, 1e-30);
    }

    // One step of the polyphase analysis: 32 new samples in, 32 subband samples out.
    void analyse(std::array<double, 512>& h, int& index, const double* samples, double* out) const {
        for (int i = 0; i < 32; ++i) {
            index = (index + 511) & 511;
            h[static_cast<std::size_t>(index)] = samples[i];
        }
        // h[index] is now the newest sample: X[m] = h[(index + m) & 511].
        double y[64];
        for (int i = 0; i < 64; ++i) {
            double sum = 0.0;
            for (int j = 0; j < 8; ++j) {
                const int m = i + 64 * j;
                sum += window[m] * h[static_cast<std::size_t>((index + m) & 511)];
            }
            y[i] = sum;
        }
        for (int k = 0; k < 32; ++k) {
            double sum = 0.0;
            for (int i = 0; i < 64; ++i) sum += matrix[k][i] * y[i];
            out[k] = sum;
        }
    }

    // Subband samples of one granule -> 576 lines (MDCT, then alias reduction).
    void transform(const double* subbands, std::array<double, kGranule>& previous, double* lines) const {
        double current[kGranule];
        for (int k = 0; k < 32; ++k)
            for (int t = 0; t < 18; ++t) {
                // The decoder inverts the odd samples of odd bands; do the same here.
                const double sign = ((k & 1) && (t & 1)) ? -1.0 : 1.0;
                current[k * 18 + t] = sign * subbands[k * 18 + t];
            }
        for (int k = 0; k < 32; ++k) {
            double z[36];
            for (int n = 0; n < 18; ++n) {
                z[n] = previous[static_cast<std::size_t>(k * 18 + n)];
                z[n + 18] = current[k * 18 + n];
            }
            for (int m = 0; m < 18; ++m) {
                double sum = 0.0;
                for (int n = 0; n < 36; ++n) sum += mdct[m][n] * z[n];
                lines[k * 18 + m] = sum;
            }
        }
        std::copy(current, current + kGranule, previous.begin());
        for (int k = 0; k < 31; ++k)
            for (int i = 0; i < 8; ++i) {
                const double u = lines[(k + 1) * 18 + i], d = lines[k * 18 + 17 - i];
                lines[(k + 1) * 18 + i] = u * cs[i] + d * ca[i];
                lines[k * 18 + 17 - i] = d * cs[i] - u * ca[i];
            }
    }

    // Noise each band can take without being heard.
    void allowedNoise(const double* lines, double* allowed) const {
        double energy[22];
        for (int b = 0; b < 22; ++b) {
            double sum = 0.0;
            for (int i = bands[b]; i < bands[b + 1]; ++i) sum += lines[i] * lines[i];
            energy[b] = sum;
        }
        for (int b = 0; b < 22; ++b) {
            // A band is masked by itself and, less, by its neighbours: lower bands
            // reach up further than higher bands reach down.
            double masking = 0.0;
            for (int j = 0; j < 22; ++j) {
                const int d = b - j;
                const double attenuationDb = d >= 0 ? 12.0 * d : 25.0 * -d;
                if (attenuationDb > 60.0) continue;
                masking += energy[j] * std::pow(10.0, -attenuationDb / 10.0);
            }
            double ath = std::numeric_limits<double>::max();
            for (int i = bands[b]; i < bands[b + 1]; ++i) ath = std::min(ath, athLine[i]);
            ath *= bands[b + 1] - bands[b];
            allowed[b] = std::max(masking * std::pow(10.0, -snrDb / 10.0), ath);
        }
    }

    // Quantizes `lines` with a global gain and per-band amplification into g.ix.
    void quantize(const double* lines34, int gain, const int* scalefac, GranuleInfo& g) const {
        for (int b = 0; b < 22; ++b) {
            const double factor = std::pow(2.0, -0.75 * (gain - 210) / 4.0 + 0.75 * 0.5 * scalefac[b]);
            for (int i = bands[b]; i < bands[b + 1]; ++i) {
                const double v = lines34[i] * factor + 0.4054;
                g.ix[i] = v >= kMaxValue ? kMaxValue : static_cast<int>(v);
            }
        }
    }

    // Splits g.ix into big values, count1 and zero regions and counts the bits;
    // when `optimize`, also searches the region boundaries.
    int huffmanBits(GranuleInfo& g, bool optimize) const {
        int end = kGranule;
        while (end > 1 && g.ix[end - 1] == 0 && g.ix[end - 2] == 0) end -= 2;
        int start = end;
        while (start > 3 && g.ix[start - 1] <= 1 && g.ix[start - 2] <= 1 && g.ix[start - 3] <= 1 && g.ix[start - 4] <= 1) start -= 4;
        g.bigValues = start / 2;
        g.count1 = (end - start) / 4;
        const int a = count1Bits(g.ix, start, end, false), b = count1Bits(g.ix, start, end, true);
        g.count1B = b < a;
        int bits = std::min(a, b);

        auto regionCost = [&](int r0, int r1, int* tables) {
            const int s1 = std::min(bands[std::min(r0 + 1, 22)], start);
            const int s2 = std::min(bands[std::min(r0 + r1 + 2, 22)], start);
            const auto t0 = bestTable(g.ix, 0, s1), t1 = bestTable(g.ix, s1, s2), t2 = bestTable(g.ix, s2, start);
            tables[0] = t0.first; tables[1] = t1.first; tables[2] = t2.first;
            return t0.second + t1.second + t2.second;
        };
        int tables[3];
        int best = std::numeric_limits<int>::max();
        if (!optimize) {
            // A split that suits most spectra: about a third of the bands, then a third.
            g.region0 = 7; g.region1 = 7;
            best = regionCost(g.region0, g.region1, tables);
            std::copy(tables, tables + 3, g.tableSelect);
        } else {
            for (int r0 = 0; r0 < 16; ++r0) {
                if (bands[std::min(r0 + 1, 22)] >= start && r0 > 0) break;
                for (int r1 = 0; r1 < 8 && r0 + r1 + 2 <= 22; ++r1) {
                    const int cost = regionCost(r0, r1, tables);
                    if (cost < best) {
                        best = cost; g.region0 = r0; g.region1 = r1;
                        std::copy(tables, tables + 3, g.tableSelect);
                    }
                    if (bands[std::min(r0 + r1 + 2, 22)] >= start) break;
                }
            }
        }
        return bits + best;
    }

    int scalefactorBits(GranuleInfo& g) const {
        int max1 = 0, max2 = 0;
        for (int b = 0; b < 11; ++b) max1 = std::max(max1, g.scalefac[b]);
        for (int b = 11; b < 21; ++b) max2 = std::max(max2, g.scalefac[b]);
        for (int c = 0; c < 16; ++c) {
            if ((1 << kSlen[c][0]) > max1 && (1 << kSlen[c][1]) > max2) {
                // Among the codes that fit, the cheapest.
                int best = c, bestBits = 11 * kSlen[c][0] + 10 * kSlen[c][1];
                for (int d = c + 1; d < 16; ++d) {
                    if ((1 << kSlen[d][0]) > max1 && (1 << kSlen[d][1]) > max2 && 11 * kSlen[d][0] + 10 * kSlen[d][1] < bestBits) {
                        best = d; bestBits = 11 * kSlen[d][0] + 10 * kSlen[d][1];
                    }
                }
                g.scalefacCompress = best;
                return bestBits;
            }
        }
        return -1;
    }

    // Noise in each band at the current quantization, over what is allowed (ratio).
    double worstOver(const double* lines, const GranuleInfo& g, const double* allowed, bool* over, int* overCount) const {
        double worst = 0.0;
        *overCount = 0;
        for (int b = 0; b < 22; ++b) {
            const double step = std::pow(2.0, (g.globalGain - 210) / 4.0 - 0.5 * g.scalefac[b]);
            double noise = 0.0;
            for (int i = bands[b]; i < bands[b + 1]; ++i) {
                const double e = std::fabs(lines[i]) - pow43[g.ix[i]] * step;
                noise += e * e;
            }
            const double ratio = noise / allowed[b];
            over[b] = ratio > 1.0;
            if (over[b]) ++*overCount;
            worst = std::max(worst, ratio);
        }
        return worst;
    }

    // The smallest gain whose coding fits `bits` (Huffman bits only).
    int rateLoop(const double* lines34, double largest34, int bits, GranuleInfo& g, int hint) const {
        // Smallest gain at which no value exceeds the largest codable one.
        int lowest = 0;
        if (largest34 > 0.0) {
            double scaled = 0.0;
            for (int b = 0; b < 22; ++b) {
                double m = 0.0;
                for (int i = bands[b]; i < bands[b + 1]; ++i) m = std::max(m, lines34[i]);
                scaled = std::max(scaled, m * std::pow(2.0, 0.375 * g.scalefac[b]));
            }
            lowest = static_cast<int>(std::ceil(210.0 + 4.0 / 0.75 * std::log2(scaled / (kMaxValue - 0.5))));
            lowest = std::clamp(lowest, 0, 255);
        }
        auto fits = [&](int gain) {
            quantize(lines34, gain, g.scalefac, g);
            return huffmanBits(g, false) <= bits;
        };
        int lo = lowest, hi = 255;
        // Start near the last granule's gain; widen until the answer is bracketed.
        int guess = std::clamp(hint, lo, hi);
        if (fits(guess)) {
            hi = guess;
            int step = 4;
            while (hi - step >= lo && fits(hi - step)) { hi -= step; step *= 2; }
            lo = std::max(lo, hi - step + 1);
            if (lo > hi) lo = hi;
        } else {
            lo = guess + 1;
            int step = 4;
            while (lo + step <= 255 && !fits(lo + step - 1)) { lo += step; step *= 2; }
            hi = std::min(255, lo + step - 1);
        }
        while (lo < hi) {
            const int mid = (lo + hi) / 2;
            if (fits(mid)) hi = mid; else lo = mid + 1;
        }
        return hi;
    }

    // Codes one granule of one channel within `budget` bits (part2 + part3).
    void codeGranule(const double* lines, int budget, GranuleInfo& g, double* allowed, int channel) {
        double lines34[kGranule];
        double largest = 0.0;
        for (int i = 0; i < kGranule; ++i) {
            lines34[i] = std::pow(std::fabs(lines[i]), 0.75);
            g.negative[i] = lines[i] < 0.0;
            largest = std::max(largest, lines34[i]);
        }
        std::fill(std::begin(g.scalefac), std::end(g.scalefac), 0);
        if (largest < 1e-12) {
            std::fill(std::begin(g.ix), std::end(g.ix), 0);
            g.globalGain = 0;
            g.part23 = scalefactorBits(g) + huffmanBits(g, true);
            return;
        }
        GranuleInfo best = g;
        double bestWorst = std::numeric_limits<double>::max();
        bool overBand[22];
        for (int iteration = 0; iteration < 12; ++iteration) {
            const int part2 = scalefactorBits(g);
            if (part2 < 0 || part2 >= budget) break;
            g.globalGain = rateLoop(lines34, largest, budget - part2, g, lastGain[channel]);
            quantize(lines34, g.globalGain, g.scalefac, g);
            int overCount = 0;
            const double worst = worstOver(lines, g, allowed, overBand, &overCount);
            if (worst < bestWorst) {
                bestWorst = worst;
                g.part23 = part2 + huffmanBits(g, false);
                best = g;
            }
            if (overCount == 0) break;
            // Amplify the bands that are too noisy; band 21 has no scale factor.
            bool amplified = false, limit = false;
            for (int b = 0; b < 21; ++b) {
                if (!overBand[b]) continue;
                if (g.scalefac[b] + 1 > (b < 11 ? 15 : 7)) { limit = true; break; }
                ++g.scalefac[b];
                amplified = true;
            }
            if (!amplified || limit) break;
        }
        if (bestWorst == std::numeric_limits<double>::max()) {
            // Not even the scale factors fit: plain quantization within whatever is left.
            std::fill(std::begin(g.scalefac), std::end(g.scalefac), 0);
            g.globalGain = rateLoop(lines34, largest, budget, g, lastGain[channel]);
            quantize(lines34, g.globalGain, g.scalefac, g);
            g.part23 = scalefactorBits(g) + huffmanBits(g, true);
            return;
        }
        g = best;
        lastGain[channel] = g.globalGain;
        // Already inaudible everywhere: code it as coarsely as stays inaudible,
        // and leave the bits for harder granules.
        if (bestWorst <= 1.0) {
            int lo = g.globalGain, hi = 255;
            while (lo < hi) {
                const int mid = (lo + hi + 1) / 2;
                GranuleInfo trial = g;
                trial.globalGain = mid;
                quantize(lines34, mid, trial.scalefac, trial);
                int overCount = 0;
                if (worstOver(lines, trial, allowed, overBand, &overCount) <= 1.0) lo = mid; else hi = mid - 1;
            }
            g.globalGain = lo;
        }
        quantize(lines34, g.globalGain, g.scalefac, g);
        g.part23 = scalefactorBits(g) + huffmanBits(g, true);
    }

    void writeGranule(BitWriter& out, const GranuleInfo& g) const {
        const int slen1 = kSlen[g.scalefacCompress][0], slen2 = kSlen[g.scalefacCompress][1];
        for (int b = 0; b < 11; ++b) if (slen1) out.put(static_cast<std::uint32_t>(g.scalefac[b]), slen1);
        for (int b = 11; b < 21; ++b) if (slen2) out.put(static_cast<std::uint32_t>(g.scalefac[b]), slen2);
        const int bigEnd = g.bigValues * 2;
        const int s1 = std::min(bands[std::min(g.region0 + 1, 22)], bigEnd);
        const int s2 = std::min(bands[std::min(g.region0 + g.region1 + 2, 22)], bigEnd);
        for (int i = 0; i < bigEnd; i += 2) {
            const int region = i < s1 ? 0 : i < s2 ? 1 : 2;
            const int index = g.tableSelect[region];
            if (index == 0) continue;
            const PairTable t = pairTable(index);
            const int x = g.ix[i], y = g.ix[i + 1];
            if (t.linbits) {
                const HuffCode& code = t.codes[std::min(x, 15) * 16 + std::min(y, 15)];
                out.put(code.code, code.length);
                if (x >= 15) out.put(static_cast<std::uint32_t>(x - 15), t.linbits);
                if (x) out.put(g.negative[i], 1);
                if (y >= 15) out.put(static_cast<std::uint32_t>(y - 15), t.linbits);
                if (y) out.put(g.negative[i + 1], 1);
            } else {
                const HuffCode& code = t.codes[x * t.size + y];
                out.put(code.code, code.length);
                if (x) out.put(g.negative[i], 1);
                if (y) out.put(g.negative[i + 1], 1);
            }
        }
        const HuffCode* quads = g.count1B ? mp3::kCount1B : mp3::kCount1A;
        for (int q = 0; q < g.count1; ++q) {
            const int i = bigEnd + 4 * q;
            const HuffCode& code = quads[8 * g.ix[i] + 4 * g.ix[i + 1] + 2 * g.ix[i + 2] + g.ix[i + 3]];
            out.put(code.code, code.length);
            for (int j = 0; j < 4; ++j) if (g.ix[i + j]) out.put(g.negative[i + j], 1);
        }
    }

    bool encodeFrame() {
        // Frame size in bytes, with the padding byte keeping the average exact.
        const long long numerator = 144000LL * kbps;
        int frameBytes = static_cast<int>(numerator / sampleRate);
        slotRemainder += numerator % sampleRate;
        bool padding = false;
        if (slotRemainder >= sampleRate) { slotRemainder -= sampleRate; padding = true; ++frameBytes; }
        const int sideBytes = channels == 1 ? 17 : 32;
        const int slotBytes = frameBytes - 4 - sideBytes;

        // Analysis.
        for (int gr = 0; gr < 2; ++gr) {
            for (int ch = 0; ch < channels; ++ch) {
                double subbands[kGranule];
                for (int t = 0; t < 18; ++t) {
                    double samples[32], out[32];
                    const std::size_t offset = static_cast<std::size_t>(ch * kFrame + gr * kGranule + t * 32);
                    for (int i = 0; i < 32; ++i) samples[i] = input[offset + static_cast<std::size_t>(i)];
                    analyse(history[static_cast<std::size_t>(ch)], historyIndex[static_cast<std::size_t>(ch)], samples, out);
                    for (int k = 0; k < 32; ++k) subbands[k * 18 + t] = out[k];
                }
                transform(subbands, previousSubbands[static_cast<std::size_t>(ch)], xr[gr][ch]);
            }
        }
        // Mid/side when the channels are mostly alike.
        bool midSide = false;
        if (channels == 2) {
            double sumEnergy = 0.0, differenceEnergy = 0.0;
            for (int gr = 0; gr < 2; ++gr)
                for (int i = 0; i < kGranule; ++i) {
                    const double l = xr[gr][0][i], r = xr[gr][1][i];
                    sumEnergy += (l + r) * (l + r);
                    differenceEnergy += (l - r) * (l - r);
                }
            midSide = differenceEnergy < 0.3 * sumEnergy;
        }
        double allowed[2][2][22];
        for (int gr = 0; gr < 2; ++gr) {
            for (int ch = 0; ch < channels; ++ch) allowedNoise(xr[gr][ch], allowed[gr][ch]);
            if (midSide) {
                // Noise in M and S lands half in each of L and R; hold both to the stricter side.
                for (int b = 0; b < 22; ++b) allowed[gr][0][b] = allowed[gr][1][b] = std::min(allowed[gr][0][b], allowed[gr][1][b]);
                for (int i = 0; i < kGranule; ++i) {
                    const double l = xr[gr][0][i], r = xr[gr][1][i];
                    xr[gr][0][i] = (l + r) * M_SQRT1_2;
                    xr[gr][1][i] = (l - r) * M_SQRT1_2;
                }
            }
        }

        // Bits: each granule's share of this frame, plus some of the reservoir.
        const int units = 2 * channels;
        const int mean = slotBytes * 8 / units;
        const int bonusPool = std::min(reservoirBytes * 8, reservoirBytes * 8 * 6 / 10);
        int available = (slotBytes + reservoirBytes) * 8;
        int used = 0;
        for (int gr = 0; gr < 2; ++gr) {
            for (int ch = 0; ch < channels; ++ch) {
                const int remainingUnits = units - (gr * channels + ch);
                int budget = std::min(4095, mean + bonusPool / units);
                // Never take bits the rest of the frame needs for its own share.
                budget = std::min(budget, available - used - (remainingUnits - 1) * mean / 2);
                budget = std::max(budget, 0);
                codeGranule(xr[gr][ch], budget, info[gr][ch], allowed[gr][ch], ch);
                used += info[gr][ch].part23;
            }
        }

        // Header and side information.
        BitWriter side;
        side.put(0x7FF, 11);
        side.put(3, 2);   // MPEG-1
        side.put(1, 2);   // Layer III
        side.put(1, 1);   // no CRC
        side.put(static_cast<std::uint32_t>(bitrateIndex), 4);
        side.put(static_cast<std::uint32_t>(rateIndex), 2);
        side.put(padding ? 1 : 0, 1);
        side.put(0, 1);
        side.put(channels == 1 ? 3 : (midSide ? 1 : 0), 2);
        side.put(midSide ? 2 : 0, 2);
        side.put(0, 1);   // copyright
        side.put(1, 1);   // original
        side.put(0, 2);   // emphasis
        side.put(static_cast<std::uint32_t>(reservoirBytes), 9);
        side.put(0, channels == 1 ? 5 : 3);
        for (int ch = 0; ch < channels; ++ch) side.put(0, 4);  // no scale factor sharing
        for (int gr = 0; gr < 2; ++gr)
            for (int ch = 0; ch < channels; ++ch) {
                const GranuleInfo& g = info[gr][ch];
                side.put(static_cast<std::uint32_t>(g.part23), 12);
                side.put(static_cast<std::uint32_t>(g.bigValues), 9);
                side.put(static_cast<std::uint32_t>(g.globalGain), 8);
                side.put(static_cast<std::uint32_t>(g.scalefacCompress), 4);
                side.put(0, 1);  // long blocks
                for (int r = 0; r < 3; ++r) side.put(static_cast<std::uint32_t>(g.tableSelect[r]), 5);
                side.put(static_cast<std::uint32_t>(g.region0), 4);
                side.put(static_cast<std::uint32_t>(g.region1), 3);
                side.put(0, 1);  // preflag
                side.put(0, 1);  // scalefac_scale
                side.put(g.count1B ? 1 : 0, 1);
            }

        // Main data.
        main.clear();
        for (int gr = 0; gr < 2; ++gr)
            for (int ch = 0; ch < channels; ++ch) writeGranule(main, info[gr][ch]);
        if (static_cast<int>(main.bitCount()) != used) return false;  // the counts and the writer must agree
        main.alignToByte();
        const int mainBytes = static_cast<int>(main.bytes().size());
        if (mainBytes > slotBytes + reservoirBytes) return false;

        Pending frame;
        frame.headerSize = side.bytes().size();
        frame.bytes = side.bytes();
        frame.bytes.resize(frame.headerSize + static_cast<std::size_t>(slotBytes), 0);
        frame.used = 0;
        pending.push_back(std::move(frame));
        append(main.bytes().data(), main.bytes().size());
        reservoirBytes = reservoirBytes + slotBytes - mainBytes;
        if (reservoirBytes > 511) {
            // More saved than main_data_begin can point back to: the rest is filler.
            const std::vector<std::uint8_t> filler(static_cast<std::size_t>(reservoirBytes - 511), 0);
            append(filler.data(), filler.size());
            reservoirBytes = 511;
        }
        return flushFull();
    }

    void append(const std::uint8_t* data, std::size_t size) {
        for (Pending& frame : pending) {
            if (size == 0) return;
            const std::size_t room = frame.bytes.size() - frame.headerSize - frame.used;
            const std::size_t take = std::min(room, size);
            std::memcpy(frame.bytes.data() + frame.headerSize + frame.used, data, take);
            frame.used += take;
            data += take;
            size -= take;
        }
    }

    bool flushFull() {
        while (!pending.empty() && pending.front().used == pending.front().bytes.size() - pending.front().headerSize) {
            if (!sink.write(pending.front().bytes.data(), pending.front().bytes.size())) return false;
            ++framesWritten;
            pending.pop_front();
        }
        return true;
    }

    bool flushAll() {
        for (Pending& frame : pending) {
            if (!sink.write(frame.bytes.data(), frame.bytes.size())) return false;
            ++framesWritten;
        }
        pending.clear();
        return true;
    }
};

bool Mp3Encoder::supports(std::uint32_t sampleRate, int bitrateKbps) noexcept {
    if (sampleRate != 32000 && sampleRate != 44100 && sampleRate != 48000) return false;
    return bitrateKbps == 128 || bitrateKbps == 160 || bitrateKbps == 192 || bitrateKbps == 224 || bitrateKbps == 256 ||
           bitrateKbps == 320;
}

Mp3Encoder::Mp3Encoder(ByteSink& sink, std::uint32_t sampleRate, int channels, int bitrateKbps)
    : impl_(std::make_unique<Impl>(sink, static_cast<int>(sampleRate), channels, bitrateKbps)) {
    impl_->ok = supports(sampleRate, bitrateKbps) && (channels == 1 || channels == 2);
    if (impl_->ok) impl_->setUp();
}

Mp3Encoder::~Mp3Encoder() = default;

bool Mp3Encoder::writeFloat(const float* interleaved, std::size_t frames) {
    Impl& s = *impl_;
    if (!s.ok) return false;
    for (std::size_t i = 0; i < frames; ++i) {
        for (int c = 0; c < s.channels; ++c) {
            const float v = interleaved[i * static_cast<std::size_t>(s.channels) + static_cast<std::size_t>(c)];
            s.input[static_cast<std::size_t>(c * kFrame) + s.filled] = std::isfinite(v) ? std::clamp(v, -1.0f, 1.0f) : 0.0f;
        }
        if (++s.filled == static_cast<std::size_t>(kFrame)) {
            if (!s.encodeFrame()) return s.ok = false;
            s.filled = 0;
        }
    }
    return true;
}

bool Mp3Encoder::finish() {
    Impl& s = *impl_;
    if (!s.ok) return false;
    // Zeros push the last samples through both filter banks' delay.
    const std::size_t tail = 2 * kFrame - s.filled;
    std::vector<float> silence(tail * static_cast<std::size_t>(s.channels), 0.0f);
    if (!writeFloat(silence.data(), tail)) return false;
    const std::vector<std::uint8_t> filler(static_cast<std::size_t>(s.reservoirBytes), 0);
    s.append(filler.data(), filler.size());
    return s.flushAll();
}

}  // namespace maqam
