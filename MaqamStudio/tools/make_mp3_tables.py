#!/usr/bin/env python3
"""Derives the MP3 encoder's tables from an independent decoder, and checks them.

The MPEG-1 Layer III Huffman codes and the polyphase window are fixed by the
standard (ISO/IEC 11172-3). Rather than typing them in, this reads them out of
minimp3 (public domain, CC0; vendored for the tests in Core/tests/third_party):

  * Huffman codes: minimp3 stores each table as a decoding tree. Walking every
    branch gives every (x, y) pair's code word and length. Each table is
    checked to be prefix-free and complete (Kraft sum exactly 1), and to
    cover every pair its size allows.
  * The window: minimp3's synthesis filter bank is run on a unit sample in
    each of the 32 subbands. Every response is the same 512-tap prototype
    times cos((2k+1)(n-48)pi/64); a least-squares fit per tap recovers the
    prototype, which is the standard's window in units of 2^-16 and comes out
    as exact integers.

Writes Core/src/mp3_tables.cpp. Needs a C++ compiler for the window step.
"""
from __future__ import annotations

import pathlib
import re
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
MINIMP3 = ROOT / "Core" / "tests" / "third_party" / "minimp3.h"
OUTPUT = ROOT / "Core" / "src" / "mp3_tables.cpp"


def array(text: str, name: str) -> list[int]:
    match = re.search(r"\b" + re.escape(name) + r"\[[^\]]*\]\s*=\s*\{([^}]*)\}", text)
    if not match:
        sys.exit(f"{name} not found in minimp3.h")
    return [int(value) for value in match.group(1).replace("\n", " ").split(",") if value.strip()]


def big_value_codes(tabs: list[int], start: int) -> dict[tuple[int, int], tuple[int, int]]:
    """Every (x, y) -> (code, length) reachable in the tree at tabs[start:]."""
    codes: dict[tuple[int, int], tuple[int, int]] = {}

    def walk(base: int, prefix: int, prefix_length: int, width: int) -> None:
        for value in range(1 << width):
            leaf = tabs[start + base + value]
            if leaf < 0:
                # Decoder: consume `width` bits, then index the next level.
                walk(-(leaf >> 3), (prefix << width) | value, prefix_length + width, leaf & 7)
                continue
            length = leaf >> 8
            code = (prefix << length) | (value >> (width - length))
            pair = (leaf & 15, (leaf >> 4) & 15)
            entry = (code, prefix_length + length)
            if codes.setdefault(pair, entry) != entry:
                sys.exit(f"pair {pair} has two codes")

    walk(0, 0, 0, 5)
    return codes


def count1_codes(table: list[int]) -> dict[int, tuple[int, int]]:
    """Quadruple value (8v + 4w + 2x + y) -> (code, length)."""
    codes: dict[int, tuple[int, int]] = {}
    for first in range(16):
        leaf = table[first]
        candidates = []
        if leaf & 8:
            candidates.append((leaf, first, 4))
        else:
            extra = leaf & 3
            for more in range(1 << extra):
                candidates.append((table[(leaf >> 3) + more], (first << extra) | more, 4 + extra))
        for entry, bits, width in candidates:
            length = entry & 7
            value = entry >> 4
            code = (length and bits >> (width - length), length)
            if codes.setdefault(value, code) != code:
                sys.exit(f"count1 value {value} has two codes")
    return codes


def check_code(name: str, lengths_and_codes: list[tuple[int, int]]) -> None:
    kraft = sum(2.0 ** -length for _, length in lengths_and_codes)
    if abs(kraft - 1.0) > 1e-12:
        sys.exit(f"{name}: Kraft sum {kraft}, not a complete code")
    words = sorted(format(code, f"0{length}b") for code, length in lengths_and_codes)
    for a, b in zip(words, words[1:]):
        if b.startswith(a):
            sys.exit(f"{name}: {a} is a prefix of {b}")


PROBE = r"""
#define MINIMP3_IMPLEMENTATION
#define MINIMP3_FLOAT_OUTPUT
#define MINIMP3_NO_SIMD
#include "minimp3.h"
#include <cmath>
#include <cstdio>
#include <vector>
static std::vector<double> response(int k) {
    float qmf[15*2*32] = {0};
    std::vector<double> out;
    for (int g = 0; g < 2; ++g) {
        float grbuf[576] = {0};
        if (g == 0) grbuf[k*18] = 1.0f;
        float pcm[576], lins[(18 + 15)*64];
        mp3d_synth_granule(qmf, grbuf, 18, 1, pcm, lins);
        for (int i = 0; i < 576; ++i) out.push_back(pcm[i]);
    }
    return out;
}
int main() {
    std::vector<std::vector<double>> f(32);
    for (int k = 0; k < 32; ++k) f[k] = response(k);
    double worstFit = 0, worstRound = 0;
    for (int n = 0; n < 512; ++n) {
        double num = 0, den = 0;
        for (int k = 0; k < 32; ++k) { double c = std::cos((2*k + 1)*(n - 48)*M_PI/64); num += c*f[k][n]; den += c*c; }
        // Where (n - 48) is an odd multiple of 32 every subband's cosine is zero:
        // the tap is never used, by synthesis or by analysis, and is stored as 0.
        const double a = den > 1e-9 ? num/den : 0.0;
        for (int k = 0; k < 32; ++k) worstFit = std::fmax(worstFit, std::fabs(f[k][n] - a*std::cos((2*k + 1)*(n - 48)*M_PI/64)));
        const double scaled = a*32768.0;
        worstRound = std::fmax(worstRound, std::fabs(scaled - std::lround(scaled)));
        std::printf("%ld\n", std::lround(scaled));
    }
    std::fprintf(stderr, "%g %g\n", worstFit*32768.0, worstRound);
    return worstFit*32768.0 < 0.1 && worstRound < 0.05 ? 0 : 1;
}
"""


def window() -> list[int]:
    with tempfile.TemporaryDirectory() as folder:
        source = pathlib.Path(folder) / "probe.cpp"
        binary = pathlib.Path(folder) / "probe"
        source.write_text(PROBE)
        subprocess.run(["c++", "-O1", "-I", str(MINIMP3.parent), str(source), "-o", str(binary)], check=True)
        result = subprocess.run([str(binary)], capture_output=True, text=True)
        if result.returncode != 0:
            sys.exit(f"window probe failed: {result.stderr}")
        return [int(line) for line in result.stdout.split()]


def emit_pairs(name: str, codes: dict[tuple[int, int], tuple[int, int]], size: int) -> str:
    rows = []
    for x in range(size):
        rows.append("    " + ", ".join(f"{{0x{codes[(x, y)][0]:x}, {codes[(x, y)][1]}}}" for y in range(size)) + ",")
    return f"const HuffCode {name}[{size * size}] = {{\n" + "\n".join(rows) + "\n};\n"


def main() -> int:
    text = MINIMP3.read_text()
    tabs = array(text, "tabs")
    tab32 = array(text, "tab32")
    tab33 = array(text, "tab33")
    tabindex = array(text, "tabindex")
    linbits = array(text, "g_linbits")

    # The standard's table sizes (xlen = ylen); 0, 4 and 14 are not used.
    sizes = {1: 2, 2: 3, 3: 3, 5: 4, 6: 4, 7: 6, 8: 6, 9: 6, 10: 8, 11: 8, 12: 8, 13: 16, 15: 16, 16: 16, 24: 16}
    out = [
        "// Generated by tools/make_mp3_tables.py from minimp3 (public domain, CC0). Do not edit.",
        "// MPEG-1 Layer III Huffman codes and analysis window, as fixed by ISO/IEC 11172-3.",
        '#include "mp3_tables.hpp"',
        "",
        "namespace maqam::mp3 {",
        "",
    ]
    for table, size in sizes.items():
        codes = big_value_codes(tabs, tabindex[table])
        expected = {(x, y) for x in range(size) for y in range(size)}
        if set(codes) != expected:
            sys.exit(f"table {table}: covers {sorted(set(codes) ^ expected)[:5]} wrongly")
        check_code(f"table {table}", list(codes.values()))
        out.append(emit_pairs(f"kTable{table}", codes, size))
    for name, table in (("kCount1A", tab32), ("kCount1B", tab33)):
        codes = count1_codes(table)
        if set(codes) != set(range(16)):
            sys.exit(f"{name}: incomplete")
        check_code(name, list(codes.values()))
        out.append(f"const HuffCode {name}[16] = {{" + ", ".join(f"{{0x{codes[v][0]:x}, {codes[v][1]}}}" for v in range(16)) + "};\n")
    if linbits[16:24] != [1, 2, 3, 4, 6, 8, 10, 13] or linbits[24:] != [4, 5, 6, 7, 8, 9, 11, 13]:
        sys.exit("unexpected linbits")
    taps = window()
    if len(taps) != 512 or abs(taps[256]) != 75038:
        sys.exit("window does not match the standard's centre tap 1.144989014 * 65536")
    rows = [", ".join(str(v) for v in taps[i:i + 16]) for i in range(0, 512, 16)]
    out.append("// Synthesis prototype in units of 2^-16 (the standard's D[i] up to sign), in")
    out.append("// the order where subband k's filter is window[n] * cos((2k+1)(n-48)pi/64).")
    out.append("const int32_t kWindow[512] = {\n    " + ",\n    ".join(rows) + "\n};\n")
    out.append("}  // namespace maqam::mp3")
    OUTPUT.write_text("\n".join(out) + "\n")
    print(f"wrote {OUTPUT.relative_to(ROOT)}: {len(sizes)} pair tables, 2 quad tables, 512-tap window")
    return 0


if __name__ == "__main__":
    sys.exit(main())
