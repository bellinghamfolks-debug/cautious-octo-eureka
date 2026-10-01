import CryptoKit
import Foundation

/// Projects on disk.
///
/// Each project is a folder, `<id>.maqamproj`, inside `Documents/Projects`:
///
///     project.json        the document (ProjectDocument)
///     project.json.bak    the previous good save, used if project.json is damaged
///     analysis.json       cached analysis, recomputable
///     audio/original.*    the original recording, never modified
///     session.lock        present while the project is open
///
/// Saves are atomic: the new document is written to a temporary file and moved
/// into place, so a crash mid-save leaves either the old file or the new one,
/// never half of each.
final class ProjectStore {
    static let folderExtension = "maqamproj"
    static let documentName = "project.json"
    static let backupName = "project.json.bak"
    static let analysisName = "analysis.json"
    static let lockName = "session.lock"
    static let audioFolder = "audio"

    let root: URL
    private let fileManager: FileManager
    /// Identifies this run of the app, so a lock from an earlier run means the
    /// project was not closed cleanly.
    let sessionId: String

    init(root: URL, fileManager: FileManager = .default, sessionId: String = UUID().uuidString) throws {
        self.root = root
        self.fileManager = fileManager
        self.sessionId = sessionId
        try fileManager.createDirectory(at: root, withIntermediateDirectories: true)
    }

    static func defaultRoot(fileManager: FileManager = .default) throws -> URL {
        let documents = try fileManager.url(for: .documentDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
        return documents.appendingPathComponent("Projects", isDirectory: true)
    }

    // MARK: Locations

    func folder(for id: UUID) -> URL {
        root.appendingPathComponent("\(id.uuidString).\(Self.folderExtension)", isDirectory: true)
    }

    func originalURL(for document: ProjectDocument) -> URL? {
        guard let original = document.original else { return nil }
        return folder(for: document.id)
            .appendingPathComponent(Self.audioFolder, isDirectory: true)
            .appendingPathComponent(original.fileName)
    }

    // MARK: Create, save, open

    func create(name: String, now: Date = Date()) throws -> ProjectDocument {
        let document = ProjectDocument(name: name, now: now)
        let folder = folder(for: document.id)
        do {
            try fileManager.createDirectory(at: folder.appendingPathComponent(Self.audioFolder, isDirectory: true),
                                            withIntermediateDirectories: true)
            try write(document)
        } catch {
            throw AppError.from(error) { .projectSaveFailed(detail: $0) }
        }
        return document
    }

    func save(_ document: ProjectDocument) throws {
        do {
            try write(document)
        } catch {
            throw AppError.from(error) { .projectSaveFailed(detail: $0) }
        }
    }

    private func write(_ document: ProjectDocument) throws {
        let folder = folder(for: document.id)
        let target = folder.appendingPathComponent(Self.documentName)
        let backup = folder.appendingPathComponent(Self.backupName)
        let data = try Self.encoder.encode(document)
        let temporary = folder.appendingPathComponent(".project-\(UUID().uuidString).tmp")
        try data.write(to: temporary, options: [.atomic])
        if fileManager.fileExists(atPath: target.path) {
            // Keep the last good save before replacing it.
            if fileManager.fileExists(atPath: backup.path) { try fileManager.removeItem(at: backup) }
            try fileManager.copyItem(at: target, to: backup)
            _ = try fileManager.replaceItemAt(target, withItemAt: temporary)
        } else {
            try fileManager.moveItem(at: temporary, to: target)
        }
    }

    /// Opens a project, falling back to the backup if the main file is damaged.
    /// Returns whether the backup had to be used.
    func open(id: UUID) throws -> (document: ProjectDocument, restoredFromBackup: Bool) {
        let folder = folder(for: id)
        let target = folder.appendingPathComponent(Self.documentName)
        let backup = folder.appendingPathComponent(Self.backupName)
        guard fileManager.fileExists(atPath: folder.path) else { throw AppError.projectNotFound }
        if let document = try? decode(at: target) { return (document, false) }
        if let document = try? decode(at: backup) {
            try? save(document)  // repair the main file from the backup
            return (document, true)
        }
        throw AppError.projectCorrupted(name: id.uuidString)
    }

    private func decode(at url: URL) throws -> ProjectDocument {
        let data = try Data(contentsOf: url)
        let document = try Self.decoder.decode(ProjectDocument.self, from: data)
        guard document.formatVersion <= ProjectDocument.currentFormatVersion else {
            throw AppError.projectCorrupted(name: document.name)
        }
        return document
    }

    // MARK: Original audio

    /// Copies a file into the project as its original, byte for byte, and
    /// records its checksum. The source file is only read.
    func importOriginal(from source: URL, into document: inout ProjectDocument, metadata: AudioMetadata,
                        recorded: Bool) throws {
        let folder = folder(for: document.id).appendingPathComponent(Self.audioFolder, isDirectory: true)
        try fileManager.createDirectory(at: folder, withIntermediateDirectories: true)
        let fileExtension = source.pathExtension.lowercased().isEmpty ? "caf" : source.pathExtension.lowercased()
        let fileName = "original.\(fileExtension)"
        let destination = folder.appendingPathComponent(fileName)
        try ensureSpace(forBytes: metadata.byteCount)
        if fileManager.fileExists(atPath: destination.path) { try fileManager.removeItem(at: destination) }
        if recorded {
            try fileManager.moveItem(at: source, to: destination)
        } else {
            try fileManager.copyItem(at: source, to: destination)
        }
        // Read-only from now on, as a second guard against accidental writes.
        try? fileManager.setAttributes([.posixPermissions: 0o444], ofItemAtPath: destination.path)
        document.original = OriginalAudio(
            fileName: fileName,
            sha256: try Self.sha256(of: destination),
            byteCount: metadata.byteCount,
            sampleRate: metadata.sampleRate,
            channels: metadata.channels,
            frames: metadata.frames,
            sourceName: metadata.sourceName,
            sourceFormat: metadata.formatDescription,
            recorded: recorded)
    }

    /// Whether the stored original still matches the checksum taken at import.
    func verifyOriginal(of document: ProjectDocument) -> Bool {
        guard let original = document.original, let url = originalURL(for: document),
              let digest = try? Self.sha256(of: url) else { return false }
        return digest == original.sha256
    }

    // MARK: Analysis cache

    func saveAnalysis(_ analysis: AudioAnalysis, for id: UUID) throws {
        let url = folder(for: id).appendingPathComponent(Self.analysisName)
        try Self.encoder.encode(analysis).write(to: url, options: [.atomic])
    }

    func loadAnalysis(for document: ProjectDocument) -> AudioAnalysis? {
        let url = folder(for: document.id).appendingPathComponent(Self.analysisName)
        guard let data = try? Data(contentsOf: url),
              let analysis = try? Self.decoder.decode(AudioAnalysis.self, from: data),
              analysis.sourceSHA256 == document.original?.sha256,
              analysis.pitch?.version == PitchAnalysis.currentVersion else { return nil }
        return analysis
    }

    // MARK: Listing, duplicating, deleting

    func list() -> [ProjectSummary] {
        guard let folders = try? fileManager.contentsOfDirectory(at: root, includingPropertiesForKeys: nil) else { return [] }
        return folders
            .filter { $0.pathExtension == Self.folderExtension }
            .compactMap { folder -> ProjectSummary? in
                guard let id = UUID(uuidString: folder.deletingPathExtension().lastPathComponent) else { return nil }
                let document = (try? decode(at: folder.appendingPathComponent(Self.documentName)))
                    ?? (try? decode(at: folder.appendingPathComponent(Self.backupName)))
                guard let document else { return nil }
                return ProjectSummary(id: id, name: document.name, modifiedAt: document.modifiedAt,
                                      duration: document.original?.duration, url: folder,
                                      needsRecovery: staleLock(in: folder))
            }
            .sorted { $0.modifiedAt > $1.modifiedAt }
    }

    func duplicate(id: UUID, newName: String, now: Date = Date()) throws -> ProjectDocument {
        let (source, _) = try open(id: id)
        var copy = source
        copy.id = UUID()
        copy.name = newName
        copy.createdAt = now
        copy.modifiedAt = now
        let sourceFolder = folder(for: id)
        let destinationFolder = folder(for: copy.id)
        do {
            try fileManager.copyItem(at: sourceFolder, to: destinationFolder)
            try? fileManager.removeItem(at: destinationFolder.appendingPathComponent(Self.lockName))
            // The source's backup describes the source, not the copy.
            try? fileManager.removeItem(at: destinationFolder.appendingPathComponent(Self.backupName))
            try write(copy)
        } catch {
            try? fileManager.removeItem(at: destinationFolder)
            throw AppError.from(error) { .projectSaveFailed(detail: $0) }
        }
        return copy
    }

    func delete(id: UUID) throws {
        let folder = folder(for: id)
        // The original is read-only; make it removable first.
        if let audio = try? fileManager.contentsOfDirectory(at: folder.appendingPathComponent(Self.audioFolder),
                                                           includingPropertiesForKeys: nil) {
            for file in audio { try? fileManager.setAttributes([.posixPermissions: 0o644], ofItemAtPath: file.path) }
        }
        try fileManager.removeItem(at: folder)
    }

    // MARK: Session locks (crash recovery)

    func lock(id: UUID) {
        let url = folder(for: id).appendingPathComponent(Self.lockName)
        try? Data(sessionId.utf8).write(to: url, options: [.atomic])
    }

    func unlock(id: UUID) {
        try? fileManager.removeItem(at: folder(for: id).appendingPathComponent(Self.lockName))
    }

    /// A lock left by an earlier run: the app ended while this project was open.
    private func staleLock(in folder: URL) -> Bool {
        guard let data = try? Data(contentsOf: folder.appendingPathComponent(Self.lockName)) else { return false }
        return String(decoding: data, as: UTF8.self) != sessionId
    }

    // MARK: Helpers

    func ensureSpace(forBytes bytes: Int64) throws {
        let required = bytes * 2 + 50 * 1024 * 1024
        guard let values = try? root.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey]),
              let available = values.volumeAvailableCapacityForImportantUsage else { return }
        if available < required {
            throw AppError.insufficientStorage(requiredMegabytes: Int(required / (1024 * 1024)))
        }
    }

    static func sha256(of url: URL) throws -> String {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        var hasher = SHA256()
        while true {
            let chunk = try handle.read(upToCount: 1 << 20) ?? Data()
            if chunk.isEmpty { break }
            hasher.update(data: chunk)
        }
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }

    static let encoder: JSONEncoder = {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        encoder.dateEncodingStrategy = .iso8601
        return encoder
    }()

    static let decoder: JSONDecoder = {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return decoder
    }()
}
