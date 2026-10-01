import AVFoundation
import Foundation

/// Owns the shared AVAudioSession configuration and microphone permission.
///
/// One configuration serves recording, playback and (later) live monitoring:
/// play-and-record with a short IO buffer, so switching tasks never needs a
/// session change that would interrupt audio.
final class AudioSessionController {
    enum Permission { case granted, denied, undetermined }

    private let session = AVAudioSession.sharedInstance()

    func configure() throws {
        do {
            try session.setCategory(.playAndRecord, mode: .default,
                                    options: [.defaultToSpeaker, .allowBluetoothA2DP, .mixWithOthers])
            // 5 ms requested; the hardware may round it, which is fine.
            try session.setPreferredIOBufferDuration(0.005)
            try session.setActive(true)
        } catch {
            throw AppError.audioEngineFailed(detail: (error as NSError).localizedDescription)
        }
    }

    var recordPermission: Permission {
        if #available(iOS 17.0, *) {
            switch AVAudioApplication.shared.recordPermission {
            case .granted: return .granted
            case .denied: return .denied
            default: return .undetermined
            }
        }
        switch session.recordPermission {
        case .granted: return .granted
        case .denied: return .denied
        default: return .undetermined
        }
    }

    func requestRecordPermission() async -> Bool {
        if #available(iOS 17.0, *) {
            return await AVAudioApplication.requestRecordPermission()
        }
        return await withCheckedContinuation { continuation in
            session.requestRecordPermission { continuation.resume(returning: $0) }
        }
    }

    var hasInput: Bool { session.isInputAvailable }
}
