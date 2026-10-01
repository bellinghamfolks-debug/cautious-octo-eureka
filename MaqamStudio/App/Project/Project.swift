import Foundation

/// Everything a project remembers, except the audio itself.
///
/// The original recording is stored once, byte for byte, beside this file and
/// is never written again. Every later decision — maqam, tuning, effects,
/// edits — lives here as parameters applied on top of it.
struct ProjectDocument: Codable, Equatable {
    static let currentFormatVersion = 1

    var formatVersion: Int = ProjectDocument.currentFormatVersion
    var id: UUID
    var name: String
    var createdAt: Date
    var modifiedAt: Date
    var original: OriginalAudio?
    var maqam: MaqamSettings = MaqamSettings()
    var processing: ProcessingSettings = ProcessingSettings()
    var playback: PlaybackSettings = PlaybackSettings()

    init(id: UUID = UUID(), name: String, now: Date = Date()) {
        self.id = id
        self.name = name
        self.createdAt = now
        self.modifiedAt = now
    }

    /// The parts a user edits, and therefore the parts undo and redo restore.
    /// Audio identity and timestamps are not undone.
    struct Editable: Equatable {
        var name: String
        var maqam: MaqamSettings
        var processing: ProcessingSettings
    }

    var editable: Editable {
        get { Editable(name: name, maqam: maqam, processing: processing) }
        set {
            name = newValue.name
            maqam = newValue.maqam
            processing = newValue.processing
        }
    }
}

struct OriginalAudio: Codable, Equatable {
    /// File name inside the project's `audio` folder.
    var fileName: String
    /// SHA-256 of the file as stored; checked on open so a damaged original is
    /// reported, never silently played.
    var sha256: String
    var byteCount: Int64
    var sampleRate: Double
    var channels: Int
    var frames: UInt64
    /// The name the user knew it by, before it was copied in.
    var sourceName: String
    var sourceFormat: String
    var recorded: Bool

    var duration: Double { sampleRate > 0 ? Double(frames) / sampleRate : 0 }
}

/// Which maqam the performance is heard against, and how it is tuned.
struct MaqamSettings: Codable, Equatable {
    /// `nil` until chosen or detected; a built-in id or a custom maqam's id.
    var maqamId: String?
    /// Tonic frequency in Hz; `nil` means "not chosen yet".
    var tonicHz: Double?
    /// Reference pitch for note names.
    var a4Hz: Double = 440
    /// Per-degree cents adjustments on top of the chosen maqam's definition,
    /// keyed by degree index. Empty means "as defined".
    var degreeOffsets: [Int: Double] = [:]
    /// Whether the choice came from the user rather than from detection.
    var manuallyChosen: Bool = false
}

/// Processing decisions. Empty in this phase: the chain is added by later
/// phases, and the structure exists now so saved projects carry it forward.
struct ProcessingSettings: Codable, Equatable {
    var chainVersion: Int = 1
}

struct PlaybackSettings: Codable, Equatable {
    var positionSeconds: Double = 0
}

/// Cached analysis of the original. It can always be recomputed from the
/// audio, so it lives in a separate file and is not part of undo.
struct AudioAnalysis: Codable, Equatable {
    var levels: LevelSummary
    var waveform: WaveformSummary
    var analyzedAt: Date
    /// The original's checksum at the time of analysis, to detect staleness.
    var sourceSHA256: String
}

/// A row in the projects list, read without loading the whole project.
struct ProjectSummary: Identifiable, Equatable {
    var id: UUID
    var name: String
    var modifiedAt: Date
    var duration: Double?
    var url: URL
    /// Set when the app ended without closing this project (a crash or a kill).
    var needsRecovery: Bool
}
