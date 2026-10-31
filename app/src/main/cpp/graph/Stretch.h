// Rubber Band behind a flat C++ surface, for the offline render and for the JNI's StretchNative.
#pragma once
#include <cstdint>
#include <vector>

namespace stave {
// Stretches [in] (interleaved) by [ratio] (2 = twice as long) and shifts by [semitones] at the
// same time, with formant preservation when asked. Returns interleaved frames.
std::vector<float> stretchOffline(const float* in, int64_t frames, int channels, int sampleRate, double ratio, double semitones, bool formant);
}
