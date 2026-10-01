import AVFoundation
import XCTest
@testable import MaqamStudio

/// Detection through the app's own path: synthesised singing → file → analysis → candidates.
final class DetectionTests: XCTestCase {
    /// A presentation of `maqam` on `tonicHz`: up the scale, down, resting on the tonic.
    private func sing(_ maqam: MaqamDefinition, tonicHz: Double) throws -> URL {
        var cents = maqam.degrees.map(\.cents)
        if maqam.octaveEquivalent { cents.append(1200) }
        cents += maqam.degrees.dropFirst().reversed().map { $0.alternates.first ?? $0.cents }
        cents.append(0)
        let rate = 48000.0
        let format = try XCTUnwrap(AVAudioFormat(standardFormatWithSampleRate: rate, channels: 1))
        let noteFrames = Int(rate * 0.45)
        let gap = Int(rate * 0.06)
        let finalFrames = Int(rate * 1.2)
        let total = AVAudioFrameCount(cents.count * (noteFrames + gap) + finalFrames)
        let buffer = try XCTUnwrap(AVAudioPCMBuffer(pcmFormat: format, frameCapacity: total))
        buffer.frameLength = total
        let data = buffer.floatChannelData![0]
        for index in 0..<Int(total) { data[index] = 0 }
        var at = 0
        for (position, value) in cents.enumerated() {
            let frames = position == cents.count - 1 ? noteFrames + finalFrames : noteFrames
            let hz = tonicHz * pow(2, value / 1200)
            for n in 0..<frames {
                let t = Double(n) / rate
                let fade = min(1, Double(min(n, frames - n)) / 480)
                data[at + n] = Float(fade * 0.3 * (sin(2 * .pi * hz * t) + 0.4 * sin(4 * .pi * hz * t) + 0.2 * sin(6 * .pi * hz * t)) / 1.6)
            }
            at += frames + gap
        }
        let url = try TestSupport.temporaryFolder(self).appendingPathComponent("\(maqam.id).wav")
        let file = try AVAudioFile(forWriting: url, settings: format.settings)
        try file.write(from: buffer)
        return url
    }

    private func detect(_ url: URL, extra: [MaqamDefinition] = []) throws -> MaqamDetectionResult {
        let notes = try XCTUnwrap(try AudioFileReader.analyzeFully(url, buckets: 32) { _ in }.pitch).notes
        return try XCTUnwrap(MaqamDetectionResult.detect(notes: notes, maqamat: MaqamCatalog.builtins + extra))
    }

    func testRequiredMaqamatAreDetectedFromAudio() throws {
        for id in ["rast", "bayati", "hijaz", "saba", "sikah"] {
            let maqam = try XCTUnwrap(MaqamCatalog.definition(id: id))
            let result = try detect(sing(maqam, tonicHz: maqam.typicalTonicHz))
            XCTAssertTrue(result.enoughData, id)
            XCTAssertEqual(result.best?.maqam.id, id)
            XCTAssertEqual(mq_hz_to_cents(try XCTUnwrap(result.best).tonicHz, maqam.typicalTonicHz), 0, accuracy: 6, id)
            XCTAssertGreaterThan(try XCTUnwrap(result.best).probability, 0.5, id)
        }
    }

    func testTheSingersOwnTonicIsKept() throws {
        let bayati = try XCTUnwrap(MaqamCatalog.definition(id: "bayati"))
        let tonic = bayati.typicalTonicHz * pow(2, 28.0 / 1200)  // a singer 28 cents high
        let result = try detect(sing(bayati, tonicHz: tonic))
        XCTAssertEqual(result.best?.maqam.id, "bayati")
        XCTAssertEqual(mq_hz_to_cents(try XCTUnwrap(result.best).tonicHz, tonic), 0, accuracy: 6)
        let english = L10n(language: .english, defaults: UserDefaults(suiteName: "detect-\(UUID())")!)
        XCTAssertTrue(DetectionText.tonicOffset(try XCTUnwrap(result.best).tonicHz, l10n: english).contains("sharp"))
    }

    func testAUsersOwnMaqamCanBeDetected() throws {
        // Hijaz with a quarter-tone-lowered sixth: not a built-in.
        var mine = try XCTUnwrap(MaqamCatalog.definition(id: "hijaz")).customCopy(arabicName: "حجازي", englishName: "")
        mine.degrees = [0, 100, 400, 500, 700, 750, 1000].map { MaqamDegree(cents: $0, alternates: []) }
        let result = try detect(sing(mine, tonicHz: mine.typicalTonicHz), extra: [mine])
        XCTAssertEqual(result.best?.maqam.id, mine.id)
    }

    func testAShortPhraseMakesNoClaim() throws {
        // The 2.9-second fixture has four notes: too little to name a maqam.
        let result = try detect(TestSupport.fixture("bayati_phrase_48k.wav"))
        XCTAssertFalse(result.enoughData)
    }

    func testDisagreementIsSpottedOnlyWhenConfident() throws {
        let bayati = try XCTUnwrap(MaqamCatalog.definition(id: "bayati"))
        let result = try detect(sing(bayati, tonicHz: bayati.typicalTonicHz))
        XCTAssertFalse(result.disagrees(with: "bayati", tonicHz: bayati.typicalTonicHz))
        XCTAssertFalse(result.disagrees(with: "bayati", tonicHz: bayati.typicalTonicHz * 2), "an octave apart is the same tonic")
        XCTAssertTrue(result.disagrees(with: "rast", tonicHz: bayati.typicalTonicHz))
        XCTAssertTrue(result.disagrees(with: nil, tonicHz: nil))
        var unsure = result
        unsure.candidates = unsure.candidates.map { var c = $0; c.probability = 0.3; return c }
        XCTAssertFalse(unsure.disagrees(with: "rast", tonicHz: 100))
    }
}
