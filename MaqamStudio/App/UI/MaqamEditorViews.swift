import SwiftUI
import UIKit

/// Edit and delete for the user's own maqamat, copy-to-edit for built-ins:
/// as swipe actions, a context menu and VoiceOver custom actions alike.
struct MaqamRowActions: ViewModifier {
    @EnvironmentObject private var l10n: L10n
    let maqam: MaqamDefinition
    let edit: (MaqamDefinition) -> Void
    let delete: (MaqamDefinition) -> Void

    private var copy: MaqamDefinition {
        maqam.customCopy(arabicName: l10n("library.copyname", maqam.arabicName),
                         englishName: l10n("library.copyname", maqam.englishName))
    }

    func body(content: Content) -> some View {
        if maqam.isCustom {
            content
                .swipeActions(edge: .trailing) {
                    Button(role: .destructive) { delete(maqam) } label: { Label(l10n("action.delete"), systemImage: "trash") }
                    Button { edit(maqam) } label: { Label(l10n("action.editmaqam"), systemImage: "slider.horizontal.3") }
                        .tint(.blue)
                }
                .contextMenu {
                    Button { edit(maqam) } label: { Label(l10n("action.editmaqam"), systemImage: "slider.horizontal.3") }
                    Button { edit(copy) } label: { Label(l10n("action.copymaqam"), systemImage: "plus.square.on.square") }
                    Button(role: .destructive) { delete(maqam) } label: { Label(l10n("action.delete"), systemImage: "trash") }
                }
                .accessibilityAction(named: l10n("action.editmaqam")) { edit(maqam) }
                .accessibilityAction(named: l10n("action.copymaqam")) { edit(copy) }
                .accessibilityAction(named: l10n("action.delete")) { delete(maqam) }
        } else {
            content
                .swipeActions(edge: .trailing) {
                    Button { edit(copy) } label: { Label(l10n("action.copymaqam"), systemImage: "plus.square.on.square") }
                        .tint(.blue)
                }
                .contextMenu {
                    Button { edit(copy) } label: { Label(l10n("action.copymaqam"), systemImage: "plus.square.on.square") }
                }
                .accessibilityAction(named: l10n("action.copymaqam")) { edit(copy) }
        }
    }
}

struct SharedFile: Identifiable {
    let url: URL
    var id: URL { url }
}

/// The system share sheet.
struct ActivitySheet: UIViewControllerRepresentable {
    let items: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }

    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}

/// Step sizes offered when moving a pitch, in cents.
enum CentsStep: Double, CaseIterable, Identifiable {
    case one = 1, five = 5, ten = 10, twentyFive = 25, quarterTone = 50
    var id: Double { rawValue }
}

// MARK: - Tuning the degrees of this project

/// Adjusts each degree of the chosen maqam for this project, in cents.
///
/// The definition is untouched; the adjustments live in the project and are
/// undoable. They can be saved as a named tuning table and applied to any
/// other project that uses the same maqam.
struct DegreeTuningView: View {
    @EnvironmentObject private var l10n: L10n
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var library: MaqamLibrary
    @State private var step: CentsStep = .one
    @State private var showingSave = false
    @State private var tableName = ""

    var body: some View {
        List {
            if let maqam = model.currentMaqam {
                Section {
                    Picker(l10n("tuning.step"), selection: $step) {
                        ForEach([CentsStep.one, .five, .ten]) { step in
                            Text(l10n("units.cents", l10n.number(step.rawValue))).tag(step)
                        }
                    }
                    .pickerStyle(.segmented)
                    .accessibilityLabel(l10n("tuning.step"))
                } footer: {
                    Text(l10n("tuning.footer"))
                }
                Section {
                    ForEach(Array(maqam.degrees.enumerated()), id: \.offset) { index, degree in
                        degreeRow(index: index, degree: degree, maqam: maqam)
                    }
                    Button(role: .destructive) {
                        model.resetDegreeOffsets()
                    } label: {
                        Label(l10n("action.resetoffsets"), systemImage: "arrow.counterclockwise")
                    }
                    .disabled(model.document?.maqam.degreeOffsets.isEmpty ?? true)
                } header: {
                    Text(l10n("maqam.degrees.header", maqam.name(l10n)))
                }
                tablesSection(maqam)
            } else {
                Text(l10n("maqam.none"))
            }
        }
        .navigationTitle(l10n("tuning.title"))
        .navigationBarTitleDisplayMode(.inline)
        .alert(l10n("tables.save.title"), isPresented: $showingSave) {
            TextField(l10n("tables.save.field"), text: $tableName)
            Button(l10n("action.save")) { model.saveOffsetsAsTable(named: tableName) }
            Button(l10n("action.cancel"), role: .cancel) {}
        }
    }

    private func degreeRow(index: Int, degree: MaqamDegree, maqam: MaqamDefinition) -> some View {
        let offset = model.document?.maqam.degreeOffsets[index] ?? 0
        let tuned = degree.cents + offset
        let tonic = model.document?.maqam.tonicHz ?? maqam.typicalTonicHz
        let name = PitchText.targetName(cents: tuned, tonicHz: tonic, l10n: l10n)
        let value = l10n("tuning.degree.value", l10n.number(tuned), name,
                         offset == 0 ? l10n("tuning.asdefined") : l10n("tuning.offset", signed(offset)))
        return Stepper(onIncrement: { model.setDegreeOffset(index, to: offset + step.rawValue) },
                       onDecrement: { model.setDegreeOffset(index, to: offset - step.rawValue) }) {
            VStack(alignment: .leading, spacing: 2) {
                Text(PitchText.degree(index, l10n: l10n)).font(.headline)
                Text(value).font(.footnote).foregroundStyle(offset == 0 ? Color.secondary : Color.accentColor)
            }
        }
        .disabled(index == 0)  // the tonic is moved with the tonic control
        .accessibilityLabel(PitchText.degree(index, l10n: l10n))
        .accessibilityValue(value)
        .accessibilityHint(index == 0 ? l10n("tuning.tonic.hint") : l10n("tuning.degree.hint",
                                                                         l10n("units.cents", l10n.number(step.rawValue))))
    }

    private func tablesSection(_ maqam: MaqamDefinition) -> some View {
        let tables = library.tables(for: maqam.id)
        return Section {
            ForEach(tables) { table in
                Button {
                    model.applyTable(table)
                } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(table.name)
                        Text(describe(table, maqam: maqam)).font(.footnote).foregroundStyle(.secondary)
                    }
                }
                .accessibilityHint(l10n("tables.apply.hint"))
                .swipeActions { Button(role: .destructive) { model.deleteTable(table) } label: { Label(l10n("action.delete"), systemImage: "trash") } }
                .accessibilityAction(named: l10n("action.delete")) { model.deleteTable(table) }
            }
            Button {
                tableName = ""
                showingSave = true
            } label: {
                Label(l10n("action.savetable"), systemImage: "square.and.arrow.down.on.square")
            }
            .disabled(model.document?.maqam.degreeOffsets.isEmpty ?? true)
        } header: {
            Text(l10n("tables.title"))
        } footer: {
            Text(l10n(tables.isEmpty ? "tables.empty" : "tables.footer"))
        }
    }

    private func describe(_ table: TuningTable, maqam: MaqamDefinition) -> String {
        let parts = table.offsets.keys.sorted().compactMap { index -> String? in
            guard let offset = table.offsets[index], maqam.degrees.indices.contains(index) else { return nil }
            return "\(PitchText.degree(index, l10n: l10n)) \(signed(offset))"
        }
        return parts.isEmpty ? l10n("tuning.asdefined") : parts.joined(separator: l10n.listSeparator)
    }

    private func signed(_ cents: Double) -> String {
        (cents > 0 ? "+" : "") + l10n("units.cents", l10n.number(cents))
    }
}

// MARK: - Building a maqam

/// Creates or edits one of the user's own maqamat.
///
/// Works on a draft: nothing is saved until Save, and Save is offered only when
/// the scale is valid. What is wrong is listed in words, so it can be fixed
/// without seeing anything. Every degree is a number of cents, adjustable in
/// 1, 5, 25 or 50 cent steps; nothing is rounded to a semitone.
struct CustomMaqamEditor: View {
    @EnvironmentObject private var l10n: L10n
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var draft: MaqamDefinition
    @State private var step: CentsStep = .five

    init(original: MaqamDefinition) {
        _draft = State(initialValue: original)
    }

    private var problems: [MaqamProblem] { draft.problems }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField(l10n("editor.arabicname"), text: $draft.arabicName)
                    TextField(l10n("editor.englishname"), text: $draft.englishName)
                        .environment(\.layoutDirection, .leftToRight)
                } header: {
                    Text(l10n("editor.names"))
                }
                Section {
                    Stepper(onIncrement: { draft.typicalTonicHz = MaqamPickerView.clampTonic(draft.typicalTonicHz * MaqamPickerView.ratio(cents: 50)) },
                            onDecrement: { draft.typicalTonicHz = MaqamPickerView.clampTonic(draft.typicalTonicHz / MaqamPickerView.ratio(cents: 50)) }) {
                        Text(l10n("editor.tonic", PitchNaming.label(hz: draft.typicalTonicHz, l10n: l10n)))
                    }
                    .accessibilityLabel(l10n("editor.tonic.label"))
                    .accessibilityValue(PitchNaming.label(hz: draft.typicalTonicHz, l10n: l10n))
                    Toggle(l10n("editor.octave"), isOn: $draft.octaveEquivalent)
                        .accessibilityHint(l10n("editor.octave.hint"))
                }
                Section {
                    Picker(l10n("tuning.step"), selection: $step) {
                        ForEach([CentsStep.one, .five, .twentyFive, .quarterTone]) { step in
                            Text(l10n("units.cents", l10n.number(step.rawValue))).tag(step)
                        }
                    }
                    .pickerStyle(.segmented)
                    ForEach(Array(draft.degrees.indices), id: \.self) { index in
                        degreeEditor(index)
                    }
                    Button {
                        addDegree()
                    } label: {
                        Label(l10n("action.adddegree"), systemImage: "plus.circle")
                    }
                    .disabled(draft.degrees.count >= MaqamDefinition.maximumDegrees)
                } header: {
                    Text(l10n("editor.degrees"))
                } footer: {
                    Text(l10n("editor.degrees.footer"))
                }
                if !problems.isEmpty {
                    Section {
                        ForEach(Array(problems.enumerated()), id: \.offset) { _, problem in
                            Label(problem.message(l10n), systemImage: "exclamationmark.triangle")
                                .foregroundStyle(.orange)
                        }
                    } header: {
                        Text(l10n("editor.problems"))
                    }
                }
            }
            .navigationTitle(draft.name(l10n).isEmpty ? l10n("editor.title.new") : draft.name(l10n))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(l10n("action.cancel")) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(l10n("action.save")) {
                        if model.saveCustomMaqam(normalized) { dismiss() }
                    }
                    .disabled(!problems.isEmpty)
                    .accessibilityHint(problems.isEmpty ? "" : l10n("editor.save.blocked", l10n.number(Double(problems.count))))
                }
            }
        }
        .environment(\.locale, l10n.locale)
        .environment(\.layoutDirection, l10n.layoutDirection)
    }

    /// The draft with surrounding spaces trimmed and alternates kept in order.
    private var normalized: MaqamDefinition {
        var maqam = draft
        maqam.arabicName = maqam.arabicName.trimmingCharacters(in: .whitespacesAndNewlines)
        maqam.englishName = maqam.englishName.trimmingCharacters(in: .whitespacesAndNewlines)
        for index in maqam.degrees.indices { maqam.degrees[index].alternates.sort() }
        return maqam
    }

    @ViewBuilder
    private func degreeEditor(_ index: Int) -> some View {
        let degree = draft.degrees[index]
        let name = PitchText.targetName(cents: degree.cents, tonicHz: draft.typicalTonicHz, l10n: l10n)
        let value = l10n("editor.degree.value", l10n("units.cents", l10n.number(degree.cents)), name)
        Stepper(onIncrement: { move(index, by: step.rawValue) }, onDecrement: { move(index, by: -step.rawValue) }) {
            VStack(alignment: .leading, spacing: 2) {
                Text(PitchText.degree(index, l10n: l10n)).font(.headline)
                Text(value).font(.footnote).foregroundStyle(.secondary)
            }
        }
        .disabled(index == 0)
        .accessibilityLabel(PitchText.degree(index, l10n: l10n))
        .accessibilityValue(value)
        .modifier(DegreeActions(enabled: index > 0, canAddAlternate: degree.alternates.count < MaqamDefinition.maximumAlternates,
                                remove: { draft.degrees.remove(at: index) },
                                addAlternate: { addAlternate(to: index) }))
        ForEach(Array(degree.alternates.enumerated()), id: \.offset) { slot, alternate in
            let alternateName = PitchText.targetName(cents: alternate, tonicHz: draft.typicalTonicHz, l10n: l10n)
            let alternateValue = l10n("editor.degree.value", l10n("units.cents", l10n.number(alternate)), alternateName)
            Stepper(onIncrement: { moveAlternate(index, slot, by: step.rawValue) },
                    onDecrement: { moveAlternate(index, slot, by: -step.rawValue) }) {
                Text(l10n("editor.alternate", alternateValue)).font(.footnote)
            }
            .padding(.leading, 16)
            .accessibilityLabel(l10n("editor.alternate.label", PitchText.degree(index, l10n: l10n)))
            .accessibilityValue(alternateValue)
            .swipeActions { Button(role: .destructive) { draft.degrees[index].alternates.remove(at: slot) } label: { Label(l10n("action.delete"), systemImage: "trash") } }
            .accessibilityAction(named: l10n("action.removealternate")) { draft.degrees[index].alternates.remove(at: slot) }
        }
    }

    private func move(_ index: Int, by cents: Double) {
        guard index > 0 else { return }
        draft.degrees[index].cents = min(max(draft.degrees[index].cents + cents, 1), 1199)
    }

    private func moveAlternate(_ index: Int, _ slot: Int, by cents: Double) {
        let value = draft.degrees[index].alternates[slot] + cents
        draft.degrees[index].alternates[slot] = min(max(value, 0), 1199)
    }

    private func addDegree() {
        let last = draft.degrees.last?.cents ?? 0
        draft.degrees.append(MaqamDegree(cents: min(1150, last + 100), alternates: []))
    }

    private func addAlternate(to index: Int) {
        let base = draft.degrees[index].cents
        draft.degrees[index].alternates.append(min(1199, base + 50))
    }
}

/// Remove-degree and add-alternate, as swipe actions and VoiceOver actions.
private struct DegreeActions: ViewModifier {
    @EnvironmentObject private var l10n: L10n
    let enabled: Bool
    let canAddAlternate: Bool
    let remove: () -> Void
    let addAlternate: () -> Void

    func body(content: Content) -> some View {
        if enabled {
            content
                .swipeActions {
                    Button(role: .destructive, action: remove) { Label(l10n("action.removedegree"), systemImage: "trash") }
                    if canAddAlternate {
                        Button(action: addAlternate) { Label(l10n("action.addalternate"), systemImage: "plus") }.tint(.blue)
                    }
                }
                .accessibilityAction(named: l10n("action.removedegree"), remove)
                .modifier(OptionalAction(name: l10n("action.addalternate"), enabled: canAddAlternate, action: addAlternate))
        } else {
            content
        }
    }
}

private struct OptionalAction: ViewModifier {
    let name: String
    let enabled: Bool
    let action: () -> Void

    func body(content: Content) -> some View {
        if enabled { content.accessibilityAction(named: name, action) } else { content }
    }
}
