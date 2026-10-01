import AVFoundation
import CryptoKit
import Foundation

/// How the voice is tuned. Saved in the project, undoable; the original audio
/// is never changed: tuning renders a separate file beside it.
struct PitchCorrectionSettings: Codable, Equatable {
    enum Preset: String, Codable, CaseIterable, Identifiable {
        case natural, strong, robotic, custom
        var id: String { rawValue }
    }

    /// A hand-made decision about one note, by note id.
    struct NoteOverride: Codable, Equatable {
        /// Leave the note exactly as sung.
        var bypass = false
        /// Pin the note to this target, in cents from the tonic (unfolded).
        var targetCents: Double?
    }

    var preset: Preset
    var retuneMs: Double
    var strength: Double
    var humanize: Double
    var vibratoAmount: Double
    /// 0 keeps the singer's own vibrato rate.
    var vibratoRateHz: Double
    var transitionSensitivity: Double
    var driftCorrection: Double
    var smoothingMs: Double
    var preserveFormants: Bool
    var formantShiftCents: Double
    var noteOverrides: [Int: NoteOverride]

    static func preset(_ preset: Preset) -> PitchCorrectionSettings {
        let c: MQCorrectionSettings
        switch preset {
        case .strong: c = mq_correction_preset(MQ_CORRECTION_STRONG)
        case .robotic: c = mq_correction_preset(MQ_CORRECTION_ROBOTIC)
        case .natural, .custom: c = mq_correction_preset(MQ_CORRECTION_NATURAL)
        }
        return PitchCorrectionSettings(
            preset: preset, retuneMs: c.retune_ms, strength: c.strength, humanize: c.humanize,
            vibratoAmount: c.vibrato_amount, vibratoRateHz: c.vibrato_rate_hz,
            transitionSensitivity: c.transition_sensitivity, driftCorrection: c.drift_correction,
            smoothingMs: c.smoothing_ms, preserveFormants: true, formantShiftCents: 0,
            noteOverrides: [:])
    }

    /// The same values with another preset applied, keeping hand-made note choices.
    func applying(_ preset: Preset) -> PitchCorrectionSettings {
        guard preset != .custom else { var copy = self; copy.preset = .custom; return copy }
        var fresh = Self.preset(preset)
        fresh.noteOverrides = noteOverrides
        fresh.preserveFormants = preserveFormants
        fresh.formantShiftCents = formantShiftCents
        return fresh
    }

    var cSettings: MQCorrectionSettings {
        MQCorrectionSettings(retune_ms: retuneMs, strength: strength, humanize: humanize, vibrato_amount: vibratoAmount,
                             vibrato_rate_hz: vibratoRateHz, transition_sensitivity: transitionSensitivity,
                             drift_correction: driftCorrection, smoothing_ms: smoothingMs, maximum_shift_cents: 1200)
    }
}

/// What was rendered, so the app knows whether the tuned file still matches
/// the current settings, and how well it came out.
struct TuningRenderInfo: Codable, Equatable {
    var fileName: String
    var sourceSHA256: String
    var fingerprint: String
    var renderedAt: Date
    /// Intonation measured on the tuned audio itself, not assumed.
    var after: Measured?

    struct Measured: Codable, Equatable {
        var noteCount: Int
        var inTuneFraction: Double
        var meanAbsoluteDeviationCents: Double
    }
}

enum TuningRenderer {
    static let fileName = "tuned.caf"

    /// Identifies everything the tuned audio depends on.
    static func fingerprint(settings: PitchCorrectionSettings, maqam: MaqamDefinition, tonicHz: Double,
                            sourceSHA256: String) -> String {
        struct Inputs: Encodable {
            var settings: PitchCorrectionSettings
            var degrees: [MaqamDegree]
            var octaveEquivalent: Bool
            var tonicHz: Double
            var source: String
            var engine = 1
        }
        let encoder = JSONEncoder()
        encoder.outputFormatting = .sortedKeys
        let data = (try? encoder.encode(Inputs(settings: settings, degrees: maqam.degrees,
                                               octaveEquivalent: maqam.octaveEquivalent, tonicHz: tonicHz,
                                               source: sourceSHA256))) ?? Data()
        return SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }

    /// The per-frame shift in cents, and each note's target (nil if bypassed).
    static func correction(pitch: PitchAnalysis, maqam: MaqamDefinition, tonicHz: Double,
                           settings: PitchCorrectionSettings) throws -> (shift: [Double], targets: [Double?]) {
        let track = pitch.track
        let times = (0..<track.count).map { track.time(at: $0) }
        let estimates = (0..<track.count).map { index -> MQPitchEstimate in
            let hz = Double(track.hz[index])
            return MQPitchEstimate(frequency_hz: hz, confidence: Double(track.confidence[index]), rms_dbfs: 0,
                                   voiced: hz > 0 ? 1 : 0)
        }
        let notes = pitch.notes.map(\.cNote)
        let overrides = pitch.notes.map { note -> MQNoteOverride in
            let manual = settings.noteOverrides[note.id]
            return MQNoteOverride(bypass: manual?.bypass == true ? 1 : 0,
                                  has_target: manual?.targetCents != nil ? 1 : 0,
                                  target_cents_from_tonic: manual?.targetCents ?? 0)
        }
        var scale = maqam.cScale
        var c = settings.cSettings
        var shift = [Double](repeating: 0, count: track.count)
        var targets = [Double](repeating: .nan, count: notes.count)
        let status = mq_compute_correction(times, estimates, track.count, notes, notes.count, overrides, &scale,
                                           tonicHz, &c, &shift, &targets)
        guard status == MQ_OK else { throw AppError.coreFailure(code: Int32(status.rawValue)) }
        return (shift, targets.map { $0.isNaN ? nil : $0 })
    }

    /// Renders the tuned version of `source` to `destination` (32-bit float CAF,
    /// same rate and channels). Streams: marks while reading, then output block
    /// by block, so memory stays at a few seconds of audio whatever the length.
    static func render(source: URL, destination: URL, pitch: PitchAnalysis, maqam: MaqamDefinition, tonicHz: Double,
                       settings: PitchCorrectionSettings, progress: @escaping @Sendable (Double) -> Void) throws {
        let (shift, _) = try correction(pitch: pitch, maqam: maqam, tonicHz: tonicHz, settings: settings)
        let input: AVAudioFile
        do { input = try AVAudioFile(forReading: source) } catch { throw AppError.corruptedAudio }
        let format = input.processingFormat
        let channels = Int(format.channelCount)
        let total = input.length
        guard total > 0 else { throw AppError.emptyAudio }
        let block = AVAudioFrameCount(max(4096, Int(format.sampleRate)))

        // 1. Pitch marks, from a mono mix, while reading once.
        let track = pitch.track
        guard let finder = track.hz.withUnsafeBufferPointer({ hz in
            mq_marks_create(format.sampleRate, track.firstTime, track.hopSeconds, hz.baseAddress, track.count)
        }) else { throw AppError.coreFailure(code: Int32(MQ_ERROR_INVALID_ARGUMENT.rawValue)) }
        defer { mq_marks_destroy(finder) }
        guard let reading = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: block) else {
            throw AppError.audioEngineFailed(detail: "buffer")
        }
        var mono = [Float](repeating: 0, count: Int(block))
        input.framePosition = 0
        while input.framePosition < total {
            try Task.checkCancellation()
            do { try input.read(into: reading, frameCount: block) } catch { throw AppError.corruptedAudio }
            let frames = Int(reading.frameLength)
            if frames == 0 { break }
            guard let data = reading.floatChannelData else { throw AppError.corruptedAudio }
            for index in 0..<frames {
                var sum: Float = 0
                for channel in 0..<channels { sum += data[channel][index] }
                mono[index] = sum / Float(channels)
            }
            mono.withUnsafeBufferPointer { mq_marks_push(finder, $0.baseAddress, frames) }
            progress(0.3 * Double(input.framePosition) / Double(total))
        }

        // 2. Grains for the shift curve.
        guard let plan = shift.withUnsafeBufferPointer({ curve in
            mq_grain_plan_create(finder, curve.baseAddress, track.count, track.firstTime, track.hopSeconds,
                                 format.sampleRate, UInt64(total), settings.formantShiftCents,
                                 settings.preserveFormants ? 1 : 0)
        }) else { throw AppError.coreFailure(code: Int32(MQ_ERROR_OUT_OF_MEMORY.rawValue)) }
        defer { mq_grain_plan_destroy(plan) }

        // 3. Output, block by block, each from just the input it needs. The
        // writer must be gone before this returns: AVAudioFile finishes the file
        // only when it is released, and an autoreleased writer would leave a
        // short file behind for whoever reads it next.
        try autoreleasepool {
            try writeOutput(plan: plan, input: input, format: format, total: total, block: block,
                            destination: destination, settings: settings, progress: progress)
        }
    }

    private static func writeOutput(plan: OpaquePointer, input: AVAudioFile, format: AVAudioFormat,
                                    total: AVAudioFramePosition, block: AVAudioFrameCount, destination: URL,
                                    settings: PitchCorrectionSettings,
                                    progress: @escaping @Sendable (Double) -> Void) throws {
        let channels = Int(format.channelCount)
        let outputSettings: [String: Any] = [
            AVFormatIDKey: kAudioFormatLinearPCM, AVSampleRateKey: format.sampleRate,
            AVNumberOfChannelsKey: channels, AVLinearPCMBitDepthKey: 32, AVLinearPCMIsFloatKey: true,
            AVLinearPCMIsNonInterleaved: false,
        ]
        try? FileManager.default.removeItem(at: destination)
        let output: AVAudioFile
        do {
            output = try AVAudioFile(forWriting: destination, settings: outputSettings, commonFormat: .pcmFormatFloat32,
                                     interleaved: false)
        } catch {
            throw AppError.from(error) { .projectSaveFailed(detail: $0) }
        }
        guard let written = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: block) else {
            throw AppError.audioEngineFailed(detail: "buffer")
        }
        var inputBuffer: AVAudioPCMBuffer?
        var start: AVAudioFramePosition = 0
        while start < total {
            try Task.checkCancellation()
            let frames = Int(min(AVAudioFramePosition(block), total - start))
            var from: Int64 = 0
            var to: Int64 = 0
            mq_grain_plan_input_range(plan, start, frames, &from, &to)
            from = max(0, from)
            to = min(total, max(from, to))
            let needed = AVAudioFrameCount(max(1, to - from))
            if inputBuffer == nil || inputBuffer!.frameCapacity < needed {
                inputBuffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: needed)
            }
            guard let source = inputBuffer, let outData = written.floatChannelData else {
                throw AppError.audioEngineFailed(detail: "buffer")
            }
            source.frameLength = 0
            if to > from {
                input.framePosition = from
                do { try input.read(into: source, frameCount: AVAudioFrameCount(to - from)) } catch { throw AppError.corruptedAudio }
            }
            guard let inData = source.floatChannelData else { throw AppError.corruptedAudio }
            let inputs: [UnsafePointer<Float>?] = (0..<channels).map { UnsafePointer(inData[$0]) }
            let outputs: [UnsafeMutablePointer<Float>?] = (0..<channels).map { outData[$0] }
            let status = inputs.withUnsafeBufferPointer { inPointers in
                outputs.withUnsafeBufferPointer { outPointers in
                    mq_grain_plan_render(plan, inPointers.baseAddress, Int32(channels), from, Int(source.frameLength),
                                         start, frames, outPointers.baseAddress)
                }
            }
            guard status == MQ_OK else { throw AppError.coreFailure(code: Int32(status.rawValue)) }
            written.frameLength = AVAudioFrameCount(frames)
            do { try output.write(from: written) } catch { throw AppError.from(error) { .projectSaveFailed(detail: $0) } }
            start += AVAudioFramePosition(frames)
            progress(0.3 + 0.6 * Double(start) / Double(total))
        }
    }
}
