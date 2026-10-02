import SwiftUI

/// The export card: what will be exported, the button, and the last result
/// with a share button.
struct ExportSection: View {
    @EnvironmentObject private var l10n: L10n
    @EnvironmentObject private var model: AppModel
    @State private var showingOptions = false

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(l10n("export.title")).font(.headline).accessibilityAddTraits(.isHeader)
            Text(ExportText.format(model.exportOptions, l10n: l10n)).font(.footnote).foregroundStyle(.secondary)
            Button {
                showingOptions = true
            } label: {
                Label(l10n("action.export"), systemImage: "square.and.arrow.up.on.square")
                    .frame(maxWidth: .infinity, minHeight: 48)
            }
            .buttonStyle(.bordered)
            .disabled(model.activity != .idle || model.exportSources.isEmpty)
            .accessibilityHint(l10n("hint.export"))

            if let result = model.exportResult {
                Text(model.exportResultText).font(.subheadline).fixedSize(horizontal: false, vertical: true)
                ShareLink(item: result.url) {
                    Label(l10n("action.share"), systemImage: "square.and.arrow.up")
                        .frame(maxWidth: .infinity, minHeight: 48)
                }
                .buttonStyle(.borderedProminent)
                .accessibilityHint(l10n("hint.share"))
                Text(l10n("export.where")).font(.footnote).foregroundStyle(.secondary)
            }
        }
        .padding()
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 14).fill(Color(.secondarySystemBackground)))
        .sheet(isPresented: $showingOptions) { ExportOptionsView() }
    }
}

/// Every export choice, then one button. Choices are remembered.
struct ExportOptionsView: View {
    @EnvironmentObject private var l10n: L10n
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var options = ExportOptions()
    @State private var source = ExportSource.original

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Picker(l10n("export.source"), selection: $source) {
                        ForEach(model.exportSources) { item in
                            Text(l10n("listen." + item.rawValue)).tag(item)
                        }
                    }
                    .accessibilityHint(l10n("hint.exportsource"))
                } footer: {
                    Text(l10n("export.source.about"))
                }

                Section {
                    Picker(l10n("export.format"), selection: $options.format) {
                        ForEach(ExportFormat.allCases) { format in
                            Text(l10n("export.format." + format.rawValue)).tag(format)
                        }
                    }
                    .pickerStyle(.segmented)
                    .accessibilityLabel(l10n("export.format"))
                    Text(l10n("export.format." + options.format.rawValue + ".about")).font(.footnote).foregroundStyle(.secondary)

                    if options.format == .mp3 {
                        Picker(l10n("export.quality"), selection: $options.mp3Kbps) {
                            ForEach(ExportOptions.bitrates, id: \.self) { kbps in
                                Text(l10n("export.kbps", l10n.number(Double(kbps)))).tag(kbps)
                            }
                        }
                        .accessibilityHint(l10n("hint.exportquality"))
                    } else {
                        Picker(l10n("export.bits"), selection: $options.bitDepth) {
                            ForEach(options.format.bitDepths, id: \.self) { bits in
                                Text(ExportText.bits(bits, l10n: l10n)).tag(bits)
                            }
                        }
                    }
                    Picker(l10n("export.rate"), selection: $options.sampleRate) {
                        ForEach(ExportOptions.sampleRates, id: \.self) { rate in
                            Text(ExportText.rate(rate, l10n: l10n)).tag(rate)
                        }
                    }
                    Picker(l10n("export.channels"), selection: $options.stereo) {
                        Text(l10n("export.stereo")).tag(true)
                        Text(l10n("export.mono")).tag(false)
                    }
                } header: {
                    Text(l10n("export.file"))
                }

                Section {
                    Picker(l10n("export.loudness"), selection: $options.loudnessTarget) {
                        ForEach(ExportOptions.loudnessTargets, id: \.self) { target in
                            Text(ExportText.loudness(target, l10n: l10n)).tag(target)
                        }
                    }
                    .accessibilityHint(l10n("hint.exportloudness"))
                } footer: {
                    Text(l10n(options.loudnessTarget == nil ? "export.loudness.keep.about" : "export.loudness.about"))
                }

                Section {
                    Button {
                        model.exportOptions = options
                        model.export(source)
                        dismiss()
                    } label: {
                        Label(l10n("action.exportnow"), systemImage: "square.and.arrow.down")
                            .font(.headline)
                            .frame(maxWidth: .infinity, minHeight: 48)
                    }
                    .disabled(!model.exportSources.contains(source))
                    .accessibilityHint(ExportText.format(options, l10n: l10n))
                }
            }
            .navigationTitle(l10n("export.title"))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(l10n("action.close")) { dismiss() }
                }
            }
            .onAppear {
                options = model.exportOptions
                source = model.preferredExportSource
            }
            .onChange(of: options) { newValue in
                let normalized = newValue.normalized()
                if normalized != newValue { options = normalized }
                model.exportOptions = normalized
            }
        }
    }
}

/// Export choices in words.
enum ExportText {
    static func rate(_ rate: Int, l10n: L10n) -> String {
        l10n("export.khz", l10n.number(Double(rate) / 1000, fractionDigits: rate % 1000 == 0 ? 0 : 1))
    }

    static func bits(_ bits: Int, l10n: L10n) -> String {
        bits == 32 ? l10n("export.float") : l10n("export.bitdepth", l10n.number(Double(bits)))
    }

    static func loudness(_ target: Double?, l10n: L10n) -> String {
        guard let target else { return l10n("export.loudness.keep") }
        return l10n("export.loudness.target", l10n.number(target))
    }

    /// "MP3, 256 kbit/s, 48 kHz, stereo" / "WAV, 24-bit, 44.1 kHz, mono".
    static func format(_ options: ExportOptions, l10n: L10n) -> String {
        let quality = options.format == .mp3 ? l10n("export.kbps", l10n.number(Double(options.mp3Kbps)))
                                             : bits(options.bitDepth, l10n: l10n)
        return [l10n("export.format." + options.format.rawValue), quality, rate(options.sampleRate, l10n: l10n),
                l10n(options.stereo ? "export.stereo" : "export.mono"), loudness(options.loudnessTarget, l10n: l10n)]
            .joined(separator: l10n.listSeparator)
    }
}
