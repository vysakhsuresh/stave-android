#include "Stretch.h"
#include <memory>
#include <vector>
#include "rubberband/RubberBandStretcher.h"

namespace stave {

std::vector<float> stretchOffline(const float* in, int64_t frames, int channels, int sampleRate, double ratio, double semitones, bool formant) {
    using RubberBand::RubberBandStretcher;
    int options = RubberBandStretcher::OptionProcessOffline | RubberBandStretcher::OptionEngineFiner | RubberBandStretcher::OptionPitchHighQuality;
    if (formant) options |= RubberBandStretcher::OptionFormantPreserved;
    RubberBandStretcher s((size_t) sampleRate, (size_t) channels, options, ratio, std::pow(2.0, semitones / 12.0));
    s.setExpectedInputDuration((size_t) frames);
    std::vector<std::vector<float>> planar(channels, std::vector<float>((size_t) frames));
    std::vector<float*> ptrs(channels);
    for (int c = 0; c < channels; c++) { for (int64_t i = 0; i < frames; i++) planar[c][i] = in[i * channels + c]; ptrs[c] = planar[c].data(); }
    s.study(ptrs.data(), (size_t) frames, true);
    const size_t block = 8192;
    std::vector<float> out;
    std::vector<std::vector<float>> outPlanar(channels, std::vector<float>(block * 4));
    std::vector<float*> outPtrs(channels);
    for (int c = 0; c < channels; c++) outPtrs[c] = outPlanar[c].data();
    size_t done = 0;
    while (done < (size_t) frames) {
        size_t n = std::min(block, (size_t) frames - done);
        std::vector<const float*> cp(channels);
        for (int c = 0; c < channels; c++) cp[c] = planar[c].data() + done;
        s.process(cp.data(), n, done + n >= (size_t) frames);
        done += n;
        int avail;
        while ((avail = s.available()) > 0) {
            size_t got = s.retrieve(outPtrs.data(), std::min((size_t) avail, block * 4));
            size_t base = out.size();
            out.resize(base + got * channels);
            for (size_t i = 0; i < got; i++) for (int c = 0; c < channels; c++) out[base + i * channels + c] = outPlanar[c][i];
        }
    }
    int avail;
    while ((avail = s.available()) > 0) {
        size_t got = s.retrieve(outPtrs.data(), std::min((size_t) avail, block * 4));
        size_t base = out.size();
        out.resize(base + got * channels);
        for (size_t i = 0; i < got; i++) for (int c = 0; c < channels; c++) out[base + i * channels + c] = outPlanar[c][i];
    }
    return out;
}

} // namespace stave
