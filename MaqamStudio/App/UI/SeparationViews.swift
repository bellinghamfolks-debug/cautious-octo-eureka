import SwiftUI
import UniformTypeIdentifiers

/// Separate a mixed song into its voice and its music, then work on the voice.
struct SeparationSection: View {
    @EnvironmentObject private var l10n: L10n
    @EnvironmentObject private var model: AppModel
    @State private var showingModels = false

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(l10n("separation.title")).font(.headline).accessibilityAddTraits(.isHeader)
            Text(l10n("separation.about")).font(.footnote).foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            if model.separators.count > 1 {
                Picker(l10n("separation.engine"), selection: Binding(get: { model.separatorId }, set: { model.separatorId = $0 })) {
                    ForEach(model.separators, id: \.id) { separator in
                        Text(SeparationText.name(separator, l10n: l10n)).tag(separator.id)
                    }
                }
                .pickerStyle(.menu)
                .accessibilityLabel(l10n("separation.engine"))
            }
            Button {
                model.separateVocal()
            } label: {
                Label(l10n("action.separate"), systemImage: "person.wave.2")
                    .frame(maxWidth: .infinity, minHeight: 48)
            }
            .buttonStyle(.bordered)
            .disabled(model.activity != .idle)
            .accessibilityHint(l10n("hint.separate"))

            if model.separationIsFresh, let separation = model.separation {
                Text(l10n("separation.done", SeparationText.engineName(separation.engineId, model: model, l10n: l10n)))
                    .font(.subheadline).fixedSize(horizontal: false, vertical: true)
                Button {
                    model.startProjectFromVocal()
                } label: {
                    Label(l10n("action.vocalproject"), systemImage: "plus.rectangle.on.rectangle")
                        .frame(maxWidth: .infinity, minHeight: 48)
                }
                .buttonStyle(.borderedProminent)
                .disabled(model.activity != .idle)
                .accessibilityHint(l10n("hint.vocalproject"))
            }
            Button(l10n("action.separationmodels")) { showingModels = true }
                .font(.footnote)
                .accessibilityHint(l10n("hint.separationmodels"))
        }
        .padding()
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 14).fill(Color(.secondarySystemBackground)))
        .sheet(isPresented: $showingModels) { SeparationModelsView() }
    }
}

/// The engines: the built-in one, and Core ML models the user adds.
struct SeparationModelsView: View {
    @EnvironmentObject private var l10n: L10n
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var showingImporter = false

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text(l10n("separation.engine.classical"))
                        .accessibilityAddTraits(model.separatorId == "classical" ? .isSelected : [])
                    Text(l10n("separation.classical.about")).font(.footnote).foregroundStyle(.secondary)
                } header: {
                    Text(l10n("separation.builtin"))
                }
                Section {
                    if model.separationModels.isEmpty {
                        Text(l10n("separation.nomodels")).foregroundStyle(.secondary)
                    }
                    ForEach(model.separationModels, id: \.id) { separator in
                        HStack {
                            Text(separator.name)
                            Spacer()
                            Button(role: .destructive) {
                                model.removeSeparationModel(separator)
                            } label: {
                                Image(systemName: "trash")
                            }
                            .accessibilityLabel(l10n("action.removemodel", separator.name))
                        }
                    }
                    Button {
                        showingImporter = true
                    } label: {
                        Label(l10n("action.addmodel"), systemImage: "plus")
                    }
                } header: {
                    Text(l10n("separation.models"))
                } footer: {
                    Text(l10n("separation.contract", CoreMLSeparator.inputName, CoreMLSeparator.outputName,
                              "1 × \(CoreMLSeparator.framesPerCall) × \(SeparationSession.bins)"))
                }
            }
            .navigationTitle(l10n("separation.engines"))
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(l10n("action.done")) { dismiss() }
                }
            }
            .fileImporter(isPresented: $showingImporter, allowedContentTypes: Self.modelTypes) { result in
                if case .success(let url) = result { model.installSeparationModel(from: url) }
            }
        }
    }

    /// .mlmodel and .mlpackage; anything else is refused with a reason when checked.
    static let modelTypes: [UTType] = {
        var types = [UTType("com.apple.coreml.model"), UTType("com.apple.coreml.mlpackage"),
                     UTType(filenameExtension: "mlmodel"), UTType(filenameExtension: "mlpackage")].compactMap { $0 }
        if types.isEmpty { types = [.item] }
        return types
    }()
}

@MainActor
enum SeparationText {
    static func name(_ separator: VocalSeparator, l10n: L10n) -> String {
        if let coreML = separator as? CoreMLSeparator { return l10n("separation.engine.model", coreML.name) }
        return l10n("separation.engine.classical")
    }

    static func engineName(_ id: String, model: AppModel, l10n: L10n) -> String {
        if let separator = model.separators.first(where: { $0.id == id }) { return name(separator, l10n: l10n) }
        return id.hasPrefix("coreml:") ? l10n("separation.engine.model", String(id.dropFirst(7))) : l10n("separation.engine.classical")
    }
}
