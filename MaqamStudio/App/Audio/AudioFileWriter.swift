import AudioToolbox
import AVFoundation

/// Writes 32-bit float CAF files synchronously and finishes them on `close()`.
///
/// AVAudioFile completes a file only when it is deallocated, which is hard to
/// pin down; a recording or a render read back too early came up short. This
/// writer makes "the file is complete" an explicit, immediate step.
final class AudioFileWriter {
    private var file: ExtAudioFileRef?
    let format: AVAudioFormat
    private(set) var framesWritten: AVAudioFramePosition = 0

    /// `format` is the format of the buffers that will be written (standard
    /// deinterleaved float, as AVAudioEngine and AVAudioFile produce).
    init(url: URL, format: AVAudioFormat) throws {
        self.format = format
        let channels = format.channelCount
        var fileFormat = AudioStreamBasicDescription(
            mSampleRate: format.sampleRate, mFormatID: kAudioFormatLinearPCM,
            mFormatFlags: kAudioFormatFlagIsFloat | kAudioFormatFlagIsPacked,
            mBytesPerPacket: 4 * channels, mFramesPerPacket: 1, mBytesPerFrame: 4 * channels,
            mChannelsPerFrame: channels, mBitsPerChannel: 32, mReserved: 0)
        var created: ExtAudioFileRef?
        var status = ExtAudioFileCreateWithURL(url as CFURL, kAudioFileCAFType, &fileFormat, nil,
                                               AudioFileFlags.eraseFile.rawValue, &created)
        guard status == noErr, let created else {
            throw AppError.recordingFailed(detail: "ExtAudioFileCreate \(status)")
        }
        var client = format.streamDescription.pointee
        status = ExtAudioFileSetProperty(created, kExtAudioFileProperty_ClientDataFormat,
                                         UInt32(MemoryLayout<AudioStreamBasicDescription>.size), &client)
        guard status == noErr else {
            ExtAudioFileDispose(created)
            throw AppError.recordingFailed(detail: "client format \(status)")
        }
        file = created
    }

    deinit { close() }

    func write(_ buffer: AVAudioPCMBuffer) throws {
        guard let file else { throw AppError.recordingFailed(detail: "closed") }
        let status = ExtAudioFileWrite(file, buffer.frameLength, buffer.audioBufferList)
        guard status == noErr else { throw AppError.recordingFailed(detail: "ExtAudioFileWrite \(status)") }
        framesWritten += AVAudioFramePosition(buffer.frameLength)
    }

    /// Completes the file. Safe to call more than once.
    func close() {
        if let file { ExtAudioFileDispose(file) }
        file = nil
    }
}
