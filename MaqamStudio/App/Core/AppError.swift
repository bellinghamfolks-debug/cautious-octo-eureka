import Foundation

/// Every failure the user can meet, each with a message that says what
/// happened and what to do next. Messages are localization keys, so they are
/// spoken in the interface language.
enum AppError: Error, Equatable {
    case unsupportedFormat(fileExtension: String)
    case corruptedAudio
    case emptyAudio
    case fileAccessDenied
    case microphonePermissionDenied
    case noInputDevice
    case insufficientStorage(requiredMegabytes: Int)
    case audioEngineFailed(detail: String)
    case recordingFailed(detail: String)
    case playbackFailed(detail: String)
    case projectCorrupted(name: String)
    case projectSaveFailed(detail: String)
    case projectNotFound
    case coreFailure(code: Int32)
    case maqamInvalid(name: String)
    case libraryUnreadable
    case libraryTooNew
    case exportFailed(detail: String)
    case exportSettings(problem: Int32)

    /// Short title for alerts.
    var titleKey: String {
        switch self {
        case .unsupportedFormat: return "error.unsupported.title"
        case .corruptedAudio, .emptyAudio: return "error.corrupted.title"
        case .fileAccessDenied: return "error.access.title"
        case .microphonePermissionDenied: return "error.microphone.title"
        case .noInputDevice: return "error.noinput.title"
        case .insufficientStorage: return "error.storage.title"
        case .audioEngineFailed, .coreFailure: return "error.engine.title"
        case .recordingFailed: return "error.recording.title"
        case .playbackFailed: return "error.playback.title"
        case .projectCorrupted, .projectNotFound: return "error.project.title"
        case .projectSaveFailed: return "error.save.title"
        case .maqamInvalid, .libraryUnreadable, .libraryTooNew: return "error.library.title"
        case .exportFailed, .exportSettings: return "error.export.title"
        }
    }

    /// The full, actionable explanation.
    func message(_ l10n: L10n) -> String {
        switch self {
        case .unsupportedFormat(let fileExtension):
            return l10n("error.unsupported.message", fileExtension.isEmpty ? "?" : fileExtension.uppercased())
        case .corruptedAudio: return l10n("error.corrupted.message")
        case .emptyAudio: return l10n("error.empty.message")
        case .fileAccessDenied: return l10n("error.access.message")
        case .microphonePermissionDenied: return l10n("error.microphone.message")
        case .noInputDevice: return l10n("error.noinput.message")
        case .insufficientStorage(let megabytes): return l10n("error.storage.message", l10n.number(Double(megabytes)))
        case .audioEngineFailed(let detail): return l10n("error.engine.message", detail)
        case .recordingFailed(let detail): return l10n("error.recording.message", detail)
        case .playbackFailed(let detail): return l10n("error.playback.message", detail)
        case .projectCorrupted(let name): return l10n("error.corruptproject.message", name)
        case .projectSaveFailed(let detail): return l10n("error.save.message", detail)
        case .projectNotFound: return l10n("error.notfound.message")
        case .coreFailure(let code): return l10n("error.core.message", String(code))
        case .maqamInvalid(let name): return l10n("error.maqaminvalid.message", name)
        case .libraryUnreadable: return l10n("error.library.message")
        case .libraryTooNew: return l10n("error.librarynew.message")
        case .exportFailed(let detail): return l10n("error.export.message", detail)
        case .exportSettings(let problem):
            switch problem {
            case Int32(MQ_EXPORT_MP3_SAMPLE_RATE.rawValue): return l10n("error.export.mp3rate")
            default: return l10n("error.export.settings")
            }
        }
    }

    /// Whether the alert should offer to open the app's page in Settings.
    var offersSettings: Bool { self == .microphonePermissionDenied }
}

extension AppError {
    /// Wraps an unexpected error from the system into the closest case.
    static func from(_ error: Error, fallback: (String) -> AppError) -> AppError {
        if let known = error as? AppError { return known }
        let nsError = error as NSError
        if nsError.domain == NSCocoaErrorDomain, nsError.code == NSFileWriteOutOfSpaceError {
            return .insufficientStorage(requiredMegabytes: 200)
        }
        return fallback(nsError.localizedDescription)
    }
}
