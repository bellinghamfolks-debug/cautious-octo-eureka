import AVFoundation
import Foundation

/// What is known about an audio file without decoding all of it.
struct AudioMetadata: Equatable {
    var sampleRate: Double
    var channels: Int
    var frames: UInt64
    var byteCount: Int64
    var sourceName: String
    var formatDescription: String

    var duration: Double { sampleRate > 0 ? Double(frames) / sampleRate : 0 }
}

/// Opens, validates and analyses audio files off the main thread.
///
/// Decoding happens in one-second chunks into float buffers, so a long file
/// costs a second of memory, not its whole length. Nothing here runs on the
/// audio render thread.
enum AudioFileReader {
    static let supportedExtensions: Set<String> = ["wav", "wave", "flac", "mp3", "m4a", "aac", "caf", "aif", "aiff"]

    /// Validates that `url` is an audio file this app can play and analyse.
    static func inspect(_ url: URL) throws -> AudioMetadata {
        let fileExtension = url.pathExtension.lowercased()
        guard supportedExtensions.contains(fileExtension) else {
            throw AppError.unsupportedFormat(fileExtension: fileExtension)
        }
        let file: AVAudioFile
        do {
            file = try AVAudioFile(forReading: url)
        } catch {
            throw AppError.corruptedAudio
        }
        let format = file.processingFormat
        guard format.sampleRate > 0, format.channelCount > 0 else { throw AppError.corruptedAudio }
        guard file.length > 0 else { throw AppError.emptyAudio }
        let size = Int64((try? url.resourceValues(forKeys: [.fileSizeKey]))?.fileSize ?? 0)
        return AudioMetadata(
            sampleRate: format.sampleRate,
            channels: Int(format.channelCount),
            frames: UInt64(file.length),
            byteCount: size,
            sourceName: url.lastPathComponent,
            formatDescription: describe(file.fileFormat, fileExtension: fileExtension))
    }

    /// Levels and the waveform summary only, without pitch.
    static func analyze(_ url: URL, buckets: Int = 1200,
                        progress: @escaping @Sendable (Double) -> Void) throws -> (LevelSummary, WaveformSummary) {
        let result = try analyzeFully(url, buckets: buckets, includePitch: false, progress: progress)
        return (result.levels, result.waveform)
    }

    struct FullAnalysis {
        var levels: LevelSummary
        var waveform: WaveformSummary
        var pitch: PitchAnalysis?
    }

    /// Levels, waveform and (optionally) the pitch track and its notes, all in
    /// one decoding pass, reporting progress in [0, 1]. Throws
    /// `CancellationError` when the surrounding task is cancelled.
    static func analyzeFully(_ url: URL, buckets: Int = 1200, includePitch: Bool = true,
                             progress: @escaping @Sendable (Double) -> Void) throws -> FullAnalysis {
        let file: AVAudioFile
        do {
            file = try AVAudioFile(forReading: url)
        } catch {
            throw AppError.corruptedAudio
        }
        let format = file.processingFormat
        let channels = Int(format.channelCount)
        let total = UInt64(max(0, file.length))
        guard total > 0 else { throw AppError.emptyAudio }

        let analyzer = try StreamingAnalyzer(totalFrames: total, channels: channels,
                                             sampleRate: format.sampleRate, buckets: buckets)
        let chunkFrames = AVAudioFrameCount(max(4096, Int(format.sampleRate)))
        guard let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: chunkFrames) else {
            throw AppError.audioEngineFailed(detail: "buffer")
        }
        var interleaved = [Float](repeating: 0, count: Int(chunkFrames) * channels)
        var mono = [Float](repeating: 0, count: Int(chunkFrames))
        let pitch: StreamingPitchAnalyzer? = try includePitch ? StreamingPitchAnalyzer(sampleRate: format.sampleRate) : nil
        var processed: UInt64 = 0

        while processed < total {
            try Task.checkCancellation()
            do {
                try file.read(into: buffer, frameCount: chunkFrames)
            } catch {
                // A file that opened but fails mid-way is damaged after its header.
                throw AppError.corruptedAudio
            }
            let frames = Int(buffer.frameLength)
            if frames == 0 { break }
            guard let channelData = buffer.floatChannelData else { throw AppError.corruptedAudio }
            let scale = 1 / Float(channels)
            for frame in 0..<frames {
                var sum: Float = 0
                for channel in 0..<channels {
                    let sample = channelData[channel][frame]
                    interleaved[frame * channels + channel] = sample
                    sum += sample
                }
                mono[frame] = sum * scale
            }
            interleaved.withUnsafeBufferPointer { pointer in
                if let base = pointer.baseAddress { analyzer.push(interleaved: base, frames: frames) }
            }
            if let pitch {
                mono.withUnsafeBufferPointer { pointer in
                    if let base = pointer.baseAddress { pitch.push(mono: base, frames: frames) }
                }
            }
            processed += UInt64(frames)
            progress(min(1, Double(processed) / Double(total)))
        }
        let (levels, waveform) = try analyzer.finish()
        try Task.checkCancellation()
        return FullAnalysis(levels: levels, waveform: waveform, pitch: try pitch?.finish())
    }

    private static func describe(_ format: AVAudioFormat, fileExtension: String) -> String {
        let description = format.streamDescription.pointee
        let bits = description.mBitsPerChannel
        let name = fileExtension.uppercased()
        let rate = Int(format.sampleRate)
        return bits > 0 ? "\(name) \(rate) Hz \(bits)-bit" : "\(name) \(rate) Hz"
    }
}
