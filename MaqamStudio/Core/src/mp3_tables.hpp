// Tables of the MP3 encoder; see tools/make_mp3_tables.py for where they come from.
#pragma once

#include <cstdint>

namespace maqam::mp3 {

struct HuffCode {
    std::uint32_t code;
    std::uint8_t length;
};

// Pair tables, indexed [x * size + y]. Tables 16-23 share kTable16's codes and
// 24-31 share kTable24's; they differ only in their linbits.
extern const HuffCode kTable1[4], kTable2[9], kTable3[9], kTable5[16], kTable6[16], kTable7[36], kTable8[36],
    kTable9[36], kTable10[64], kTable11[64], kTable12[64], kTable13[256], kTable15[256], kTable16[256], kTable24[256];
// Quadruples, indexed 8v + 4w + 2x + y.
extern const HuffCode kCount1A[16], kCount1B[16];
extern const std::int32_t kWindow[512];

}  // namespace maqam::mp3
