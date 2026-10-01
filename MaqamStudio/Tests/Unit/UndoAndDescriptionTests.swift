import XCTest
@testable import MaqamStudio

final class UndoHistoryTests: XCTestCase {
    func testUndoRedoRestoreSnapshotsInOrder() {
        var history = UndoHistory<Int>()
        var value = 1
        history.record(value, actionKey: "a"); value = 2
        history.record(value, actionKey: "b"); value = 3
        XCTAssertEqual(history.undoActionKey, "b")

        value = history.undo(current: value)!.value
        XCTAssertEqual(value, 2)
        value = history.undo(current: value)!.value
        XCTAssertEqual(value, 1)
        XCTAssertNil(history.undo(current: value))

        value = history.redo(current: value)!.value
        XCTAssertEqual(value, 2)
        XCTAssertEqual(history.redoActionKey, "b")
    }

    func testNewEditClearsRedo() {
        var history = UndoHistory<Int>()
        history.record(1, actionKey: "a")
        _ = history.undo(current: 2)
        XCTAssertTrue(history.canRedo)
        history.record(1, actionKey: "c")
        XCTAssertFalse(history.canRedo)
    }

    func testHistoryIsBounded() {
        var history = UndoHistory<Int>(limit: 3)
        for index in 0..<10 { history.record(index, actionKey: "k") }
        XCTAssertEqual(history.undoStack.map(\.value), [7, 8, 9])
    }

    func testProjectEditsUndoThroughEditable() {
        var document = ProjectDocument(name: "قبل", now: TestSupport.fixedDate)
        var history = UndoHistory<ProjectDocument.Editable>()
        history.record(document.editable, actionKey: "action.maqam")
        document.maqam.maqamId = "hijaz"
        document.name = "بعد"
        document.editable = history.undo(current: document.editable)!.value
        XCTAssertNil(document.maqam.maqamId)
        XCTAssertEqual(document.name, "قبل")
    }
}

final class AudioDescriptionTests: XCTestCase {
    private func waveform(_ levels: [Float]) -> WaveformSummary {
        WaveformSummary(minimum: levels.map { _ in -0.1 }, maximum: levels.map { _ in 0.1 }, rmsDbfs: levels)
    }

    func testSectionsGroupLoudnessAndMergeShortRuns() {
        // 10 buckets over 10 s: 2 s silence, 5 s singing with a 1 s dip, 3 s silence.
        let shape = waveform([-90, -90, -20, -20, -60, -20, -20, -90, -90, -90])
        let sections = AudioDescription.sections(of: shape, duration: 10, minimumSeconds: 1.5)
        XCTAssertEqual(sections.map(\.loudness), [.silent, .moderate, .silent])
        XCTAssertEqual(sections[1].start, 2, accuracy: 1e-9)
        XCTAssertEqual(sections[1].end, 7, accuracy: 1e-9)
    }

    func testClassification() {
        XCTAssertEqual(AudioDescription.classify(-70), .silent)
        XCTAssertEqual(AudioDescription.classify(-40), .quiet)
        XCTAssertEqual(AudioDescription.classify(-25), .moderate)
        XCTAssertEqual(AudioDescription.classify(-10), .loud)
    }

    func testSummaryTellsWhereTheSoundIs() {
        let l10n = L10n(language: .english, defaults: UserDefaults(suiteName: "maqam-desc-\(UUID())")!)
        let shape = waveform([-90, -90, -20, -12, -20, -20, -20, -90, -90, -90])
        let levels = LevelSummary(peakDbfs: -3, rmsDbfs: -20, crestDb: 17, dcOffset: 0, noiseFloorDbfs: -90,
                                  dynamicRangeDb: 70, clippedSamples: 0, frames: 480_000, channels: 1)
        let text = AudioDescription.summary(levels: levels, waveform: shape, duration: 10, l10n: l10n)
        XCTAssertTrue(text.contains("Sound starts after 2 seconds"), text)
        XCTAssertTrue(text.contains("Sound ends at 7 seconds"), text)
        XCTAssertTrue(text.contains("Loudest moment at 3 seconds"), text)
        XCTAssertTrue(text.contains("No clipping"), text)
    }

    func testSilentRecordingIsSaidToBeSilent() {
        let l10n = L10n(language: .arabic, defaults: UserDefaults(suiteName: "maqam-desc-\(UUID())")!)
        let shape = waveform(Array(repeating: -120, count: 20))
        let levels = LevelSummary(peakDbfs: -160, rmsDbfs: -160, crestDb: 0, dcOffset: 0, noiseFloorDbfs: -160,
                                  dynamicRangeDb: 0, clippedSamples: 0, frames: 48_000, channels: 1)
        let text = AudioDescription.summary(levels: levels, waveform: shape, duration: 1, l10n: l10n)
        XCTAssertTrue(text.contains(l10n("describe.allsilent")), text)
    }

    func testChartDownsamplingKeepsPeaks() {
        let values: [Float] = (0..<1000).map { $0 == 517 ? -1 : -60 }
        let reduced = WaveformChart.downsample(values, to: 100)
        XCTAssertEqual(reduced.count, 100)
        XCTAssertEqual(reduced.max(), -1)
    }
}
