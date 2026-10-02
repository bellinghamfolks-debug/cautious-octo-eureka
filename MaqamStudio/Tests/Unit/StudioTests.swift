import AVFoundation
import XCTest
@testable import MaqamStudio

final class StudioTests: XCTestCase {
    /// Writes a mono 48 kHz take: eight sung notes with vibrato over white noise
    /// at -55 dBFS and 50 Hz mains hum, like a voice recorded in a home room.
    private func noisyTake() throws -> URL {
        let url = try TestSupport.temporaryFolder(self).appendingPathComponent("take.wav")
        let rate = 48000.0
        let format = try XCTUnwrap(AVAudioFormat(standardFormatWithSampleRate: rate, channels: 1))
        let total = AVAudioFrameCount(8 * rate)
        let buffer = try XCTUnwrap(AVAudioPCMBuffer(pcmFormat: format, frameCapacity: total))
        buffer.frameLength = total
        let data = buffer.floatChannelData![0]
        var random = SeededNoise(seed: 99)
        let noise = pow(10, -55.0 / 20) * sqrt(3.0)
        for index in 0..<Int(total) {
            let s = Double(index) / rate
            let hum = 0.006 * sin(2 * .pi * 50 * s) + 0.003 * sin(2 * .pi * 100 * s) + 0.002 * sin(2 * .pi * 150 * s)
            data[index] = Float(noise * random.uniform() + hum)
        }
        var start = 0.3
        for hz in [220.0, 247.5, 261.6, 293.7, 261.6, 247.5, 220.0, 196.0] {
            let frames = Int(0.6 * rate)
            let first = Int(start * rate)
            var phase = 0.0
            for n in 0..<frames where first + n < Int(total) {
                let t = Double(n) / rate
                phase += 2 * .pi * hz * pow(2, 25 * sin(2 * .pi * 5.5 * t) / 1200) / rate
                let fade = min(1, Double(min(n, frames - n)) / 480)
                var sample = 0.0
                for harmonic in 1...8 { sample += sin(Double(harmonic) * phase) / Double(harmonic) }
                data[first + n] += Float(fade * 0.25 * sample / 2.72)
            }
            start += 0.95
        }
        let file = try AVAudioFile(forWriting: url, settings: format.settings)
        try file.write(from: buffer)
        return url
    }

    private func session(for source: URL) throws -> StudioRenderer.Session {
        let pitch = try XCTUnwrap(try AudioFileReader.analyzeFully(source, buckets: 32) { _ in }.pitch)
        return try StudioRenderer.analyse(source: source, pitch: pitch.track) { _ in }
    }

    func testAutoStudioCleansAndReachesTheProfilesLoudness() throws {
        let source = try noisyTake()
        let session = try session(for: source)
        let before = session.measurements
        XCTAssertEqual(before.durationSeconds, 8, accuracy: 0.01)
        XCTAssertEqual(before.humHz, 50)
        // The room is the noise and the hum together: -55 dBFS and -46.1 dBFS make -45.6.
        XCTAssertEqual(before.noiseFloorDbfs, -45.6, accuracy: 2)

        let (plan, reasons) = try session.plan(for: .arabicPop)
        let codes = Set(reasons.map(\.code))
        XCTAssertTrue(codes.contains(MQ_REASON_DENOISE), "noise is found and removed")
        XCTAssertTrue(codes.contains(MQ_REASON_HUM), "hum is found and removed")
        XCTAssertTrue(codes.contains(MQ_REASON_LOUDNESS))
        XCTAssertGreaterThan(plan.denoiseDb, 0)
        XCTAssertEqual(plan.humHz, 50)

        // The same two passes AppModel runs: measure, then render at the gain that reaches the target.
        let measured = try StudioRenderer.run(session: session, plan: plan, source: source, gainDb: 0, limit: false,
                                              destination: nil) { _ in }
        let destination = source.deletingLastPathComponent().appendingPathComponent(StudioRenderer.fileName)
        let final = try StudioRenderer.run(session: session, plan: plan, source: source,
                                           gainDb: plan.loudnessTargetLufs - measured.lufs, limit: true,
                                           destination: destination) { _ in }
        XCTAssertEqual(final.lufs, plan.loudnessTargetLufs, accuracy: 1)
        XCTAssertLessThanOrEqual(final.peakDbfs, plan.ceilingDb + 0.05)

        // What was written is what was measured, in stereo, and the original is untouched.
        let written = try AVAudioFile(forReading: destination)
        XCTAssertEqual(written.processingFormat.channelCount, 2)
        XCTAssertEqual(written.processingFormat.sampleRate, 48000)
        XCTAssertGreaterThanOrEqual(written.length, try AVAudioFile(forReading: source).length)
        XCTAssertEqual(try loudness(of: destination), final.lufs, accuracy: 0.05)
        XCTAssertEqual(try AVAudioFile(forReading: source).processingFormat.channelCount, 1)
    }

    func testCleanupLowersTheRoomAgainstTheVoice() throws {
        let source = try noisyTake()
        let session = try session(for: source)
        var (plan, _) = try session.plan(for: .cleanStudio)
        // Only the cleanup, so the comparison is about noise and hum, not level, tone or space.
        plan.leveler = false
        plan.compressorRatio = 1
        plan.multiband = false
        plan.reverbMix = 0
        plan.delayMix = 0
        plan.saturationDriveDb = 0
        plan.saturationMix = 0
        plan.exciterAmount = 0
        let destination = source.deletingLastPathComponent().appendingPathComponent("clean.caf")
        _ = try StudioRenderer.run(session: session, plan: plan, source: source, gainDb: 0, limit: false,
                                   destination: destination) { _ in }
        let original = try TestSupport.monoSamples(of: source).samples
        let cleaned = try TestSupport.monoSamples(of: destination).samples
        // The first 0.25 s is room only; note 1 sings from 0.3 to 0.9 s.
        func level(_ samples: [Float], _ from: Double, _ to: Double) -> Double {
            let slice = samples[Int(from * 48000)..<Int(to * 48000)]
            let power = slice.reduce(0.0) { $0 + Double($1) * Double($1) } / Double(slice.count)
            return 10 * log10(max(power, 1e-20))
        }
        let roomBefore = level(original, 0.05, 0.25), roomAfter = level(cleaned, 0.05, 0.25)
        let voiceBefore = level(original, 0.4, 0.8), voiceAfter = level(cleaned, 0.4, 0.8)
        XCTAssertGreaterThan((voiceAfter - roomAfter) - (voiceBefore - roomBefore), 12,
                             "the voice stands at least 12 dB further above the room")
    }

    func testPlansSurviveTheCoreAndJSON() throws {
        let session = try session(for: try noisyTake())
        for profile in GenreProfile.allCases {
            let plan = try session.plan(for: profile).plan
            XCTAssertEqual(plan.profile, profile)
            XCTAssertEqual(StudioPlanValues(plan.cPlan), plan, "\(profile)")
            XCTAssertEqual(try JSONDecoder().decode(StudioPlanValues.self, from: JSONEncoder().encode(plan)), plan)
        }
    }

    func testProfilesSoundDifferentAndPairWithATuningStyle() throws {
        let session = try session(for: try noisyTake())
        let tarab = try session.plan(for: .tarab).plan
        let commercial = try session.plan(for: .modernCommercial).plan
        XCTAssertGreaterThan(commercial.loudnessTargetLufs, tarab.loudnessTargetLufs)
        XCTAssertNotEqual(tarab, commercial)
        XCTAssertEqual(GenreProfile.heavyAutoTune.tuningPreset, .robotic)
        XCTAssertEqual(GenreProfile.tarab.tuningPreset, .natural)
        XCTAssertEqual(GenreProfile.arabicPop.tuningPreset, .strong)
    }

    func testFingerprintFollowsSettingsAndInput() {
        let base = StudioRenderer.fingerprint(settings: StudioSettings(), inputFingerprint: "a")
        XCTAssertEqual(base, StudioRenderer.fingerprint(settings: StudioSettings(), inputFingerprint: "a"))
        XCTAssertNotEqual(base, StudioRenderer.fingerprint(settings: StudioSettings(profile: .tarab), inputFingerprint: "a"))
        XCTAssertNotEqual(base, StudioRenderer.fingerprint(settings: StudioSettings(useTuned: false), inputFingerprint: "a"))
        XCTAssertNotEqual(base, StudioRenderer.fingerprint(settings: StudioSettings(), inputFingerprint: "b"))
    }

    func testProjectsFromBeforeTheStudioStillOpen() throws {
        let settings = try JSONDecoder().decode(ProcessingSettings.self, from: Data(#"{"chainVersion":1}"#.utf8))
        XCTAssertNil(settings.studio)
        var withStudio = settings
        withStudio.studio = StudioSettings(profile: .khaleeji, useTuned: false, plan: nil)
        let data = try JSONEncoder().encode(withStudio)
        XCTAssertEqual(try JSONDecoder().decode(ProcessingSettings.self, from: data), withStudio)
    }

    func testEveryProfileAndDecisionHasWordsInBothLanguages() {
        for language in L10n.Language.allCases {
            let l10n = L10n(language: language, defaults: UserDefaults(suiteName: "studio-\(UUID())")!)
            for profile in GenreProfile.allCases {
                XCTAssertNotEqual(l10n(profile.key), profile.key)
                XCTAssertNotEqual(l10n(profile.key + ".about"), profile.key + ".about")
            }
            let unknown = StudioText.reason(StudioReason(code: 0, a: 0, b: 0), l10n: l10n)
            for code in MQ_REASON_HIGH_PASS...MQ_REASON_TONAL {
                let sentence = StudioText.reason(StudioReason(code: code, a: 120, b: 6), l10n: l10n)
                XCTAssertFalse(sentence.hasPrefix("reason."), "\(language) \(code)")
                XCTAssertNotEqual(sentence, unknown, "\(language) \(code)")
                for placeholder in ["%@", "%d", "%1$", "%2$"] {
                    XCTAssertFalse(sentence.contains(placeholder), "\(language) \(code): \(sentence)")
                }
            }
        }
    }

    /// Integrated loudness of a stereo file, through the core's BS.1770 meter.
    private func loudness(of url: URL) throws -> Double {
        let file = try AVAudioFile(forReading: url)
        let format = file.processingFormat
        let meter = try XCTUnwrap(mq_loudness_create(format.sampleRate, 2))
        defer { mq_loudness_destroy(meter) }
        let buffer = try XCTUnwrap(AVAudioPCMBuffer(pcmFormat: format, frameCapacity: 4096))
        while file.framePosition < file.length {
            try file.read(into: buffer, frameCount: 4096)
            guard buffer.frameLength > 0, let data = buffer.floatChannelData else { break }
            mq_loudness_push(meter, data[0], data[1], Int(buffer.frameLength))
        }
        return mq_loudness_integrated(meter)
    }
}

/// A small deterministic generator, so the take is the same on every run.
private struct SeededNoise {
    var state: UInt64
    init(seed: UInt64) { state = seed &* 0x9E37_79B9_7F4A_7C15 | 1 }
    /// Uniform in -1...1.
    mutating func uniform() -> Double {
        state ^= state << 13
        state ^= state >> 7
        state ^= state << 17
        return Double(state >> 11) / Double(UInt64(1) << 52) - 1
    }
}
