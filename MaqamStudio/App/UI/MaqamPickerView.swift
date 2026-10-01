import SwiftUI

/// Choose the maqam and its tonic by hand.
///
/// Everything here is text first: each maqam is read with its ajnas and its
/// notes named in quarter tones from the chosen tonic, so the scale can be
/// understood without seeing a staff or a keyboard. The tonic moves in
/// quarter tones, or in 5-cent steps for singers who do not tune to A = 440.
struct MaqamPickerView: View {
    @EnvironmentObject private var l10n: L10n
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss

    /// The tonic range offered: C2 to C6, enough for any voice.
    static let tonicRange: ClosedRange<Double> = 65.41...1046.5

    var body: some View {
        NavigationStack {
            List {
                if let maqam = model.currentMaqam {
                    tonicSection(maqam)
                    degreesSection(maqam)
                }
                ForEach(Self.families, id: \.self) { family in
                    Section {
                        ForEach(MaqamCatalog.builtins.filter { $0.family == family }) { maqam in
                            row(maqam)
                        }
                    } header: {
                        Text(l10n("maqam.family", familyName(family)))
                    }
                }
                Section {
                    Button(role: .destructive) {
                        model.chooseMaqam(id: nil)
                    } label: {
                        Label(l10n("action.clearmaqam"), systemImage: "xmark.circle")
                    }
                    .disabled(model.currentMaqam == nil)
                } footer: {
                    Text(l10n("maqam.manual.footer"))
                }
            }
            .navigationTitle(l10n("maqam.picker.title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(l10n("action.done")) { dismiss() }
                }
            }
        }
        .environment(\.locale, l10n.locale)
        .environment(\.layoutDirection, l10n.layoutDirection)
    }

    // MARK: Rows

    private func row(_ maqam: MaqamDefinition) -> some View {
        let selected = model.currentMaqam?.id == maqam.id
        return Button {
            model.chooseMaqam(id: maqam.id)
        } label: {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(maqam.name(l10n)).font(.headline)
                    Text(ajnas(maqam)).font(.footnote).foregroundStyle(.secondary)
                    Text(MaqamText.noteNames(maqam, tonicHz: maqam.typicalTonicHz, l10n: l10n))
                        .font(.footnote).foregroundStyle(.secondary)
                }
                Spacer(minLength: 0)
                if selected {
                    Image(systemName: "checkmark").foregroundStyle(Color.accentColor).accessibilityHidden(true)
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(maqam.name(l10n))
        .accessibilityValue(selected ? l10n("maqam.selected") : "")
        .accessibilityHint(ajnas(maqam) + ". "
                           + MaqamText.noteNames(maqam, tonicHz: maqam.typicalTonicHz, l10n: l10n))
        .accessibilityAddTraits(selected ? [.isButton, .isSelected] : .isButton)
    }

    private func tonicSection(_ maqam: MaqamDefinition) -> some View {
        let tonic = model.document?.maqam.tonicHz ?? maqam.typicalTonicHz
        return Section {
            Stepper(onIncrement: { model.chooseTonic(hz: Self.clampTonic(tonic * Self.ratio(cents: 50))) },
                    onDecrement: { model.chooseTonic(hz: Self.clampTonic(tonic / Self.ratio(cents: 50))) }) {
                tonicText(tonic)
            }
            .accessibilityLabel(l10n("maqam.tonic.quarter"))
            .accessibilityValue(tonicSpoken(tonic))

            Stepper(onIncrement: { model.chooseTonic(hz: Self.clampTonic(tonic * Self.ratio(cents: 5))) },
                    onDecrement: { model.chooseTonic(hz: Self.clampTonic(tonic / Self.ratio(cents: 5))) }) {
                Text(l10n("maqam.tonic.fine"))
            }
            .accessibilityLabel(l10n("maqam.tonic.fine"))
            .accessibilityValue(tonicSpoken(tonic))

            Button {
                model.chooseTonic(hz: maqam.typicalTonicHz)
            } label: {
                Label(l10n("action.resettonic", PitchNaming.label(hz: maqam.typicalTonicHz, l10n: l10n)),
                      systemImage: "arrow.counterclockwise")
            }
            .disabled(abs(tonic - maqam.typicalTonicHz) < 0.01)
        } header: {
            Text(l10n("maqam.tonic.header"))
        }
    }

    private func degreesSection(_ maqam: MaqamDefinition) -> some View {
        let tonic = model.document?.maqam.tonicHz ?? maqam.typicalTonicHz
        return Section {
            ForEach(Array(maqam.degrees.enumerated()), id: \.offset) { index, degree in
                VStack(alignment: .leading, spacing: 2) {
                    Text(l10n("maqam.degree.row", l10n.number(Double(index + 1)),
                              PitchNaming.label(hz: tonic * Self.ratio(cents: degree.cents), l10n: l10n),
                              l10n.number(degree.cents)))
                    if !degree.alternates.isEmpty {
                        Text(l10n("maqam.degree.alternates", degree.alternates.map { alternate in
                            "\(PitchNaming.label(hz: tonic * Self.ratio(cents: alternate), l10n: l10n)) (\(l10n.number(alternate)))"
                        }.joined(separator: l10n.listSeparator)))
                        .font(.footnote).foregroundStyle(.secondary)
                    }
                }
                .accessibilityElement(children: .combine)
            }
            if !maqam.octaveEquivalent {
                Text(l10n("maqam.nooctave")).font(.footnote).foregroundStyle(.secondary)
            }
        } header: {
            Text(l10n("maqam.degrees.header", maqam.name(l10n)))
        }
    }

    // MARK: Text

    private func tonicText(_ hz: Double) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(PitchNaming.label(hz: hz, l10n: l10n)).font(.headline)
            Text(l10n("units.hertz", l10n.number(hz, fractionDigits: 1))).font(.footnote).foregroundStyle(.secondary)
        }
    }

    private func tonicSpoken(_ hz: Double) -> String {
        let named = PitchNaming.name(hz: hz)
        var text = PitchNaming.label(hz: hz, l10n: l10n) + l10n.listSeparator
            + l10n("units.hertz", l10n.number(hz, fractionDigits: 1))
        let offset = named.remainderCents.rounded()
        if abs(offset) >= 1 {
            text += l10n.listSeparator + l10n("maqam.tonic.offset", l10n.number(offset))
        }
        return text
    }

    private func ajnas(_ maqam: MaqamDefinition) -> String {
        l10n("maqam.ajnas", MaqamText.jinsName(maqam.lowerJins, l10n: l10n), MaqamText.jinsName(maqam.upperJins, l10n: l10n))
    }

    private func familyName(_ family: String) -> String {
        MaqamCatalog.definition(id: family)?.name(l10n) ?? family
    }

    // MARK: Helpers

    /// Families in catalog order, each listed once.
    static var families: [String] {
        var seen = Set<String>()
        return MaqamCatalog.builtins.map(\.family).filter { seen.insert($0).inserted }
    }

    static func ratio(cents: Double) -> Double { pow(2, cents / 1200) }

    static func clampTonic(_ hz: Double) -> Double { min(max(hz, tonicRange.lowerBound), tonicRange.upperBound) }
}

extension MaqamText {
    /// The scale's notes named in quarter tones from `tonicHz`, e.g.
    /// "دو ٤، ري ٤، مي نصف بيمول ٤ …". Names come from the actual cents, never
    /// from a rounded semitone.
    static func noteNames(_ maqam: MaqamDefinition, tonicHz: Double, l10n: L10n) -> String {
        maqam.degrees.map { degree in
            PitchNaming.label(hz: tonicHz * pow(2, degree.cents / 1200), l10n: l10n)
        }.joined(separator: l10n.listSeparator)
    }

    /// "jins rast" → the localized name of jins Rast.
    static func jinsName(_ raw: String, l10n: L10n) -> String {
        var id = raw.lowercased()
        if id.hasPrefix("jins ") { id.removeFirst(5) }
        let key = "jins." + id.replacingOccurrences(of: " ", with: "_")
        let text = l10n(key)
        return text == key ? raw : text
    }
}
