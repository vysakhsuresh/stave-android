// TinySoundFont behind a small surface: load an SF2/SF3, play notes, render interleaved float.
#pragma once
#include <cstdint>
#include <string>

struct tsf;
namespace stave {
class SoundFont {
public:
    bool load(const std::string& path, int sampleRate);
    void close();
    int presetCount() const;
    std::string presetName(int index) const;
    void noteOn(int preset, int key, float velocity);
    void noteOff(int preset, int key);
    void allOff();
    void render(float* interleavedStereo, int frames);
    ~SoundFont() { close(); }
private:
    tsf* font = nullptr;
};
}
