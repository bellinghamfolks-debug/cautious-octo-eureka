#include "maqam/tuning.hpp"

namespace maqam {

QuarterToneName nameQuarterTone(double hz, double a4Hz) noexcept {
    QuarterToneName name;
    if (!(hz > 0.0) || !(a4Hz > 0.0)) return name;
    // Cents above C0: C4 is 900 cents below A4, and C0 four octaves below C4.
    const double centsAboveC0 = hzToCents(hz, a4Hz) + 900.0 + 4.0 * kCentsPerOctave;
    const double quarterTones = centsAboveC0 / 50.0;
    const long nearest = std::lround(quarterTones);
    name.remainderCents = (quarterTones - static_cast<double>(nearest)) * 50.0;
    long octave = nearest / 24;
    long step = nearest % 24;
    if (step < 0) { step += 24; octave -= 1; }
    name.octave = static_cast<int>(octave);
    name.step = static_cast<int>(step);
    return name;
}

}  // namespace maqam
