import XCTest
@testable import MaqamStudio

final class ProjectStoreTests: XCTestCase {
    private func makeStore(root: URL? = nil, session: String = "session-a") throws -> ProjectStore {
        try ProjectStore(root: root ?? TestSupport.temporaryFolder(self), sessionId: session)
    }

    private func metadata(for url: URL) throws -> AudioMetadata {
        try AudioFileReader.inspect(url)
    }

    func testCreateSaveAndOpenRoundTrip() throws {
        let store = try makeStore()
        var document = try store.create(name: "تجربة", now: TestSupport.fixedDate)
        document.maqam.maqamId = "bayati"
        document.maqam.tonicHz = 293.66
        document.maqam.degreeOffsets = [1: -10, 5: 25]
        try store.save(document)

        let (opened, restored) = try store.open(id: document.id)
        XCTAssertFalse(restored)
        XCTAssertEqual(opened, document)
    }

    func testDamagedDocumentIsRestoredFromBackup() throws {
        let store = try makeStore()
        var document = try store.create(name: "أول", now: TestSupport.fixedDate)
        document.name = "ثانٍ"
        try store.save(document)  // the first version is now the backup
        let main = store.folder(for: document.id).appendingPathComponent(ProjectStore.documentName)
        try Data("{ not json".utf8).write(to: main)

        let (opened, restored) = try store.open(id: document.id)
        XCTAssertTrue(restored)
        XCTAssertEqual(opened.name, "أول")
        // The main file was repaired from the backup.
        let (reopened, restoredAgain) = try store.open(id: document.id)
        XCTAssertFalse(restoredAgain)
        XCTAssertEqual(reopened.name, "أول")
    }

    func testProjectWithBothFilesDamagedIsReportedNotOpened() throws {
        let store = try makeStore()
        let document = try store.create(name: "x", now: TestSupport.fixedDate)
        try store.save(document)
        let folder = store.folder(for: document.id)
        try Data("bad".utf8).write(to: folder.appendingPathComponent(ProjectStore.documentName))
        try Data("bad".utf8).write(to: folder.appendingPathComponent(ProjectStore.backupName))
        XCTAssertThrowsError(try store.open(id: document.id)) { error in
            guard case AppError.projectCorrupted = error else { return XCTFail("\(error)") }
        }
    }

    func testMissingProjectIsNotFound() throws {
        let store = try makeStore()
        XCTAssertThrowsError(try store.open(id: UUID())) { error in
            XCTAssertEqual(error as? AppError, .projectNotFound)
        }
    }

    func testImportKeepsOriginalByteExactAndReadOnly() throws {
        let store = try makeStore()
        let source = try TestSupport.fixture("bayati_phrase_48k.wav")
        var document = try store.create(name: "استيراد", now: TestSupport.fixedDate)
        try store.importOriginal(from: source, into: &document, metadata: metadata(for: source), recorded: false)

        let stored = try XCTUnwrap(store.originalURL(for: document))
        XCTAssertEqual(try Data(contentsOf: stored), try Data(contentsOf: source))
        XCTAssertTrue(FileManager.default.fileExists(atPath: source.path), "an imported file must be copied, not moved")
        XCTAssertFalse(FileManager.default.isWritableFile(atPath: stored.path))
        XCTAssertEqual(document.original?.sha256, try ProjectStore.sha256(of: source))
        XCTAssertTrue(store.verifyOriginal(of: document))
    }

    func testTamperedOriginalFailsVerification() throws {
        let store = try makeStore()
        let source = try TestSupport.fixture("bayati_phrase_48k.wav")
        var document = try store.create(name: "t", now: TestSupport.fixedDate)
        try store.importOriginal(from: source, into: &document, metadata: metadata(for: source), recorded: false)
        let stored = try XCTUnwrap(store.originalURL(for: document))
        try FileManager.default.setAttributes([.posixPermissions: 0o644], ofItemAtPath: stored.path)
        let handle = try FileHandle(forWritingTo: stored)
        try handle.seek(toOffset: 1000)
        try handle.write(contentsOf: Data([0x7F, 0x7F, 0x7F]))
        try handle.close()
        XCTAssertFalse(store.verifyOriginal(of: document))
    }

    func testRecordedTakeIsMovedIntoProject() throws {
        let folder = try TestSupport.temporaryFolder(self)
        let store = try makeStore(root: folder.appendingPathComponent("Projects"))
        let take = folder.appendingPathComponent("take.wav")
        try FileManager.default.copyItem(at: TestSupport.fixture("bayati_phrase_48k.wav"), to: take)
        var document = try store.create(name: "تسجيل", now: TestSupport.fixedDate)
        try store.importOriginal(from: take, into: &document, metadata: metadata(for: take), recorded: true)
        XCTAssertFalse(FileManager.default.fileExists(atPath: take.path))
        XCTAssertEqual(document.original?.recorded, true)
        XCTAssertTrue(store.verifyOriginal(of: document))
    }

    func testAnalysisCacheIsIgnoredWhenOriginalChangesOrPitchIsMissing() throws {
        let store = try makeStore()
        let source = try TestSupport.fixture("bayati_phrase_48k.wav")
        var document = try store.create(name: "a", now: TestSupport.fixedDate)
        try store.importOriginal(from: source, into: &document, metadata: metadata(for: source), recorded: false)
        let full = try AudioFileReader.analyzeFully(source, buckets: 64) { _ in }
        var analysis = AudioAnalysis(levels: full.levels, waveform: full.waveform, analyzedAt: TestSupport.fixedDate,
                                     sourceSHA256: try XCTUnwrap(document.original?.sha256), pitch: full.pitch)
        try store.saveAnalysis(analysis, for: document.id)
        XCTAssertEqual(store.loadAnalysis(for: document), analysis)

        // A cache from before pitch tracking is redone, not shown without notes.
        analysis.pitch = nil
        try store.saveAnalysis(analysis, for: document.id)
        XCTAssertNil(store.loadAnalysis(for: document))

        analysis.pitch = full.pitch
        try store.saveAnalysis(analysis, for: document.id)
        document.original?.sha256 = "different"
        XCTAssertNil(store.loadAnalysis(for: document))
    }

    func testListIsNewestFirstAndFlagsProjectsLeftOpenByAnEarlierRun() throws {
        let root = try TestSupport.temporaryFolder(self)
        let earlier = try makeStore(root: root, session: "earlier-run")
        let old = try earlier.create(name: "قديم", now: TestSupport.fixedDate)
        let new = try earlier.create(name: "جديد", now: TestSupport.fixedDate.addingTimeInterval(60))
        earlier.lock(id: old.id)  // the earlier run never closed it

        let current = try makeStore(root: root, session: "this-run")
        current.lock(id: new.id)  // open in this run: not a crash
        let list = current.list()
        XCTAssertEqual(list.map(\.name), ["جديد", "قديم"])
        XCTAssertEqual(list.first { $0.id == old.id }?.needsRecovery, true)
        XCTAssertEqual(list.first { $0.id == new.id }?.needsRecovery, false)

        current.unlock(id: old.id)
        XCTAssertEqual(current.list().first { $0.id == old.id }?.needsRecovery, false)
    }

    func testDuplicateCopiesAudioAndGetsItsOwnIdentity() throws {
        let store = try makeStore()
        let source = try TestSupport.fixture("bayati_phrase_48k.wav")
        var document = try store.create(name: "أصل", now: TestSupport.fixedDate)
        try store.importOriginal(from: source, into: &document, metadata: metadata(for: source), recorded: false)
        document.maqam.maqamId = "saba"
        try store.save(document)
        store.lock(id: document.id)

        let copy = try store.duplicate(id: document.id, newName: "نسخة", now: TestSupport.fixedDate)
        XCTAssertNotEqual(copy.id, document.id)
        XCTAssertEqual(copy.name, "نسخة")
        XCTAssertEqual(copy.maqam, document.maqam)
        XCTAssertTrue(store.verifyOriginal(of: copy))
        let copyFolder = store.folder(for: copy.id)
        XCTAssertFalse(FileManager.default.fileExists(atPath: copyFolder.appendingPathComponent(ProjectStore.lockName).path))
        XCTAssertEqual(try store.open(id: copy.id).document.id, copy.id)
        XCTAssertEqual(store.list().count, 2)
    }

    func testDeleteRemovesReadOnlyOriginal() throws {
        let store = try makeStore()
        let source = try TestSupport.fixture("bayati_phrase_48k.wav")
        var document = try store.create(name: "حذف", now: TestSupport.fixedDate)
        try store.importOriginal(from: source, into: &document, metadata: metadata(for: source), recorded: false)
        try store.save(document)
        try store.delete(id: document.id)
        XCTAssertFalse(FileManager.default.fileExists(atPath: store.folder(for: document.id).path))
        XCTAssertTrue(store.list().isEmpty)
    }
}
