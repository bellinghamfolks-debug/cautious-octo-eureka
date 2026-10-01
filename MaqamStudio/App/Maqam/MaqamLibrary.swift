import Foundation

/// A named set of per-degree cents adjustments for one maqam, kept for reuse
/// across projects (a singer's own Sikah, a teacher's Bayati).
struct TuningTable: Codable, Equatable, Identifiable {
    var id: UUID
    var name: String
    var maqamId: String
    /// Degree index → cents added to that degree (and its alternates).
    var offsets: [Int: Double]
}

/// Why a maqam cannot be saved, each in words the user can act on.
enum MaqamProblem: Equatable {
    case missingName
    case noDegrees
    case tooManyDegrees(maximum: Int)
    case firstDegreeNotTonic
    case outOfOctave(degree: Int)
    case notRising(degree: Int)
    case tooManyAlternates(degree: Int, maximum: Int)
    case alternateOutOfOctave(degree: Int)
    case invalidTonic

    func message(_ l10n: L10n) -> String {
        switch self {
        case .missingName: return l10n("problem.name")
        case .noDegrees: return l10n("problem.nodegrees")
        case .tooManyDegrees(let maximum): return l10n("problem.toomany", l10n.number(Double(maximum)))
        case .firstDegreeNotTonic: return l10n("problem.firsttonic")
        case .outOfOctave(let degree): return l10n("problem.outofoctave", PitchText.degree(degree, l10n: l10n))
        case .notRising(let degree): return l10n("problem.notrising", PitchText.degree(degree, l10n: l10n))
        case .tooManyAlternates(let degree, let maximum):
            return l10n("problem.alternates", PitchText.degree(degree, l10n: l10n), l10n.number(Double(maximum)))
        case .alternateOutOfOctave(let degree): return l10n("problem.alternaterange", PitchText.degree(degree, l10n: l10n))
        case .invalidTonic: return l10n("problem.tonic")
        }
    }
}

extension MaqamDefinition {
    static let customPrefix = "custom."
    static let maximumDegrees = Int(MQ_MAX_DEGREES)
    static let maximumAlternates = Int(MQ_MAX_ALTERNATES)

    var isCustom: Bool { id.hasPrefix(Self.customPrefix) }

    /// Everything the core needs to hold for this scale to mean what it says.
    var problems: [MaqamProblem] {
        var found: [MaqamProblem] = []
        if arabicName.trimmingCharacters(in: .whitespaces).isEmpty
            && englishName.trimmingCharacters(in: .whitespaces).isEmpty { found.append(.missingName) }
        if degrees.isEmpty { found.append(.noDegrees) }
        if degrees.count > Self.maximumDegrees { found.append(.tooManyDegrees(maximum: Self.maximumDegrees)) }
        if let first = degrees.first, first.cents != 0 { found.append(.firstDegreeNotTonic) }
        for (index, degree) in degrees.enumerated() {
            if degree.cents < 0 || degree.cents >= 1200 { found.append(.outOfOctave(degree: index)) }
            if index > 0, degree.cents <= degrees[index - 1].cents { found.append(.notRising(degree: index)) }
            if degree.alternates.count > Self.maximumAlternates {
                found.append(.tooManyAlternates(degree: index, maximum: Self.maximumAlternates))
            }
            if degree.alternates.contains(where: { $0 < 0 || $0 >= 1200 }) {
                found.append(.alternateOutOfOctave(degree: index))
            }
        }
        if !(typicalTonicHz > 20 && typicalTonicHz < 5000) { found.append(.invalidTonic) }
        return found
    }

    /// A new, empty-named copy the user can change without touching the original.
    func customCopy(arabicName: String, englishName: String) -> MaqamDefinition {
        var copy = self
        copy.id = Self.customPrefix + UUID().uuidString
        copy.arabicName = arabicName
        copy.englishName = englishName
        return copy
    }

    /// A starting point for a maqam built from nothing: the tonic and a fifth.
    static func blankCustom(arabicName: String, englishName: String) -> MaqamDefinition {
        MaqamDefinition(id: customPrefix + UUID().uuidString, family: "custom", arabicName: arabicName,
                        englishName: englishName, lowerJins: "", upperJins: "", typicalTonicHz: 261.6256,
                        degrees: [MaqamDegree(cents: 0, alternates: []), MaqamDegree(cents: 700, alternates: [])],
                        octaveEquivalent: true)
    }
}

/// The user's own maqamat and tuning tables, shared by every project.
///
/// Stored as one JSON file, written atomically. A project that uses a custom
/// maqam also keeps a copy of it, so deleting it here never breaks a project.
final class MaqamLibrary: ObservableObject {
    struct Contents: Codable, Equatable {
        static let format = "maqamstudio.library"
        static let currentVersion = 1
        var format: String = Contents.format
        var version: Int = Contents.currentVersion
        var maqamat: [MaqamDefinition] = []
        var tables: [TuningTable] = []
    }

    enum ImportError: Error, Equatable {
        case notALibrary
        case newerVersion
        case invalidMaqam(name: String)
    }

    @Published private(set) var maqamat: [MaqamDefinition] = []
    @Published private(set) var tables: [TuningTable] = []

    let fileURL: URL
    private let fileManager: FileManager

    init(fileURL: URL, fileManager: FileManager = .default) {
        self.fileURL = fileURL
        self.fileManager = fileManager
        if let data = try? Data(contentsOf: fileURL), let contents = try? Self.decode(data) {
            maqamat = contents.maqamat.filter { $0.isCustom && $0.problems.isEmpty }
            tables = contents.tables
        }
    }

    static func defaultURL(fileManager: FileManager = .default) throws -> URL {
        let support = try fileManager.url(for: .applicationSupportDirectory, in: .userDomainMask,
                                          appropriateFor: nil, create: true)
        return support.appendingPathComponent("MaqamStudio", isDirectory: true).appendingPathComponent("library.json")
    }

    // MARK: Lookup

    /// A built-in or custom maqam by id.
    func definition(id: String) -> MaqamDefinition? {
        MaqamCatalog.definition(id: id) ?? maqamat.first { $0.id == id }
    }

    func tables(for maqamId: String) -> [TuningTable] {
        tables.filter { $0.maqamId == maqamId }
    }

    // MARK: Changes

    /// Adds or replaces a custom maqam. Refuses one that would not be valid.
    func save(_ maqam: MaqamDefinition) throws {
        guard maqam.isCustom else { throw ImportError.invalidMaqam(name: maqam.arabicName) }
        guard maqam.problems.isEmpty else { throw ImportError.invalidMaqam(name: maqam.arabicName) }
        var updated = maqamat
        if let index = updated.firstIndex(where: { $0.id == maqam.id }) { updated[index] = maqam } else { updated.append(maqam) }
        try write(maqamat: updated, tables: tables)
    }

    func delete(maqamId: String) throws {
        try write(maqamat: maqamat.filter { $0.id != maqamId }, tables: tables.filter { $0.maqamId != maqamId })
    }

    func saveTable(_ table: TuningTable) throws {
        var updated = tables
        if let index = updated.firstIndex(where: { $0.id == table.id }) { updated[index] = table } else { updated.append(table) }
        try write(maqamat: maqamat, tables: updated)
    }

    func deleteTable(id: UUID) throws {
        try write(maqamat: maqamat, tables: tables.filter { $0.id != id })
    }

    // MARK: Sharing

    /// The whole library, or just some maqamat, as a file to share.
    func exportData(maqamIds: Set<String>? = nil) throws -> Data {
        let chosen = maqamIds.map { ids in maqamat.filter { ids.contains($0.id) } } ?? maqamat
        let ids = Set(chosen.map(\.id))
        let contents = Contents(maqamat: chosen, tables: tables.filter { ids.contains($0.maqamId) || MaqamCatalog.definition(id: $0.maqamId) != nil })
        return try Self.encoder.encode(contents)
    }

    /// Adds maqamat and tables from a shared file. Nothing is replaced: an
    /// imported maqam whose id is already here gets a new id. Returns how many
    /// maqamat and tables were added.
    @discardableResult
    func importData(_ data: Data) throws -> (maqamat: Int, tables: Int) {
        let contents: Contents
        do {
            contents = try Self.decode(data)
        } catch let error as ImportError {
            throw error
        } catch {
            throw ImportError.notALibrary
        }
        var newMaqamat = maqamat
        var renamed: [String: String] = [:]
        for incoming in contents.maqamat {
            guard incoming.isCustom, incoming.problems.isEmpty else {
                throw ImportError.invalidMaqam(name: incoming.arabicName.isEmpty ? incoming.englishName : incoming.arabicName)
            }
            var maqam = incoming
            if newMaqamat.contains(where: { $0.id == maqam.id }) {
                maqam.id = MaqamDefinition.customPrefix + UUID().uuidString
                renamed[incoming.id] = maqam.id
            }
            newMaqamat.append(maqam)
        }
        var newTables = tables
        var addedTables = 0
        for incoming in contents.tables {
            var table = incoming
            table.maqamId = renamed[incoming.maqamId] ?? incoming.maqamId
            guard newMaqamat.contains(where: { $0.id == table.maqamId }) || MaqamCatalog.definition(id: table.maqamId) != nil
            else { continue }
            if newTables.contains(where: { $0.id == table.id }) { table.id = UUID() }
            newTables.append(table)
            addedTables += 1
        }
        try write(maqamat: newMaqamat, tables: newTables)
        return (contents.maqamat.count, addedTables)
    }

    // MARK: Storage

    private func write(maqamat newMaqamat: [MaqamDefinition], tables newTables: [TuningTable]) throws {
        let data = try Self.encoder.encode(Contents(maqamat: newMaqamat, tables: newTables))
        do {
            try fileManager.createDirectory(at: fileURL.deletingLastPathComponent(), withIntermediateDirectories: true)
            try data.write(to: fileURL, options: [.atomic])
        } catch {
            throw AppError.from(error) { .projectSaveFailed(detail: $0) }
        }
        maqamat = newMaqamat
        tables = newTables
    }

    private static func decode(_ data: Data) throws -> Contents {
        let contents = try JSONDecoder().decode(Contents.self, from: data)
        guard contents.format == Contents.format else { throw ImportError.notALibrary }
        guard contents.version <= Contents.currentVersion else { throw ImportError.newerVersion }
        return contents
    }

    static let encoder: JSONEncoder = {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        return encoder
    }()
}
