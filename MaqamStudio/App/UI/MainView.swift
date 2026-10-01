import SwiftUI
import UniformTypeIdentifiers

/// The one screen most people need: record or import, listen, see what the
/// recording contains, choose the maqam. Only controls that work are shown;
/// later phases add Auto Studio, Live Tune, comparison and export here.
struct MainView: View {
    @EnvironmentObject private var l10n: L10n
    @EnvironmentObject private var model: AppModel
    @State private var showingImporter = false
    @State private var showingProjects = false
    @State private var showingMaqam = false
    @State private var showingRename = false
    @State private var pendingName = ""

    static let importTypes: [UTType] = [.wav, .mp3, .mpeg4Audio, .aiff, .audio]
        + [UTType(filenameExtension: "flac"), UTType(filenameExtension: "aac"), UTType(filenameExtension: "caf")].compactMap { $0 }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    if let notice = model.recoveryNotice {
                        RecoveryBanner(text: notice) { model.recoveryNotice = nil }
                    }
                    if model.document == nil {
                        welcome
                    } else {
                        projectContent
                    }
                }
                .padding()
                .frame(maxWidth: 720)
                .frame(maxWidth: .infinity)
            }
            .navigationTitle(model.document?.name ?? l10n("app.name"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { toolbar }
            .fileImporter(isPresented: $showingImporter, allowedContentTypes: Self.importTypes,
                          allowsMultipleSelection: false) { result in
                switch result {
                case .success(let urls): if let url = urls.first { model.importAudio(from: url) }
                case .failure: model.error = .fileAccessDenied
                }
            }
            .sheet(isPresented: $showingProjects) { ProjectsView() }
            .sheet(isPresented: $showingMaqam) { MaqamPickerView() }
            .alert(l10n("rename.title"), isPresented: $showingRename) {
                TextField(l10n("rename.field"), text: $pendingName)
                Button(l10n("action.save")) { model.rename(to: pendingName) }
                Button(l10n("action.cancel"), role: .cancel) {}
            }
        }
    }

    // MARK: Sections

    private var welcome: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(l10n("welcome.title")).font(.title2.bold())
                .accessibilityAddTraits(.isHeader)
            Text(l10n("welcome.body")).foregroundStyle(.secondary)
            captureButtons
            Button {
                showingProjects = true
            } label: {
                Label(l10n("action.openproject"), systemImage: "folder")
                    .frame(maxWidth: .infinity, minHeight: 52)
            }
            .buttonStyle(.bordered)
        }
    }

    @ViewBuilder
    private var projectContent: some View {
        switch model.activity {
        case .recording, .finishingRecording:
            RecordingPanel()
        case .importing(let fraction):
            ProgressSection(fraction: fraction)
        case .idle:
            if model.hasAudio {
                audioSections
            } else {
                Text(l10n("empty.title")).font(.title3.bold()).accessibilityAddTraits(.isHeader)
                Text(l10n("empty.body")).foregroundStyle(.secondary)
                captureButtons
            }
        }
    }

    @ViewBuilder
    private var audioSections: some View {
        if let analysis = model.analysis {
            WaveformView(waveform: analysis.waveform, levels: analysis.levels, duration: model.duration,
                         position: model.position) { model.seek(to: $0) }
        }
        TransportView()
        if let analysis = model.analysis, let original = model.document?.original {
            AnalysisSummaryView(levels: analysis.levels, original: original)
        }
        MaqamSummaryView { showingMaqam = true }
        VStack(alignment: .leading, spacing: 10) {
            Text(l10n("newtake.title")).font(.headline).accessibilityAddTraits(.isHeader)
            Text(l10n("newtake.body")).font(.footnote).foregroundStyle(.secondary)
            captureButtons
        }
    }

    private var captureButtons: some View {
        HStack(spacing: 12) {
            Button {
                model.startRecording()
            } label: {
                Label(l10n("action.record"), systemImage: "record.circle")
                    .frame(maxWidth: .infinity, minHeight: 56)
            }
            .buttonStyle(.borderedProminent)
            .tint(.red)
            .accessibilityHint(l10n("hint.record"))

            Button {
                showingImporter = true
            } label: {
                Label(l10n("action.import"), systemImage: "square.and.arrow.down")
                    .frame(maxWidth: .infinity, minHeight: 56)
            }
            .buttonStyle(.borderedProminent)
            .accessibilityHint(l10n("hint.import"))
        }
        .disabled(model.activity != .idle)
    }

    @ToolbarContentBuilder
    private var toolbar: some ToolbarContent {
        ToolbarItemGroup(placement: .navigationBarLeading) {
            Button {
                showingProjects = true
            } label: {
                Label(l10n("action.projects"), systemImage: "folder")
            }
            Menu {
                ForEach(L10n.Language.allCases) { language in
                    Button {
                        l10n.setLanguage(language)
                    } label: {
                        if language == l10n.language {
                            Label(language.nativeName, systemImage: "checkmark")
                        } else {
                            Text(language.nativeName)
                        }
                    }
                }
            } label: {
                Label(l10n("action.language"), systemImage: "globe")
            }
            .accessibilityValue(l10n.language.nativeName)
        }
        ToolbarItemGroup(placement: .navigationBarTrailing) {
            if model.document != nil {
                Button {
                    model.undo()
                } label: {
                    Label(model.undoLabel, systemImage: "arrow.uturn.backward")
                }
                .disabled(!model.canUndo)
                .keyboardShortcut("z", modifiers: .command)

                Button {
                    model.redo()
                } label: {
                    Label(model.redoLabel, systemImage: "arrow.uturn.forward")
                }
                .disabled(!model.canRedo)
                .keyboardShortcut("z", modifiers: [.command, .shift])

                Menu {
                    Button {
                        pendingName = model.document?.name ?? ""
                        showingRename = true
                    } label: { Label(l10n("action.rename"), systemImage: "pencil") }
                    Button {
                        model.duplicateCurrentProject()
                    } label: { Label(l10n("action.duplicate"), systemImage: "plus.square.on.square") }
                    Button {
                        model.newProject()
                    } label: { Label(l10n("action.newproject"), systemImage: "doc.badge.plus") }
                } label: {
                    Label(l10n("action.projectmenu"), systemImage: "ellipsis.circle")
                }
            }
        }
    }
}

// MARK: - Pieces

struct RecoveryBanner: View {
    @EnvironmentObject private var l10n: L10n
    let text: String
    let dismiss: () -> Void

    var body: some View {
        HStack(alignment: .top) {
            Image(systemName: "lifepreserver").accessibilityHidden(true)
            Text(text).frame(maxWidth: .infinity, alignment: .leading)
            Button(l10n("action.close"), action: dismiss)
        }
        .padding()
        .background(RoundedRectangle(cornerRadius: 12).fill(Color.yellow.opacity(0.18)))
        .accessibilityElement(children: .contain)
    }
}

struct ProgressSection: View {
    @EnvironmentObject private var l10n: L10n
    let fraction: Double

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(l10n("progress.analyzing")).font(.headline).accessibilityAddTraits(.isHeader)
            ProgressView(value: fraction)
                .accessibilityLabel(l10n("progress.analyzing"))
                .accessibilityValue(l10n.percent(fraction))
            Text(l10n.percent(fraction)).font(.title3.monospacedDigit()).accessibilityHidden(true)
        }
    }
}

struct RecordingPanel: View {
    @EnvironmentObject private var l10n: L10n
    @EnvironmentObject private var model: AppModel

    var body: some View {
        VStack(spacing: 18) {
            Text(l10n("recording.title")).font(.title2.bold()).accessibilityAddTraits(.isHeader)
            Text(l10n.clock(model.recordingSeconds))
                .font(.system(size: 48, weight: .semibold, design: .rounded).monospacedDigit())
                .accessibilityLabel(l10n("recording.elapsed"))
                .accessibilityValue(l10n.spokenDuration(model.recordingSeconds))
            LevelMeter(level: model.inputLevel)
            Button {
                model.stopRecording()
            } label: {
                Label(l10n("action.stoprecording"), systemImage: "stop.circle.fill")
                    .frame(maxWidth: .infinity, minHeight: 64)
            }
            .buttonStyle(.borderedProminent)
            .tint(.red)
            .disabled(model.activity == .finishingRecording)
        }
        .frame(maxWidth: .infinity)
    }
}

struct LevelMeter: View {
    @EnvironmentObject private var l10n: L10n
    let level: AudioEngine.InputLevel

    private var fraction: Double { min(1, max(0, (level.peakDbfs + 60) / 60)) }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            GeometryReader { geometry in
                ZStack(alignment: .leading) {
                    Capsule().fill(Color(.tertiarySystemFill))
                    Capsule()
                        .fill(level.clipped ? Color.red : (level.peakDbfs > -6 ? Color.orange : Color.green))
                        .frame(width: geometry.size.width * fraction)
                }
            }
            .frame(height: 14)
            Text(level.clipped ? l10n("level.clipped") : l10n("level.ok"))
                .font(.footnote)
                .foregroundStyle(level.clipped ? .red : .secondary)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(l10n("level.label"))
        .accessibilityValue(level.clipped
            ? l10n("level.clipped")
            : l10n("level.value", l10n.decibels(level.peakDbfs)))
    }
}

struct TransportView: View {
    @EnvironmentObject private var l10n: L10n
    @EnvironmentObject private var model: AppModel

    var body: some View {
        VStack(spacing: 12) {
            HStack {
                Text(l10n.clock(model.position)).monospacedDigit()
                Spacer()
                Text(l10n.clock(model.duration)).monospacedDigit().foregroundStyle(.secondary)
            }
            .font(.callout)
            .accessibilityHidden(true)

            Slider(value: Binding(get: { model.position }, set: { model.seek(to: $0) }),
                   in: 0...max(model.duration, 0.01))
                .accessibilityLabel(l10n("transport.position"))
                .accessibilityValue(l10n("waveform.value", l10n.spokenDuration(model.position),
                                         l10n.spokenDuration(model.duration)))

            HStack(spacing: 14) {
                Button { model.skip(by: -5) } label: {
                    Label(l10n("action.back5"), systemImage: "gobackward.5").labelStyle(.iconOnly)
                        .frame(minWidth: 52, minHeight: 52)
                }
                .accessibilityLabel(l10n("action.back5"))

                Button { model.togglePlayback() } label: {
                    Label(model.playbackState == .playing ? l10n("action.pause") : l10n("action.play"),
                          systemImage: model.playbackState == .playing ? "pause.fill" : "play.fill")
                        .labelStyle(.iconOnly)
                        .font(.title)
                        .frame(minWidth: 72, minHeight: 72)
                }
                .buttonStyle(.borderedProminent)
                .clipShape(Circle())
                .accessibilityLabel(model.playbackState == .playing ? l10n("action.pause") : l10n("action.play"))
                .keyboardShortcut(.space, modifiers: [])

                Button { model.stopPlayback() } label: {
                    Label(l10n("action.stop"), systemImage: "stop.fill").labelStyle(.iconOnly)
                        .frame(minWidth: 52, minHeight: 52)
                }
                .accessibilityLabel(l10n("action.stop"))

                Button { model.skip(by: 5) } label: {
                    Label(l10n("action.forward5"), systemImage: "goforward.5").labelStyle(.iconOnly)
                        .frame(minWidth: 52, minHeight: 52)
                }
                .accessibilityLabel(l10n("action.forward5"))
            }
            .font(.title3)
        }
    }
}

struct AnalysisSummaryView: View {
    @EnvironmentObject private var l10n: L10n
    let levels: LevelSummary
    let original: OriginalAudio

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(l10n("analysis.title")).font(.headline).accessibilityAddTraits(.isHeader)
            row("analysis.duration", l10n.spokenDuration(original.duration))
            row("analysis.format", "\(original.sourceFormat), "
                + (original.channels == 1 ? l10n("analysis.mono") : l10n("analysis.stereo", l10n.number(Double(original.channels)))))
            row("analysis.peak", l10n.decibels(levels.peakDbfs))
            row("analysis.rms", l10n.decibels(levels.rmsDbfs))
            row("analysis.noisefloor", l10n.decibels(levels.noiseFloorDbfs))
            row("analysis.dynamicrange", l10n("units.decibels", l10n.number(levels.dynamicRangeDb, fractionDigits: 1)))
            row("analysis.clipping", levels.isClipping
                ? l10n("analysis.clipping.yes", l10n.number(Double(levels.clippedSamples)))
                : l10n("analysis.clipping.no"))
            if abs(levels.dcOffset) > 0.01 {
                row("analysis.dcoffset", l10n.number(levels.dcOffset, fractionDigits: 3))
            }
        }
        .padding()
        .background(RoundedRectangle(cornerRadius: 14).fill(Color(.secondarySystemBackground)))
    }

    private func row(_ key: String, _ value: String) -> some View {
        HStack(alignment: .firstTextBaseline) {
            Text(l10n(key)).foregroundStyle(.secondary)
            Spacer()
            Text(value).multilineTextAlignment(.trailing)
        }
        .accessibilityElement(children: .combine)
    }
}

struct MaqamSummaryView: View {
    @EnvironmentObject private var l10n: L10n
    @EnvironmentObject private var model: AppModel
    let choose: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(l10n("maqam.title")).font(.headline).accessibilityAddTraits(.isHeader)
            if let maqam = model.currentMaqam {
                Text(maqam.name(l10n)).font(.title3.bold())
                if let tonic = model.document?.maqam.tonicHz {
                    Text(l10n("maqam.tonic.value", PitchNaming.label(hz: tonic, l10n: l10n),
                              l10n.number(tonic, fractionDigits: 1)))
                        .foregroundStyle(.secondary)
                }
                Text(MaqamText.degrees(maqam, l10n: l10n)).font(.footnote).foregroundStyle(.secondary)
            } else {
                Text(l10n("maqam.none")).foregroundStyle(.secondary)
            }
            Button(action: choose) {
                Label(model.currentMaqam == nil ? l10n("action.choosemaqam") : l10n("action.changemaqam"),
                      systemImage: "music.quarternote.3")
                    .frame(maxWidth: .infinity, minHeight: 48)
            }
            .buttonStyle(.bordered)
        }
        .padding()
        .background(RoundedRectangle(cornerRadius: 14).fill(Color(.secondarySystemBackground)))
    }
}

/// Text forms of maqam data, used both on screen and by VoiceOver.
enum MaqamText {
    static func degrees(_ maqam: MaqamDefinition, l10n: L10n) -> String {
        let values = maqam.degrees.map { l10n.number($0.cents) }
        return l10n("maqam.degrees", values.joined(separator: l10n.listSeparator))
    }
}
