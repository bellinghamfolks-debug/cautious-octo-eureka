// Built without warnings or sanitizers: this is other people's code, used as a referee.
#define MINIMP3_IMPLEMENTATION
#define MINIMP3_FLOAT_OUTPUT
#include "minimp3.h"
#define DR_FLAC_IMPLEMENTATION
#include "dr_flac.h"
#define DR_WAV_IMPLEMENTATION
#include "dr_wav.h"

#include "decoders.hpp"

namespace thirdparty {

Mp3Decoded decodeMp3(const std::vector<std::uint8_t>& bytes) {
    Mp3Decoded result;
    mp3dec_t decoder;
    mp3dec_init(&decoder);
    std::size_t position = 0;
    float pcm[MINIMP3_MAX_SAMPLES_PER_FRAME];
    while (position < bytes.size()) {
        mp3dec_frame_info_t info;
        const int samples = mp3dec_decode_frame(&decoder, bytes.data() + position, static_cast<int>(bytes.size() - position), pcm, &info);
        if (info.frame_bytes == 0) break;
        position += static_cast<std::size_t>(info.frame_bytes);
        ++result.frames;
        if (samples == 0) { ++result.framesWithoutAudio; continue; }
        result.channels = info.channels;
        result.sampleRate = info.hz;
        result.bitrateKbps = info.bitrate_kbps;
        result.samples.insert(result.samples.end(), pcm, pcm + samples * info.channels);
    }
    return result;
}

bool decodeFlac(const std::vector<std::uint8_t>& bytes, std::vector<std::int32_t>& samples, unsigned& channels,
                unsigned& sampleRate) {
    drflac_uint64 frames = 0;
    drflac_int32* data = drflac_open_memory_and_read_pcm_frames_s32(bytes.data(), bytes.size(), &channels, &sampleRate, &frames, nullptr);
    if (!data) return false;
    samples.assign(data, data + frames * channels);
    drflac_free(data, nullptr);
    return true;
}

bool decodeWavInt(const std::vector<std::uint8_t>& bytes, std::vector<std::int32_t>& samples, unsigned& channels,
                  unsigned& sampleRate, unsigned& bits) {
    drwav wav;
    if (!drwav_init_memory(&wav, bytes.data(), bytes.size(), nullptr)) return false;
    channels = wav.channels;
    sampleRate = wav.sampleRate;
    bits = wav.bitsPerSample;
    samples.resize(static_cast<std::size_t>(wav.totalPCMFrameCount) * channels);
    const drwav_uint64 read = drwav_read_pcm_frames_s32(&wav, wav.totalPCMFrameCount, samples.data());
    drwav_uninit(&wav);
    samples.resize(static_cast<std::size_t>(read) * channels);
    return true;
}

bool decodeWavFloat(const std::vector<std::uint8_t>& bytes, std::vector<float>& samples, unsigned& channels,
                    unsigned& sampleRate) {
    drwav_uint64 frames = 0;
    float* data = drwav_open_memory_and_read_pcm_frames_f32(bytes.data(), bytes.size(), &channels, &sampleRate, &frames, nullptr);
    if (!data) return false;
    samples.assign(data, data + frames * channels);
    drwav_free(data, nullptr);
    return true;
}

}  // namespace thirdparty
