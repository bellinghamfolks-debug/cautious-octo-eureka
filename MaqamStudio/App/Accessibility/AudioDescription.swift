import Foundation

/// Turns a waveform summary into sentences, so a blind user learns what a
/// sighted user sees at a glance: where the singing is, where it is loud, where
/// it is silent, and whether anything clipped.
///
/// Pure and testable: it works on numbers and a localizer, not on views.
struct AudioDescription {
    struct Section: Equatable {
        enum Loudness: Equatable { case silent, quiet, moderate, loud }
        var start: Double
        var end: Double
        var loudness: Loudness
    }

    /// Thresholds in dBFS RMS. Singing typically sits around -30 to -12 dBFS.
    static let silentBelow: Float = -55
    static let quietBelow: Float = -35
    static let loudAbove: Float = -16

    /// Groups the waveform into runs of similar loudness, merging runs shorter
    /// than `minimumSeconds` into their neighbours so the description stays short.
    static func sections(of waveform: WaveformSummary, duration: Double, minimumSeconds: Double = 1.0) -> [Section] {
        let count = waveform.rmsDbfs.count
        guard count > 0, duration > 0 else { return [] }
        let bucketSeconds = duration / Double(count)
        var sections: [Section] = []
        for (index, level) in waveform.rmsDbfs.enumerated() {
            let loudness = classify(level)
            let start = Double(index) * bucketSeconds
            let end = start + bucketSeconds
            if var last = sections.last, last.loudness == loudness {
                last.end = end
                sections[sections.count - 1] = last
            } else {
                sections.append(Section(start: start, end: end, loudness: loudness))
            }
        }
        // Merge short runs into the previous section until none remain.
        var merged: [Section] = []
        for section in sections {
            if let last = merged.last, section.end - section.start < minimumSeconds {
                merged[merged.count - 1] = Section(start: last.start, end: section.end, loudness: last.loudness)
            } else if let last = merged.last, last.loudness == section.loudness {
                merged[merged.count - 1] = Section(start: last.start, end: section.end, loudness: last.loudness)
            } else {
                merged.append(section)
            }
        }
        return merged
    }

    static func classify(_ rmsDbfs: Float) -> Section.Loudness {
        if rmsDbfs < silentBelow { return .silent }
        if rmsDbfs < quietBelow { return .quiet }
        if rmsDbfs > loudAbove { return .loud }
        return .moderate
    }

    /// The loudest moment, in seconds.
    static func loudestMoment(of waveform: WaveformSummary, duration: Double) -> Double? {
        guard let index = waveform.rmsDbfs.indices.max(by: { waveform.rmsDbfs[$0] < waveform.rmsDbfs[$1] }),
              waveform.rmsDbfs.count > 0 else { return nil }
        return (Double(index) + 0.5) * duration / Double(waveform.rmsDbfs.count)
    }

    static func summary(levels: LevelSummary, waveform: WaveformSummary, duration: Double, l10n: L10n) -> String {
        var sentences: [String] = []
        sentences.append(l10n("describe.duration", l10n.spokenDuration(duration)))
        let parts = sections(of: waveform, duration: duration)
        let sung = parts.filter { $0.loudness != .silent }
        if sung.isEmpty {
            sentences.append(l10n("describe.allsilent"))
        } else {
            if let first = sung.first, first.start > 0.5 {
                sentences.append(l10n("describe.startsat", l10n.spokenDuration(first.start)))
            }
            if let last = sung.last, duration - last.end > 0.5 {
                sentences.append(l10n("describe.endsat", l10n.spokenDuration(last.end)))
            }
            let silences = parts.filter { $0.loudness == .silent && $0.start > 0.5 && $0.end < duration - 0.5 }
            if !silences.isEmpty { sentences.append(l10n("describe.pauses", l10n.number(Double(silences.count)))) }
        }
        if let loudest = loudestMoment(of: waveform, duration: duration) {
            sentences.append(l10n("describe.loudest", l10n.spokenDuration(loudest)))
        }
        sentences.append(levels.isClipping
            ? l10n("describe.clipping", l10n.number(Double(levels.clippedSamples)))
            : l10n("describe.noclipping"))
        return sentences.joined(separator: " ")
    }

    static func loudnessWord(_ loudness: Section.Loudness, l10n: L10n) -> String {
        switch loudness {
        case .silent: return l10n("loudness.silent")
        case .quiet: return l10n("loudness.quiet")
        case .moderate: return l10n("loudness.moderate")
        case .loud: return l10n("loudness.loud")
        }
    }
}
