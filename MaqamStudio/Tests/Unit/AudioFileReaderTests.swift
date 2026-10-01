import XCTest
@testable import MaqamStudio

final class AudioFileReaderTests: XCTestCase {
    func testInspectsMonoWav() throws {
        let metadata = try AudioFileReader.inspect(TestSupport.fixture("bayati_phrase_48k.wav"))
        XCTAssertEqual(metadata.sampleRate, 48000)
        XCTAssertEqual(metadata.channels, 1)
        // 0.5 s silence + four 0.6 s notes.
        XCTAssertEqual(metadata.frames, 24000 + 4 * 28800)
        XCTAssertEqual(metadata.duration, 2.9, accuracy: 0.001)
        XCTAssertEqual(metadata.formatDescription, "WAV 48000 Hz 16-bit")
    }

    func testInspectsStereoWav() throws {
        let metadata = try AudioFileReader.inspect(TestSupport.fixture("rast_stereo_44k.wav"))
        XCTAssertEqual(metadata.sampleRate, 44100)
        XCTAssertEqual(metadata.channels, 2)
    }

    func testRejectsUnsupportedExtension() throws {
        let folder = try TestSupport.temporaryFolder(self)
        let renamed = folder.appendingPathComponent("song.txt")
        try FileManager.default.copyItem(at: TestSupport.fixture("bayati_phrase_48k.wav"), to: renamed)
        XCTAssertThrowsError(try AudioFileReader.inspect(renamed)) { error in
            XCTAssertEqual(error as? AppError, .unsupportedFormat(fileExtension: "txt"))
        }
    }

    func testCorruptedFileIsReportedAsCorrupted() throws {
        let url = try TestSupport.fixture("corrupted.wav")
        XCTAssertThrowsError(try AudioFileReader.inspect(url)) { error in
            XCTAssertEqual(error as? AppError, .corruptedAudio)
        }
        XCTAssertThrowsError(try AudioFileReader.analyze(url) { _ in }) { error in
            XCTAssertEqual(error as? AppError, .corruptedAudio)
        }
    }

    func testEmptyFileIsReportedAsEmpty() throws {
        XCTAssertThrowsError(try AudioFileReader.inspect(TestSupport.fixture("empty.wav"))) { error in
            XCTAssertEqual(error as? AppError, .emptyAudio)
        }
    }

    func testAnalysisMeasuresLevelsAndReportsProgress() throws {
        let progress = ProgressRecorder()
        let (levels, waveform) = try AudioFileReader.analyze(TestSupport.fixture("bayati_phrase_48k.wav"),
                                                             buckets: 290) { progress.add($0) }
        // Measured from the generated file: three harmonics at amplitude 0.4 peak at -9.98 dBFS.
        XCTAssertEqual(levels.peakDbfs, -9.98, accuracy: 0.1)
        XCTAssertFalse(levels.isClipping)
        XCTAssertEqual(levels.channels, 1)
        XCTAssertEqual(levels.frames, 139_200)
        XCTAssertEqual(waveform.buckets, 290)
        // The first 0.5 s is digital silence: 50 of 290 buckets.
        XCTAssertTrue(waveform.rmsDbfs.prefix(49).allSatisfy { $0 < -100 })
        XCTAssertTrue(waveform.rmsDbfs[60..<280].allSatisfy { $0 > -26 })
        XCTAssertEqual(progress.values.last ?? 0, 1, accuracy: 1e-9)
        XCTAssertEqual(progress.values, progress.values.sorted())
    }

    func testClippingIsDetected() throws {
        let (levels, _) = try AudioFileReader.analyze(TestSupport.fixture("clipped_48k.wav")) { _ in }
        XCTAssertTrue(levels.isClipping)
        XCTAssertGreaterThan(levels.clippedSamples, 1000)
    }

    func testStereoAnalysisKeepsBothChannels() throws {
        let (levels, waveform) = try AudioFileReader.analyze(TestSupport.fixture("rast_stereo_44k.wav"),
                                                             buckets: 100) { _ in }
        XCTAssertEqual(levels.channels, 2)
        XCTAssertEqual(waveform.buckets, 100)
        XCTAssertFalse(levels.isClipping)
    }

    func testAnalysisStopsWhenCancelled() async throws {
        let url = try TestSupport.fixture("rast_stereo_44k.wav")
        let task = Task.detached { () throws -> (LevelSummary, WaveformSummary) in
            withUnsafeCurrentTask { $0?.cancel() }
            return try AudioFileReader.analyze(url) { _ in }
        }
        do {
            _ = try await task.value
            XCTFail("expected cancellation")
        } catch is CancellationError {
        }
    }
}

/// Collects progress callbacks, which may arrive from any thread.
final class ProgressRecorder: @unchecked Sendable {
    private let lock = NSLock()
    private var stored: [Double] = []

    func add(_ value: Double) {
        lock.lock(); stored.append(value); lock.unlock()
    }

    var values: [Double] {
        lock.lock(); defer { lock.unlock() }
        return stored
    }
}
