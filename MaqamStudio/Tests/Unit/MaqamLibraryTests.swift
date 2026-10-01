import XCTest
@testable import MaqamStudio

final class MaqamLibraryTests: XCTestCase {
    private func makeLibrary(_ folder: URL? = nil) throws -> MaqamLibrary {
        let root = try folder ?? TestSupport.temporaryFolder(self)
        return MaqamLibrary(fileURL: root.appendingPathComponent("library.json"))
    }

    private func sikahCopy() throws -> MaqamDefinition {
        try XCTUnwrap(MaqamCatalog.definition(id: "sikah")).customCopy(arabicName: "سيكاه عراقي", englishName: "")
    }

    func testValidationExplainsEveryProblem() throws {
        var maqam = MaqamDefinition.blankCustom(arabicName: "", englishName: "")
        XCTAssertEqual(maqam.problems, [.missingName])
        maqam.arabicName = "تجربة"
        XCTAssertTrue(maqam.problems.isEmpty)

        maqam.degrees[0].cents = 10
        XCTAssertTrue(maqam.problems.contains(.firstDegreeNotTonic))
        maqam.degrees[0].cents = 0
        maqam.degrees.append(MaqamDegree(cents: 650, alternates: []))  // lower than the 700 before it
        XCTAssertTrue(maqam.problems.contains(.notRising(degree: 2)))
        maqam.degrees[2].cents = 1200
        XCTAssertTrue(maqam.problems.contains(.outOfOctave(degree: 2)))
        maqam.degrees[2] = MaqamDegree(cents: 1050, alternates: [1000, 1100, 1150])
        XCTAssertTrue(maqam.problems.contains(.tooManyAlternates(degree: 2, maximum: 2)))
        maqam.degrees[2].alternates = [1250]
        XCTAssertTrue(maqam.problems.contains(.alternateOutOfOctave(degree: 2)))

        var crowded = MaqamDefinition.blankCustom(arabicName: "م", englishName: "")
        crowded.degrees = (0..<13).map { MaqamDegree(cents: Double($0) * 90, alternates: []) }
        XCTAssertTrue(crowded.problems.contains(.tooManyDegrees(maximum: 12)))

        let l10n = L10n(language: .arabic, defaults: UserDefaults(suiteName: "lib-\(UUID())")!)
        for problem in maqam.problems + crowded.problems + [.missingName, .invalidTonic, .noDegrees] {
            XCTAssertFalse(problem.message(l10n).hasPrefix("problem."), "\(problem)")
        }
    }

    func testEveryBuiltinPassesTheSameValidation() {
        for maqam in MaqamCatalog.builtins {
            XCTAssertEqual(maqam.problems, [], maqam.id)
            XCTAssertFalse(maqam.isCustom)
        }
    }

    func testCustomMaqamatPersistAcrossLaunches() throws {
        let folder = try TestSupport.temporaryFolder(self)
        var maqam = try sikahCopy()
        maqam.degrees[1].cents = 160  // a higher second, as some singers place it
        try makeLibrary(folder).save(maqam)

        let reopened = try makeLibrary(folder)
        XCTAssertEqual(reopened.maqamat, [maqam])
        XCTAssertEqual(reopened.definition(id: maqam.id)?.degrees[1].cents, 160)
        XCTAssertEqual(reopened.definition(id: "rast")?.id, "rast", "built-ins are still found")
    }

    func testInvalidOrBuiltinMaqamIsRefused() throws {
        let library = try makeLibrary()
        var broken = try sikahCopy()
        broken.degrees[2].cents = 50
        XCTAssertThrowsError(try library.save(broken))
        XCTAssertThrowsError(try library.save(try XCTUnwrap(MaqamCatalog.definition(id: "rast"))))
        XCTAssertTrue(library.maqamat.isEmpty)
    }

    func testDeletingAMaqamDeletesItsTables() throws {
        let library = try makeLibrary()
        let maqam = try sikahCopy()
        try library.save(maqam)
        try library.saveTable(TuningTable(id: UUID(), name: "حفلة", maqamId: maqam.id, offsets: [2: 10]))
        try library.saveTable(TuningTable(id: UUID(), name: "راست أستاذي", maqamId: "rast", offsets: [2: -5]))
        XCTAssertEqual(library.tables(for: maqam.id).count, 1)
        try library.delete(maqamId: maqam.id)
        XCTAssertNil(library.definition(id: maqam.id))
        XCTAssertEqual(library.tables.map(\.name), ["راست أستاذي"])
    }

    func testExportedFileImportsOnAnotherDeviceWithoutReplacingAnything() throws {
        let source = try makeLibrary()
        let maqam = try sikahCopy()
        try source.save(maqam)
        try source.saveTable(TuningTable(id: UUID(), name: "ضبطي", maqamId: maqam.id, offsets: [1: 5]))
        let data = try source.exportData()

        let other = try makeLibrary()
        let added = try other.importData(data)
        XCTAssertEqual(added.maqamat, 1)
        XCTAssertEqual(added.tables, 1)
        XCTAssertEqual(other.maqamat, [maqam])

        // Importing the same file again adds a second copy under a new id.
        try other.importData(data)
        XCTAssertEqual(other.maqamat.count, 2)
        XCTAssertNotEqual(other.maqamat[0].id, other.maqamat[1].id)
        XCTAssertEqual(Set(other.tables.map(\.maqamId)), Set(other.maqamat.map(\.id)))
    }

    func testImportRejectsForeignBrokenOrNewerFiles() throws {
        let library = try makeLibrary()
        XCTAssertThrowsError(try library.importData(Data("not json".utf8))) {
            XCTAssertEqual($0 as? MaqamLibrary.ImportError, .notALibrary)
        }
        XCTAssertThrowsError(try library.importData(Data(#"{"format":"other","version":1,"maqamat":[],"tables":[]}"#.utf8))) {
            XCTAssertEqual($0 as? MaqamLibrary.ImportError, .notALibrary)
        }
        XCTAssertThrowsError(try library.importData(Data(#"{"format":"maqamstudio.library","version":99,"maqamat":[],"tables":[]}"#.utf8))) {
            XCTAssertEqual($0 as? MaqamLibrary.ImportError, .newerVersion)
        }
        var broken = try sikahCopy()
        broken.degrees[3].cents = 10
        let contents = MaqamLibrary.Contents(maqamat: [broken], tables: [])
        XCTAssertThrowsError(try library.importData(try MaqamLibrary.encoder.encode(contents))) {
            guard case .invalidMaqam = $0 as? MaqamLibrary.ImportError else { return XCTFail("\($0)") }
        }
        XCTAssertTrue(library.maqamat.isEmpty, "a refused file adds nothing")
    }

    func testOffsetsMoveDegreesAndAlternatesTogether() throws {
        let nahawand = try XCTUnwrap(MaqamCatalog.definition(id: "nahawand"))
        let tuned = nahawand.applying(offsets: [6: -15, 2: 5, 40: 100])
        XCTAssertEqual(tuned.degrees[2].cents, 305)
        XCTAssertEqual(tuned.degrees[6].cents, 1085)
        XCTAssertEqual(tuned.degrees[6].alternates, [985])
        XCTAssertEqual(tuned.degrees.count, nahawand.degrees.count, "an offset for a missing degree is ignored")
    }

    func testProjectKeepsItsCustomMaqamWhenTheLibraryLosesIt() throws {
        let store = try ProjectStore(root: TestSupport.temporaryFolder(self))
        var document = try store.create(name: "مشروع", now: TestSupport.fixedDate)
        let maqam = try sikahCopy()
        document.maqam.maqamId = maqam.id
        document.maqam.customDefinition = maqam
        document.maqam.degreeOffsets = [2: 7]
        try store.save(document)
        let opened = try store.open(id: document.id).document
        XCTAssertEqual(opened.maqam.customDefinition, maqam)
        XCTAssertEqual(opened.maqam.degreeOffsets, [2: 7])
    }

    func testOldProjectsWithoutACustomMaqamStillOpen() throws {
        // Exactly as phase 1 and 2 wrote it: offsets keyed by degree, no custom copy.
        let json = #"{"maqamId":"rast","tonicHz":261.6,"a4Hz":440,"degreeOffsets":{"2":7},"manuallyChosen":true}"#
        let settings = try JSONDecoder().decode(MaqamSettings.self, from: Data(json.utf8))
        XCTAssertNil(settings.customDefinition)
        XCTAssertEqual(settings.maqamId, "rast")
        XCTAssertEqual(settings.degreeOffsets, [2: 7])
    }

    func testNameFallsBackToTheOtherLanguage() throws {
        let english = L10n(language: .english, defaults: UserDefaults(suiteName: "lib-\(UUID())")!)
        XCTAssertEqual(try sikahCopy().name(english), "سيكاه عراقي")
    }
}
