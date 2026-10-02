import AVFoundation
import XCTest
@testable import MaqamStudio

/// Exports are read back with Apple's own decoders (AVAudioFile), independent
/// of the encoders that wrote them.
final class ExportTests: XCTestCase {
    private func export(_ options: ExportOptions, source: URL? = nil) throws -> (ExportResult, AVAudioFile) {
        let input = try source ?? TestSupport.fixture("bayati_phrase_48k.wav")
        let folder = try TestSupport.temporaryFolder(self)
        let destination = folder.appendingPathComponent("export.\(options.format.fileExtension)")
        var fractions: [Double] = []
        let result = try AudioExporter.export(source: input, kind: .original, options: options, destination: destination) {
            fractions.append($0)
        }
        XCTAssertEqual(fractions.last ?? 0, 1, accuracy: 1e-9)
        XCTAssertEqual(fractions, fractions.sorted(), "progress only moves forward")
        return (result, try AVAudioFile(forReading: destination))
    }

    private func rmsDb(_ samples: [Float]) -> Double {
        let power = samples.reduce(0.0) { $0 + Double($1) * Double($1) } / Double(max(1, samples.count))
        return 10 * log10(max(power, 1e-20))
    }

    func testFlacIsTheSameAudioInApplesDecoder() throws {
        var options = ExportOptions()
        options.format = .flac
        options.bitDepth = 16
        options.sampleRate = 48000
        options.stereo = false
        options.loudnessTarget = nil
        let (result, file) = try export(options)
        XCTAssertEqual(file.processingFormat.sampleRate, 48000)
        XCTAssertEqual(file.processingFormat.channelCount, 1)
        let original = try TestSupport.monoSamples(of: TestSupport.fixture("bayati_phrase_48k.wav")).samples
        let decoded = try TestSupport.monoSamples(of: result.url).samples
        XCTAssertEqual(decoded.count, original.count)
        // 16 bits with dither: within two steps everywhere.
        let worst = zip(original, decoded).map { abs($0 - $1) }.max() ?? 1
        XCTAssertLessThan(worst, 2.5 / 32768)
        XCTAssertLessThan(result.bytes, UInt64(original.count * 2), "smaller than 16-bit PCM")
    }

    func testMp3PlaysInApplesDecoderAtTheRightLevelAndLength() throws {
        var options = ExportOptions()
        options.format = .mp3
        options.mp3Kbps = 192
        options.sampleRate = 44100
        options.stereo = true
        options.loudnessTarget = nil
        let (result, file) = try export(options)
        XCTAssertEqual(file.processingFormat.sampleRate, 44100)
        XCTAssertEqual(file.processingFormat.channelCount, 2)
        let source = try TestSupport.monoSamples(of: TestSupport.fixture("bayati_phrase_48k.wav"))
        let seconds = Double(source.samples.count) / source.sampleRate
        let decoded = try TestSupport.monoSamples(of: result.url).samples
        let decodedSeconds = Double(decoded.count) / 44100
        // The coder adds its fixed delay and whole frames, never loses audio.
        XCTAssertGreaterThanOrEqual(decodedSeconds, seconds - 0.001)
        XCTAssertLessThan(decodedSeconds, seconds + 0.15)
        XCTAssertEqual(rmsDb(decoded), rmsDb(source.samples), accuracy: 0.5)
        // Constant bit rate: the size follows from the length.
        XCTAssertEqual(Double(result.bytes), 192_000 / 8 * decodedSeconds, accuracy: 192_000 / 8 * 0.06)
    }

    func testLoudnessTargetIsReachedUnderTheCeiling() throws {
        var options = ExportOptions()
        options.format = .wav
        options.bitDepth = 24
        options.sampleRate = 44100
        options.loudnessTarget = -14
        let (result, file) = try export(options)
        XCTAssertEqual(result.lufs, -14, accuracy: 1)
        XCTAssertLessThanOrEqual(result.truePeakDbtp, -0.9)
        XCTAssertEqual(result.clippedSamples, 0)
        XCTAssertEqual(file.processingFormat.sampleRate, 44100)
        XCTAssertEqual(file.processingFormat.channelCount, 2)
        let source = try AVAudioFile(forReading: TestSupport.fixture("bayati_phrase_48k.wav"))
        XCTAssertEqual(Double(file.length), (Double(source.length) * 44100 / 48000).rounded(.up))
    }

    func testFloatWavKeepsEverySampleExactly() throws {
        var options = ExportOptions()
        options.format = .wav
        options.bitDepth = 32
        options.sampleRate = 48000
        options.stereo = false
        options.loudnessTarget = nil
        let (result, _) = try export(options)
        let original = try TestSupport.monoSamples(of: TestSupport.fixture("bayati_phrase_48k.wav")).samples
        let decoded = try TestSupport.monoSamples(of: result.url).samples
        XCTAssertEqual(decoded, original)
    }

    func testImpossibleSettingsAreExplained() throws {
        var options = ExportOptions()
        options.format = .mp3
        options.sampleRate = 96000
        XCTAssertEqual(options.normalized().sampleRate, 48000, "only offered rates survive")
        options.format = .flac
        options.bitDepth = 32
        XCTAssertEqual(options.normalized().bitDepth, 24, "FLAC has no float")
        options.format = .wav
        XCTAssertEqual(options.normalized().bitDepth, 32)
        var settings = ExportOptions(format: .mp3, sampleRate: 48000).cSettings(gainDb: 0, limit: false)
        settings.sample_rate = 22050
        XCTAssertEqual(mq_export_check(&settings, 48000, 1), Int32(MQ_EXPORT_MP3_SAMPLE_RATE.rawValue))
    }

    func testFileNamesAreSafeAndSaySourceAndFormat() {
        XCTAssertEqual(AudioExporter.fileName(projectName: "Layali / Bayati: take 2", source: .studio, format: .mp3,
                                              suffix: "studio"), "Layali - Bayati- take 2 - studio.mp3")
        XCTAssertEqual(AudioExporter.fileName(projectName: "..", source: .original, format: .wav, suffix: "original"),
                       "Maqam Studio - original.wav")
        XCTAssertEqual(AudioExporter.fileName(projectName: "تسجيل ١", source: .tuned, format: .flac, suffix: "منغَّم"),
                       "تسجيل ١ - منغَّم.flac")
    }

    func testOptionsAreRememberedAsJSON() throws {
        let options = ExportOptions(format: .flac, sampleRate: 44100, bitDepth: 16, stereo: false, mp3Kbps: 320,
                                    loudnessTarget: nil, ceilingDb: -1)
        XCTAssertEqual(try JSONDecoder().decode(ExportOptions.self, from: JSONEncoder().encode(options)), options)
    }

    func testExportAndProgressHaveWordsInBothLanguages() {
        for language in L10n.Language.allCases {
            let l10n = L10n(language: language, defaults: UserDefaults(suiteName: "export-\(UUID())")!)
            var keys = ExportFormat.allCases.flatMap { ["export.format." + $0.rawValue, "export.format." + $0.rawValue + ".about"] }
            keys += ExportSource.allCases.map { "export.suffix." + $0.rawValue }
            for task in ["tuning", "studio", "export"] { keys += ["announce.\(task)progress", "announce.\(task)cancelled"] }
            for key in keys { XCTAssertNotEqual(l10n(key), key, "\(language) \(key)") }
            let sentence = ExportText.format(ExportOptions(), l10n: l10n)
            XCTAssertFalse(sentence.contains("%"), sentence)
        }
    }
}
