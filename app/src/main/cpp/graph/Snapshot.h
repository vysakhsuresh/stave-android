// What the audio callback plays: an immutable description of the session, built on the UI
// thread from the bytes Kotlin's SnapshotEncoder writes, with every source already opened. The
// callback reads whichever snapshot is current and never touches the project itself.
#pragma once
#include <atomic>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>
#include "Dsp.h"

namespace stave {

// A source: raw interleaved 32-bit float, memory-mapped read-only. Random access, no streaming
// state, and the kernel pages it in and out; an hour of stereo 48k is 1.4 GB of address space
// and a handful of megabytes resident while it plays.
struct Source {
    std::string path;
    int sampleRate = 48000;
    int channels = 2;
    int64_t frames = 0;
    const float* data = nullptr;   // frames * channels
    size_t mappedBytes = 0;
    int fd = -1;
    bool open();
    void close();
    ~Source() { close(); }
    inline float sample(int64_t frame, int channel) const {
        if (frame < 0 || frame >= frames) return 0.0f;
        return data[frame * channels + (channel < channels ? channel : channels - 1)];
    }
};

struct EnvelopePoint { int64_t position; float gainDb; };

struct Clip {
    int track = 0;
    int source = 0;
    int64_t position = 0, offset = 0, length = 0;
    float gain = 1.0f;
    int64_t fadeIn = 0, fadeOut = 0;
    int fadeInShape = 1, fadeOutShape = 1;
    bool muted = false, reversed = false;
    double stretch = 1.0;    // timeline samples per source frame, with the rate ratio folded in
    std::vector<EnvelopePoint> envelope;
    inline int64_t end() const { return position + length; }
};

struct InsertSpec {
    int kind = 0;              // 1 eq, 2 compressor, 3 limiter, 4 gate
    bool enabled = true;
    std::vector<float> params;
    std::vector<BiquadCoefficients> bands;
};

struct Track {
    int kind = 0;              // 0 audio, 1 midi, 2 bus, 3 master
    float volume = 1.0f;
    float pan = 0.0f;
    bool muted = false, solo = false, armed = false;
    int output = -1;           // bus track index, or -1 for the master
    std::vector<InsertSpec> inserts;
    struct Send { int to; float gain; bool pre; };
    std::vector<Send> sends;
};

struct Snapshot {
    int sampleRate = 48000;
    std::vector<std::shared_ptr<Source>> sources;
    std::vector<Track> tracks;
    std::vector<Clip> clips;
    std::vector<int> clipsByTrackStart;   // clips sorted by (track, position); index of first clip per track
    float masterVolume = 1.0f;
    std::vector<InsertSpec> masterInserts;
    bool anySolo = false;
    uint32_t generation = 0;

    // Parses the bytes SnapshotEncoder wrote (little-endian; see Snapshot.cpp for the layout) and
    // opens every source, reusing those already open in [previous].
    static std::shared_ptr<Snapshot> parse(const uint8_t* bytes, size_t length, const Snapshot* previous, int sessionRate);
};

} // namespace stave
