import XCTest
@testable import MaqamStudio

/// The maqam layer as the app sees it, through the C API: quarter tones stay
/// quarter tones, and a sung phrase maps onto the right degrees.
final class MaqamTests: XCTestCase {
    func testCatalogHasTheRequiredMaqamat() {
        let ids = Set(MaqamCatalog.builtins.map(\.id))
        for required in ["rast", "bayati", "hijaz", "saba", "kurd", "nahawand", "ajam", "sikah"] {
            XCTAssertTrue(ids.contains(required), "missing \(required)")
        }
        for maqam in MaqamCatalog.builtins {
            XCTAssertFalse(maqam.arabicName.isEmpty)
            XCTAssertFalse(maqam.englishName.isEmpty)
            XCTAssertEqual(maqam.degrees.first?.cents, 0, "\(maqam.id) must start on its tonic")
            XCTAssertEqual(maqam.degrees.map(\.cents), maqam.degrees.map(\.cents).sorted(), maqam.id)
        }
    }

    func testQuarterToneDegreesAreNotRoundedToSemitones() throws {
        let rast = try XCTUnwrap(MaqamCatalog.definition(id: "rast"))
        XCTAssertEqual(rast.degrees.map(\.cents), [0, 200, 350, 500, 700, 900, 1050])
        let bayati = try XCTUnwrap(MaqamCatalog.definition(id: "bayati"))
        XCTAssertEqual(bayati.degrees[1].cents, 150)

        // A note sung at 340 cents belongs to Rast's 350, not to 300 or 400.
        let target = try XCTUnwrap(rast.nearestTarget(centsFromTonic: 340))
        XCTAssertEqual(target.targetCents, 350)
        XCTAssertEqual(target.deviationCents, -10, accuracy: 1e-9)
        XCTAssertEqual(target.degreeIndex, 2)
    }

    func testAlternateDegreeIsChosenWhenCloser() throws {
        let nahawand = try XCTUnwrap(MaqamCatalog.definition(id: "nahawand"))
        let target = try XCTUnwrap(nahawand.nearestTarget(centsFromTonic: 1010))
        XCTAssertEqual(target.targetCents, 1000)
        XCTAssertTrue(target.isAlternate)
    }

    func testSabaDoesNotTreatTheOctaveAsItsTonic() throws {
        let saba = try XCTUnwrap(MaqamCatalog.definition(id: "saba"))
        XCTAssertFalse(saba.octaveEquivalent)
        let target = try XCTUnwrap(saba.nearestTarget(centsFromTonic: 1190))
        XCTAssertNotEqual(target.targetCents, 1200)
    }

    func testQuarterToneNamesInBothLanguages() {
        let eHalfFlat = 293.6648 * pow(2, 150.0 / 1200)
        let name = PitchNaming.name(hz: eHalfFlat)
        XCTAssertEqual(name.step, 7)
        XCTAssertEqual(name.octave, 4)
        XCTAssertEqual(name.remainderCents, 0, accuracy: 0.5)
        XCTAssertEqual(PitchNaming.arabicSteps[name.step], "مي نصف بيمول")
        XCTAssertEqual(PitchNaming.englishSteps[name.step], "E half-flat")
        XCTAssertEqual(PitchNaming.name(hz: 440).step, 18)
        XCTAssertEqual(PitchNaming.name(hz: 440).octave, 4)
    }

    func testScaleIsReadableAsText() throws {
        let l10n = L10n(language: .arabic, defaults: UserDefaults(suiteName: "maqam-tests-\(UUID())")!)
        let bayati = try XCTUnwrap(MaqamCatalog.definition(id: "bayati"))
        let text = MaqamText.noteNames(bayati, tonicHz: 293.6648, l10n: l10n)
        XCTAssertTrue(text.hasPrefix("ري "), text)
        XCTAssertTrue(text.contains("مي نصف بيمول"), text)
        XCTAssertEqual(MaqamText.jinsName("jins bayati", l10n: l10n), "بياتي")
    }

    /// The Bayati fixture, sung degree by degree: the pitch tracker plus the
    /// maqam matcher must land on 0, 150, 300 and 500 cents, within 5 cents.
    func testSungBayatiPhraseMapsToItsDegrees() throws {
        let (samples, rate) = try TestSupport.monoSamples(of: TestSupport.fixture("bayati_phrase_48k.wav"))
        var config = mq_pitch_default_config(rate)
        config.minimum_hz = 80
        config.maximum_hz = 1000
        let hop: UInt32 = 480
        var needed = 0
        XCTAssertEqual(mq_pitch_track(samples, samples.count, &config, hop, nil, nil, 0, &needed), MQ_OK)
        var times = [Double](repeating: 0, count: needed)
        var estimates = [MQPitchEstimate](repeating: MQPitchEstimate(), count: needed)
        var count = 0
        XCTAssertEqual(mq_pitch_track(samples, samples.count, &config, hop, &times, &estimates, needed, &count), MQ_OK)

        let bayati = try XCTUnwrap(MaqamCatalog.definition(id: "bayati"))
        let tonic = 293.6648
        for (index, expected) in [0.0, 150, 300, 500].enumerated() {
            let middle = 0.5 + 0.6 * Double(index) + 0.3
            let frame = try XCTUnwrap((0..<count).min { abs(times[$0] - middle) < abs(times[$1] - middle) })
            let estimate = estimates[frame]
            XCTAssertNotEqual(estimate.voiced, 0, "degree \(index) unvoiced")
            let cents = mq_hz_to_cents(estimate.frequency_hz, tonic)
            let target = try XCTUnwrap(bayati.nearestTarget(centsFromTonic: cents))
            XCTAssertEqual(target.targetCents, expected, "degree \(index)")
            XCTAssertLessThan(abs(target.deviationCents), 5, "degree \(index)")
        }
        // The leading silence is not given a pitch.
        let silent = try XCTUnwrap((0..<count).first { times[$0] > 0.1 })
        XCTAssertEqual(estimates[silent].voiced, 0)
    }
}
