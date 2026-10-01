import Foundation

// MARK: - Stored pitch analysis

/// The pitch of the original over time, one frame every `hopSeconds`.
/// Unvoiced frames are 0 Hz. Values are kept exactly as detected; nothing is
/// snapped to a scale.
struct PitchTrack: Codable, Equatable {
    var hopSeconds: Double
    var firstTime: Double
    var hz: [Float]
    var confidence: [Float]

    static let empty = PitchTrack(hopSeconds: 0.01, firstTime: 0, hz: [], confidence: [])

    var count: Int { hz.count }

    func time(at index: Int) -> Double { firstTime + Double(index) * hopSeconds }

    /// Frame indices whose times fall in `range`.
    func indices(in range: ClosedRange<Double>) -> Range<Int> {
        guard count > 0, hopSeconds > 0 else { return 0..<0 }
        let low = Int(((range.lowerBound - firstTime) / hopSeconds).rounded(.down))
        let high = Int(((range.upperBound - firstTime) / hopSeconds).rounded(.up))
        let clampedLow = min(max(0, low), count)
        return clampedLow..<min(max(clampedLow, high + 1), count)
    }
}

/// One sung note, as found by the core's segmenter.
struct SungNote: Codable, Equatable, Identifiable {
    var id: Int
    var start: Double
    var end: Double
    /// Median pitch of the held part (slides into and out of it excluded).
    var hz: Double
    /// How steadily it was held: half the 10th–90th percentile range, in cents.
    var spreadCents: Double
    var vibratoRateHz: Double
    var vibratoExtentCents: Double

    var duration: Double { end - start }
    var hasVibrato: Bool { vibratoRateHz > 0 }

    var cNote: MQSungNote {
        MQSungNote(start_seconds: start, end_seconds: end, hz: hz, cents: mq_hz_to_cents(hz, 440),
                   spread_cents: spreadCents, vibrato_rate_hz: vibratoRateHz,
                   vibrato_extent_cents: vibratoExtentCents, first_frame: 0, frame_count: 0)
    }
}

struct PitchAnalysis: Codable, Equatable {
    /// Bumped when the tracker or segmenter changes, so stale caches are redone.
    static let currentVersion = 1

    var version: Int = PitchAnalysis.currentVersion
    var track: PitchTrack
    var notes: [SungNote]

    /// The lowest and highest sung notes, by their held pitch.
    var range: (low: SungNote, high: SungNote)? {
        guard let low = notes.min(by: { $0.hz < $1.hz }), let high = notes.max(by: { $0.hz < $1.hz }) else { return nil }
        return (low, high)
    }

    /// The note sounding at `seconds`, or nil between notes.
    func note(at seconds: Double) -> SungNote? {
        notes.first { $0.start <= seconds && seconds < $0.end }
    }
}

// MARK: - Building it from audio

/// Feeds decoded mono audio through the core's streaming pitch tracker, then
/// segments the track into notes. Runs on a background task, never the
/// audio thread.
final class StreamingPitchAnalyzer {
    private let tracker: OpaquePointer
    private let sampleRate: Double
    let hopFrames: UInt32

    init(sampleRate: Double) throws {
        var config = mq_pitch_default_config(sampleRate)
        let hop = UInt32(max(1, (sampleRate * 0.01).rounded()))
        guard let tracker = mq_pitch_tracker_create(&config, hop) else {
            throw AppError.coreFailure(code: Int32(MQ_ERROR_INVALID_ARGUMENT.rawValue))
        }
        self.tracker = tracker
        self.sampleRate = sampleRate
        self.hopFrames = hop
    }

    deinit { mq_pitch_tracker_destroy(tracker) }

    func push(mono: UnsafePointer<Float>, frames: Int) {
        mq_pitch_tracker_push(tracker, mono, frames)
    }

    func finish() throws -> PitchAnalysis {
        let count = mq_pitch_tracker_count(tracker)
        var times = [Double](repeating: 0, count: count)
        var estimates = [MQPitchEstimate](repeating: MQPitchEstimate(), count: count)
        let read = mq_pitch_tracker_read(tracker, &times, &estimates, count)
        let hop = Double(hopFrames) / sampleRate
        let track = PitchTrack(
            hopSeconds: hop,
            firstTime: times.first ?? 0,
            hz: estimates.prefix(read).map { $0.voiced != 0 ? Float($0.frequency_hz) : 0 },
            confidence: estimates.prefix(read).map { Float($0.confidence) })
        return PitchAnalysis(track: track, notes: try Self.segment(times: times, estimates: estimates, count: read))
    }

    static func segment(times: [Double], estimates: [MQPitchEstimate], count: Int) throws -> [SungNote] {
        var config = mq_note_default_config(440)
        var needed = 0
        var status = mq_segment_notes(times, estimates, count, &config, nil, 0, &needed)
        guard status == MQ_OK else { throw AppError.coreFailure(code: Int32(status.rawValue)) }
        guard needed > 0 else { return [] }
        var notes = [MQSungNote](repeating: MQSungNote(), count: needed)
        var written = 0
        status = mq_segment_notes(times, estimates, count, &config, &notes, needed, &written)
        guard status == MQ_OK else { throw AppError.coreFailure(code: Int32(status.rawValue)) }
        return notes.prefix(written).enumerated().map { index, note in
            SungNote(id: index, start: note.start_seconds, end: note.end_seconds, hz: note.hz,
                     spreadCents: note.spread_cents, vibratoRateHz: note.vibrato_rate_hz,
                     vibratoExtentCents: note.vibrato_extent_cents)
        }
    }
}

// MARK: - Intonation against the chosen maqam

/// How the notes sit against a maqam on a tonic. Recomputed whenever the maqam,
/// tonic or degree offsets change; the notes themselves never change.
struct Intonation: Equatable {
    /// Notes within this many cents of their target count as in tune.
    static let toleranceCents = 15.0

    struct NoteResult: Equatable, Identifiable {
        var note: SungNote
        var degreeIndex: Int
        var targetCents: Double
        var deviationCents: Double
        var isAlternate: Bool
        var inTune: Bool
        var id: Int { note.id }
    }

    struct DegreeTendency: Equatable, Identifiable {
        var degreeIndex: Int
        var noteCount: Int
        var seconds: Double
        var meanDeviationCents: Double
        var id: Int { degreeIndex }
    }

    var results: [NoteResult]
    var inTuneCount: Int
    var inTuneFraction: Double
    var meanAbsoluteDeviationCents: Double
    var degrees: [DegreeTendency]

    static func evaluate(_ notes: [SungNote], maqam: MaqamDefinition, tonicHz: Double) -> Intonation? {
        guard tonicHz > 0, !maqam.degrees.isEmpty else { return nil }
        var scale = maqam.cScale
        let cNotes = notes.map(\.cNote)
        var matches = [MQNoteMatch](repeating: MQNoteMatch(), count: cNotes.count)
        var summary = MQIntonationSummary()
        let status = mq_evaluate_intonation(&scale, tonicHz, cNotes, cNotes.count, toleranceCents, &matches, &summary)
        guard status == MQ_OK else { return nil }
        let results = zip(notes, matches).map { note, match in
            NoteResult(note: note, degreeIndex: Int(match.target.degree_index), targetCents: match.target.target_cents,
                       deviationCents: match.target.deviation_cents, isAlternate: match.target.alternate != 0,
                       inTune: match.in_tune != 0)
        }
        let degrees: [DegreeTendency] = withUnsafeBytes(of: summary.degrees) { raw in
            let buffer = raw.bindMemory(to: MQDegreeTendency.self)
            return (0..<min(maqam.degrees.count, buffer.count)).compactMap { index in
                let tendency = buffer[index]
                guard tendency.note_count > 0 else { return nil }
                return DegreeTendency(degreeIndex: index, noteCount: Int(tendency.note_count),
                                      seconds: tendency.seconds, meanDeviationCents: tendency.mean_deviation_cents)
            }
        }
        return Intonation(results: results, inTuneCount: Int(summary.in_tune_count),
                          inTuneFraction: summary.in_tune_fraction,
                          meanAbsoluteDeviationCents: summary.mean_absolute_deviation_cents, degrees: degrees)
    }

    func result(for note: SungNote) -> NoteResult? {
        results.first { $0.note.id == note.id }
    }
}

extension MaqamDefinition {
    /// The definition with the user's per-degree cents adjustments applied.
    func applying(offsets: [Int: Double]) -> MaqamDefinition {
        guard !offsets.isEmpty else { return self }
        var copy = self
        for (index, offset) in offsets where copy.degrees.indices.contains(index) {
            copy.degrees[index].cents += offset
            copy.degrees[index].alternates = copy.degrees[index].alternates.map { $0 + offset }
        }
        return copy
    }
}

// MARK: - Live pitch

/// A pitch reading from the microphone, published at most ~20 times a second.
struct LivePitch: Equatable {
    var hz: Double
    var confidence: Double
    var levelDbfs: Double
    var voiced: Bool

    static let silent = LivePitch(hz: 0, confidence: 0, levelDbfs: -160, voiced: false)
}

/// Turns captured input buffers into pitch readings.
///
/// Used only on the engine's analysis queue: the input tap copies each buffer
/// and hands it here, so detection never runs on the render thread. The
/// detector itself is allocation-free; this wrapper reuses its buffers.
final class LivePitchAnalyzer {
    private let detector: OpaquePointer
    private let frameSize: Int
    private let hop: Int
    private var pending: [Float] = []
    private var recent: [Double] = []

    init?(sampleRate: Double) {
        var config = mq_pitch_default_config(sampleRate)
        guard let detector = mq_pitch_create(&config) else { return nil }
        self.detector = detector
        self.frameSize = Int(config.frame_size)
        self.hop = Int(config.frame_size) / 2
        pending.reserveCapacity(frameSize * 4)
    }

    deinit { mq_pitch_destroy(detector) }

    /// Adds mono samples; returns the newest reading, if a frame completed.
    func push(_ samples: UnsafeBufferPointer<Float>) -> LivePitch? {
        pending.append(contentsOf: samples)
        var latest: LivePitch?
        while pending.count >= frameSize {
            var estimate = MQPitchEstimate()
            pending.withUnsafeBufferPointer { buffer in
                _ = mq_pitch_detect(detector, buffer.baseAddress, &estimate)
            }
            pending.removeFirst(hop)
            latest = smooth(estimate)
        }
        return latest
    }

    /// A three-reading median steadies the display without lagging a real change.
    private func smooth(_ estimate: MQPitchEstimate) -> LivePitch {
        guard estimate.voiced != 0, estimate.frequency_hz > 0 else {
            recent.removeAll(keepingCapacity: true)
            return LivePitch(hz: 0, confidence: estimate.confidence, levelDbfs: estimate.rms_dbfs, voiced: false)
        }
        recent.append(estimate.frequency_hz)
        if recent.count > 3 { recent.removeFirst(recent.count - 3) }
        let sorted = recent.sorted()
        return LivePitch(hz: sorted[sorted.count / 2], confidence: estimate.confidence,
                         levelDbfs: estimate.rms_dbfs, voiced: true)
    }
}
