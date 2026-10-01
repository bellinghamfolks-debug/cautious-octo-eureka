import Foundation

/// Which maqam and tonic the singing suggests, with probabilities.
///
/// A suggestion, never a silent decision: the app applies it on its own only
/// when no maqam has been chosen and it is confident, and a choice made by
/// hand is never replaced.
struct MaqamDetectionResult: Equatable {
    struct Candidate: Equatable, Identifiable {
        var maqam: MaqamDefinition
        var tonicHz: Double
        var probability: Double
        var meanDeviationCents: Double
        var id: String { "\(maqam.id)@\(Int((tonicHz * 10).rounded()))" }
    }

    /// Below this, a candidate is offered but not applied automatically.
    static let applyAutomaticallyAbove = 0.6

    var enoughData: Bool
    var sungSeconds: Double
    var pitchClasses: Int
    var tonicHz: Double
    var tonicConfidence: Double
    var candidates: [Candidate]

    var best: Candidate? { candidates.first }

    /// Ranks every given maqam (built-in and the user's own) on every sung tonic.
    static func detect(notes: [SungNote], maqamat: [MaqamDefinition], limit: Int = 8) -> MaqamDetectionResult? {
        guard !notes.isEmpty, !maqamat.isEmpty else { return nil }
        let cNotes = notes.map(\.cNote)
        let scales = maqamat.map(\.cScale)
        var summary = MQDetectionSummary()
        var count = 0
        guard mq_detect_maqam(cNotes, cNotes.count, scales, scales.count, &summary, nil, 0, &count) == MQ_OK else {
            return nil
        }
        var ranked = [MQMaqamCandidate](repeating: MQMaqamCandidate(), count: min(count, max(1, limit)))
        var written = 0
        guard !ranked.isEmpty,
              mq_detect_maqam(cNotes, cNotes.count, scales, scales.count, &summary, &ranked, ranked.count, &written) == MQ_OK
        else { return nil }
        let candidates = ranked.prefix(written).compactMap { candidate -> Candidate? in
            let index = Int(candidate.scale_index)
            guard maqamat.indices.contains(index) else { return nil }
            return Candidate(maqam: maqamat[index], tonicHz: candidate.tonic_hz, probability: candidate.probability,
                             meanDeviationCents: candidate.mean_deviation_cents)
        }
        return MaqamDetectionResult(enoughData: summary.enough_data != 0, sungSeconds: summary.sung_seconds,
                                    pitchClasses: Int(summary.pitch_classes), tonicHz: summary.tonic_hz,
                                    tonicConfidence: summary.tonic_confidence, candidates: candidates)
    }

    /// Whether the current choice disagrees with a confident detection.
    func disagrees(with maqamId: String?, tonicHz: Double?) -> Bool {
        guard enoughData, let best, best.probability >= 0.5 else { return false }
        guard let maqamId, let tonicHz else { return true }
        let tonicDistance = abs(mq_hz_to_cents(best.tonicHz, tonicHz).truncatingRemainder(dividingBy: 1200))
        return best.maqam.id != maqamId || min(tonicDistance, 1200 - tonicDistance) > 30
    }
}
