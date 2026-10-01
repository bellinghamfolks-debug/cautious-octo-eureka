import AVFoundation
import Foundation

/// The single AVAudioEngine behind playback and recording.
///
/// Thread rules, which every later DSP stage must keep:
/// - The render thread only runs AVAudioEngine's own nodes. Nothing in this
///   class runs on it: no file I/O, no locks, no allocation.
/// - The input tap is delivered on AVFoundation's tap thread, not the render
///   thread. It copies the buffer and hands it to `writerQueue`, which does
///   the file write, so a slow disk can never stall capture.
/// - Public methods are called from the main actor.
final class AudioEngine {
    enum State: Equatable { case idle, playing, paused, recording }

    struct InputLevel: Equatable {
        var peakDbfs: Double
        var clipped: Bool
    }

    private let engine = AVAudioEngine()
    private let player = AVAudioPlayerNode()
    private let writerQueue = DispatchQueue(label: "maqamstudio.recording-writer", qos: .userInitiated)

    private(set) var state: State = .idle

    // Playback
    private var playbackFile: AVAudioFile?
    private var segmentStartFrame: AVAudioFramePosition = 0
    private var scheduleGeneration = 0
    var onPlaybackFinished: (() -> Void)?

    // Recording
    private var recordingFile: AVAudioFile?
    private var recordingURL: URL?
    private var recordedFrames: AVAudioFramePosition = 0
    private var recordingError: Error?
    private var lastLevelReport = Date.distantPast
    var onInputLevel: ((InputLevel) -> Void)?

    init() {
        engine.attach(player)
        // Connected from the start so the graph is complete even before a file
        // is loaded; load(url:) reconnects with the file's own format.
        engine.connect(player, to: engine.mainMixerNode, format: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(configurationChanged),
                                               name: .AVAudioEngineConfigurationChange, object: engine)
    }

    deinit {
        NotificationCenter.default.removeObserver(self)
        engine.stop()
    }

    // MARK: Playback

    var duration: Double {
        guard let file = playbackFile else { return 0 }
        return Double(file.length) / file.processingFormat.sampleRate
    }

    func load(url: URL) throws {
        stopPlayback()
        do {
            let file = try AVAudioFile(forReading: url)
            playbackFile = file
            engine.disconnectNodeOutput(player)
            engine.connect(player, to: engine.mainMixerNode, format: file.processingFormat)
            segmentStartFrame = 0
        } catch {
            playbackFile = nil
            throw AppError.playbackFailed(detail: (error as NSError).localizedDescription)
        }
    }

    func unload() {
        stopPlayback()
        playbackFile = nil
    }

    /// Current position in seconds, read from the player's render clock.
    var position: Double {
        guard let file = playbackFile else { return 0 }
        let sampleRate = file.processingFormat.sampleRate
        if state == .playing, let nodeTime = player.lastRenderTime, let playerTime = player.playerTime(forNodeTime: nodeTime) {
            let frame = segmentStartFrame + playerTime.sampleTime
            return min(Double(file.length), Double(max(0, frame))) / sampleRate
        }
        return Double(segmentStartFrame) / sampleRate
    }

    func play(from seconds: Double? = nil) throws {
        guard let file = playbackFile else { return }
        if state == .recording { return }
        let sampleRate = file.processingFormat.sampleRate
        let start = seconds.map { AVAudioFramePosition($0 * sampleRate) } ?? segmentStartFrame
        let clamped = min(max(0, start), max(0, file.length - 1))
        try startEngineIfNeeded()
        player.stop()
        schedule(file: file, from: clamped)
        player.play()
        state = .playing
    }

    func pause() {
        guard state == .playing else { return }
        segmentStartFrame = AVAudioFramePosition(position * (playbackFile?.processingFormat.sampleRate ?? 1))
        scheduleGeneration += 1  // the stop below must not report "finished"
        player.stop()
        state = .paused
    }

    func stopPlayback() {
        guard state == .playing || state == .paused else { return }
        scheduleGeneration += 1
        player.stop()
        segmentStartFrame = 0
        state = .idle
    }

    func seek(to seconds: Double) throws {
        guard let file = playbackFile else { return }
        let frame = AVAudioFramePosition(seconds * file.processingFormat.sampleRate)
        let clamped = min(max(0, frame), max(0, file.length - 1))
        if state == .playing {
            try play(from: Double(clamped) / file.processingFormat.sampleRate)
        } else {
            segmentStartFrame = clamped
        }
    }

    private func schedule(file: AVAudioFile, from frame: AVAudioFramePosition) {
        scheduleGeneration += 1
        let generation = scheduleGeneration
        segmentStartFrame = frame
        let remaining = AVAudioFrameCount(max(0, file.length - frame))
        guard remaining > 0 else { return }
        player.scheduleSegment(file, startingFrame: frame, frameCount: remaining, at: nil,
                               completionCallbackType: .dataPlayedBack) { [weak self] _ in
            DispatchQueue.main.async {
                guard let self, generation == self.scheduleGeneration, self.state == .playing else { return }
                self.state = .idle
                self.segmentStartFrame = 0
                self.onPlaybackFinished?()
            }
        }
    }

    // MARK: Recording

    /// Starts writing the input to a new 32-bit float CAF at `url`.
    func startRecording(to url: URL) throws {
        stopPlayback()
        let input = engine.inputNode
        let format = input.outputFormat(forBus: 0)
        guard format.sampleRate > 0, format.channelCount > 0 else { throw AppError.noInputDevice }
        let settings: [String: Any] = [
            AVFormatIDKey: kAudioFormatLinearPCM,
            AVSampleRateKey: format.sampleRate,
            AVNumberOfChannelsKey: format.channelCount,
            AVLinearPCMBitDepthKey: 32,
            AVLinearPCMIsFloatKey: true,
            AVLinearPCMIsNonInterleaved: false,
        ]
        let file: AVAudioFile
        do {
            file = try AVAudioFile(forWriting: url, settings: settings, commonFormat: .pcmFormatFloat32, interleaved: false)
        } catch {
            throw AppError.recordingFailed(detail: (error as NSError).localizedDescription)
        }
        recordingFile = file
        recordingURL = url
        recordedFrames = 0
        recordingError = nil

        input.removeTap(onBus: 0)
        input.installTap(onBus: 0, bufferSize: 4096, format: format) { [weak self] buffer, _ in
            self?.captured(buffer)
        }
        do {
            try startEngineIfNeeded()
        } catch {
            input.removeTap(onBus: 0)
            recordingFile = nil
            throw error
        }
        state = .recording
    }

    /// Stops recording and returns the file and how many frames reached it.
    func stopRecording() throws -> (url: URL, frames: AVAudioFramePosition) {
        guard state == .recording, let url = recordingURL else { throw AppError.recordingFailed(detail: "not recording") }
        engine.inputNode.removeTap(onBus: 0)
        state = .idle
        // Wait for every queued chunk to reach the file before closing it.
        writerQueue.sync {}
        let frames = recordedFrames
        let error = recordingError
        recordingFile = nil  // releasing the AVAudioFile finalises the CAF header
        recordingURL = nil
        if let error { throw AppError.recordingFailed(detail: (error as NSError).localizedDescription) }
        return (url, frames)
    }

    var recordedSeconds: Double {
        guard let file = recordingFile else { return 0 }
        return Double(recordedFrames) / file.processingFormat.sampleRate
    }

    private func captured(_ buffer: AVAudioPCMBuffer) {
        // Tap thread: copy, measure, hand off. No file access here.
        guard let copy = AVAudioPCMBuffer(pcmFormat: buffer.format, frameCapacity: buffer.frameLength),
              let source = buffer.floatChannelData, let target = copy.floatChannelData else { return }
        copy.frameLength = buffer.frameLength
        let frames = Int(buffer.frameLength)
        var peak: Float = 0
        for channel in 0..<Int(buffer.format.channelCount) {
            target[channel].update(from: source[channel], count: frames)
            for index in 0..<frames { peak = max(peak, abs(source[channel][index])) }
        }
        writerQueue.async { [weak self] in
            guard let self, let file = self.recordingFile else { return }
            do {
                try file.write(from: copy)
                self.recordedFrames += AVAudioFramePosition(copy.frameLength)
            } catch {
                self.recordingError = error
            }
        }
        let now = Date()
        if now.timeIntervalSince(lastLevelReport) > 0.1 {
            lastLevelReport = now
            let level = InputLevel(peakDbfs: peak > 0 ? max(-160, 20 * log10(Double(peak))) : -160, clipped: peak >= 0.999)
            DispatchQueue.main.async { [weak self] in self?.onInputLevel?(level) }
        }
    }

    // MARK: Engine lifecycle

    private func startEngineIfNeeded() throws {
        guard !engine.isRunning else { return }
        engine.prepare()
        do {
            try engine.start()
        } catch {
            throw AppError.audioEngineFailed(detail: (error as NSError).localizedDescription)
        }
    }

    /// Called when the route changes (headphones in or out) or the hardware
    /// sample rate changes. The engine has stopped; playback resumes paused.
    @objc private func configurationChanged(_ notification: Notification) {
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            if self.state == .playing { self.pause() }
            if let file = self.playbackFile {
                self.engine.disconnectNodeOutput(self.player)
                self.engine.connect(self.player, to: self.engine.mainMixerNode, format: file.processingFormat)
            }
        }
    }

    /// Pauses playback and the engine, as when the system interrupts the app
    /// (a phone call). A recording in progress must be stopped by the caller
    /// first, so the take is kept rather than lost.
    func interrupt() {
        if state == .playing { pause() }
        engine.pause()
    }
}
