import Accessibility
import SwiftUI

/// The sung pitch over time, against the maqam's own targets.
///
/// Horizontal lines are the maqam's degrees on the chosen tonic (quarter tones
/// where the maqam has them), the curve is the voice, and each found note is a
/// bar marked in tune or not. For VoiceOver it is one element that reads the
/// note under the playhead and moves note by note with a swipe up or down; it
/// is also an Audio Graph of the notes. A text list of every note is one tap
/// away, so nothing here has to be seen.
struct PitchCurveView: View {
    @EnvironmentObject private var l10n: L10n
    let pitch: PitchAnalysis
    let intonation: Intonation?
    let maqam: MaqamDefinition?
    let tonicHz: Double?
    let duration: Double
    let position: Double
    let onSeek: (Double) -> Void

    /// Seconds shown at once; the window follows the playhead.
    static let windowSeconds = 10.0

    private var reference: Double { tonicHz ?? 261.6256 }

    private var window: ClosedRange<Double> {
        guard duration > Self.windowSeconds else { return 0...max(duration, 0.001) }
        let start = min(max(0, position - 2), duration - Self.windowSeconds)
        return start...(start + Self.windowSeconds)
    }

    var body: some View {
        GeometryReader { geometry in
            Canvas { context, size in draw(in: &context, size: size) }
                .contentShape(Rectangle())
                .gesture(DragGesture(minimumDistance: 0).onEnded { value in
                    guard geometry.size.width > 0 else { return }
                    var fraction = value.location.x / geometry.size.width
                    if l10n.layoutDirection == .rightToLeft { fraction = 1 - fraction }
                    let span = window.upperBound - window.lowerBound
                    onSeek(window.lowerBound + min(max(0, fraction), 1) * span)
                })
        }
        .frame(height: 210)
        .background(RoundedRectangle(cornerRadius: 14).fill(Color(.secondarySystemBackground)))
        .accessibilityElement()
        .accessibilityLabel(l10n("curve.label"))
        .accessibilityValue(spokenValue)
        .accessibilityHint(l10n("curve.hint"))
        .accessibilityAdjustableAction { direction in
            switch direction {
            case .increment: if let next = nextNote { onSeek(next.start) }
            case .decrement: if let previous = previousNote { onSeek(previous.start) }
            @unknown default: break
            }
        }
        .accessibilityChartDescriptor(NotesChart(pitch: pitch, reference: reference, duration: duration,
                                                 hasTonic: tonicHz != nil, l10n: l10n))
    }

    // MARK: Navigation

    private var currentNote: SungNote? {
        pitch.note(at: position) ?? pitch.notes.first { $0.start >= position }
    }

    private var nextNote: SungNote? {
        pitch.notes.first { $0.start > position + 0.01 }
    }

    private var previousNote: SungNote? {
        if let current = pitch.note(at: position), position - current.start > 0.3 { return current }
        return pitch.notes.last { $0.start < position - 0.05 }
    }

    private var spokenValue: String {
        guard let note = currentNote, let index = pitch.notes.firstIndex(of: note) else {
            return l10n("curve.nonotes")
        }
        return l10n("curve.value", l10n.number(Double(index + 1)), l10n.number(Double(pitch.notes.count)),
                    PitchText.note(note, result: intonation?.result(for: note), l10n: l10n),
                    l10n.spokenDuration(note.start))
    }

    // MARK: Drawing

    private func draw(in context: inout GraphicsContext, size: CGSize) {
        let visible = window
        let span = visible.upperBound - visible.lowerBound
        guard span > 0 else { return }
        let rightToLeft = l10n.layoutDirection == .rightToLeft
        let frames = pitch.track.indices(in: visible)

        // Vertical range from the voice itself, padded, at least an octave half.
        var low = Double.infinity
        var high = -Double.infinity
        for index in frames where pitch.track.hz[index] > 0 {
            let cents = mq_hz_to_cents(Double(pitch.track.hz[index]), reference)
            low = min(low, cents)
            high = max(high, cents)
        }
        if !low.isFinite { low = -300; high = 900 }
        let middle = (low + high) / 2
        let half = max(350, (high - low) / 2 + 100)
        let bottom = middle - half
        let top = middle + half

        func x(_ seconds: Double) -> CGFloat {
            let fraction = CGFloat((seconds - visible.lowerBound) / span)
            return (rightToLeft ? 1 - fraction : fraction) * size.width
        }
        func y(_ cents: Double) -> CGFloat {
            size.height * CGFloat(1 - (cents - bottom) / (top - bottom))
        }

        // The maqam's targets in every octave on screen.
        if let maqam, tonicHz != nil {
            let firstOctave = Int((bottom / 1200).rounded(.down))
            let lastOctave = Int((top / 1200).rounded(.up))
            for octave in firstOctave...lastOctave {
                for (index, degree) in maqam.degrees.enumerated() {
                    let cents = Double(octave) * 1200 + degree.cents
                    guard cents >= bottom, cents <= top else { continue }
                    var line = Path()
                    line.move(to: CGPoint(x: 0, y: y(cents)))
                    line.addLine(to: CGPoint(x: size.width, y: y(cents)))
                    context.stroke(line, with: .color(.secondary.opacity(index == 0 ? 0.6 : 0.25)),
                                   lineWidth: index == 0 ? 1.5 : 1)
                    let label = Text(PitchText.targetName(cents: cents, tonicHz: reference, l10n: l10n))
                        .font(.caption2).foregroundColor(.secondary)
                    context.draw(label, at: CGPoint(x: rightToLeft ? size.width - 4 : 4, y: y(cents) - 7),
                                 anchor: rightToLeft ? .trailing : .leading)
                }
            }
        }

        // Found notes, marked in tune (green) or not (orange); the list says the same in words.
        for note in pitch.notes where note.end >= visible.lowerBound && note.start <= visible.upperBound {
            let cents = mq_hz_to_cents(note.hz, reference)
            let colour: Color
            if let result = intonation?.result(for: note) { colour = result.inTune ? .green : .orange } else { colour = .gray }
            let left = min(x(note.start), x(note.end))
            let width = abs(x(note.end) - x(note.start))
            context.fill(Path(roundedRect: CGRect(x: left, y: y(cents) - 3, width: max(2, width), height: 6), cornerRadius: 3),
                         with: .color(colour.opacity(0.55)))
        }

        // The voice, broken where it is unvoiced.
        var curve = Path()
        var drawing = false
        for index in frames {
            let hz = pitch.track.hz[index]
            guard hz > 0 else { drawing = false; continue }
            let point = CGPoint(x: x(pitch.track.time(at: index)), y: y(mq_hz_to_cents(Double(hz), reference)))
            if drawing { curve.addLine(to: point) } else { curve.move(to: point); drawing = true }
        }
        context.stroke(curve, with: .color(.accentColor), lineWidth: 2)

        if visible.contains(position) {
            var head = Path()
            head.move(to: CGPoint(x: x(position), y: 0))
            head.addLine(to: CGPoint(x: x(position), y: size.height))
            context.stroke(head, with: .color(.primary), lineWidth: 2)
        }
    }
}

/// The notes as an Audio Graph: one point per note, pitch in cents from the
/// tonic (or from middle C before a tonic is chosen) over time.
struct NotesChart: AXChartDescriptorRepresentable {
    let pitch: PitchAnalysis
    let reference: Double
    let duration: Double
    let hasTonic: Bool
    let l10n: L10n

    func makeChartDescriptor() -> AXChartDescriptor {
        let l10n = self.l10n
        let values = pitch.notes.map { mq_hz_to_cents($0.hz, reference) }
        let low = (values.min() ?? -100) - 100
        let high = (values.max() ?? 100) + 100
        let xAxis = AXNumericDataAxisDescriptor(title: l10n("chart.time"), range: 0...max(duration, 0.001),
                                                gridlinePositions: []) { l10n.spokenDuration($0) }
        let yAxis = AXNumericDataAxisDescriptor(title: l10n(hasTonic ? "chart.cents.tonic" : "chart.cents.c4"),
                                                range: low...high, gridlinePositions: []) {
            l10n("units.cents", l10n.number($0))
        }
        let points = zip(pitch.notes, values).map { note, cents in
            AXDataPoint(x: note.start, y: cents, additionalValues: [],
                        label: PitchNaming.label(hz: note.hz, l10n: l10n))
        }
        let series = AXDataSeriesDescriptor(name: l10n("chart.notes"), isContinuous: false, dataPoints: points)
        return AXChartDescriptor(title: l10n("curve.label"), summary: nil, xAxis: xAxis, yAxis: yAxis,
                                 additionalAxes: [], series: [series])
    }

    func updateChartDescriptor(_ descriptor: AXChartDescriptor) {
        let fresh = makeChartDescriptor()
        descriptor.series = fresh.series
        descriptor.xAxis = fresh.xAxis
        descriptor.yAxis = fresh.yAxis
    }
}
