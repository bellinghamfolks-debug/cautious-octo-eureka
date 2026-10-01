import Accessibility
import SwiftUI

/// The recording's shape, with the playhead.
///
/// For VoiceOver the waveform is three things at once: an Audio Graph (the
/// system can play it as a tone and read its points), a spoken description of
/// where the singing is, and an adjustable control — swipe up or down to move
/// the playhead in 5% steps. Seeing it is never required.
struct WaveformView: View {
    @EnvironmentObject private var l10n: L10n
    let waveform: WaveformSummary
    let levels: LevelSummary
    let duration: Double
    let position: Double
    let onSeek: (Double) -> Void

    var body: some View {
        GeometryReader { geometry in
            Canvas { context, size in
                draw(in: &context, size: size)
            }
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 0).onEnded { value in
                guard duration > 0, geometry.size.width > 0 else { return }
                var fraction = value.location.x / geometry.size.width
                if l10n.layoutDirection == .rightToLeft { fraction = 1 - fraction }
                onSeek(min(max(0, fraction), 1) * duration)
            })
        }
        .frame(height: 140)
        .background(RoundedRectangle(cornerRadius: 14).fill(Color(.secondarySystemBackground)))
        .accessibilityElement()
        .accessibilityLabel(l10n("waveform.label"))
        .accessibilityValue(l10n("waveform.value", l10n.spokenDuration(position), l10n.spokenDuration(duration)))
        .accessibilityHint(AudioDescription.summary(levels: levels, waveform: waveform, duration: duration, l10n: l10n)
                           + " " + l10n("waveform.hint"))
        .accessibilityAdjustableAction { direction in
            let step = duration * 0.05
            switch direction {
            case .increment: onSeek(min(duration, position + step))
            case .decrement: onSeek(max(0, position - step))
            @unknown default: break
            }
        }
        .accessibilityChartDescriptor(WaveformChart(waveform: waveform, duration: duration, l10n: l10n))
    }

    private func draw(in context: inout GraphicsContext, size: CGSize) {
        let count = waveform.buckets
        guard count > 0 else { return }
        let middle = size.height / 2
        let rightToLeft = l10n.layoutDirection == .rightToLeft
        var path = Path()
        for index in 0..<count {
            let fraction = (CGFloat(index) + 0.5) / CGFloat(count)
            let x = (rightToLeft ? 1 - fraction : fraction) * size.width
            let top = middle - CGFloat(max(0, waveform.maximum[index])) * middle
            let bottom = middle - CGFloat(min(0, waveform.minimum[index])) * middle
            path.move(to: CGPoint(x: x, y: top))
            path.addLine(to: CGPoint(x: x, y: max(bottom, top + 1)))
        }
        context.stroke(path, with: .color(.accentColor), lineWidth: max(1, size.width / CGFloat(count)))
        if duration > 0 {
            let fraction = CGFloat(position / duration)
            let x = (rightToLeft ? 1 - fraction : fraction) * size.width
            var head = Path()
            head.move(to: CGPoint(x: x, y: 0))
            head.addLine(to: CGPoint(x: x, y: size.height))
            context.stroke(head, with: .color(.primary), lineWidth: 2)
        }
    }
}

/// The waveform as an accessible chart: loudness in dB over time.
struct WaveformChart: AXChartDescriptorRepresentable {
    let waveform: WaveformSummary
    let duration: Double
    let l10n: L10n

    /// Few enough points to sonify and step through, enough to keep the shape.
    static let points = 120

    func makeChartDescriptor() -> AXChartDescriptor {
        let values = Self.downsample(waveform.rmsDbfs, to: Self.points)
        let step = values.isEmpty ? 0 : duration / Double(values.count)
        let l10n = self.l10n
        let xAxis = AXNumericDataAxisDescriptor(
            title: l10n("chart.time"),
            range: 0...max(duration, 0.001),
            gridlinePositions: []) { value in l10n.spokenDuration(value) }
        let yAxis = AXNumericDataAxisDescriptor(
            title: l10n("chart.loudness"),
            range: -80...0,
            gridlinePositions: [-60, -40, -20]) { value in l10n.decibels(value) }
        let points = values.enumerated().map { index, value in
            AXDataPoint(x: (Double(index) + 0.5) * step, y: max(-80, Double(value)))
        }
        let series = AXDataSeriesDescriptor(name: l10n("chart.series"), isContinuous: true, dataPoints: points)
        return AXChartDescriptor(title: l10n("waveform.label"), summary: nil,
                                 xAxis: xAxis, yAxis: yAxis, additionalAxes: [], series: [series])
    }

    func updateChartDescriptor(_ descriptor: AXChartDescriptor) {
        let fresh = makeChartDescriptor()
        descriptor.series = fresh.series
        descriptor.xAxis = fresh.xAxis
    }

    static func downsample(_ values: [Float], to count: Int) -> [Float] {
        guard values.count > count, count > 0 else { return values }
        return (0..<count).map { index in
            let start = index * values.count / count
            let end = max(start + 1, (index + 1) * values.count / count)
            return values[start..<end].max() ?? -160
        }
    }
}
