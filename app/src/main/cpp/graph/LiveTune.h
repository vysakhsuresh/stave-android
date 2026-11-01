// Live pitch correction on the monitored input. The rules (which scale note, how far, how
// fast) are engine/dsp/LiveTuneRules.kt line for line - that file is executed on the JVM
// and this one is read against it; the two are compared by ear on the first phone build.
#pragma once
#include <atomic>
#include <cstdint>
#include <vector>

class LiveTune {
public:
    void configure(int sampleRate);
    void setParams(bool on, int rootPc, int scaleMask, float strength, float retuneMs);
    bool enabled() const { return on.load(std::memory_order_relaxed); }
    // Mono, in place. Adds about 11 ms of delay (half the shifter's window) while on.
    void process(float* mono, int frames);
    float detectedHz() const { return detected.load(std::memory_order_relaxed); }
    float targetHz() const { return target.load(std::memory_order_relaxed); }

    static constexpr int WINDOW = 2048;      // analysis window in input samples (43 ms at 48 kHz)
    static constexpr int HOP = 256;          // analysis every this many samples
    static constexpr int SHIFT_WINDOW = 1024; // the shifter's grain: two taps a half apart, Hann-crossfaded
    static constexpr double HOLD_SEMITONES = 0.6;
    static constexpr float YIN_THRESHOLD = 0.15f;
    static constexpr float SILENCE_RMS = 0.002f;

private:
    int rate = 48000;
    std::atomic<bool> on{false};
    std::atomic<int> root{0}, mask{0xFFF};
    std::atomic<float> strength{1.0f}, retune{20.0f};
    std::atomic<float> detected{0.0f}, target{0.0f};

    // Analysis: a ring of the raw input, decimated by two into a window for YIN.
    std::vector<float> ring; size_t ringHead = 0; int sinceAnalysis = 0;
    std::vector<float> window, diff;
    int lastTargetMidi = -1;
    double ratio = 1.0;

    // The shifter: a delay line read by two taps whose delay ramps by (1 - ratio) a sample.
    std::vector<float> line; size_t writeAt = 0;
    double phase = 0.0;      // tap A's position in its cycle, 0..1; tap B is half a cycle on
    float wet = 0.0f;        // 0 dry (ratio is 1), 1 shifted; ramped over a block to avoid a click

    double detect();         // Hz, or 0 for unvoiced/silence
    double targetFor(double midi);
    float readTap(double delaySamples) const;
};
