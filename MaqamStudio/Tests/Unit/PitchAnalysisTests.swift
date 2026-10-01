import AVFoundation
import XCTest
@testable import MaqamStudio

/// Pitch, notes and intonation through the app's own path: decode, track,
/// segment, judge against a maqam.
final class PitchAnalysisTests: XCTestCase {
    private let d4 = 293.6648

    private func english() -> L10n {
        L10n(language: .english, defaults: UserDefaults(suiteName: "maqam-pitch-\(UUID())")!)
    }

    private func bayatiNotes() throws -> [SungNote] {
        let full = try AudioFileReader.analyzeFully(TestSupport.fixture("bayati_phrase_48k.wav"), buckets: 100) { _ in }
        return try XCTUnwrap(full.pitch).notes
    }

    func testFixturePhraseIsFoundAsFourQuarterToneNotes() throws {
        let notes = try bayatiNotes()
        XCTAssertEqual(notes.count, 4)
        for (index, (note, expected)) in zip(notes, [0.0, 150, 300, 500]).enumerated() {
            XCTAssertEqual(mq_hz_to_cents(note.hz, d4), expected, accuracy: 4, "note \(index)")
            XCTAssertEqual(note.start, 0.5 + 0.6 * Double(index), accuracy: 0.05, "note \(index)")
            XCTAssertEqual(note.duration, 0.6, accuracy: 0.08, "note \(index)")
            XCTAssertFalse(note.hasVibrato)
        }
    }

    func testPitchTrackCoversTheFileAndIsSilentWhereTheAudioIs() throws {
        let full = try AudioFileReader.analyzeFully(TestSupport.fixture("bayati_phrase_48k.wav"), buckets: 100) { _ in }
        let track = try XCTUnwrap(full.pitch).track
        XCTAssertEqual(track.hopSeconds, 0.01, accuracy: 1e-9)
        XCTAssertEqual(track.time(at: track.count - 1), 2.9, accuracy: 0.05)
        let leading = track.indices(in: 0...0.4)
        XCTAssertFalse(leading.isEmpty)
        XCTAssertTrue(leading.allSatisfy { track.hz[$0] == 0 })
        let held = track.indices(in: 0.7...0.9)
        XCTAssertTrue(held.allSatisfy { abs(mq_hz_to_cents(Double(track.hz[$0]), d4)) < 4 })
    }

    func testIntonationAgainstTheRightMaqamIsInTune() throws {
        let notes = try bayatiNotes()
        let bayati = try XCTUnwrap(MaqamCatalog.definition(id: "bayati"))
        let intonation = try XCTUnwrap(Intonation.evaluate(notes, maqam: bayati, tonicHz: d4))
        XCTAssertEqual(intonation.inTuneCount, 4)
        XCTAssertEqual(intonation.inTuneFraction, 1, accuracy: 0.01)
        XCTAssertEqual(intonation.results.map(\.degreeIndex), [0, 1, 2, 3])
        XCTAssertEqual(intonation.degrees.count, 4)
    }

    func testTheSamePhraseAgainstRastShowsTheQuarterToneDifference() throws {
        // Rast on D has 200 and 350 where Bayati has 150 and 300.
        let notes = try bayatiNotes()
        let rast = try XCTUnwrap(MaqamCatalog.definition(id: "rast"))
        let intonation = try XCTUnwrap(Intonation.evaluate(notes, maqam: rast, tonicHz: d4))
        XCTAssertEqual(intonation.inTuneCount, 2)
        XCTAssertEqual(intonation.results[1].deviationCents, -50, accuracy: 4)
        XCTAssertEqual(intonation.results[2].deviationCents, -50, accuracy: 4)
    }

    func testUserDegreeOffsetsChangeTheTargets() throws {
        let notes = try bayatiNotes()
        let bayati = try XCTUnwrap(MaqamCatalog.definition(id: "bayati")).applying(offsets: [1: 20])
        XCTAssertEqual(bayati.degrees[1].cents, 170)
        let intonation = try XCTUnwrap(Intonation.evaluate(notes, maqam: bayati, tonicHz: d4))
        XCTAssertEqual(intonation.results[1].deviationCents, -20, accuracy: 4)
        XCTAssertFalse(intonation.results[1].inTune)
        XCTAssertEqual(intonation.inTuneCount, 3)
    }

    func testHighSampleRateFilesAreTrackedNotRejected() throws {
        let folder = try TestSupport.temporaryFolder(self)
        let url = folder.appendingPathComponent("tone96k.wav")
        let rate = 96000.0
        let format = try XCTUnwrap(AVAudioFormat(standardFormatWithSampleRate: rate, channels: 1))
        let frames = AVAudioFrameCount(rate * 0.8)
        let buffer = try XCTUnwrap(AVAudioPCMBuffer(pcmFormat: format, frameCapacity: frames))
        buffer.frameLength = frames
        let hz = 110.0  // low male voice: needs the larger frame at 96 kHz
        for index in 0..<Int(frames) {
            let t = Double(index) / rate
            buffer.floatChannelData![0][index] = Float(0.3 * sin(2 * .pi * hz * t) + 0.15 * sin(4 * .pi * hz * t))
        }
        do {
            let file = try AVAudioFile(forWriting: url, settings: format.settings)
            try file.write(from: buffer)
        }
        let full = try AudioFileReader.analyzeFully(url, buckets: 50) { _ in }
        let notes = try XCTUnwrap(full.pitch).notes
        XCTAssertEqual(notes.count, 1)
        XCTAssertEqual(mq_hz_to_cents(try XCTUnwrap(notes.first).hz, hz), 0, accuracy: 4)
    }

    func testLiveAnalyzerReadsAToneFedInSmallBuffers() throws {
        let rate = 48000.0
        let analyzer = try XCTUnwrap(LivePitchAnalyzer(sampleRate: rate))
        let hz = 220.0 * pow(2, 50.0 / 1200)  // a quarter tone above A3
        let samples = (0..<Int(rate / 2)).map { Float(0.3 * sin(2 * .pi * hz * Double($0) / rate)) }
        var last: LivePitch?
        for start in stride(from: 0, to: samples.count, by: 512) {
            let chunk = Array(samples[start..<min(start + 512, samples.count)])
            if let reading = chunk.withUnsafeBufferPointer({ analyzer.push($0) }) { last = reading }
        }
        let reading = try XCTUnwrap(last)
        XCTAssertTrue(reading.voiced)
        XCTAssertEqual(mq_hz_to_cents(reading.hz, hz), 0, accuracy: 3)
        let named = PitchNaming.name(hz: reading.hz)
        XCTAssertEqual(PitchNaming.englishSteps[named.step], "A half-sharp")
    }

    func testNotesAreDescribedInWordsWithTheirDegree() throws {
        let l10n = english()
        let notes = try bayatiNotes()
        let bayati = try XCTUnwrap(MaqamCatalog.definition(id: "bayati"))
        let intonation = try XCTUnwrap(Intonation.evaluate(notes, maqam: bayati, tonicHz: d4))
        let text = PitchText.note(notes[1], result: intonation.result(for: notes[1]), l10n: l10n)
        XCTAssertTrue(text.hasPrefix("E half-flat 4"), text)
        XCTAssertTrue(text.contains("degree 2"), text)
        XCTAssertTrue(text.contains("in the centre"), text)
        XCTAssertEqual(PitchText.deviation(12.4, l10n: l10n), "12 cents sharp")
        XCTAssertEqual(PitchText.deviation(-8, l10n: l10n), "8 cents flat")
        XCTAssertEqual(PitchText.degree(0, l10n: l10n), "the tonic")
        let tendency = PitchText.tendency(intonation.degrees[1], maqam: bayati, tonicHz: d4, l10n: l10n)
        XCTAssertTrue(tendency.hasPrefix("degree 2 (E half-flat 4)"), tendency)
    }

    func testOldAnalysisWithoutPitchStillDecodes() throws {
        let json = """
        {"levels":{"peakDbfs":-3,"rmsDbfs":-20,"crestDb":17,"dcOffset":0,"noiseFloorDbfs":-90,
        "dynamicRangeDb":70,"clippedSamples":0,"frames":48000,"channels":1},
        "waveform":{"minimum":[0],"maximum":[0],"rmsDbfs":[-160]},
        "analyzedAt":"2026-09-30T10:00:00Z","sourceSHA256":"abc"}
        """
        let analysis = try ProjectStore.decoder.decode(AudioAnalysis.self, from: Data(json.utf8))
        XCTAssertNil(analysis.pitch)
    }
}
