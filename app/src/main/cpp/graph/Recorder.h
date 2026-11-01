// The take writer: the input callback pushes frames into a lock-free ring, a thread drains it to
// a 32-bit float WAV. The header's sizes are written as 0xFFFFFFFF until Stop, so a take cut
// short by a crash is still a readable file once the app fixes its header from the length
// (files/TakeFiles.kt) - nothing recorded is ever lost to a header.
#pragma once
#include <atomic>
#include <cstdint>
#include <cstdio>
#include <string>
#include <thread>
#include <vector>

namespace stave {

class Recorder {
public:
    bool start(const std::string& path, int sampleRate, int channels);
    void stop();
    bool recording() const { return running.load(); }
    // From the audio callback: never blocks. Frames dropped when the ring is full are counted.
    void push(const float* interleaved, int frames);
    // Paused: frames are dropped on the floor and the file stays open, so Resume continues the same take.
    void setPaused(bool p) { paused.store(p); }
    bool isPaused() const { return paused.load(); }
    int64_t framesWritten() const { return written.load(); }
    int64_t framesDropped() const { return dropped.load(); }
    int channels = 1;
    std::atomic<bool> paused{false};
private:
    std::atomic<bool> running{false};
    std::atomic<int64_t> written{0}, dropped{0};
    std::vector<float> ring;
    std::atomic<size_t> head{0}, tail{0};
    std::thread writer;
    FILE* file = nullptr;
    int rate = 48000;
    void run();
    void finishHeader();
};

} // namespace stave
