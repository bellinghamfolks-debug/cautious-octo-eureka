import AVFoundation
import CoreML
import XCTest
@testable import MaqamStudio

final class SeparationTests: XCTestCase {
    /// Stereo 44.1 kHz: a repeating band panned left and right, and a centred
    /// voice that never repeats. Returns the mix file and the true voice.
    private func song(seconds: Double = 12) throws -> (url: URL, voice: [Float], mix: [Float]) {
        let rate = 44100.0
        let frames = Int(seconds * rate)
        let format = try XCTUnwrap(AVAudioFormat(standardFormatWithSampleRate: rate, channels: 2))
        let buffer = try XCTUnwrap(AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(frames)))
        buffer.frameLength = AVAudioFrameCount(frames)
        let left = buffer.floatChannelData![0], right = buffer.floatChannelData![1]
        var voice = [Float](repeating: 0, count: frames)
        var mix = [Float](repeating: 0, count: 2 * frames)
        let loop = Int(2 * rate)
        let notes: [Double] = [293.7, 330.0, 349.2, 392.0, 440.0, 349.2, 311.1, 293.7, 261.6, 370.0, 415.3, 466.2]
        let plucks: [Double] = [220, 262, 330, 262, 294, 349, 440, 349]
        let basses: [Double] = [55, 55, 73.4, 65.4]
        var phase = 0.0
        for i in 0..<frames {
            let p = i % loop
            let t = Double(p) / rate
            let step = p / (loop / 8)
            let decay: Double = exp(-Double(p % (loop / 8)) / rate * 6)
            let pluck: Double = 0.12 * decay * sin(2 * Double.pi * plucks[step] * t)
            let pad: Double = 0.05 * (sin(2 * Double.pi * 196 * t) + sin(2 * Double.pi * 247 * t))
            let bass: Double = 0.15 * sin(2 * Double.pi * basses[p / (loop / 4)] * t)
            let transpose: Double = pow(2, Double((i / Int(6.6 * rate)) % 3) / 12)
            let note: Double = notes[(i / Int(0.55 * rate)) % notes.count] * transpose
            let s = Double(i) / rate
            let vibrato: Double = pow(2, 25 * sin(2 * Double.pi * 5.5 * s) / 1200)
            phase += 2 * Double.pi * note * vibrato / rate
            var v = 0.0
            for h in 1...8 { v += sin(Double(h) * phase) / Double(h) }
            voice[i] = Float(0.07 * v * (i < Int(0.3 * rate) ? 0 : 1))
            let l = Float(bass + pluck + 0.3 * pad) + voice[i]
            let r = Float(bass + 0.2 * pluck + pad) + voice[i]
            left[i] = l
            right[i] = r
            mix[2 * i] = l
            mix[2 * i + 1] = r
        }
        let url = try TestSupport.temporaryFolder(self).appendingPathComponent("song.wav")
        let file = try AVAudioFile(forWriting: url, settings: [AVFormatIDKey: kAudioFormatLinearPCM, AVSampleRateKey: rate,
                                                              AVNumberOfChannelsKey: 2, AVLinearPCMBitDepthKey: 32,
                                                              AVLinearPCMIsFloatKey: true])
        try file.write(from: buffer)
        return (url, voice, mix)
    }

    private func stereo(_ url: URL) throws -> (samples: [Float], rate: Double) {
        let file = try AVAudioFile(forReading: url)
        let format = file.processingFormat
        let buffer = try XCTUnwrap(AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(file.length)))
        try file.read(into: buffer)
        var out = [Float](repeating: 0, count: 2 * Int(buffer.frameLength))
        for i in 0..<Int(buffer.frameLength) {
            out[2 * i] = buffer.floatChannelData![0][i]
            out[2 * i + 1] = buffer.floatChannelData![format.channelCount > 1 ? 1 : 0][i]
        }
        return (out, format.sampleRate)
    }

    private func run(_ source: URL, _ separator: VocalSeparator) throws -> (vocals: URL, rest: URL, rate: Double, frames: Int) {
        let folder = try TestSupport.temporaryFolder(self)
        let vocals = folder.appendingPathComponent("vocals.caf"), rest = folder.appendingPathComponent("accompaniment.caf")
        var last = 0.0
        let result = try SeparationRenderer.separate(source: source, separator: separator, vocals: vocals, accompaniment: rest) {
            XCTAssertGreaterThanOrEqual($0, last - 1e-9)
            last = $0
        }
        XCTAssertEqual(last, 1, accuracy: 1e-9)
        return (vocals, rest, result.sampleRate, result.frames)
    }

    /// A test model from Tests/Fixtures, copied to a .mlmodel so Core ML will compile it.
    private func fixtureModel(_ name: String) throws -> URL {
        let url = try TestSupport.temporaryFolder(self).appendingPathComponent(name + ".mlmodel")
        try FileManager.default.copyItem(at: TestSupport.fixture(name + ".coremlspec"), to: url)
        return url
    }

    func testClassicalStemsAddUpToTheMixAndHoldTheVoice() throws {
        let song = try song()
        let result = try run(song.url, ClassicalSeparator())
        XCTAssertEqual(result.rate, 44100)
        let vocals = try stereo(result.vocals).samples, rest = try stereo(result.rest).samples
        XCTAssertEqual(vocals.count, song.mix.count)
        XCTAssertEqual(rest.count, song.mix.count)
        var worst: Float = 0
        for i in song.mix.indices { worst = max(worst, abs(vocals[i] + rest[i] - song.mix[i])) }
        XCTAssertLessThan(worst, 1e-4, "vocal + music = mix")
        // Signal-to-distortion of the voice, against simply taking the mix.
        func sdr(_ estimate: [Float]) -> Double {
            var signal = 0.0, error = 0.0
            for i in song.voice.indices {
                let truth = Double(song.voice[i])
                signal += 2 * truth * truth
                for c in 0..<2 { let e = truth - Double(estimate[2 * i + c]); error += e * e }
            }
            return 10 * log10(signal / max(error, 1e-30))
        }
        let before = sdr(song.mix), after = sdr(vocals)
        XCTAssertGreaterThan(after - before, 5, "voice \(before) -> \(after) dB")
    }

    func testAModelsMasksReachTheStems() throws {
        let folder = try TestSupport.temporaryFolder(self)
        let separator = try SeparationModels.install(from: fixtureModel("separation_half_mask"), into: folder)
        XCTAssertEqual(separator.sampleRate, 44100)
        XCTAssertEqual(SeparationModels.installed(in: folder).map(\.id), [separator.id])
        let source = try TestSupport.fixture("rast_stereo_44k.wav")
        let result = try run(source, separator)
        let mix = try stereo(source).samples
        let vocals = try stereo(result.vocals).samples, rest = try stereo(result.rest).samples
        XCTAssertEqual(vocals.count, mix.count)
        // The fixture says half of every bin is voice (stored as 128/255).
        let half = Float(128.0 / 255.0)
        var worst: Float = 0
        for i in mix.indices {
            worst = max(worst, abs(vocals[i] - half * mix[i]), abs(rest[i] - (1 - half) * mix[i]))
        }
        XCTAssertLessThan(worst, 1e-3)
        try SeparationModels.remove(separator, from: folder)
        XCTAssertTrue(SeparationModels.installed(in: folder).isEmpty)
    }

    func testAModelAtAnotherRateGetsTheSongConverted() throws {
        let folder = try TestSupport.temporaryFolder(self)
        let separator = try SeparationModels.install(from: fixtureModel("separation_half_mask"), into: folder)
        let source = try TestSupport.fixture("bayati_phrase_48k.wav")  // mono, 48 kHz
        let result = try run(source, separator)
        XCTAssertEqual(result.rate, 44100)
        let input = try AVAudioFile(forReading: source)
        XCTAssertEqual(Double(result.frames), Double(input.length) * 44100 / 48000, accuracy: Double(input.length) * 0.01)
        XCTAssertEqual(try AVAudioFile(forReading: result.vocals).processingFormat.channelCount, 2)
    }

    func testAModelThatBreaksTheContractIsRefusedWithAReason() throws {
        let folder = try TestSupport.temporaryFolder(self)
        XCTAssertThrowsError(try SeparationModels.install(from: fixtureModel("separation_wrong_input"), into: folder)) { error in
            XCTAssertEqual(error as? SeparationModelProblem, .missingInput)
            let spoken = AppError.from(error) { .separationModel(.unreadable(detail: $0)) }
            XCTAssertEqual(spoken, .separationModel(.missingInput))
        }
        XCTAssertTrue(SeparationModels.installed(in: folder).isEmpty, "nothing is kept")
        let notAModel = folder.appendingPathComponent("song.mlmodel")
        try Data("not a model".utf8).write(to: notAModel)
        XCTAssertThrowsError(try SeparationModels.install(from: notAModel, into: folder)) { error in
            guard case .unreadable = error as? SeparationModelProblem else { return XCTFail("\(error)") }
        }
    }

    func testEveryModelProblemHasWordsInBothLanguages() {
        let problems: [AppError] = [.separationTooLong(minutes: 10), .separationModel(.missingInput), .separationModel(.missingOutput),
                                    .separationModel(.unreadable(detail: "x")),
                                    .separationModel(.wrongShape(name: "magnitudes", shape: [1, 128, 1025]))]
        for language in L10n.Language.allCases {
            let l10n = L10n(language: language, defaults: UserDefaults(suiteName: "separation-\(UUID())")!)
            for problem in problems {
                XCTAssertNotEqual(l10n(problem.titleKey), problem.titleKey)
                let message = problem.message(l10n)
                XCTAssertFalse(message.hasPrefix("error.") || message.contains("%@"), message)
            }
            for source in AppModel.ListeningSource.allCases {
                XCTAssertNotEqual(l10n("listen." + source.rawValue), "listen." + source.rawValue)
            }
        }
    }
}
