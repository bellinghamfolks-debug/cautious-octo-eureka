import AVFoundation
import CoreML
import Foundation

/// Decides, bin by bin, how much of a mixed song is voice. Reading the song,
/// the spectra and writing the stems are shared (SeparationRenderer), so an
/// engine is only the masks: the built-in classical method today, any Core ML
/// model that follows the contract tomorrow.
protocol VocalSeparator {
    var id: String { get }
    /// The engine's own sample rate; nil works at the song's rate.
    var sampleRate: Double? { get }
    func estimateMasks(_ session: SeparationSession, progress: (Double) -> Void) throws
}

/// Owns the core's separation state for one song.
final class SeparationSession {
    let pointer: OpaquePointer
    static var bins: Int { mq_separation_bins() }
    var frameCount: Int { mq_separation_frame_count(pointer) }

    init(sampleRate: Double) throws {
        guard let pointer = mq_separation_create(sampleRate) else { throw AppError.coreFailure(code: Int32(MQ_ERROR_INVALID_ARGUMENT.rawValue)) }
        self.pointer = pointer
    }

    deinit { mq_separation_destroy(pointer) }
}

/// Repetition, position and range: no machine learning, nothing downloaded.
struct ClassicalSeparator: VocalSeparator {
    let id = "classical"
    let sampleRate: Double? = nil

    func estimateMasks(_ session: SeparationSession, progress: (Double) -> Void) throws {
        let total = session.frameCount
        var frame = 0
        while frame < total {
            try Task.checkCancellation()
            let count = min(64, total - frame)
            guard mq_separation_estimate_classical(session.pointer, frame, count) == MQ_OK else {
                throw AppError.coreFailure(code: Int32(MQ_ERROR_OUT_OF_MEMORY.rawValue))
            }
            frame += count
            progress(Double(frame) / Double(max(1, total)))
        }
    }
}

/// Why a Core ML model cannot be used for separation.
enum SeparationModelProblem: Error, Equatable {
    case unreadable(detail: String)
    case missingInput
    case missingOutput
    case wrongShape(name: String, shape: [Int])
}

/// A Core ML model following the separation contract:
///   input  "magnitudes"  Float32 [1, 256, 2049] (or [256, 2049])
///   output "vocal_mask"  same shape, 0..1
///   metadata key maqam.sample_rate (optional; 44100 if absent)
/// Magnitudes are the mid channel's 4096-point STFT (Hann, hop 1024).
struct CoreMLSeparator: VocalSeparator {
    static let inputName = "magnitudes"
    static let outputName = "vocal_mask"
    static let framesPerCall = 256
    static let sampleRateKey = "maqam.sample_rate"  // not localized: model metadata

    let id: String
    let name: String
    let model: MLModel
    let sampleRate: Double?

    init(compiledURL: URL) throws {
        let model: MLModel
        do { model = try MLModel(contentsOf: compiledURL) } catch {
            throw SeparationModelProblem.unreadable(detail: (error as NSError).localizedDescription)
        }
        if let problem = Self.problem(with: model.modelDescription) { throw problem }
        self.model = model
        name = compiledURL.deletingPathExtension().lastPathComponent
        id = "coreml:" + name
        let metadata = model.modelDescription.metadata[.creatorDefinedKey] as? [String: String]
        sampleRate = metadata?[Self.sampleRateKey].flatMap(Double.init) ?? 44100
    }

    /// The first way the model breaks the contract, if any.
    static func problem(with description: MLModelDescription) -> SeparationModelProblem? {
        let bins = SeparationSession.bins
        func fits(_ constraint: MLMultiArrayConstraint?) -> [Int]? {
            guard let constraint, constraint.dataType == .float32 || constraint.dataType == .double else { return [] }
            let shape = constraint.shape.map(\.intValue)
            return shape == [1, framesPerCall, bins] || shape == [framesPerCall, bins] ? nil : shape
        }
        guard let input = description.inputDescriptionsByName[inputName] else { return .missingInput }
        if let shape = fits(input.multiArrayConstraint) { return .wrongShape(name: inputName, shape: shape) }
        guard let output = description.outputDescriptionsByName[outputName] else { return .missingOutput }
        if let shape = fits(output.multiArrayConstraint) { return .wrongShape(name: outputName, shape: shape) }
        return nil
    }

    func estimateMasks(_ session: SeparationSession, progress: (Double) -> Void) throws {
        let bins = SeparationSession.bins
        let total = session.frameCount
        let shape = model.modelDescription.inputDescriptionsByName[Self.inputName]?.multiArrayConstraint?.shape
            ?? [1, NSNumber(value: Self.framesPerCall), NSNumber(value: bins)]
        var magnitudes = [Float](repeating: 0, count: Self.framesPerCall * bins)
        var masks = [Float](repeating: 0, count: Self.framesPerCall * bins)
        var frame = 0
        while frame < total {
            try Task.checkCancellation()
            let count = min(Self.framesPerCall, total - frame)
            // Frames past the end of the song are zeros, as the contract says.
            magnitudes.withUnsafeMutableBufferPointer { _ = mq_separation_magnitudes(session.pointer, frame, Self.framesPerCall, $0.baseAddress) }
            let input = try MLMultiArray(shape: shape, dataType: .float32)
            magnitudes.withUnsafeBufferPointer { source in
                input.dataPointer.bindMemory(to: Float.self, capacity: source.count).update(from: source.baseAddress!, count: source.count)
            }
            let features = try MLDictionaryFeatureProvider(dictionary: [Self.inputName: MLFeatureValue(multiArray: input)])
            let prediction = try model.prediction(from: features)
            guard let output = prediction.featureValue(for: Self.outputName)?.multiArrayValue, output.count >= count * bins else {
                throw SeparationModelProblem.missingOutput
            }
            // By strides, not position: a model may pad its output.
            let strides = output.strides.map(\.intValue)
            let frameStride = strides[strides.count - 2], binStride = strides[strides.count - 1]
            for f in 0..<count {
                for k in 0..<bins { masks[f * bins + k] = output[f * frameStride + k * binStride].floatValue }
            }
            masks.withUnsafeBufferPointer { _ = mq_separation_set_masks(session.pointer, frame, count, $0.baseAddress) }
            frame += count
            progress(Double(frame) / Double(max(1, total)))
        }
    }
}

/// What a separation produced, saved beside the project's renders.
struct SeparationInfo: Codable, Equatable {
    static let vocalsFile = "vocals.caf"
    static let accompanimentFile = "accompaniment.caf"

    var engineId: String
    var sourceSHA256: String
    var separatedAt: Date
    var sampleRate: Double
    var frames: Int
}

/// Installed Core ML separation models: compiled into Application Support.
enum SeparationModels {
    static func folder() throws -> URL {
        let support = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask,
                                                  appropriateFor: nil, create: true)
        let folder = support.appendingPathComponent("Separators", isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        return folder
    }

    /// The models that load and follow the contract; broken ones are skipped.
    static func installed(in folder: URL? = nil) -> [CoreMLSeparator] {
        guard let folder = folder ?? (try? Self.folder()),
              let items = try? FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil) else { return [] }
        return items.filter { $0.pathExtension == "mlmodelc" }.sorted { $0.path < $1.path }
            .compactMap { try? CoreMLSeparator(compiledURL: $0) }
    }

    /// Compiles a .mlmodel or .mlpackage, checks the contract, and keeps it.
    static func install(from source: URL, into folder: URL? = nil) throws -> CoreMLSeparator {
        let folder = try folder ?? Self.folder()
        let compiled: URL
        do { compiled = try MLModel.compileModel(at: source) } catch {
            throw SeparationModelProblem.unreadable(detail: (error as NSError).localizedDescription)
        }
        defer { try? FileManager.default.removeItem(at: compiled) }
        _ = try CoreMLSeparator(compiledURL: compiled)
        var name = source.deletingPathExtension().lastPathComponent
        if name.isEmpty { name = "Model" }
        let destination = folder.appendingPathComponent(name).appendingPathExtension("mlmodelc")
        try? FileManager.default.removeItem(at: destination)
        try FileManager.default.copyItem(at: compiled, to: destination)
        return try CoreMLSeparator(compiledURL: destination)
    }

    static func remove(_ separator: CoreMLSeparator, from folder: URL? = nil) throws {
        let folder = try folder ?? Self.folder()
        try FileManager.default.removeItem(at: folder.appendingPathComponent(separator.name).appendingPathExtension("mlmodelc"))
    }
}

enum SeparationRenderer {
    /// Separates `source` into two stereo float CAF stems at the engine's rate
    /// (the song's own for the classical engine). Returns their rate and length.
    static func separate(source: URL, separator: VocalSeparator, vocals: URL, accompaniment: URL,
                         progress: (Double) -> Void) throws -> (sampleRate: Double, frames: Int) {
        let reader = try StereoReader(url: source, targetRate: separator.sampleRate)
        // Ten minutes is about 230 MB of analysis; longer songs are refused rather than risk running out.
        guard reader.estimatedFrames <= Int64(reader.sampleRate * 600) else { throw AppError.separationTooLong(minutes: 10) }
        let session = try SeparationSession(sampleRate: reader.sampleRate)
        try reader.read { chunk, frames in
            guard mq_separation_analyse(session.pointer, chunk, frames) == MQ_OK else {
                throw AppError.coreFailure(code: Int32(MQ_ERROR_OUT_OF_MEMORY.rawValue))
            }
            progress(0.3 * reader.fraction)
        }
        guard mq_separation_finish_analysis(session.pointer) == MQ_OK else {
            throw AppError.coreFailure(code: Int32(MQ_ERROR_OUT_OF_MEMORY.rawValue))
        }
        try separator.estimateMasks(session) { progress(0.3 + 0.4 * $0) }

        guard let format = AVAudioFormat(standardFormatWithSampleRate: reader.sampleRate, channels: 2) else {
            throw AppError.audioEngineFailed(detail: "format")
        }
        for url in [vocals, accompaniment] { try? FileManager.default.removeItem(at: url) }
        let vocalWriter = try AudioFileWriter(url: vocals, format: format)
        let restWriter = try AudioFileWriter(url: accompaniment, format: format)
        defer { vocalWriter.close(); restWriter.close() }
        let capacity = 8192
        guard let vocalBuffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(capacity)),
              let restBuffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(capacity)) else {
            throw AppError.audioEngineFailed(detail: "buffer")
        }
        var vocal = [Float](repeating: 0, count: 2 * capacity)
        var rest = [Float](repeating: 0, count: 2 * capacity)
        var written = 0
        func drain() throws {
            while true {
                let got = vocal.withUnsafeMutableBufferPointer { v in
                    rest.withUnsafeMutableBufferPointer { r in mq_separation_pull(session.pointer, v.baseAddress, r.baseAddress, capacity) }
                }
                if got == 0 { return }
                for (buffer, data) in [(vocalBuffer, vocal), (restBuffer, rest)] {
                    guard let channels = buffer.floatChannelData else { throw AppError.audioEngineFailed(detail: "buffer") }
                    for index in 0..<got {
                        channels[0][index] = data[2 * index]
                        channels[1][index] = data[2 * index + 1]
                    }
                    buffer.frameLength = AVAudioFrameCount(got)
                }
                try vocalWriter.write(vocalBuffer)
                try restWriter.write(restBuffer)
                written += got
            }
        }
        try reader.read { chunk, frames in
            guard mq_separation_render(session.pointer, chunk, frames) == MQ_OK else {
                throw AppError.coreFailure(code: Int32(MQ_ERROR_OUT_OF_MEMORY.rawValue))
            }
            try drain()
            progress(0.7 + 0.3 * reader.fraction)
        }
        guard mq_separation_finish_render(session.pointer) == MQ_OK else {
            throw AppError.coreFailure(code: Int32(MQ_ERROR_OUT_OF_MEMORY.rawValue))
        }
        try drain()
        progress(1)
        return (reader.sampleRate, written)
    }
}

/// Reads any audio file as interleaved stereo float chunks, at its own rate or
/// converted to `targetRate`. Mono is copied to both sides; beyond two
/// channels the first two are used.
final class StereoReader {
    let url: URL
    let sampleRate: Double
    let estimatedFrames: Int64
    private(set) var fraction = 0.0
    private let targetRate: Double?

    init(url: URL, targetRate: Double?) throws {
        let file: AVAudioFile
        do { file = try AVAudioFile(forReading: url) } catch { throw AppError.corruptedAudio }
        self.url = url
        self.targetRate = targetRate
        let rate = file.processingFormat.sampleRate
        sampleRate = targetRate ?? rate
        estimatedFrames = Int64((Double(file.length) * sampleRate / rate).rounded(.up))
    }

    /// Calls `body` with each chunk (pointer to interleaved stereo, frames).
    func read(_ body: (UnsafePointer<Float>, Int) throws -> Void) throws {
        let file: AVAudioFile
        do { file = try AVAudioFile(forReading: url) } catch { throw AppError.corruptedAudio }
        let input = file.processingFormat
        let capacity: AVAudioFrameCount = 16384
        guard let inBuffer = AVAudioPCMBuffer(pcmFormat: input, frameCapacity: capacity) else {
            throw AppError.audioEngineFailed(detail: "buffer")
        }
        var interleaved = [Float](repeating: 0, count: Int(capacity) * 2)
        let total = max(1, file.length)
        fraction = 0

        func emit(_ buffer: AVAudioPCMBuffer) throws {
            let frames = Int(buffer.frameLength)
            guard frames > 0, let data = buffer.floatChannelData else { return }
            if interleaved.count < frames * 2 { interleaved = [Float](repeating: 0, count: frames * 2) }
            let right = buffer.format.channelCount > 1 ? 1 : 0
            for index in 0..<frames {
                interleaved[2 * index] = data[0][index]
                interleaved[2 * index + 1] = data[right][index]
            }
            try interleaved.withUnsafeBufferPointer { try body($0.baseAddress!, frames) }
        }

        func readInput() throws -> Bool {
            try Task.checkCancellation()
            do { try file.read(into: inBuffer, frameCount: capacity) } catch { throw AppError.corruptedAudio }
            fraction = Double(file.framePosition) / Double(total)
            return inBuffer.frameLength > 0
        }

        guard let targetRate, targetRate != input.sampleRate else {
            while file.framePosition < file.length {
                guard try readInput() else { break }
                try emit(inBuffer)
            }
            fraction = 1
            return
        }
        // A different rate: convert with Core Audio's sample-rate converter.
        guard let output = AVAudioFormat(standardFormatWithSampleRate: targetRate, channels: input.channelCount),
              let converter = AVAudioConverter(from: input, to: output),
              let outBuffer = AVAudioPCMBuffer(pcmFormat: output, frameCapacity: AVAudioFrameCount(Double(capacity) * targetRate / input.sampleRate) + 1024) else {
            throw AppError.audioEngineFailed(detail: "converter")
        }
        converter.sampleRateConverterQuality = AVAudioQuality.max.rawValue
        var finished = false
        var readError: Error?
        while true {
            outBuffer.frameLength = 0
            var conversionError: NSError?
            let status = converter.convert(to: outBuffer, error: &conversionError) { _, inputStatus in
                if finished { inputStatus.pointee = .endOfStream; return nil }
                do {
                    if try readInput() {
                        inputStatus.pointee = .haveData
                        return inBuffer
                    }
                } catch {
                    readError = error
                }
                finished = true
                inputStatus.pointee = .endOfStream
                return nil
            }
            if let readError { throw readError }
            if status == .error { throw AppError.audioEngineFailed(detail: conversionError?.localizedDescription ?? "convert") }
            try emit(outBuffer)
            if status == .endOfStream || (finished && outBuffer.frameLength == 0) { break }
        }
        fraction = 1
    }
}
