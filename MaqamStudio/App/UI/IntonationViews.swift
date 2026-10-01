import SwiftUI

/// What the analysis found in the singing, in sentences: range, notes, and,
/// once a maqam and tonic are chosen, how well each degree was sung.
struct IntonationSummaryView: View {
    @EnvironmentObject private var l10n: L10n
    @EnvironmentObject private var model: AppModel
    let showNotes: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(l10n("intonation.title")).font(.headline).accessibilityAddTraits(.isHeader)
            if let pitch = model.pitch, !pitch.notes.isEmpty {
                line(l10n("intonation.notes", l10n.number(Double(pitch.notes.count)),
                          l10n.number(Double(pitch.notes.filter(\.hasVibrato).count))))
                if let range = pitch.range {
                    line(l10n("intonation.range", PitchNaming.label(hz: range.low.hz, l10n: l10n),
                              PitchNaming.label(hz: range.high.hz, l10n: l10n),
                              l10n.number(mq_hz_to_cents(range.high.hz, range.low.hz).rounded())))
                }
                if let intonation = model.intonation, let maqam = model.effectiveMaqam,
                   let tonic = model.document?.maqam.tonicHz {
                    line(l10n("intonation.accuracy", l10n.percent(intonation.inTuneFraction),
                              l10n.number(Intonation.toleranceCents)))
                        .font(.title3.bold())
                    line(l10n("intonation.meandeviation", l10n.number(intonation.meanAbsoluteDeviationCents.rounded())))
                    if !intonation.degrees.isEmpty {
                        Text(l10n("intonation.bydegree")).font(.subheadline.bold()).padding(.top, 4)
                            .accessibilityAddTraits(.isHeader)
                        ForEach(intonation.degrees) { tendency in
                            line(PitchText.tendency(tendency, maqam: maqam, tonicHz: tonic, l10n: l10n))
                                .foregroundStyle(abs(tendency.meanDeviationCents) > Intonation.toleranceCents
                                                 ? Color.orange : Color.primary)
                        }
                    }
                } else {
                    line(l10n("intonation.needsmaqam")).foregroundStyle(.secondary)
                }
                Button(action: showNotes) {
                    Label(l10n("action.shownotes"), systemImage: "list.bullet")
                        .frame(maxWidth: .infinity, minHeight: 48)
                }
                .buttonStyle(.bordered)
            } else {
                line(l10n("intonation.nonotes")).foregroundStyle(.secondary)
            }
        }
        .padding()
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 14).fill(Color(.secondarySystemBackground)))
    }

    private func line(_ text: String) -> some View {
        Text(text).fixedSize(horizontal: false, vertical: true)
    }
}

/// Every sung note as a row of text; choosing one plays from it.
struct NotesView: View {
    enum Filter: String, CaseIterable, Identifiable {
        case all, outOfTune
        var id: String { rawValue }
    }

    @EnvironmentObject private var l10n: L10n
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var filter: Filter = .all
    @State private var pinning: SungNote?

    var body: some View {
        NavigationStack {
            List {
                if model.intonation != nil {
                    Picker(l10n("notes.filter"), selection: $filter) {
                        Text(l10n("notes.filter.all")).tag(Filter.all)
                        Text(l10n("notes.filter.outoftune")).tag(Filter.outOfTune)
                    }
                    .pickerStyle(.segmented)
                }
                let rows = visibleNotes
                if rows.isEmpty {
                    Text(l10n(filter == .outOfTune ? "notes.allintune" : "intonation.nonotes"))
                        .foregroundStyle(.secondary)
                }
                ForEach(rows) { note in
                    row(note)
                }
            }
            .navigationTitle(l10n("notes.title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button(l10n("action.done")) { dismiss() } }
            }
            .confirmationDialog(l10n("pin.title"),
                                isPresented: Binding(get: { pinning != nil }, set: { if !$0 { pinning = nil } }),
                                titleVisibility: .visible, presenting: pinning) { note in
                ForEach(candidates(for: note), id: \.cents) { candidate in
                    Button(candidate.label) {
                        model.setNoteOverride(note.id, .init(bypass: false, targetCents: candidate.cents))
                    }
                }
                Button(l10n("action.cancel"), role: .cancel) {}
            } message: { note in
                Text(l10n("pin.message", PitchNaming.label(hz: note.hz, l10n: l10n)))
            }
        }
        .environment(\.locale, l10n.locale)
        .environment(\.layoutDirection, l10n.layoutDirection)
    }

    private var visibleNotes: [SungNote] {
        let notes = model.pitch?.notes ?? []
        guard filter == .outOfTune, let intonation = model.intonation else { return notes }
        return notes.filter { intonation.result(for: $0)?.inTune == false }
    }

    private func row(_ note: SungNote) -> some View {
        let result = model.intonation?.result(for: note)
        let index = (model.pitch?.notes.firstIndex(of: note) ?? 0) + 1
        return Button {
            model.play(from: note.start)
        } label: {
            HStack(alignment: .firstTextBaseline, spacing: 10) {
                Text(l10n.clock(note.start)).monospacedDigit().foregroundStyle(.secondary)
                VStack(alignment: .leading, spacing: 2) {
                    Text(PitchNaming.label(hz: note.hz, l10n: l10n)).font(.headline)
                    if let result {
                        Text(PitchText.degree(result.degreeIndex, l10n: l10n) + l10n.listSeparator
                             + PitchText.deviation(result.deviationCents, l10n: l10n))
                            .foregroundStyle(result.inTune ? Color.secondary : Color.orange)
                    }
                    if note.hasVibrato {
                        Text(l10n("pitch.vibrato", l10n.number(note.vibratoRateHz, fractionDigits: 1),
                                  l10n.number(note.vibratoExtentCents)))
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                    if let manual = overrideText(note) {
                        Label(manual, systemImage: "hand.point.up.left").font(.footnote).foregroundStyle(Color.accentColor)
                    }
                }
                Spacer(minLength: 0)
                if let result {
                    Image(systemName: result.inTune ? "checkmark.circle" : "exclamationmark.circle")
                        .foregroundStyle(result.inTune ? Color.green : Color.orange)
                        .accessibilityHidden(true)
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(l10n("notes.row", l10n.number(Double(index)), l10n.spokenDuration(note.start),
                                 PitchText.note(note, result: result, l10n: l10n))
                            + (overrideText(note).map { l10n.listSeparator + $0 } ?? ""))
        .accessibilityHint(l10n("notes.row.hint"))
        .accessibilityAddTraits(.isButton)
        .modifier(NoteOverrideActions(note: note, hasOverride: model.tuningSettings.noteOverrides[note.id] != nil,
                                      canPin: model.effectiveMaqam != nil && model.document?.maqam.tonicHz != nil,
                                      pin: { pinning = note }))
    }

    private func overrideText(_ note: SungNote) -> String? {
        guard let manual = model.tuningSettings.noteOverrides[note.id] else { return nil }
        if manual.bypass { return l10n("override.bypass") }
        if let target = manual.targetCents, let tonic = model.document?.maqam.tonicHz {
            return l10n("override.pinned", PitchText.targetName(cents: target, tonicHz: tonic, l10n: l10n))
        }
        return nil
    }

    /// The maqam targets nearest a note, for pinning it by hand.
    private func candidates(for note: SungNote) -> [(cents: Double, label: String)] {
        guard let maqam = model.effectiveMaqam, let tonic = model.document?.maqam.tonicHz else { return [] }
        let sung = mq_hz_to_cents(note.hz, tonic)
        let octave = (sung / 1200).rounded(.down)
        var targets: [(cents: Double, degree: Int)] = []
        for shift in [-1.0, 0, 1] {
            for (index, degree) in maqam.degrees.enumerated() {
                for value in [degree.cents] + degree.alternates {
                    targets.append(((octave + shift) * 1200 + value, index))
                }
            }
        }
        return targets
            .sorted { abs($0.cents - sung) < abs($1.cents - sung) }
            .prefix(5)
            .sorted { $0.cents < $1.cents }
            .map { target in
                (target.cents, l10n("pin.option", PitchText.degree(target.degree, l10n: l10n),
                                    PitchText.targetName(cents: target.cents, tonicHz: tonic, l10n: l10n),
                                    PitchText.deviation(sung - target.cents, l10n: l10n)))
            }
    }
}

/// Leave as sung, pin to a degree, back to automatic: by context menu, swipe
/// and VoiceOver action.
private struct NoteOverrideActions: ViewModifier {
    @EnvironmentObject private var l10n: L10n
    @EnvironmentObject private var model: AppModel
    let note: SungNote
    let hasOverride: Bool
    let canPin: Bool
    let pin: () -> Void

    func body(content: Content) -> some View {
        content
            .contextMenu {
                Button { model.setNoteOverride(note.id, .init(bypass: true, targetCents: nil)) } label: {
                    Label(l10n("action.bypassnote"), systemImage: "hand.raised")
                }
                if canPin {
                    Button(action: pin) { Label(l10n("action.pinnote"), systemImage: "pin") }
                }
                if hasOverride {
                    Button { model.setNoteOverride(note.id, nil) } label: {
                        Label(l10n("action.autonote"), systemImage: "wand.and.stars")
                    }
                }
            }
            .swipeActions(edge: .trailing) {
                Button { model.setNoteOverride(note.id, .init(bypass: true, targetCents: nil)) } label: {
                    Label(l10n("action.bypassnote"), systemImage: "hand.raised")
                }
                .tint(.orange)
                if canPin {
                    Button(action: pin) { Label(l10n("action.pinnote"), systemImage: "pin") }.tint(.blue)
                }
            }
            .accessibilityAction(named: l10n("action.bypassnote")) {
                model.setNoteOverride(note.id, .init(bypass: true, targetCents: nil))
            }
            .modifier(PinAction(enabled: canPin, name: l10n("action.pinnote"), pin: pin))
            .modifier(PinAction(enabled: hasOverride, name: l10n("action.autonote")) { model.setNoteOverride(note.id, nil) })
    }
}

private struct PinAction: ViewModifier {
    let enabled: Bool
    let name: String
    let pin: () -> Void

    func body(content: Content) -> some View {
        if enabled { content.accessibilityAction(named: name, pin) } else { content }
    }
}

/// Live pitch from the microphone, against the chosen maqam's targets.
struct LiveTunerView: View {
    @EnvironmentObject private var l10n: L10n
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            VStack(spacing: 24) {
                if model.tunerActive {
                    reading
                } else {
                    Text(l10n("tuner.intro")).multilineTextAlignment(.center).foregroundStyle(.secondary)
                }
                Toggle(l10n("tuner.speak"), isOn: $model.tunerSpeaksNotes)
                    .accessibilityHint(l10n("tuner.speak.hint"))
                Button {
                    if model.tunerActive { model.stopTuner() } else { model.startTuner() }
                } label: {
                    Label(model.tunerActive ? l10n("action.stoptuner") : l10n("action.starttuner"),
                          systemImage: model.tunerActive ? "stop.circle.fill" : "tuningfork")
                        .frame(maxWidth: .infinity, minHeight: 56)
                }
                .buttonStyle(.borderedProminent)
                Text(l10n(model.effectiveMaqam == nil ? "tuner.nomaqam" : "tuner.withmaqam",
                          model.effectiveMaqam?.name(l10n) ?? ""))
                    .font(.footnote).foregroundStyle(.secondary).multilineTextAlignment(.center)
                Spacer()
            }
            .padding()
            .navigationTitle(l10n("tuner.title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(l10n("action.done")) { model.stopTuner(); dismiss() }
                }
            }
            .onDisappear { model.stopTuner() }
        }
        .environment(\.locale, l10n.locale)
        .environment(\.layoutDirection, l10n.layoutDirection)
    }

    @ViewBuilder
    private var reading: some View {
        let current = model.livePitch.flatMap { TunerText.reading($0, model: model, l10n: l10n) }
        VStack(spacing: 10) {
            Text(current?.noteName ?? l10n("tuner.silent"))
                .font(.system(size: 40, weight: .bold, design: .rounded))
                .multilineTextAlignment(.center)
            if let current {
                if let degree = current.degreeIndex, let target = current.targetName {
                    Text(PitchText.degree(degree, l10n: l10n) + l10n.listSeparator + target)
                        .foregroundStyle(.secondary)
                }
                Text(PitchText.deviation(current.deviationCents, l10n: l10n))
                    .font(.title3.bold())
                    .foregroundStyle(abs(current.deviationCents) <= Intonation.toleranceCents ? Color.green : Color.orange)
                DeviationMeter(cents: current.deviationCents)
                if let hz = model.livePitch?.hz {
                    Text(l10n("units.hertz", l10n.number(hz, fractionDigits: 1))).font(.footnote).foregroundStyle(.secondary)
                }
            }
        }
        .frame(maxWidth: .infinity, minHeight: 200)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(l10n("tuner.reading"))
        .accessibilityValue(model.livePitch.map { TunerText.spoken($0, model: model, l10n: l10n) } ?? l10n("tuner.silent"))
        .accessibilityAddTraits(.updatesFrequently)
    }
}

/// A needle from -50 to +50 cents. Its value is also given in words beside it.
struct DeviationMeter: View {
    let cents: Double

    var body: some View {
        GeometryReader { geometry in
            let width = geometry.size.width
            let fraction = CGFloat((min(max(cents, -50), 50) + 50) / 100)
            ZStack(alignment: .leading) {
                Capsule().fill(Color(.tertiarySystemFill)).frame(height: 10)
                Rectangle().fill(Color.secondary).frame(width: 2, height: 24).offset(x: width / 2 - 1)
                Circle()
                    .fill(abs(cents) <= Intonation.toleranceCents ? Color.green : Color.orange)
                    .frame(width: 22, height: 22)
                    .offset(x: fraction * width - 11)
            }
            .frame(height: 24)
        }
        .frame(height: 24)
        .accessibilityHidden(true)
    }
}
