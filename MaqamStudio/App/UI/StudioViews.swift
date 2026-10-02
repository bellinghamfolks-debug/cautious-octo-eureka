import SwiftUI

/// Original, tuned, studio, or the separated vocal and music: switched at
/// the same moment of the song.
struct ListeningPicker: View {
    @EnvironmentObject private var l10n: L10n
    @EnvironmentObject private var model: AppModel

    var body: some View {
        let sources = AppModel.ListeningSource.allCases.filter { model.canListen(to: $0) }
        if sources.count > 1 {
            let picker = Picker(l10n("listen.label"), selection: Binding(get: { model.listening }, set: { model.setListening($0) })) {
                ForEach(sources) { source in
                    Text(l10n("listen." + source.rawValue)).tag(source)
                }
            }
            Group {
                // Segments while they fit, a menu beyond three versions.
                if sources.count <= 3 { picker.pickerStyle(.segmented) } else { picker.pickerStyle(.menu) }
            }
            .accessibilityLabel(l10n("listen.label"))
            .accessibilityHint(l10n("listen.hint"))
        }
    }
}

/// The AUTO STUDIO card: style, one big button, the result, and what was done.
struct StudioSection: View {
    @EnvironmentObject private var l10n: L10n
    @EnvironmentObject private var model: AppModel
    @State private var showingDecisions = false
    @State private var showingPro = false

    private var settings: StudioSettings { model.studioSettings }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(l10n("studio.title")).font(.headline).accessibilityAddTraits(.isHeader)
            Picker(l10n("studio.profile"), selection: Binding(get: { settings.profile }, set: { model.setStudioProfile($0) })) {
                ForEach(GenreProfile.allCases) { profile in
                    Text(l10n(profile.key)).tag(profile)
                }
            }
            .pickerStyle(.menu)
            .accessibilityLabel(l10n("studio.profile"))
            Text(l10n(settings.profile.key + ".about")).font(.footnote).foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)

            Button {
                model.runAutoStudio()
            } label: {
                Label(l10n("action.autostudio"), systemImage: "sparkles")
                    .font(.title3.bold())
                    .frame(maxWidth: .infinity, minHeight: 64)
            }
            .buttonStyle(.borderedProminent)
            .disabled(model.activity != .idle || model.pitch == nil || model.studioIsFresh)
            .accessibilityHint(l10n(model.canTune ? "hint.autostudio" : "hint.autostudio.notuning"))

            Toggle(l10n("studio.usetuned"), isOn: Binding(get: { settings.useTuned }, set: { model.setStudioUsesTuned($0) }))
                .disabled(!model.canTune)
            if settings.plan != nil {
                HStack {
                    Label(l10n("studio.custom"), systemImage: "slider.horizontal.3").font(.footnote)
                    Spacer()
                    Button(l10n("action.studioautomatic")) { model.resetStudioToAutomatic() }.font(.footnote)
                }
            }

            if let render = model.studioRender {
                if model.studioIsFresh {
                    Text(model.studioResultText).font(.subheadline.bold()).fixedSize(horizontal: false, vertical: true)
                    if render.usedTuned { Text(l10n("studio.usedtuned")).font(.footnote).foregroundStyle(.secondary) }
                } else {
                    Label(l10n("studio.stale"), systemImage: "arrow.triangle.2.circlepath").foregroundStyle(.orange)
                }
                HStack {
                    Button(l10n("action.decisions")) { showingDecisions = true }
                    Spacer()
                    Button(l10n("action.promode")) { showingPro = true }
                }
                .buttonStyle(.bordered)
            }
        }
        .padding()
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 14).fill(Color(.secondarySystemBackground)))
        .sheet(isPresented: $showingDecisions) { DecisionsView() }
        .sheet(isPresented: $showingPro) { ProStudioView() }
    }
}

/// What Auto Studio found and did, one sentence per decision.
struct DecisionsView: View {
    @EnvironmentObject private var l10n: L10n
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                if let render = model.studioRender {
                    Section {
                        Text(StudioText.measurements(render.before, l10n: l10n))
                    } header: {
                        Text(l10n("decisions.found"))
                    }
                    Section {
                        ForEach(Array(render.reasons.enumerated()), id: \.offset) { _, reason in
                            Text(StudioText.reason(reason, l10n: l10n))
                        }
                    } header: {
                        Text(l10n("decisions.done"))
                    } footer: {
                        Text(l10n(render.usedPlan == render.automaticPlan ? "decisions.automatic" : "decisions.custom"))
                    }
                    Section {
                        Text(model.studioResultText)
                    } header: {
                        Text(l10n("decisions.result"))
                    }
                }
            }
            .navigationTitle(l10n("decisions.title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button(l10n("action.done")) { dismiss() } } }
        }
        .environment(\.locale, l10n.locale)
        .environment(\.layoutDirection, l10n.layoutDirection)
    }
}

/// Every stage of the chain, adjustable. Starts from the automatic plan;
/// any change makes the plan custom (undoable, and "Back to automatic").
struct ProStudioView: View {
    @EnvironmentObject private var l10n: L10n
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Group {
                if let plan = model.studioPlanForEditing {
                    Form { sections(plan) }
                } else {
                    Text(l10n("pro.needsrender")).padding()
                }
            }
            .navigationTitle(l10n("pro.title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button(l10n("action.done")) { dismiss() } }
                ToolbarItem(placement: .cancellationAction) {
                    Button(l10n("action.studioautomatic")) { model.resetStudioToAutomatic() }
                        .disabled(model.studioSettings.plan == nil)
                }
            }
        }
        .environment(\.locale, l10n.locale)
        .environment(\.layoutDirection, l10n.layoutDirection)
    }

    @ViewBuilder
    private func sections(_ plan: StudioPlanValues) -> some View {
        Section(l10n("pro.cleanup")) {
            value("pro.highpass", plan.highPassHz, 0...300, 10, unit: "units.hertz") { $0.highPassHz = $1 }
            toggle("pro.hum", plan.humHz > 0) { $0.humHz = $1 ? (plan.humHz > 0 ? plan.humHz : 50) : 0; $0.humHarmonics = $1 ? 4 : 0 }
            value("pro.denoise", plan.denoiseDb, 0...24, 1, unit: "units.decibels") { $0.denoiseDb = $1 }
            value("pro.plosive", plan.plosiveDb, 0...24, 1, unit: "units.decibels") { $0.plosiveDb = $1 }
            value("pro.breath", plan.breathDb, 0...24, 1, unit: "units.decibels") { $0.breathDb = $1 }
            toggle("pro.leveler", plan.leveler) { $0.leveler = $1 }
        }
        Section(l10n("pro.tone")) {
            ForEach(Array(plan.eq.enumerated()), id: \.offset) { index, band in
                value(StudioText.bandName(band, l10n: l10n), band.gainDb, -12...12, 0.5, unit: "units.decibels", literal: true) {
                    $0.eq[index].gainDb = $1
                }
            }
            toggle("pro.deesser", plan.deEsserOn) { $0.deEsserOn = $1 }
            value("pro.deesser.cut", plan.deEsserMaximumCutDb, 0...15, 1, unit: "units.decibels") { $0.deEsserMaximumCutDb = $1 }
            toggle("pro.harshness", plan.harshnessOn) { $0.harshnessOn = $1 }
        }
        Section(l10n("pro.dynamics")) {
            value("pro.compressor.ratio", plan.compressorRatio, 1...10, 0.5, unit: "units.ratio") { $0.compressorRatio = $1 }
            value("pro.compressor.threshold", plan.compressorThresholdDb, -50...0, 1, unit: "units.decibels") { $0.compressorThresholdDb = $1 }
            toggle("pro.multiband", plan.multiband) { $0.multiband = $1 }
            value("pro.saturation", plan.saturationMix, 0...1, 0.05, unit: "units.percent") { $0.saturationMix = $1 }
            value("pro.exciter", plan.exciterAmount, 0...0.5, 0.05, unit: "units.percent") { $0.exciterAmount = $1 }
        }
        Section(l10n("pro.space")) {
            value("pro.reverb", plan.reverbMix, 0...0.6, 0.02, unit: "units.percent") { $0.reverbMix = $1 }
            value("pro.reverb.size", plan.reverbSize, 0...1, 0.05, unit: "units.percent") { $0.reverbSize = $1 }
            value("pro.delay", plan.delayMix, 0...0.4, 0.02, unit: "units.percent") { $0.delayMix = $1 }
            value("pro.delay.time", plan.delayMs, 50...800, 10, unit: "units.ms") { $0.delayMs = $1 }
            value("pro.delay.feedback", plan.delayFeedback, 0...0.8, 0.05, unit: "units.percent") { $0.delayFeedback = $1 }
        }
        Section(l10n("pro.finish")) {
            value("pro.loudness", plan.loudnessTargetLufs, -24...(-8), 0.5, unit: "units.lufs") { $0.loudnessTargetLufs = $1 }
            value("pro.ceiling", plan.ceilingDb, -6...(-0.5), 0.1, unit: "units.decibels") { $0.ceilingDb = $1 }
        }
    }

    private func change(_ apply: (inout StudioPlanValues) -> Void) {
        guard var plan = model.studioPlanForEditing else { return }
        apply(&plan)
        model.setStudioPlan(plan)
    }

    private func toggle(_ key: String, _ isOn: Bool, _ apply: @escaping (inout StudioPlanValues, Bool) -> Void) -> some View {
        Toggle(l10n(key), isOn: Binding(get: { isOn }, set: { newValue in change { apply(&$0, newValue) } }))
    }

    private func value(_ title: String, _ current: Double, _ range: ClosedRange<Double>, _ step: Double, unit: String,
                       literal: Bool = false, _ apply: @escaping (inout StudioPlanValues, Double) -> Void) -> some View {
        let name = literal ? title : l10n(title)
        let spoken = StudioText.value(current, unit: unit, l10n: l10n)
        return Stepper(onIncrement: { change { apply(&$0, min(range.upperBound, current + step)) } },
                       onDecrement: { change { apply(&$0, max(range.lowerBound, current - step)) } }) {
            HStack {
                Text(name)
                Spacer()
                Text(spoken).foregroundStyle(.secondary).monospacedDigit()
            }
        }
        .accessibilityLabel(name)
        .accessibilityValue(spoken)
    }
}

/// Studio results and decisions in words.
enum StudioText {
    static func value(_ value: Double, unit: String, l10n: L10n) -> String {
        switch unit {
        case "units.percent": return l10n.percent(value)
        case "units.ratio": return l10n("units.ratio", l10n.number(value, fractionDigits: 1))
        case "units.lufs": return l10n("units.lufs", l10n.number(value, fractionDigits: 1))
        case "units.hertz", "units.ms": return l10n(unit, l10n.number(value))
        default: return l10n(unit, l10n.number(value, fractionDigits: 1))
        }
    }

    static func bandName(_ band: StudioEqBand, l10n: L10n) -> String {
        let hz = l10n.number(band.frequencyHz.rounded())
        switch band.kind {
        case .lowShelf: return l10n("eq.lowshelf", hz)
        case .highShelf: return l10n("eq.highshelf", hz)
        case .peaking: return l10n("eq.peak", hz)
        }
    }

    static func measurements(_ m: StudioMeasurements, l10n: L10n) -> String {
        var parts = [l10n("found.loudness", l10n.number(m.integratedLufs, fractionDigits: 1),
                          l10n.number(m.peakDbfs, fractionDigits: 1)),
                     l10n("found.noise", l10n.number(m.noiseFloorDbfs.rounded()), l10n.number(m.voiceLevelDbfs.rounded()))]
        if m.humHz > 0 { parts.append(l10n("found.hum", l10n.number(m.humHz))) }
        if m.breathCount > 0 { parts.append(l10n("found.breaths", l10n.number(Double(m.breathCount)))) }
        if m.clippedSamples > 0 { parts.append(l10n("found.clipped", l10n.number(Double(m.clippedSamples)))) }
        return parts.joined(separator: " ")
    }

    /// One decision as a sentence; codes match MQ_REASON_* in the core.
    static func reason(_ reason: StudioReason, l10n: L10n) -> String {
        let a = reason.a, b = reason.b
        func n(_ value: Double, _ digits: Int = 0) -> String { l10n.number(value, fractionDigits: digits) }
        switch reason.code {
        case 1: return l10n("reason.highpass", n(a))
        case 2: return l10n("reason.rumble", n(a), n(b))
        case 3: return l10n("reason.hum", n(a), n(b))
        case 4: return l10n("reason.nohum")
        case 5: return l10n("reason.denoise", n(a), n(b))
        case 6: return l10n("reason.clean", n(a))
        case 7: return l10n("reason.plosives")
        case 8: return l10n("reason.breaths", n(a), n(b))
        case 9: return l10n("reason.nobreaths")
        case 10: return l10n("reason.leveler", n(a))
        case 11: return l10n("reason.steady", n(a))
        case 12: return l10n("reason.mud", n(a, 1))
        case 13: return l10n("reason.boxy", n(a, 1))
        case 14: return l10n("reason.harsh")
        case 15: return l10n("reason.air", n(a, 1))
        case 16: return l10n("reason.resonance", n(a), n(b, 1))
        case 17: return l10n("reason.deess", n(a))
        case 18: return l10n("reason.deesslight")
        case 19: return l10n("reason.compress", n(a, 1), n(b))
        case 20: return l10n("reason.multiband")
        case 21: return l10n("reason.colour", n(a))
        case 22: return l10n("reason.reverb", l10n.percent(a), l10n.percent(b))
        case 23: return l10n("reason.delay", n(a), l10n.percent(b))
        case 24: return l10n("reason.loudness", n(a, 1), n(b, 1))
        case 25: return l10n("reason.limiter", n(a, 1))
        case 26: return l10n("reason.clipped", n(a))
        case 27: return l10n("reason.tonal", n(a, 1), n(b, 1))
        default: return l10n("reason.unknown")
        }
    }
}
