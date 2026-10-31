#include "Recorder.h"
#include <chrono>
#include <cstring>

namespace stave {

static void put32(FILE* f, uint32_t v) { fwrite(&v, 4, 1, f); }
static void put16(FILE* f, uint16_t v) { fwrite(&v, 2, 1, f); }

bool Recorder::start(const std::string& path, int sampleRate, int ch) {
    if (running.load()) return false;
    file = fopen(path.c_str(), "wb");
    if (!file) return false;
    rate = sampleRate; channels = ch;
    // RIFF/WAVE, IEEE float, sizes unknown until stop.
    fwrite("RIFF", 1, 4, file); put32(file, 0xFFFFFFFFu); fwrite("WAVE", 1, 4, file);
    fwrite("fmt ", 1, 4, file); put32(file, 16); put16(file, 3); put16(file, (uint16_t) channels); put32(file, (uint32_t) rate);
    put32(file, (uint32_t) (rate * channels * 4)); put16(file, (uint16_t) (channels * 4)); put16(file, 32);
    fwrite("data", 1, 4, file); put32(file, 0xFFFFFFFFu);
    fflush(file);
    // Four seconds of ring: a writer stalled by storage for that long drops, and says so.
    ring.assign((size_t) sampleRate * channels * 4, 0.0f);
    head = 0; tail = 0; written = 0; dropped = 0;
    running = true;
    writer = std::thread([this] { run(); });
    return true;
}

void Recorder::push(const float* in, int frames) {
    if (!running.load(std::memory_order_relaxed)) return;
    size_t n = (size_t) frames * channels;
    size_t h = head.load(std::memory_order_relaxed), t = tail.load(std::memory_order_acquire);
    size_t cap = ring.size();
    size_t free = (t + cap - h - 1) % cap;
    if (n > free) { dropped.fetch_add(frames); return; }
    for (size_t i = 0; i < n; i++) ring[(h + i) % cap] = in[i];
    head.store((h + n) % cap, std::memory_order_release);
}

void Recorder::run() {
    std::vector<float> chunk;
    while (true) {
        size_t h = head.load(std::memory_order_acquire), t = tail.load(std::memory_order_relaxed);
        if (h == t) {
            if (!running.load()) break;
            std::this_thread::sleep_for(std::chrono::milliseconds(5));
            continue;
        }
        size_t cap = ring.size();
        size_t n = (h + cap - t) % cap;
        n -= n % channels;
        chunk.resize(n);
        for (size_t i = 0; i < n; i++) chunk[i] = ring[(t + i) % cap];
        tail.store((t + n) % cap, std::memory_order_release);
        if (file) { fwrite(chunk.data(), sizeof(float), n, file); written.fetch_add((int64_t) (n / channels)); }
        if (written.load() % (rate * 2) < (int64_t) (n / channels)) fflush(file);   // flushed every couple of seconds
    }
    finishHeader();
}

void Recorder::finishHeader() {
    if (!file) return;
    fflush(file);
    long end = ftell(file);
    uint32_t dataBytes = (uint32_t) (end - 44);
    fseek(file, 4, SEEK_SET); put32(file, dataBytes + 36);
    fseek(file, 40, SEEK_SET); put32(file, dataBytes);
    fclose(file);
    file = nullptr;
}

void Recorder::stop() {
    if (!running.load()) return;
    running = false;
    if (writer.joinable()) writer.join();
}

} // namespace stave
