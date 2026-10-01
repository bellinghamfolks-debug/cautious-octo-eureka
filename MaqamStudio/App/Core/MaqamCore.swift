import Foundation

// Swift-facing wrappers over the C++ DSP core (Core/capi/maqam_core.h).
// The core owns the numbers; these types only make them pleasant to use and
// Codable, so analysis results can be stored in a project.

struct LevelSummary: Codable, Equatable {
    var peakDbfs: Double
    var rmsDbfs: Double
    var crestDb: Double
    var dcOffset: Double
    var noiseFloorDbfs: Double
    var dynamicRangeDb: Double
    var clippedSamples: UInt64
    var frames: UInt64
    var channels: Int

    init(_ report: MQLevelReport) {
        peakDbfs = report.peak_dbfs
        rmsDbfs = report.rms_dbfs
        crestDb = report.crest_db
        dcOffset = report.dc_offset
        noiseFloorDbfs = report.noise_floor_dbfs
        dynamicRangeDb = report.dynamic_range_db
        clippedSamples = report.clipped_samples
        frames = report.frames
        channels = Int(report.channels)
    }

    var isClipping: Bool { clippedSamples > 0 }
}

/// A fixed-resolution picture of the whole recording, for drawing it and for
/// describing it to people who cannot see it.
struct WaveformSummary: Codable, Equatable {
    var minimum: [Float]
    var maximum: [Float]
    var rmsDbfs: [Float]

    var buckets: Int { maximum.count }

    static let empty = WaveformSummary(minimum: [], maximum: [], rmsDbfs: [])
}

/// Streams interleaved float chunks through the core's accumulators, so a long
/// file is analysed without ever being held in memory at once.
final class StreamingAnalyzer {
    private let levels: OpaquePointer
    private let waveform: OpaquePointer
    private let bucketCount: Int

    init(totalFrames: UInt64, channels: Int, sampleRate: Double, buckets: Int) throws {
        guard let levels = mq_level_accumulator_create(Int32(channels), sampleRate) else {
            throw AppError.coreFailure(code: Int32(MQ_ERROR_INVALID_ARGUMENT.rawValue))
        }
        guard let waveform = mq_waveform_accumulator_create(max(1, totalFrames), Int32(channels), buckets) else {
            mq_level_accumulator_destroy(levels)
            throw AppError.coreFailure(code: Int32(MQ_ERROR_INVALID_ARGUMENT.rawValue))
        }
        self.levels = levels
        self.waveform = waveform
        self.bucketCount = buckets
    }

    deinit {
        mq_level_accumulator_destroy(levels)
        mq_waveform_accumulator_destroy(waveform)
    }

    func push(interleaved: UnsafePointer<Float>, frames: Int) {
        mq_level_accumulator_push(levels, interleaved, frames)
        mq_waveform_accumulator_push(waveform, interleaved, frames)
    }

    func finish() throws -> (LevelSummary, WaveformSummary) {
        var report = MQLevelReport()
        let status = mq_level_accumulator_finish(levels, &report)
        guard status == MQ_OK else { throw AppError.coreFailure(code: Int32(status.rawValue)) }
        var low = [Float](repeating: 0, count: bucketCount)
        var high = [Float](repeating: 0, count: bucketCount)
        var rms = [Float](repeating: -160, count: bucketCount)
        let read = mq_waveform_accumulator_read(waveform, &low, &high, &rms)
        guard read == MQ_OK else { throw AppError.coreFailure(code: Int32(read.rawValue)) }
        return (LevelSummary(report), WaveformSummary(minimum: low, maximum: high, rmsDbfs: rms))
    }
}

// MARK: - Maqamat

struct MaqamDegree: Codable, Equatable {
    /// Cents above the tonic. Never rounded to a semitone.
    var cents: Double
    var alternates: [Double]
}

struct MaqamDefinition: Codable, Equatable, Identifiable {
    var id: String
    var family: String
    var arabicName: String
    var englishName: String
    var lowerJins: String
    var upperJins: String
    var typicalTonicHz: Double
    var degrees: [MaqamDegree]
    var octaveEquivalent: Bool

    /// The name in the interface language, or the other one if a custom maqam has only one.
    func name(_ l10n: L10n) -> String {
        let preferred = l10n.language == .arabic ? arabicName : englishName
        return preferred.isEmpty ? (l10n.language == .arabic ? englishName : arabicName) : preferred
    }

    /// The C representation, for target matching and (later) correction.
    var cScale: MQScale {
        var scale = MQScale()
        scale.degree_count = Int32(min(degrees.count, Int(MQ_MAX_DEGREES)))
        scale.octave_equivalent = octaveEquivalent ? 1 : 0
        withUnsafeMutableBytes(of: &scale.degrees) { raw in
            let buffer = raw.bindMemory(to: MQDegree.self)
            for (index, degree) in degrees.prefix(Int(MQ_MAX_DEGREES)).enumerated() {
                var cDegree = MQDegree()
                cDegree.cents = degree.cents
                cDegree.alternate_count = Int32(min(degree.alternates.count, Int(MQ_MAX_ALTERNATES)))
                withUnsafeMutableBytes(of: &cDegree.alternates) { alternatesRaw in
                    let alternates = alternatesRaw.bindMemory(to: Double.self)
                    for (slot, value) in degree.alternates.prefix(Int(MQ_MAX_ALTERNATES)).enumerated() {
                        alternates[slot] = value
                    }
                }
                buffer[index] = cDegree
            }
        }
        return scale
    }

    struct Target: Equatable {
        var targetCents: Double
        var deviationCents: Double
        var degreeIndex: Int
        var isAlternate: Bool
    }

    /// The nearest degree to a pitch given in cents above the tonic.
    func nearestTarget(centsFromTonic: Double) -> Target? {
        var scale = cScale
        let match = mq_nearest_target(&scale, centsFromTonic)
        guard match.degree_index >= 0 else { return nil }
        return Target(targetCents: match.target_cents, deviationCents: match.deviation_cents,
                      degreeIndex: Int(match.degree_index), isAlternate: match.alternate != 0)
    }
}

enum MaqamCatalog {
    /// The built-in maqamat, read once from the core.
    static let builtins: [MaqamDefinition] = {
        (0..<mq_maqam_count()).compactMap { index -> MaqamDefinition? in
            var info = MQMaqamInfo()
            guard mq_maqam_info(index, &info) == MQ_OK else { return nil }
            let count = Int(info.scale.degree_count)
            var degrees: [MaqamDegree] = []
            withUnsafeBytes(of: info.scale.degrees) { raw in
                let buffer = raw.bindMemory(to: MQDegree.self)
                for degreeIndex in 0..<count {
                    let degree = buffer[degreeIndex]
                    var alternates: [Double] = []
                    withUnsafeBytes(of: degree.alternates) { alternatesRaw in
                        let values = alternatesRaw.bindMemory(to: Double.self)
                        for slot in 0..<Int(degree.alternate_count) { alternates.append(values[slot]) }
                    }
                    degrees.append(MaqamDegree(cents: degree.cents, alternates: alternates))
                }
            }
            return MaqamDefinition(
                id: string(from: info.id),
                family: string(from: info.family),
                arabicName: string(from: info.arabic_name),
                englishName: string(from: info.english_name),
                lowerJins: string(from: info.lower_jins),
                upperJins: string(from: info.upper_jins),
                typicalTonicHz: info.typical_tonic_hz,
                degrees: degrees,
                octaveEquivalent: info.scale.octave_equivalent != 0)
        }
    }()

    static func definition(id: String) -> MaqamDefinition? { builtins.first { $0.id == id } }

    private static func string<T>(from tuple: T) -> String {
        withUnsafeBytes(of: tuple) { raw in
            let bytes = raw.bindMemory(to: CChar.self)
            guard let base = bytes.baseAddress else { return "" }
            return String(cString: base)
        }
    }
}

// MARK: - Note names

enum PitchNaming {
    /// Arabic solfège names of the 24 quarter-tone steps above C, as used for
    /// maqam notation (نصف بيمول = half flat, نصف دييز = half sharp).
    static let arabicSteps = [
        "دو", "دو نصف دييز", "دو دييز", "ري نصف بيمول", "ري", "ري نصف دييز", "مي بيمول", "مي نصف بيمول",
        "مي", "مي نصف دييز", "فا", "فا نصف دييز", "فا دييز", "صول نصف بيمول", "صول", "صول نصف دييز",
        "لا بيمول", "لا نصف بيمول", "لا", "لا نصف دييز", "سي بيمول", "سي نصف بيمول", "سي", "سي نصف دييز",
    ]

    static let englishSteps = [
        "C", "C half-sharp", "C sharp", "D half-flat", "D", "D half-sharp", "E flat", "E half-flat",
        "E", "E half-sharp", "F", "F half-sharp", "F sharp", "G half-flat", "G", "G half-sharp",
        "A flat", "A half-flat", "A", "A half-sharp", "B flat", "B half-flat", "B", "B half-sharp",
    ]

    struct Name: Equatable {
        var octave: Int
        var step: Int
        var remainderCents: Double
    }

    static func name(hz: Double, a4Hz: Double = 440) -> Name {
        let name = mq_name_quarter_tone(hz, a4Hz)
        return Name(octave: Int(name.octave), step: Int(name.step), remainderCents: name.remainder_cents)
    }

    static func label(hz: Double, l10n: L10n, a4Hz: Double = 440) -> String {
        let named = name(hz: hz, a4Hz: a4Hz)
        let steps = l10n.language == .arabic ? arabicSteps : englishSteps
        return "\(steps[named.step]) \(l10n.number(Double(named.octave)))"
    }
}
