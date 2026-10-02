import AVFoundation
import CryptoKit
import Foundation

/// The 12 genre profiles. Starting points chosen by ear for each style; every
/// value they set can be changed in Pro mode.
enum GenreProfile: Int, Codable, CaseIterable, Identifiable {
    case khaleeji = 0, arabicPop, tarab, shilat, iraqi, egyptian, levantine, acoustic, cleanStudio,
         modernCommercial, natural, heavyAutoTune

    var id: Int { rawValue }
    var key: String { "profile.\(String(describing: self))" }

    /// The tuning style that goes with the profile.
    var tuningPreset: PitchCorrectionSettings.Preset {
        switch mq_studio_profile_tuning(Int32(rawValue)) {
        case Int32(MQ_CORRECTION_ROBOTIC.rawValue): return .robotic
        case Int32(MQ_CORRECTION_STRONG.rawValue): return .strong
        default: return .natural
        }
    }
}

/// One equaliser band of a plan.
struct StudioEqBand: Codable, Equatable {
    enum Kind: Int32, Codable { case peaking = 0, lowShelf = 1, highShelf = 2 }
    var kind: Kind
    var frequencyHz: Double
    var q: Double
    var gainDb: Double
}

/// Every setting of the studio chain, in Swift form so it can be saved,
/// undone and edited in Pro mode. Mirrors MQStudioPlan.
struct StudioPlanValues: Codable, Equatable {
    var profile: GenreProfile
    var highPassHz: Double
    var humHz: Double
    var humHarmonics: Int
    var denoiseDb: Double
    var plosiveDb: Double
    var breathDb: Double
    var leveler: Bool
    var levelerTargetDb: Double
    var levelerRangeDb: Double
    var eq: [StudioEqBand]
    var deEsserOn: Bool
    var deEsserHz: Double
    var deEsserThresholdDb: Double
    var deEsserMaximumCutDb: Double
    var harshnessOn: Bool
    var harshnessHz: Double
    var harshnessThresholdDb: Double
    var harshnessMaximumCutDb: Double
    var compressorThresholdDb: Double
    var compressorRatio: Double
    var compressorAttackMs: Double
    var compressorReleaseMs: Double
    var multiband: Bool
    var bandThresholdsDb: [Double]
    var bandRatios: [Double]
    var bandAttacksMs: [Double]
    var bandReleasesMs: [Double]
    var saturationDriveDb: Double
    var saturationMix: Double
    var exciterAmount: Double
    var reverbMix: Double
    var reverbSize: Double
    var reverbDamping: Double
    var reverbPreDelayMs: Double
    var delayMix: Double
    var delayMs: Double
    var delayFeedback: Double
    var loudnessTargetLufs: Double
    var ceilingDb: Double

    init(_ c: MQStudioPlan) {
        profile = GenreProfile(rawValue: Int(c.profile)) ?? .natural
        highPassHz = c.high_pass_hz
        humHz = c.hum_hz
        humHarmonics = Int(c.hum_harmonics)
        denoiseDb = c.denoise_db
        plosiveDb = c.plosive_db
        breathDb = c.breath_db
        leveler = c.leveler != 0
        levelerTargetDb = c.leveler_target_db
        levelerRangeDb = c.leveler_range_db
        var bands: [StudioEqBand] = []
        withUnsafeBytes(of: c.eq) { raw in
            let all = raw.bindMemory(to: MQEqBand.self)
            for index in 0..<min(Int(c.eq_count), all.count) {
                let band = all[index]
                bands.append(StudioEqBand(kind: StudioEqBand.Kind(rawValue: band.type) ?? .peaking,
                                          frequencyHz: band.frequency_hz, q: band.q, gainDb: band.gain_db))
            }
        }
        eq = bands
        deEsserOn = c.de_esser.enabled != 0
        deEsserHz = c.de_esser.frequency_hz
        deEsserThresholdDb = c.de_esser.threshold_db
        deEsserMaximumCutDb = c.de_esser.maximum_cut_db
        harshnessOn = c.harshness.enabled != 0
        harshnessHz = c.harshness.frequency_hz
        harshnessThresholdDb = c.harshness.threshold_db
        harshnessMaximumCutDb = c.harshness.maximum_cut_db
        compressorThresholdDb = c.compressor.threshold_db
        compressorRatio = c.compressor.ratio
        compressorAttackMs = c.compressor.attack_ms
        compressorReleaseMs = c.compressor.release_ms
        multiband = c.multiband != 0
        let multibandBands: [MQCompressorBand] = withUnsafeBytes(of: c.bands) { Array($0.bindMemory(to: MQCompressorBand.self)) }
        bandThresholdsDb = multibandBands.map(\.threshold_db)
        bandRatios = multibandBands.map(\.ratio)
        bandAttacksMs = multibandBands.map(\.attack_ms)
        bandReleasesMs = multibandBands.map(\.release_ms)
        saturationDriveDb = c.saturation_drive_db
        saturationMix = c.saturation_mix
        exciterAmount = c.exciter_amount
        reverbMix = c.reverb_mix
        reverbSize = c.reverb_size
        reverbDamping = c.reverb_damping
        reverbPreDelayMs = c.reverb_pre_delay_ms
        delayMix = c.delay_mix
        delayMs = c.delay_ms
        delayFeedback = c.delay_feedback
        loudnessTargetLufs = c.loudness_target_lufs
        ceilingDb = c.ceiling_db
    }

    var cPlan: MQStudioPlan {
        var c = MQStudioPlan()
        c.profile = Int32(profile.rawValue)
        c.high_pass_hz = highPassHz
        c.hum_hz = humHz
        c.hum_harmonics = Int32(humHarmonics)
        c.denoise_db = denoiseDb
        c.plosive_db = plosiveDb
        c.breath_db = breathDb
        c.leveler = leveler ? 1 : 0
        c.leveler_target_db = levelerTargetDb
        c.leveler_range_db = levelerRangeDb
        c.eq_count = Int32(min(eq.count, 8))
        withUnsafeMutableBytes(of: &c.eq) { raw in
            let all = raw.bindMemory(to: MQEqBand.self)
            for (index, band) in eq.prefix(8).enumerated() {
                all[index] = MQEqBand(type: band.kind.rawValue, frequency_hz: band.frequencyHz, q: band.q, gain_db: band.gainDb)
            }
        }
        c.de_esser = MQDynamicBand(enabled: deEsserOn ? 1 : 0, frequency_hz: deEsserHz, threshold_db: deEsserThresholdDb,
                                   maximum_cut_db: deEsserMaximumCutDb)
        c.harshness = MQDynamicBand(enabled: harshnessOn ? 1 : 0, frequency_hz: harshnessHz,
                                    threshold_db: harshnessThresholdDb, maximum_cut_db: harshnessMaximumCutDb)
        c.compressor = MQCompressorBand(threshold_db: compressorThresholdDb, ratio: compressorRatio,
                                        attack_ms: compressorAttackMs, release_ms: compressorReleaseMs)
        c.multiband = multiband ? 1 : 0
        withUnsafeMutableBytes(of: &c.bands) { raw in
            let all = raw.bindMemory(to: MQCompressorBand.self)
            for index in 0..<min(3, bandRatios.count) {
                all[index] = MQCompressorBand(threshold_db: bandThresholdsDb[index], ratio: bandRatios[index],
                                              attack_ms: bandAttacksMs[index], release_ms: bandReleasesMs[index])
            }
        }
        c.saturation_drive_db = saturationDriveDb
        c.saturation_mix = saturationMix
        c.exciter_amount = exciterAmount
        c.reverb_mix = reverbMix
        c.reverb_size = reverbSize
        c.reverb_damping = reverbDamping
        c.reverb_pre_delay_ms = reverbPreDelayMs
        c.delay_mix = delayMix
        c.delay_ms = delayMs
        c.delay_feedback = delayFeedback
        c.loudness_target_lufs = loudnessTargetLufs
        c.ceiling_db = ceilingDb
        return c
    }
}

/// What the user chose for the studio; saved in the project, undoable.
struct StudioSettings: Codable, Equatable {
    var profile: GenreProfile = .arabicPop
    /// Process the tuned voice when a current tuning exists.
    var useTuned: Bool = true
    /// Pro-mode settings; nil means "decide automatically from the recording".
    var plan: StudioPlanValues?
}

/// Why the chain is set as it is, as a code and its numbers.
struct StudioReason: Codable, Equatable {
    var code: Int
    var a: Double
    var b: Double
}

struct StudioMeasurements: Codable, Equatable {
    var durationSeconds: Double
    var integratedLufs: Double
    var peakDbfs: Double
    var noiseFloorDbfs: Double
    var voiceLevelDbfs: Double
    var levelSpreadDb: Double
    var humHz: Double
    var humStrengthDb: Double
    var sibilanceDb: Double
    var clippedSamples: UInt64
    var breathCount: UInt64

    init(_ c: MQStudioMeasurements) {
        durationSeconds = c.duration_seconds
        integratedLufs = c.integrated_lufs
        peakDbfs = c.peak_dbfs
        noiseFloorDbfs = c.noise_floor_dbfs
        voiceLevelDbfs = c.voice_level_dbfs
        levelSpreadDb = c.level_spread_db
        humHz = c.hum_hz
        humStrengthDb = c.hum_strength_db
        sibilanceDb = c.sibilance_db
        clippedSamples = c.clipped_samples
        breathCount = c.breath_count
    }
}

/// The last studio render: what went in, what was decided, what came out.
struct StudioRenderInfo: Codable, Equatable {
    var fileName: String
    var sourceSHA256: String
    var fingerprint: String
    var renderedAt: Date
    var usedTuned: Bool
    var before: StudioMeasurements
    var automaticPlan: StudioPlanValues
    var usedPlan: StudioPlanValues
    var reasons: [StudioReason]
    var afterLufs: Double
    var afterPeakDbfs: Double
}

enum StudioRenderer {
    static let fileName = "studio.caf"

    static func fingerprint(settings: StudioSettings, inputFingerprint: String) -> String {
        struct Inputs: Encodable { var settings: StudioSettings; var input: String; var engine = 1 }
        let encoder = JSONEncoder()
        encoder.outputFormatting = .sortedKeys
        let data = (try? encoder.encode(Inputs(settings: settings, input: inputFingerprint))) ?? Data()
        return SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }

    /// Owns the C analysis session.
    final class Session {
        let pointer: OpaquePointer
        init(_ pointer: OpaquePointer) { self.pointer = pointer }
        deinit { mq_studio_session_destroy(pointer) }

        var measurements: StudioMeasurements {
            var c = MQStudioMeasurements()
            mq_studio_measurements(pointer, &c)
            return StudioMeasurements(c)
        }

        func plan(for profile: GenreProfile) throws -> (plan: StudioPlanValues, reasons: [StudioReason]) {
            var plan = MQStudioPlan()
            var reasons = [MQStudioReason](repeating: MQStudioReason(), count: 64)
            var count = 0
            let status = mq_studio_plan(pointer, Int32(profile.rawValue), &plan, &reasons, reasons.count, &count)
            guard status == MQ_OK else { throw AppError.coreFailure(code: Int32(status.rawValue)) }
            return (StudioPlanValues(plan), reasons.prefix(count).map { StudioReason(code: Int($0.code), a: $0.a, b: $0.b) })
        }
    }

    /// Reads `source` once (mono) and measures it; the pitch track says where the singing is.
    static func analyse(source: URL, pitch: PitchTrack, progress: (Double) -> Void) throws -> Session {
        let file: AVAudioFile
        do { file = try AVAudioFile(forReading: source) } catch { throw AppError.corruptedAudio }
        let format = file.processingFormat
        guard let analyzer = mq_studio_analyzer_create(format.sampleRate) else {
            throw AppError.coreFailure(code: Int32(MQ_ERROR_INVALID_ARGUMENT.rawValue))
        }
        let total = file.length
        try readMono(file) { mono, frames in
            mono.withUnsafeBufferPointer { mq_studio_analyzer_push(analyzer, $0.baseAddress, frames) }
            progress(Double(file.framePosition) / Double(max(1, total)))
        }
        let session = pitch.hz.withUnsafeBufferPointer { hz in
            mq_studio_session_create(analyzer, hz.baseAddress, pitch.count, pitch.firstTime, pitch.hopSeconds)
        }
        mq_studio_analyzer_destroy(analyzer)
        guard let session else { throw AppError.coreFailure(code: Int32(MQ_ERROR_OUT_OF_MEMORY.rawValue)) }
        return Session(session)
    }

    /// Runs the chain over the file. With a destination it writes the result
    /// (stereo, 32-bit float CAF); either way it returns loudness and peak.
    static func run(session: Session, plan: StudioPlanValues, source: URL, gainDb: Double, limit: Bool,
                    destination: URL?, progress: (Double) -> Void) throws -> (lufs: Double, peakDbfs: Double) {
        let file: AVAudioFile
        do { file = try AVAudioFile(forReading: source) } catch { throw AppError.corruptedAudio }
        let rate = file.processingFormat.sampleRate
        var cPlan = plan.cPlan
        guard let chain = mq_studio_chain_create(session.pointer, &cPlan, gainDb, limit ? 1 : 0),
              let meter = mq_loudness_create(rate, 2) else {
            throw AppError.coreFailure(code: Int32(MQ_ERROR_OUT_OF_MEMORY.rawValue))
        }
        defer { mq_studio_chain_destroy(chain); mq_loudness_destroy(meter) }
        let latency = Int(mq_studio_chain_latency(chain))
        let tail = Int(mq_studio_chain_tail(chain))
        guard let stereo = AVAudioFormat(standardFormatWithSampleRate: rate, channels: 2) else {
            throw AppError.audioEngineFailed(detail: "format")
        }
        var writer: AudioFileWriter?
        if let destination {
            try? FileManager.default.removeItem(at: destination)
            do { writer = try AudioFileWriter(url: destination, format: stereo) } catch {
                throw AppError.from(error) { .projectSaveFailed(detail: $0) }
            }
        }
        defer { writer?.close() }

        var toSkip = latency
        var peak: Float = 0
        let capacity = AVAudioFrameCount(max(4096, Int(rate)))
        guard let out = AVAudioPCMBuffer(pcmFormat: stereo, frameCapacity: capacity) else {
            throw AppError.audioEngineFailed(detail: "buffer")
        }
        var left = [Float](repeating: 0, count: Int(capacity))
        var right = [Float](repeating: 0, count: Int(capacity))
        let total = file.length

        func consume(_ mono: [Float], _ frames: Int) throws {
            mono.withUnsafeBufferPointer { input in
                left.withUnsafeMutableBufferPointer { l in
                    right.withUnsafeMutableBufferPointer { r in
                        mq_studio_chain_process(chain, input.baseAddress, l.baseAddress, r.baseAddress, frames)
                    }
                }
            }
            let skip = min(toSkip, frames)
            toSkip -= skip
            let kept = frames - skip
            guard kept > 0 else { return }
            left.withUnsafeBufferPointer { l in
                right.withUnsafeBufferPointer { r in
                    mq_loudness_push(meter, l.baseAddress! + skip, r.baseAddress! + skip, kept)
                }
            }
            for index in skip..<frames { peak = max(peak, abs(left[index]), abs(right[index])) }
            if let writer, let channels = out.floatChannelData {
                for index in 0..<kept {
                    channels[0][index] = left[skip + index]
                    channels[1][index] = right[skip + index]
                }
                out.frameLength = AVAudioFrameCount(kept)
                try writer.write(out)
            }
        }

        try readMono(file) { mono, frames in
            try consume(mono, frames)
            progress(0.9 * Double(file.framePosition) / Double(max(1, total)))
        }
        // Silence after the end lets the latency drain and the reverb and echo ring out.
        var remaining = tail
        let silence = [Float](repeating: 0, count: Int(capacity))
        while remaining > 0 {
            try Task.checkCancellation()
            let frames = min(remaining, Int(capacity))
            try consume(silence, frames)
            remaining -= frames
        }
        progress(1)
        return (mq_loudness_integrated(meter), peak > 0 ? 20 * log10(Double(peak)) : -160)
    }

    /// Reads a file as mono float chunks of about one second.
    private static func readMono(_ file: AVAudioFile, _ body: ([Float], Int) throws -> Void) throws {
        let format = file.processingFormat
        let channels = Int(format.channelCount)
        let capacity = AVAudioFrameCount(max(4096, Int(format.sampleRate)))
        guard let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: capacity) else {
            throw AppError.audioEngineFailed(detail: "buffer")
        }
        var mono = [Float](repeating: 0, count: Int(capacity))
        file.framePosition = 0
        while file.framePosition < file.length {
            try Task.checkCancellation()
            do { try file.read(into: buffer, frameCount: capacity) } catch { throw AppError.corruptedAudio }
            let frames = Int(buffer.frameLength)
            if frames == 0 { break }
            guard let data = buffer.floatChannelData else { throw AppError.corruptedAudio }
            for index in 0..<frames {
                var sum: Float = 0
                for channel in 0..<channels { sum += data[channel][index] }
                mono[index] = sum / Float(channels)
            }
            try body(mono, frames)
        }
    }
}
