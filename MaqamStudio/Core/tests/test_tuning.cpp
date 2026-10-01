#include "maqam/tuning.hpp"
#include "test_support.hpp"

using namespace maqam;

TEST_CASE("cents and hertz convert both ways without rounding") {
    CHECK_NEAR(hzToCents(880.0, 440.0), 1200.0, 1e-9);
    CHECK_NEAR(centsToHz(50.0, 440.0), 440.0 * std::exp2(50.0 / 1200.0), 1e-9);
    // 150 cents (a Bayati second) survives the round trip exactly.
    CHECK_NEAR(hzToCents(centsToHz(150.0, 293.66), 293.66), 150.0, 1e-9);
    CHECK_NEAR(hzToCents(centsToHz(-37.5, 200.0), 200.0), -37.5, 1e-9);
}

TEST_CASE("octave folding and circular differences") {
    CHECK_NEAR(foldToOctave(1350.0), 150.0, 1e-9);
    CHECK_NEAR(foldToOctave(-50.0), 1150.0, 1e-9);
    CHECK_NEAR(circularDifference(50.0, 1150.0), 100.0, 1e-9);
    CHECK_NEAR(circularDifference(1150.0, 50.0), -100.0, 1e-9);
}

TEST_CASE("an E half-flat is named a quarter tone, not E or E-flat") {
    const double cFour = 261.6255653;
    const QuarterToneName name = nameQuarterTone(centsToHz(350.0, cFour));
    CHECK(name.octave == 4);
    CHECK(name.step == 7);  // seven quarter tones above C
    CHECK_NEAR(name.remainderCents, 0.0, 1e-6);
    const QuarterToneName sharp = nameQuarterTone(centsToHz(362.0, cFour));
    CHECK(sharp.step == 7);
    CHECK_NEAR(sharp.remainderCents, 12.0, 1e-6);
    const QuarterToneName a4 = nameQuarterTone(440.0);
    CHECK(a4.octave == 4);
    CHECK(a4.step == 18);
}
