#include "maqam/maqam.hpp"
#include "test_support.hpp"

#include <set>

using namespace maqam;

TEST_CASE("the eight required maqamat and their branches are defined") {
    for (const char* id : {"rast", "bayati", "hijaz", "saba", "kurd", "nahawand", "ajam", "sikah"}) {
        const MaqamDefinition* definition = findMaqam(id);
        CHECK(definition != nullptr);
        if (definition) {
            CHECK(definition->scale.degreeCount >= 7);
            CHECK(!definition->arabicName.empty());
            CHECK(definition->scale.degrees[0].cents == 0.0);
        }
    }
    std::set<std::string> ids;
    for (const auto& definition : builtinMaqamat()) ids.insert(definition.id);
    CHECK(ids.size() == builtinMaqamat().size());
    CHECK(builtinMaqamat().size() >= 15);
}

TEST_CASE("rast and bayati carry real quarter tones, not semitones") {
    CHECK(findMaqam("rast")->scale.degrees[2].cents == 350.0);
    CHECK(findMaqam("rast")->scale.degrees[6].cents == 1050.0);
    CHECK(findMaqam("bayati")->scale.degrees[1].cents == 150.0);
    CHECK(findMaqam("sikah")->scale.degrees[1].cents == 150.0);
}

TEST_CASE("a slightly flat Rast third is matched to 350 cents, not 300 or 400") {
    const Scale& rast = findMaqam("rast")->scale;
    const TargetMatch match = nearestTarget(rast, 340.0);
    CHECK(match.degreeIndex == 2);
    CHECK_NEAR(match.targetCents, 350.0, 1e-9);
    CHECK_NEAR(match.deviationCents, -10.0, 1e-9);
}

TEST_CASE("targets are found in any octave, including below the tonic") {
    const Scale& rast = findMaqam("rast")->scale;
    const TargetMatch above = nearestTarget(rast, 1200.0 + 355.0);
    CHECK(above.degreeIndex == 2);
    CHECK_NEAR(above.targetCents, 1550.0, 1e-9);
    const TargetMatch below = nearestTarget(rast, -130.0);  // just under the tonic
    CHECK(below.degreeIndex == 6);
    CHECK_NEAR(below.targetCents, -150.0, 1e-9);
    CHECK_NEAR(below.deviationCents, 20.0, 1e-9);
}

TEST_CASE("saba has no octave tonic: the eighth note is its lowered one") {
    const Scale& saba = findMaqam("saba")->scale;
    CHECK(!saba.octaveEquivalent);
    const TargetMatch match = nearestTarget(saba, 1170.0);
    CHECK_NEAR(match.targetCents, 1100.0, 1e-9);
    // Rast, which is octave-equivalent, does land on the octave.
    CHECK_NEAR(nearestTarget(findMaqam("rast")->scale, 1170.0).targetCents, 1200.0, 1e-9);
}

TEST_CASE("alternate intonations are honoured (Nahawand's descending seventh)") {
    const Scale& nahawand = findMaqam("nahawand")->scale;
    const TargetMatch raised = nearestTarget(nahawand, 1095.0);
    CHECK_NEAR(raised.targetCents, 1100.0, 1e-9);
    CHECK(!raised.alternate);
    const TargetMatch lowered = nearestTarget(nahawand, 1010.0);
    CHECK_NEAR(lowered.targetCents, 1000.0, 1e-9);
    CHECK(lowered.alternate);
    CHECK(lowered.degreeIndex == 6);
}

TEST_CASE("an edited degree is used as written") {
    Scale custom = findMaqam("bayati")->scale;
    custom.degrees[1].cents = 135.0;  // a lower, Iraqi-style second
    const TargetMatch match = nearestTarget(custom, 140.0);
    CHECK_NEAR(match.targetCents, 135.0, 1e-9);
    CHECK_NEAR(match.deviationCents, 5.0, 1e-9);
}

TEST_CASE("an empty scale reports no match instead of inventing one") {
    Scale empty;
    CHECK(nearestTarget(empty, 123.0).degreeIndex == -1);
}
