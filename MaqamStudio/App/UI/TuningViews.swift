import SwiftUI

/// Pitch correction: choose how, apply, compare.
///
/// Every control speaks its value in words; the result is reported as measured
/// accuracy before and after, and the two versions can be switched while
/// listening, at the same moment of the song.
struct TuningSection: View {
    @EnvironmentObject private var l10n: L10n
    @EnvironmentObject private var model: AppModel
    let chooseMaqam: () -> Void
    @State private var showingAdvanced = false

    private var settings: PitchCorrectionSettings { model.tuningSettings }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(l10n("tuning.section")).font(.headline).accessibilityAddTraits(.isHeader)
            if !model.canTune {
                Text(l10n(model.pitch?.notes.isEmpty ?? true ? "tuning.nonotes" : "tuning.needsmaqam"))
                    .foregroundStyle(.secondary)
                if model.effectiveMaqam == nil {
                    Button(l10n("action.choosemaqam"), action: chooseMaqam).buttonStyle(.bordered)
                }
            } else {
                Picker(l10n("tuning.preset"), selection: Binding(get: { settings.preset },
                                                                 set: { model.chooseTuningPreset($0) })) {
                    ForEach(PitchCorrectionSettings.Preset.allCases) { preset in
                        Text(l10n("preset." + preset.rawValue)).tag(preset)
                    }
                }
                .pickerStyle(.segmented)
                .accessibilityLabel(l10n("tuning.preset"))
                Text(l10n("preset." + settings.preset.rawValue + ".about")).font(.footnote).foregroundStyle(.secondary)

                DisclosureGroup(isExpanded: $showingAdvanced) {
                    AdvancedTuningControls()
                } label: {
                    Text(l10n("tuning.advanced"))
                }

                if !settings.noteOverrides.isEmpty {
                    Text(l10n("tuning.overrides", l10n.number(Double(settings.noteOverrides.count))))
                        .font(.footnote).foregroundStyle(.secondary)
                }

                Button {
                    model.renderTuning()
                } label: {
                    Label(l10n(model.tuningRender == nil ? "action.applytuning" : "action.reapplytuning"),
                          systemImage: "wand.and.stars")
                        .frame(maxWidth: .infinity, minHeight: 52)
                }
                .buttonStyle(.borderedProminent)
                .disabled(model.activity != .idle || model.tuningIsFresh)
                .accessibilityHint(l10n("hint.applytuning"))

                status
            }
        }
        .padding()
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 14).fill(Color(.secondarySystemBackground)))
    }

    @ViewBuilder
    private var status: some View {
        if model.tuningRender != nil {
            if model.tuningIsFresh {
                Text(model.tuningResultText).font(.subheadline.bold()).fixedSize(horizontal: false, vertical: true)
                Picker(l10n("listen.label"), selection: Binding(get: { model.listeningToTuned },
                                                                set: { model.setListening(tuned: $0) })) {
                    Text(l10n("listen.original")).tag(false)
                    Text(l10n("listen.tuned")).tag(true)
                }
                .pickerStyle(.segmented)
                .accessibilityLabel(l10n("listen.label"))
                .accessibilityHint(l10n("listen.hint"))
            } else {
                Label(l10n("tuning.stale"), systemImage: "arrow.triangle.2.circlepath")
                    .foregroundStyle(.orange)
            }
        }
    }
}

/// The individual settings behind the presets. Changing any of them makes the
/// preset "Custom"; each change is one undo step.
struct AdvancedTuningControls: View {
    @EnvironmentObject private var l10n: L10n
    @EnvironmentObject private var model: AppModel

    private var settings: PitchCorrectionSettings { model.tuningSettings }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            slider("control.retune", value: settings.retuneMs, range: 0...200, step: 5,
                   spoken: l10n("units.ms", l10n.number(settings.retuneMs)), hint: "control.retune.hint") {
                model.setTuning(\.retuneMs, to: $0)
            }
            slider("control.strength", value: settings.strength, range: 0...1, step: 0.05,
                   spoken: l10n.percent(settings.strength), hint: "control.strength.hint") {
                model.setTuning(\.strength, to: $0)
            }
            slider("control.humanize", value: settings.humanize, range: 0...1, step: 0.05,
                   spoken: l10n.percent(settings.humanize), hint: "control.humanize.hint") {
                model.setTuning(\.humanize, to: $0)
            }
            slider("control.vibrato", value: settings.vibratoAmount, range: 0...1.5, step: 0.05,
                   spoken: l10n.percent(settings.vibratoAmount), hint: "control.vibrato.hint") {
                model.setTuning(\.vibratoAmount, to: $0)
            }
            Toggle(l10n("control.vibratorate.change"), isOn: Binding(
                get: { settings.vibratoRateHz > 0 },
                set: { model.setTuning(\.vibratoRateHz, to: $0 ? 5.5 : 0) }))
            if settings.vibratoRateHz > 0 {
                slider("control.vibratorate", value: settings.vibratoRateHz, range: 3.5...8, step: 0.25,
                       spoken: l10n("units.hertz", l10n.number(settings.vibratoRateHz, fractionDigits: 2)),
                       hint: "control.vibratorate.hint") {
                    model.setTuning(\.vibratoRateHz, to: $0)
                }
            }
            slider("control.transitions", value: settings.transitionSensitivity, range: 0...1, step: 0.05,
                   spoken: l10n.percent(settings.transitionSensitivity), hint: "control.transitions.hint") {
                model.setTuning(\.transitionSensitivity, to: $0)
            }
            slider("control.drift", value: settings.driftCorrection, range: 0...1, step: 0.05,
                   spoken: l10n.percent(settings.driftCorrection), hint: "control.drift.hint") {
                model.setTuning(\.driftCorrection, to: $0)
            }
            slider("control.smoothing", value: settings.smoothingMs, range: 0...60, step: 5,
                   spoken: l10n("units.ms", l10n.number(settings.smoothingMs)), hint: "control.smoothing.hint") {
                model.setTuning(\.smoothingMs, to: $0)
            }
            Toggle(l10n("control.formants"), isOn: Binding(get: { settings.preserveFormants },
                                                            set: { model.setTuning(\.preserveFormants, to: $0) }))
                .accessibilityHint(l10n("control.formants.hint"))
            slider("control.formantshift", value: settings.formantShiftCents, range: -600...600, step: 25,
                   spoken: l10n("units.cents", l10n.number(settings.formantShiftCents)), hint: "control.formantshift.hint") {
                model.setTuning(\.formantShiftCents, to: $0)
            }
        }
        .padding(.top, 8)
    }

    /// A slider that commits when the finger lifts (one undo step), while
    /// VoiceOver's swipes adjust it a step at a time.
    private func slider(_ key: String, value: Double, range: ClosedRange<Double>, step: Double, spoken: String,
                        hint: String, commit: @escaping (Double) -> Void) -> some View {
        TuningSlider(title: l10n(key), value: value, range: range, step: step, spoken: spoken, hint: l10n(hint),
                     commit: commit)
    }
}

private struct TuningSlider: View {
    let title: String
    let value: Double
    let range: ClosedRange<Double>
    let step: Double
    let spoken: String
    let hint: String
    let commit: (Double) -> Void
    @State private var draft: Double?

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(title)
                Spacer()
                Text(spoken).foregroundStyle(.secondary).monospacedDigit()
            }
            .accessibilityHidden(true)
            Slider(value: Binding(get: { draft ?? value }, set: { draft = $0 }), in: range, step: step) { editing in
                if !editing, let draft {
                    commit(draft)
                    self.draft = nil
                }
            }
            .accessibilityLabel(title)
            .accessibilityValue(spoken)
            .accessibilityHint(hint)
            .accessibilityAdjustableAction { direction in
                switch direction {
                case .increment: commit(min(range.upperBound, value + step))
                case .decrement: commit(max(range.lowerBound, value - step))
                @unknown default: break
                }
            }
        }
    }
}
