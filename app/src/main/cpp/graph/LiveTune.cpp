#include "LiveTune.h"
#include <cmath>
#include <algorithm>

void LiveTune::configure(int sampleRate) {
    rate = sampleRate > 0 ? sampleRate : 48000;
    ring.assign(WINDOW * 2, 0.0f); ringHead = 0; sinceAnalysis = 0;
    window.assign(WINDOW / 2, 0.0f);
    diff.assign(WINDOW / 4, 0.0f);
    line.assign(SHIFT_WINDOW * 4, 0.0f); writeAt = 0; phase = 0.0;
    ratio = 1.0; wet = 0.0f; lastTargetMidi = -1;
}

void LiveTune::setParams(bool enable, int rootPc, int scaleMask, float s, float retuneMs) {
    root.store(((rootPc % 12) + 12) % 12);
    mask.store((scaleMask & 0xFFF) == 0 ? 0xFFF : (scaleMask & 0xFFF));
    strength.store(std::min(1.0f, std::max(0.0f, s)));
    retune.store(std::max(0.0f, retuneMs));
    if (!enable) { detected.store(0.0f); target.store(0.0f); lastTargetMidi = -1; }
    on.store(enable);
}

// YIN (de Cheveigne and Kawahara, 2002) on the window decimated by two: the difference
// function, its cumulative mean normalisation, the first dip under the threshold, a parabola
// through the dip. Voice sits under 1.2 kHz, so half the rate loses nothing and halves the work.
double LiveTune::detect() {
    const int n = WINDOW / 2;            // decimated window
    const int half = n / 2;              // the difference runs over half the window
    const double decRate = rate / 2.0;
    // The last WINDOW raw samples, newest last, averaged in pairs.
    size_t cap = ring.size();
    float rms = 0;
    for (int i = 0; i < n; i++) {
        size_t a = (ringHead + cap - WINDOW + 2 * i) % cap;
        size_t b = (a + 1) % cap;
        window[i] = 0.5f * (ring[a] + ring[b]);
        rms += window[i] * window[i];
    }
    rms = std::sqrt(rms / n);
    if (rms < SILENCE_RMS) return 0.0;
    const int tauMin = std::max(2, (int) (decRate / 1200.0));
    const int tauMax = std::min(half - 1, (int) (decRate / 60.0));
    double running = 0.0;
    int best = -1;
    diff[0] = 1.0f;
    float prevCmnd = 1.0f, prevPrev = 1.0f;
    for (int tau = 1; tau <= tauMax; tau++) {
        double d = 0.0;
        for (int i = 0; i < half; i++) { double x = window[i] - window[i + tau]; d += x * x; }
        running += d;
        float cmnd = running > 0.0 ? (float) (d * tau / running) : 1.0f;
        diff[tau] = cmnd;
        if (tau >= tauMin + 1 && best < 0 && prevCmnd < YIN_THRESHOLD && prevCmnd <= prevPrev && prevCmnd <= cmnd) { best = tau - 1; }
        prevPrev = prevCmnd; prevCmnd = cmnd;
        if (best >= 0 && tau > best + 1) break;
    }
    if (best < 0) return 0.0;
    // Parabolic interpolation around the dip.
    double t = best;
    if (best > 0 && best + 1 <= tauMax) {
        double a = diff[best - 1], b = diff[best], c = diff[best + 1];
        double denom = a - 2 * b + c;
        if (std::fabs(denom) > 1e-12) t = best + 0.5 * (a - c) / denom;
    }
    if (t <= 0) return 0.0;
    return decRate / t;
}

// LiveTuneRules.targetMidi: the nearest allowed note, or the previous target while the voice is
// still within HOLD_SEMITONES of it.
double LiveTune::targetFor(double midi) {
    int allowed = mask.load();
    if (lastTargetMidi >= 0 && ((allowed >> (((lastTargetMidi % 12) + 12) % 12)) & 1) && std::fabs(midi - lastTargetMidi) <= HOLD_SEMITONES) return lastTargetMidi;
    int rounded = (int) std::lround(midi);
    int best = rounded; double bestDistance = 1e9;
    for (int c = rounded - 6; c <= rounded + 6; c++) {
        if (!((allowed >> (((c % 12) + 12) % 12)) & 1)) continue;
        double d = std::fabs(c - midi);
        if (d < bestDistance) { bestDistance = d; best = c; }
    }
    lastTargetMidi = best;
    return best;
}

float LiveTune::readTap(double delay) const {
    size_t cap = line.size();
    double pos = (double) writeAt - 1.0 - delay;
    while (pos < 0) pos += cap;
    size_t i0 = (size_t) pos % cap; size_t i1 = (i0 + 1) % cap;
    float frac = (float) (pos - std::floor(pos));
    return line[i0] * (1.0f - frac) + line[i1] * frac;
}

void LiveTune::process(float* mono, int frames) {
    if (!enabled()) return;
    const size_t cap = ring.size();
    const double hzA4 = 440.0;
    for (int f = 0; f < frames; f++) {
        float x = mono[f];
        ring[ringHead] = x; ringHead = (ringHead + 1) % cap;
        if (++sinceAnalysis >= HOP) {
            sinceAnalysis = 0;
            double hz = detect();
            double wanted = 1.0;
            if (hz > 0.0) {
                double midi = 69.0 + 12.0 * std::log2(hz / hzA4);
                double tm = targetFor(midi);
                double targetHzNow = hzA4 * std::pow(2.0, (tm - 69.0) / 12.0);
                // LiveTuneRules.correctionRatio: all the way at strength 1, the cents scaled below it.
                wanted = std::pow(targetHzNow / hz, (double) strength.load());
                wanted = std::min(2.0, std::max(0.5, wanted));
                detected.store((float) hz); target.store((float) targetHzNow);
            } else { detected.store(0.0f); }
            // LiveTuneRules.smoothing and follow: a one-pole per hop in the log domain.
            float r = retune.load();
            double coeff = r <= 0.0f ? 1.0 : 1.0 - std::exp(-(double) HOP / ((double) r / 1000.0 * rate));
            ratio = std::exp(std::log(ratio) + (std::log(wanted) - std::log(ratio)) * coeff);
        }
        // The shifter writes every sample and reads two taps a half-cycle apart.
        line[writeAt] = x; writeAt = (writeAt + 1) % line.size();
        bool moving = std::fabs(ratio - 1.0) > 2e-4;
        float wetTarget = moving ? 1.0f : 0.0f;
        wet += (wetTarget - wet) * (1.0f / 256.0f);
        if (moving || wet > 1e-3f) {
            double phaseB = phase + 0.5; if (phaseB >= 1.0) phaseB -= 1.0;
            float envA = (float) std::sin(M_PI * phase), envB = (float) std::sin(M_PI * phaseB);
            float shifted = readTap(phase * SHIFT_WINDOW) * envA + readTap(phaseB * SHIFT_WINDOW) * envB;
            // A quarter-window delay on the dry path so the crossfade between dry and shifted does not jump in time.
            float dry = readTap(SHIFT_WINDOW / 2.0);
            mono[f] = dry * (1.0f - wet) + shifted * wet;
            phase += (1.0 - ratio) / SHIFT_WINDOW;
            while (phase >= 1.0) phase -= 1.0;
            while (phase < 0.0) phase += 1.0;
        } else {
            mono[f] = readTap(SHIFT_WINDOW / 2.0);
        }
    }
}
