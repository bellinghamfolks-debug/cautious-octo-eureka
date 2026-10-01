import Foundation

/// Words for pitch, shared by the screen and by VoiceOver, so what is heard
/// is exactly what is shown. Deviations are always in cents from a maqam
/// target; nothing is described against a semitone grid.
enum PitchText {
    /// "in the centre", "12 cents sharp", "8 cents flat".
    static func deviation(_ cents: Double, l10n: L10n) -> String {
        let rounded = cents.rounded()
        if abs(rounded) < 3 { return l10n("pitch.centred") }
        return rounded > 0
            ? l10n("pitch.sharp", l10n.number(rounded))
            : l10n("pitch.flat", l10n.number(-rounded))
    }

    /// "the tonic" or "degree 3".
    static func degree(_ index: Int, l10n: L10n) -> String {
        index == 0 ? l10n("degree.tonic") : l10n("degree.number", l10n.number(Double(index + 1)))
    }

    /// The pitch of a degree's target on this tonic, named in quarter tones.
    static func targetName(cents: Double, tonicHz: Double, l10n: L10n) -> String {
        PitchNaming.label(hz: tonicHz * pow(2, cents / 1200), l10n: l10n)
    }

    /// One note, fully: name, degree, deviation, vibrato.
    static func note(_ note: SungNote, result: Intonation.NoteResult?, l10n: L10n) -> String {
        var parts = [PitchNaming.label(hz: note.hz, l10n: l10n)]
        if let result {
            parts.append(degree(result.degreeIndex, l10n: l10n))
            parts.append(deviation(result.deviationCents, l10n: l10n))
        }
        if note.hasVibrato {
            parts.append(l10n("pitch.vibrato", l10n.number(note.vibratoRateHz, fractionDigits: 1),
                              l10n.number(note.vibratoExtentCents)))
        }
        return parts.joined(separator: l10n.listSeparator)
    }

    /// How a degree was sung across the performance.
    static func tendency(_ tendency: Intonation.DegreeTendency, maqam: MaqamDefinition, tonicHz: Double,
                         l10n: L10n) -> String {
        let cents = maqam.degrees.indices.contains(tendency.degreeIndex) ? maqam.degrees[tendency.degreeIndex].cents : 0
        let name = targetName(cents: cents, tonicHz: tonicHz, l10n: l10n)
        let deviationText = abs(tendency.meanDeviationCents) < 3
            ? l10n("tendency.centred")
            : (tendency.meanDeviationCents > 0
                ? l10n("tendency.sharp", l10n.number(tendency.meanDeviationCents.rounded()))
                : l10n("tendency.flat", l10n.number(-tendency.meanDeviationCents.rounded())))
        return l10n("tendency.row", degree(tendency.degreeIndex, l10n: l10n), name, deviationText,
                    l10n.number(Double(tendency.noteCount)))
    }
}

/// The live tuner's reading against the chosen maqam, or against the nearest
/// quarter tone when no maqam is chosen yet.
enum TunerText {
    struct Reading: Equatable {
        var noteName: String
        var deviationCents: Double
        /// The maqam degree, when a maqam and tonic are chosen.
        var degreeIndex: Int?
        var targetName: String?
    }

    @MainActor
    static func reading(_ pitch: LivePitch, model: AppModel, l10n: L10n) -> Reading? {
        guard pitch.voiced, pitch.hz > 0 else { return nil }
        let a4 = model.document?.maqam.a4Hz ?? 440
        let name = PitchNaming.label(hz: pitch.hz, l10n: l10n, a4Hz: a4)
        if let maqam = model.effectiveMaqam, let tonic = model.document?.maqam.tonicHz,
           let target = maqam.nearestTarget(centsFromTonic: mq_hz_to_cents(pitch.hz, tonic)) {
            return Reading(noteName: name, deviationCents: target.deviationCents, degreeIndex: target.degreeIndex,
                           targetName: PitchText.targetName(cents: target.targetCents, tonicHz: tonic, l10n: l10n))
        }
        return Reading(noteName: name, deviationCents: PitchNaming.name(hz: pitch.hz, a4Hz: a4).remainderCents,
                       degreeIndex: nil, targetName: nil)
    }

    @MainActor
    static func spoken(_ pitch: LivePitch, model: AppModel, l10n: L10n) -> String {
        guard let reading = reading(pitch, model: model, l10n: l10n) else { return l10n("tuner.silent") }
        var parts = [reading.noteName]
        if let degree = reading.degreeIndex { parts.append(PitchText.degree(degree, l10n: l10n)) }
        parts.append(PitchText.deviation(reading.deviationCents, l10n: l10n))
        return parts.joined(separator: l10n.listSeparator)
    }
}
