// The DSP the audio callback runs: small, allocation-free, and a port of the Kotlin engine's
// reference (engine/dsp), which the suites check; the two are compared by a null test on the
// phone (docs/PENDING_TESTS.md). Anything that is not needed per sample lives in Kotlin.
#pragma once
#include <cmath>
#include <cstdint>
#include <vector>

namespace stave {

inline float dbToLinear(float db) { return std::pow(10.0f, db / 20.0f); }
inline float linearToDb(float g) { return g <= 0.0f ? -144.0f : 20.0f * std::log10(g); }

// A value that moves to its target over one buffer, so a fader never clicks.
struct Ramp {
    float current = 1.0f, target = 1.0f, step = 0.0f;
    void set(float value, int frames) { target = value; step = (target - current) / (frames > 0 ? frames : 1); }
    inline float next() {
        if (step != 0.0f) {
            current += step;
            if ((step > 0 && current >= target) || (step < 0 && current <= target)) { current = target; step = 0.0f; }
        }
        return current;
    }
    void jump(float value) { current = target = value; step = 0.0f; }
};

struct BiquadCoefficients { double b0 = 1, b1 = 0, b2 = 0, a1 = 0, a2 = 0; };

// RBJ cookbook, but the peaking band is matched to its analog curve. kind: 0 low-pass 1 high-pass 2 band-pass 3 notch 4 all-pass 5 peaking 6 low shelf 7 high shelf.
BiquadCoefficients biquad(int kind, double hz, int sampleRate, double q, double gainDb);

struct Biquad {
    BiquadCoefficients c;
    double s1 = 0, s2 = 0;
    inline float process(float x) {
        double y = c.b0 * x + s1;
        s1 = c.b1 * x - c.a1 * y + s2;
        s2 = c.b2 * x - c.a2 * y;
        return (float) y;
    }
    void reset() { s1 = s2 = 0; }
};

struct Follower {
    float attack = 0, release = 0, level = 0;
    void set(double attackMs, double releaseMs, int rate);
    inline float process(float x) {
        float a = x < 0 ? -x : x;
        level = a > level ? level + attack * (a - level) : level + release * (a - level);
        return level;
    }
};

// Feed-forward compressor with a soft knee, linked across channels; see engine/dsp/Dynamics.kt.
struct Compressor {
    float thresholdDb = -18, ratio = 3, kneeDb = 6, makeupDb = 0;
    Follower follower;
    float reductionDb = 0;
    void configure(float threshold, float r, float knee, float attackMs, float releaseMs, float makeup, int rate);
    void process(float* interleaved, int frames, int channels);
};

// Mirrors engine/dsp/Dynamics.kt Limiter line for line: the gain is the lowest any frame in the
// look-ahead needs, averaged over the look-ahead, so it ramps rather than steps (a one-sample
// gain step on a loud signal is a click). DynamicsChecks executes the Kotlin.
struct Limiter {
    float ceiling = 0.891f;  // -1 dBTP
    int window = 1, depth = 0, at = 0;
    float gain = 1.0f, release = 0.0f;
    std::vector<float> delay;   // interleaved, [window] frames
    std::vector<long long> dqFrame;   // sliding minimum: frames and gains, gains rising from the front
    std::vector<float> dqGain;
    int dqHead = 0, dqSize = 0;
    std::vector<float> minima;  // the last [window] minima, averaged
    double sum = 1.0;
    long long frame = 0;
    int channels = 2;
    void configure(float ceilingDb, float lookaheadMs, float releaseMs, int rate, int channels);
    void process(float* interleaved, int frames);
};

// The last thing before the speaker: every sample at or under [ceiling], and otherwise bit-exact
// a millisecond late. engine/dsp/OutputCeiling.kt is the reference, line for line, and says why.
struct OutputCeiling {
    float ceiling = 0.999f, release = 0.0f, gain = 1.0f;
    int window = 48, channels = 2, at = 0, sinceOver = INT32_MAX;
    std::vector<float> delay, needs;
    void configure(int rate, int channels);
    void reset();
    void process(float* interleaved, int frames);
};

struct Gate {
    float thresholdDb = -45, rangeDb = 80, ratio = 10, hysteresisDb = 4;
    float open = 0, close = 0, gain = 0;
    int holdSamples = 0, openFor = 0;
    bool isOpen = false;
    Follower follower;
    void configure(float threshold, float range, float attackMs, float releaseMs, float holdMs, int rate);
    void process(float* interleaved, int frames, int channels);
};

// A parametric equaliser of up to 16 bands per channel.
struct Eq {
    int bands = 0;
    Biquad filters[2][16];
    void configure(const std::vector<BiquadCoefficients>& coefficients);
    void process(float* interleaved, int frames, int channels);
};

// A peak meter the UI reads: the highest absolute sample since the last read, per channel.
struct Meter {
    float peak[2] = {0, 0};
    inline void feed(float l, float r) {
        float al = l < 0 ? -l : l, ar = r < 0 ? -r : r;
        if (al > peak[0]) peak[0] = al;
        if (ar > peak[1]) peak[1] = ar;
    }
};

// Constant-power pan: left and right gains for pan -1..1.
inline void panGains(float pan, float& l, float& r) {
    float p = pan < -1 ? -1 : (pan > 1 ? 1 : pan);
    float angle = (p + 1.0f) * 0.25f * (float) M_PI;
    l = std::cos(angle); r = std::sin(angle);
}

// Four-point Hermite interpolation between samples, for a source played at another rate.
inline float hermite(float xm1, float x0, float x1, float x2, float t) {
    float c = (x1 - xm1) * 0.5f;
    float v = x0 - x1;
    float w = c + v;
    float a = w + v + (x2 - x0) * 0.5f;
    float b = w + a;
    return ((((a * t) - b) * t + c) * t + x0);
}

} // namespace stave
