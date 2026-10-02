import AVFoundation
import Foundation

enum ExportFormat: String, Codable, CaseIterable, Identifiable {
    case wav, flac, mp3
    var id: String { rawValue }
    var fileExtension: String { rawValue }
    var cValue: Int32 {
        switch self {
        case .wav: return Int32(MQ_EXPORT_WAV.rawValue)
        case .flac: return Int32(MQ_EXPORT_FLAC.rawValue)
        case .mp3: return Int32(MQ_EXPORT_MP3.rawValue)
        }
    }
    /// Bit depths the format can hold; 32 means 32-bit float.
    var bitDepths: [Int] {
        switch self {
        case .wav: return [16, 24, 32]
        case .flac: return [16, 24]
        case .mp3: return []
        }
    }
}

/// Which version of the take to export.
enum ExportSource: String, Codable, CaseIterable, Identifiable {
    case original, tuned, studio
    var id: String { rawValue }
}

/// Everything the export screen lets the user choose. Kept between exports.
struct ExportOptions: Codable, Equatable {
    static let sampleRates = [44100, 48000]
    static let bitrates = [128, 192, 256, 320]
    /// nil keeps the level as it is.
    static let loudnessTargets: [Double?] = [nil, -9, -14, -16, -23]

    var format: ExportFormat = .wav
    var sampleRate: Int = 48000
    var bitDepth: Int = 24
    var stereo: Bool = true
    var mp3Kbps: Int = 256
    var loudnessTarget: Double? = -14
    /// True-peak ceiling used whenever the level is changed.
    var ceilingDb: Double = -1

    /// The same options with a bit depth the format can hold.
    func normalized() -> ExportOptions {
        var copy = self
        if !format.bitDepths.isEmpty, !format.bitDepths.contains(bitDepth) {
            copy.bitDepth = format.bitDepths.contains(24) ? 24 : format.bitDepths[0]
        }
        if !Self.sampleRates.contains(sampleRate) { copy.sampleRate = 48000 }
        if !Self.bitrates.contains(mp3Kbps) { copy.mp3Kbps = 256 }
        return copy
    }

    func cSettings(gainDb: Double, limit: Bool) -> MQExportSettings {
        var c = MQExportSettings()
        mq_export_default_settings(&c)
        c.format = format.cValue
        c.sample_rate = Double(sampleRate)
        c.channels = stereo ? 2 : 1
        c.bits = Int32(format == .mp3 ? 16 : bitDepth)
        c.mp3_kbps = Int32(mp3Kbps)
        c.resampler_quality = 2
        c.gain_db = gainDb
        c.limit = limit ? 1 : 0
        c.ceiling_db = ceilingDb
        c.dither = 1
        return c
    }
}

/// What an export wrote, measured from the file's own samples.
struct ExportResult: Equatable {
    var url: URL
    var source: ExportSource
    var options: ExportOptions
    var seconds: Double
    var bytes: UInt64
    var lufs: Double
    var truePeakDbtp: Double
    var clippedSamples: UInt64
    var limitingDb: Double
}

enum AudioExporter {
    /// "My song - studio.mp3": the project name made safe for a file name.
    static func fileName(projectName: String, source: ExportSource, format: ExportFormat, suffix: String) -> String {
        let forbidden = CharacterSet(charactersIn: "/\\:?%*|\"<>").union(.newlines).union(.controlCharacters)
        var base = projectName.components(separatedBy: forbidden).joined(separator: "-")
            .trimmingCharacters(in: .whitespaces)
        if base.hasPrefix(".") { base = String(base.drop(while: { $0 == "." })) }
        if base.isEmpty { base = "Maqam Studio" }
        if base.count > 80 { base = String(base.prefix(80)) }
        return "\(base) - \(suffix).\(format.fileExtension)"
    }

    /// Exports `source` to `destination`. When a loudness target is set, the
    /// file is measured first and the gain that reaches the target applied,
    /// with a true-peak limiter at the ceiling.
    static func export(source: URL, kind: ExportSource, options: ExportOptions, destination: URL,
                       progress: (Double) -> Void) throws -> ExportResult {
        let options = options.normalized()
        let file: AVAudioFile
        do { file = try AVAudioFile(forReading: source) } catch { throw AppError.corruptedAudio }
        let rate = file.processingFormat.sampleRate
        let channels = Int32(file.processingFormat.channelCount)
        var probe = options.cSettings(gainDb: 0, limit: false)
        let problem = mq_export_check(&probe, rate, channels)
        guard problem == Int32(MQ_EXPORT_OK.rawValue) else { throw AppError.exportSettings(problem: problem) }

        var gainDb = 0.0
        var share = 0.0
        if let target = options.loudnessTarget {
            let measured = try run(file: file, settings: probe, path: nil) { progress(0.4 * $0) }
            if measured.integrated_lufs > -70 { gainDb = target - measured.integrated_lufs }
            share = 0.4
        }
        let settings = options.cSettings(gainDb: gainDb, limit: options.loudnessTarget != nil)
        try? FileManager.default.removeItem(at: destination)
        let stats: MQExportStats
        do {
            stats = try run(file: file, settings: settings, path: destination) { progress(share + (1 - share) * $0) }
        } catch {
            try? FileManager.default.removeItem(at: destination)
            throw error
        }
        return ExportResult(url: destination, source: kind, options: options,
                            seconds: Double(stats.frames) / Double(options.sampleRate), bytes: stats.bytes,
                            lufs: stats.integrated_lufs, truePeakDbtp: stats.true_peak_dbtp,
                            clippedSamples: stats.clipped_samples, limitingDb: stats.maximum_reduction_db)
    }

    /// One pass over the file through the core exporter.
    private static func run(file: AVAudioFile, settings: MQExportSettings, path: URL?,
                            progress: (Double) -> Void) throws -> MQExportStats {
        let format = file.processingFormat
        let channels = Int(format.channelCount)
        var settings = settings
        let exporter: OpaquePointer? = path.map { url in
            url.withUnsafeFileSystemRepresentation { mq_exporter_create(&settings, format.sampleRate, Int32(channels), $0) }
        } ?? mq_exporter_create(&settings, format.sampleRate, Int32(channels), nil)
        guard let exporter else { throw AppError.exportFailed(detail: path?.lastPathComponent ?? "") }
        defer { mq_exporter_destroy(exporter) }

        let capacity = AVAudioFrameCount(max(4096, Int(format.sampleRate)))
        guard let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: capacity) else {
            throw AppError.audioEngineFailed(detail: "buffer")
        }
        var interleaved = [Float](repeating: 0, count: Int(capacity) * channels)
        let total = max(1, file.length)
        file.framePosition = 0
        while file.framePosition < file.length {
            try Task.checkCancellation()
            do { try file.read(into: buffer, frameCount: capacity) } catch { throw AppError.corruptedAudio }
            let frames = Int(buffer.frameLength)
            if frames == 0 { break }
            guard let data = buffer.floatChannelData else { throw AppError.corruptedAudio }
            for index in 0..<frames {
                for channel in 0..<channels { interleaved[index * channels + channel] = data[channel][index] }
            }
            let status = interleaved.withUnsafeBufferPointer { mq_exporter_push(exporter, $0.baseAddress, frames) }
            guard status == MQ_OK else { throw AppError.exportFailed(detail: path?.lastPathComponent ?? "") }
            progress(Double(file.framePosition) / Double(total))
        }
        var stats = MQExportStats()
        guard mq_exporter_finish(exporter, &stats) == MQ_OK else { throw AppError.exportFailed(detail: path?.lastPathComponent ?? "") }
        progress(1)
        return stats
    }
}
