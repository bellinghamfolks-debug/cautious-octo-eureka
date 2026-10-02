// Independent decoders for checking the encoders: minimp3 (CC0), dr_flac and
// dr_wav (public domain / MIT-0). Used by the tests only, never by the app.
#pragma once

#include <cstdint>
#include <vector>

namespace thirdparty {

struct Mp3Decoded {
    std::vector<float> samples;  // interleaved, -1..1
    int channels = 0;
    int sampleRate = 0;
    int frames = 0;              // MP3 frames found
    int framesWithoutAudio = 0;  // frames the decoder could not decode
    int bitrateKbps = 0;         // of the last frame
};
Mp3Decoded decodeMp3(const std::vector<std::uint8_t>& bytes);

// Samples scaled to the full 32-bit range, as dr_flac and dr_wav return them.
bool decodeFlac(const std::vector<std::uint8_t>& bytes, std::vector<std::int32_t>& samples, unsigned& channels,
                unsigned& sampleRate);
bool decodeWavInt(const std::vector<std::uint8_t>& bytes, std::vector<std::int32_t>& samples, unsigned& channels,
                  unsigned& sampleRate, unsigned& bits);
bool decodeWavFloat(const std::vector<std::uint8_t>& bytes, std::vector<float>& samples, unsigned& channels,
                    unsigned& sampleRate);

}  // namespace thirdparty
