import AVFoundation
import XCTest
@testable import MaqamStudio

final class TuningTests: XCTestCase {
    private let d4 = 293.6648

    /// Writes a mono 48 kHz phrase of held notes (cents from D4) with short gaps.
    private func phrase(_ cents: [Double], seconds: Double = 0.6) throws -> URL {
        let url = try TestSupport.temporaryFolder(self).appendingPathComponent("phrase.wav")
        let rate = 48000.0
        let format = try XCTUnwrap(AVAudioFormat(standardFormatWithSampleRate: rate, channels: 1))
        let noteFrames = Int(rate * seconds)
        let gapFrames = Int(rate * 0.12)
        let total = AVAudioFrameCount(cents.count * (noteFrames + gapFrames))
        let buffer = try XCTUnwrap(AVAudioPCMBuffer(pcmFormat: format, frameCapacity: total))
        buffer.frameLength = total
        let data = buffer.floatChannelData![0]
        var index = 0
        for value in cents {
            let hz = d4 * pow(2, value / 1200)
            for n in 0..<noteFrames {
                let t = Double(n) / rate
                let fade = min(1, Double(min(n, noteFrames - n)) / 480)
                data[index] = Float(fade * 0.3 * (sin(2 * .pi * hz * t) + 0.5 * sin(4 * .pi * hz * t) + 0.25 * sin(6 * .pi * hz * t)) / 1.75)
                index += 1
            }
            for _ in 0..<gapFrames { data[index] = 0; index += 1 }
        }
        let file = try AVAudioFile(forWriting: url, settings: format.settings)
        try file.write(from: buffer)
        return url
    }

    private func bayati() throws -> MaqamDefinition { try XCTUnwrap(MaqamCatalog.definition(id: "bayati")) }

    private func render(_ source: URL, settings: PitchCorrectionSettings, maqam: MaqamDefinition, tonic: Double) throws -> URL {
        let pitch = try XCTUnwrap(try AudioFileReader.analyzeFully(source, buckets: 32) { _ in }.pitch)
        let destination = source.deletingLastPathComponent().appendingPathComponent("tuned-\(UUID().uuidString).caf")
        try TuningRenderer.render(source: source, destination: destination, pitch: pitch, maqam: maqam, tonicHz: tonic,
                                  settings: settings) { _ in }
        return destination
    }

    func testZeroStrengthRenderIsTheOriginal() throws {
        let source = try TestSupport.fixture("bayati_phrase_48k.wav")
        var settings = PitchCorrectionSettings.preset(.strong)
        settings.strength = 0
        let output = try render(source, settings: settings, maqam: bayati(), tonic: d4)
        addTeardownBlock { try? FileManager.default.removeItem(at: output) }
        let original = try TestSupport.monoSamples(of: source).samples
        let tuned = try TestSupport.monoSamples(of: output).samples
        XCTAssertEqual(original.count, tuned.count)
        let worst = zip(original, tuned).map { abs($0 - $1) }.max() ?? 1
        XCTAssertLessThan(worst, 1e-4)
    }

    func testSharpAndFlatNotesLandOnBayatisQuarterTones() throws {
        let source = try phrase([-15, 172, 285, 518])
        let output = try render(source, settings: .preset(.robotic), maqam: bayati(), tonic: d4)
        let notes = try XCTUnwrap(try AudioFileReader.analyzeFully(output, buckets: 32) { _ in }.pitch).notes
        XCTAssertEqual(notes.count, 4)
        for (note, expected) in zip(notes, [0.0, 150, 300, 500]) {
            XCTAssertEqual(mq_hz_to_cents(note.hz, d4), expected, accuracy: 4)
        }
        let intonation = try XCTUnwrap(Intonation.evaluate(notes, maqam: bayati(), tonicHz: d4))
        XCTAssertEqual(intonation.inTuneCount, 4)
    }

    func testNaturalMovesNotesMostOfTheWay() throws {
        let source = try phrase([172])
        let output = try render(source, settings: .preset(.natural), maqam: bayati(), tonic: d4)
        let note = try XCTUnwrap(try AudioFileReader.analyzeFully(output, buckets: 32) { _ in }.pitch?.notes.first)
        let cents = mq_hz_to_cents(note.hz, d4)
        XCTAssertLessThan(cents, 162)   // moved down from 172
        XCTAssertGreaterThan(cents, 148)
    }

    func testStereoKeepsChannelsAndLength() throws {
        let source = try TestSupport.fixture("rast_stereo_44k.wav")
        let rast = try XCTUnwrap(MaqamCatalog.definition(id: "rast"))
        let output = try render(source, settings: .preset(.natural), maqam: rast, tonic: 261.6256)
        addTeardownBlock { try? FileManager.default.removeItem(at: output) }
        let input = try AVAudioFile(forReading: source)
        let tuned = try AVAudioFile(forReading: output)
        XCTAssertEqual(tuned.processingFormat.channelCount, 2)
        XCTAssertEqual(tuned.processingFormat.sampleRate, 44100)
        XCTAssertEqual(tuned.length, input.length)
    }

    func testNotesSetByHandArePinnedOrLeftAlone() throws {
        let pitch = try XCTUnwrap(try AudioFileReader.analyzeFully(TestSupport.fixture("bayati_phrase_48k.wav"),
                                                                   buckets: 32) { _ in }.pitch)
        var settings = PitchCorrectionSettings.preset(.natural)
        settings.noteOverrides[1] = .init(bypass: false, targetCents: 200)
        settings.noteOverrides[2] = .init(bypass: true, targetCents: nil)
        let result = try TuningRenderer.correction(pitch: pitch, maqam: bayati(), tonicHz: d4, settings: settings)
        XCTAssertEqual(try XCTUnwrap(result.targets[0]), 0, accuracy: 1e-9)
        XCTAssertEqual(try XCTUnwrap(result.targets[1]), 200, accuracy: 1e-9)
        XCTAssertNil(result.targets[2])
        let third = pitch.notes[2]
        for index in pitch.track.indices(in: (third.start + 0.05)...(third.end - 0.05)) {
            XCTAssertEqual(result.shift[index], 0, accuracy: 1e-9)
        }
    }

    func testPresetsDifferAndKeepHandMadeChoices() {
        let natural = PitchCorrectionSettings.preset(.natural)
        let robotic = PitchCorrectionSettings.preset(.robotic)
        XCTAssertGreaterThan(natural.retuneMs, robotic.retuneMs)
        XCTAssertEqual(robotic.vibratoAmount, 0)
        XCTAssertEqual(natural.vibratoAmount, 1)
        var mine = natural
        mine.noteOverrides[3] = .init(bypass: true, targetCents: nil)
        mine.formantShiftCents = 100
        let switched = mine.applying(.strong)
        XCTAssertEqual(switched.preset, .strong)
        XCTAssertEqual(switched.noteOverrides, mine.noteOverrides)
        XCTAssertEqual(switched.formantShiftCents, 100)
    }

    func testFingerprintFollowsEverythingTheRenderDependsOn() throws {
        let maqam = try bayati()
        let base = TuningRenderer.fingerprint(settings: .preset(.natural), maqam: maqam, tonicHz: d4, sourceSHA256: "a")
        XCTAssertEqual(base, TuningRenderer.fingerprint(settings: .preset(.natural), maqam: maqam, tonicHz: d4, sourceSHA256: "a"))
        XCTAssertNotEqual(base, TuningRenderer.fingerprint(settings: .preset(.strong), maqam: maqam, tonicHz: d4, sourceSHA256: "a"))
        XCTAssertNotEqual(base, TuningRenderer.fingerprint(settings: .preset(.natural), maqam: maqam, tonicHz: d4 + 1, sourceSHA256: "a"))
        XCTAssertNotEqual(base, TuningRenderer.fingerprint(settings: .preset(.natural), maqam: maqam.applying(offsets: [1: 5]),
                                                           tonicHz: d4, sourceSHA256: "a"))
        XCTAssertNotEqual(base, TuningRenderer.fingerprint(settings: .preset(.natural), maqam: maqam, tonicHz: d4, sourceSHA256: "b"))
    }

    func testProjectsFromBeforeTuningStillOpen() throws {
        let settings = try JSONDecoder().decode(ProcessingSettings.self, from: Data(#"{"chainVersion":1}"#.utf8))
        XCTAssertNil(settings.tuning)
        var withTuning = settings
        withTuning.tuning = .preset(.strong)
        withTuning.tuning?.noteOverrides[4] = .init(bypass: false, targetCents: 350)
        let data = try JSONEncoder().encode(withTuning)
        XCTAssertEqual(try JSONDecoder().decode(ProcessingSettings.self, from: data), withTuning)
    }

    func testEveryPresetHasWordsInBothLanguages() {
        for language in L10n.Language.allCases {
            let l10n = L10n(language: language, defaults: UserDefaults(suiteName: "tuning-\(UUID())")!)
            for preset in PitchCorrectionSettings.Preset.allCases {
                XCTAssertNotEqual(l10n("preset." + preset.rawValue), "preset." + preset.rawValue)
                XCTAssertNotEqual(l10n("preset." + preset.rawValue + ".about"), "preset." + preset.rawValue + ".about")
            }
        }
    }
}
