#define TSF_IMPLEMENTATION
#include "tsf.h"
#include "SoundFont.h"

namespace stave {

bool SoundFont::load(const std::string& path, int sampleRate) {
    close();
    font = tsf_load_filename(path.c_str());
    if (!font) return false;
    tsf_set_output(font, TSF_STEREO_INTERLEAVED, sampleRate, 0.0f);
    tsf_set_max_voices(font, 64);
    return true;
}

void SoundFont::close() { if (font) { tsf_close(font); font = nullptr; } }
int SoundFont::presetCount() const { return font ? tsf_get_presetcount(font) : 0; }
std::string SoundFont::presetName(int index) const { if (!font) return {}; const char* n = tsf_get_presetname(font, index); return n ? n : ""; }
void SoundFont::noteOn(int preset, int key, float velocity) { if (font) tsf_note_on(font, preset, key, velocity); }
void SoundFont::noteOff(int preset, int key) { if (font) tsf_note_off(font, preset, key); }
void SoundFont::allOff() { if (font) tsf_note_off_all(font); }
void SoundFont::render(float* out, int frames) { if (font) tsf_render_float(font, out, frames, 0); }

} // namespace stave
