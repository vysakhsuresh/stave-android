#include "Snapshot.h"
#include <algorithm>
#include <cstring>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#include <android/log.h>

#define TAG "StaveGraph"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

namespace stave {

bool Source::open() {
    fd = ::open(path.c_str(), O_RDONLY);
    if (fd < 0) { LOGW("cannot open %s", path.c_str()); return false; }
    struct stat st;
    if (fstat(fd, &st) != 0 || st.st_size == 0) { close(); return false; }
    mappedBytes = (size_t) st.st_size;
    void* p = mmap(nullptr, mappedBytes, PROT_READ, MAP_PRIVATE, fd, 0);
    if (p == MAP_FAILED) { LOGW("cannot map %s", path.c_str()); mappedBytes = 0; close(); return false; }
    // Ask for the pages now, asynchronously, so the audio callback does not take a page fault
    // on its first touch of each 4 KB. Without it the moto's fast mixer found the track empty
    // more often than full while a 6-minute song played (1 Nov 2026).
    madvise(p, mappedBytes, MADV_WILLNEED);
    data = (const float*) p;
    int64_t available = (int64_t) (mappedBytes / sizeof(float) / channels);
    if (frames <= 0 || frames > available) frames = available;
    return true;
}

void Source::close() {
    if (data) { munmap((void*) data, mappedBytes); data = nullptr; }
    if (fd >= 0) { ::close(fd); fd = -1; }
}

// A little-endian reader over the bytes. The layout, in the order written by
// app/.../audio/SnapshotEncoder.kt:
//   u32 magic 'STSN', u32 version, u32 sampleRate, u32 generation
//   u32 sourceCount; per source: u32 rate, u32 channels, i64 frames, u16 pathLen, path bytes
//   u32 trackCount; per track: u8 kind, f32 volume, f32 pan, u8 muted, u8 solo, u8 armed, i32 output,
//        u16 insertCount; per insert: u8 kind, u8 enabled, u16 paramCount, f32[params], u16 bandCount, per band: u8 kind, f32 hz, f32 gain, f32 q, u8 enabled
//        u16 sendCount; per send: i32 to, f32 gain, u8 pre
//        u16 volumeAutoCount; per point: i64 position, f32 dB; u16 panAutoCount; per point: i64 position, f32 pan   (version 2)
//   u32 clipCount; per clip: i32 track, i32 source, i64 position, i64 offset, i64 length, f32 gain,
//        i64 fadeIn, u8 fadeInShape, i64 fadeOut, u8 fadeOutShape, u8 muted, u8 reversed, f64 stretch,
//        u16 envCount; per point: i64 position, f32 gainDb
//   f32 masterVolume, u16 masterInsertCount; inserts as above
struct Reader {
    const uint8_t* p; const uint8_t* end; bool ok = true;
    Reader(const uint8_t* b, size_t n) : p(b), end(b + n) {}
    template <typename T> T get() { T v{}; if (p + sizeof(T) > end) { ok = false; return v; } memcpy(&v, p, sizeof(T)); p += sizeof(T); return v; }
    std::string str(size_t n) { if (p + n > end) { ok = false; return {}; } std::string s((const char*) p, n); p += n; return s; }
};

static void readInserts(Reader& r, std::vector<InsertSpec>& out, int sampleRate) {
    uint16_t count = r.get<uint16_t>();
    for (int i = 0; i < count && r.ok; i++) {
        InsertSpec ins;
        ins.kind = r.get<uint8_t>();
        ins.enabled = r.get<uint8_t>() != 0;
        uint16_t params = r.get<uint16_t>();
        for (int k = 0; k < params && r.ok; k++) ins.params.push_back(r.get<float>());
        uint16_t bands = r.get<uint16_t>();
        for (int b = 0; b < bands && r.ok; b++) {
            int kind = r.get<uint8_t>(); float hz = r.get<float>(); float gain = r.get<float>(); float q = r.get<float>(); bool enabled = r.get<uint8_t>() != 0;
            if (enabled) ins.bands.push_back(biquad(kind, hz, sampleRate, q, gain));
        }
        out.push_back(std::move(ins));
    }
}

std::shared_ptr<Snapshot> Snapshot::parse(const uint8_t* bytes, size_t length, const Snapshot* previous, int sessionRate) {
    Reader r(bytes, length);
    if (r.get<uint32_t>() != 0x4E535453u) { LOGW("snapshot: bad magic"); return nullptr; }
    uint32_t version = r.get<uint32_t>();
    auto snap = std::make_shared<Snapshot>();
    snap->sampleRate = (int) r.get<uint32_t>();
    if (snap->sampleRate <= 0) snap->sampleRate = sessionRate;
    snap->generation = r.get<uint32_t>();
    uint32_t sources = r.get<uint32_t>();
    for (uint32_t i = 0; i < sources && r.ok; i++) {
        auto s = std::make_shared<Source>();
        s->sampleRate = (int) r.get<uint32_t>();
        s->channels = (int) r.get<uint32_t>();
        s->frames = r.get<int64_t>();
        uint16_t n = r.get<uint16_t>();
        s->path = r.str(n);
        if (s->channels <= 0) s->channels = 1;
        // Reuse an open mapping for the same path and size.
        std::shared_ptr<Source> reused;
        if (previous) for (auto& old : previous->sources) if (old && old->path == s->path && old->data && old->frames == s->frames) { reused = old; break; }
        if (reused) snap->sources.push_back(reused);
        else { if (!s->open()) LOGW("source %s did not open; it will play silence", s->path.c_str()); snap->sources.push_back(s); }
    }
    uint32_t tracks = r.get<uint32_t>();
    for (uint32_t i = 0; i < tracks && r.ok; i++) {
        Track t;
        t.kind = r.get<uint8_t>();
        t.volume = r.get<float>(); t.pan = r.get<float>();
        t.muted = r.get<uint8_t>() != 0; t.solo = r.get<uint8_t>() != 0; t.armed = r.get<uint8_t>() != 0;
        t.output = r.get<int32_t>();
        readInserts(r, t.inserts, snap->sampleRate);
        uint16_t sends = r.get<uint16_t>();
        for (int s = 0; s < sends && r.ok; s++) { Track::Send sd; sd.to = r.get<int32_t>(); sd.gain = r.get<float>(); sd.pre = r.get<uint8_t>() != 0; t.sends.push_back(sd); }
        if (version >= 2) {
            uint16_t va = r.get<uint16_t>();
            for (int k = 0; k < va && r.ok; k++) { Track::AutoPoint p; p.position = r.get<int64_t>(); p.value = r.get<float>(); t.volumeAuto.push_back(p); }
            uint16_t pa = r.get<uint16_t>();
            for (int k = 0; k < pa && r.ok; k++) { Track::AutoPoint p; p.position = r.get<int64_t>(); p.value = r.get<float>(); t.panAuto.push_back(p); }
        }
        if (t.solo && t.kind == 0) snap->anySolo = true;
        snap->tracks.push_back(std::move(t));
    }
    uint32_t clips = r.get<uint32_t>();
    for (uint32_t i = 0; i < clips && r.ok; i++) {
        Clip c;
        c.track = r.get<int32_t>(); c.source = r.get<int32_t>();
        c.position = r.get<int64_t>(); c.offset = r.get<int64_t>(); c.length = r.get<int64_t>();
        c.gain = r.get<float>();
        c.fadeIn = r.get<int64_t>(); c.fadeInShape = r.get<uint8_t>();
        c.fadeOut = r.get<int64_t>(); c.fadeOutShape = r.get<uint8_t>();
        c.muted = r.get<uint8_t>() != 0; c.reversed = r.get<uint8_t>() != 0;
        c.stretch = r.get<double>();
        uint16_t env = r.get<uint16_t>();
        for (int e = 0; e < env && r.ok; e++) { EnvelopePoint p; p.position = r.get<int64_t>(); p.gainDb = r.get<float>(); c.envelope.push_back(p); }
        if (c.source >= 0 && c.source < (int) snap->sources.size() && c.track >= 0 && c.track < (int) snap->tracks.size()) {
            // Fold the rate ratio into the stretch: one source frame covers this many timeline samples.
            double ratio = (double) snap->sampleRate / (double) snap->sources[c.source]->sampleRate;
            c.stretch = (c.stretch <= 0 ? 1.0 : c.stretch) * ratio;
            snap->clips.push_back(std::move(c));
        }
    }
    snap->masterVolume = r.get<float>();
    readInserts(r, snap->masterInserts, snap->sampleRate);
    if (!r.ok) { LOGW("snapshot: truncated"); return nullptr; }
    std::sort(snap->clips.begin(), snap->clips.end(), [](const Clip& a, const Clip& b) { return a.track != b.track ? a.track < b.track : a.position < b.position; });
    snap->clipsByTrackStart.assign(snap->tracks.size() + 1, (int) snap->clips.size());
    for (int i = (int) snap->clips.size() - 1; i >= 0; i--) snap->clipsByTrackStart[snap->clips[i].track] = i;
    for (int t = (int) snap->tracks.size() - 1; t >= 0; t--) if (snap->clipsByTrackStart[t] > snap->clipsByTrackStart[t + 1]) snap->clipsByTrackStart[t] = snap->clipsByTrackStart[t + 1];
    return snap;
}

} // namespace stave
