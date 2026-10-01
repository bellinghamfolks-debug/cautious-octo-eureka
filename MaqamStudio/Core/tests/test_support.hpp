// A deliberately small test harness: no third-party dependency, so the same
// tests build with the Linux toolchain and with Xcode's clang on macOS.
#pragma once

#include <cmath>
#include <cstdint>
#include <functional>
#include <iostream>
#include <sstream>
#include <string>
#include <vector>

namespace test {

struct Case {
    std::string name;
    std::function<void()> body;
};

std::vector<Case>& registry();
int& failures();

struct Registrar {
    Registrar(const char* name, std::function<void()> body) { registry().push_back({name, std::move(body)}); }
};

void fail(const char* file, int line, const std::string& message);

}  // namespace test

#define TEST_CONCAT_INNER(a, b) a##b
#define TEST_CONCAT(a, b) TEST_CONCAT_INNER(a, b)
#define TEST_CASE(name)                                                             \
    static void TEST_CONCAT(test_body_, __LINE__)();                                \
    static test::Registrar TEST_CONCAT(test_registrar_, __LINE__)(name, TEST_CONCAT(test_body_, __LINE__)); \
    static void TEST_CONCAT(test_body_, __LINE__)()

#define CHECK(condition)                                                            \
    do {                                                                            \
        if (!(condition)) test::fail(__FILE__, __LINE__, "CHECK(" #condition ")"); \
    } while (0)

#define CHECK_NEAR(actual, expected, tolerance)                                     \
    do {                                                                            \
        const double test_a = static_cast<double>(actual);                          \
        const double test_e = static_cast<double>(expected);                        \
        if (!(std::fabs(test_a - test_e) <= (tolerance))) {                         \
            std::ostringstream test_message;                                        \
            test_message << #actual << " = " << test_a << ", expected " << test_e   \
                         << " +/- " << (tolerance);                                 \
            test::fail(__FILE__, __LINE__, test_message.str());                     \
        }                                                                           \
    } while (0)

// ------------------------------------------------------------------ signals

namespace signal {

constexpr double kPi = 3.14159265358979323846;

// A tone with `harmonics` partials of 1/k amplitude, a crude but voice-like
// spectrum that exposes octave errors a pure sine never would.
inline std::vector<float> tone(double hz, double seconds, double sampleRate, double amplitude = 0.5,
                               int harmonics = 1) {
    const std::size_t count = static_cast<std::size_t>(seconds * sampleRate);
    std::vector<float> out(count);
    double norm = 0.0;
    for (int k = 1; k <= harmonics; ++k) norm += 1.0 / k;
    for (std::size_t i = 0; i < count; ++i) {
        double value = 0.0;
        for (int k = 1; k <= harmonics; ++k) value += std::sin(2.0 * kPi * hz * k * i / sampleRate) / k;
        out[i] = static_cast<float>(amplitude * value / norm);
    }
    return out;
}

// Continuous-phase pitch contour: `centsAt(t)` around `baseHz`.
template <typename Contour>
std::vector<float> contour(double baseHz, double seconds, double sampleRate, Contour centsAt,
                           double amplitude = 0.5, int harmonics = 3) {
    const std::size_t count = static_cast<std::size_t>(seconds * sampleRate);
    std::vector<float> out(count);
    std::vector<double> phases(static_cast<std::size_t>(harmonics), 0.0);
    double norm = 0.0;
    for (int k = 1; k <= harmonics; ++k) norm += 1.0 / k;
    for (std::size_t i = 0; i < count; ++i) {
        const double t = i / sampleRate;
        const double hz = baseHz * std::exp2(centsAt(t) / 1200.0);
        double value = 0.0;
        for (int k = 1; k <= harmonics; ++k) {
            phases[static_cast<std::size_t>(k - 1)] += 2.0 * kPi * hz * k / sampleRate;
            value += std::sin(phases[static_cast<std::size_t>(k - 1)]) / k;
        }
        out[i] = static_cast<float>(amplitude * value / norm);
    }
    return out;
}

// Deterministic white noise (xorshift), so failures reproduce exactly.
inline std::vector<float> noise(std::size_t count, double amplitude, std::uint32_t seed = 12345) {
    std::vector<float> out(count);
    std::uint32_t state = seed;
    for (std::size_t i = 0; i < count; ++i) {
        state ^= state << 13; state ^= state >> 17; state ^= state << 5;
        out[i] = static_cast<float>(amplitude * (static_cast<double>(state) / 4294967295.0 * 2.0 - 1.0));
    }
    return out;
}

}  // namespace signal
