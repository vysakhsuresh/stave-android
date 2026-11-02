#include "Dsp.h"
#include <algorithm>

namespace stave {

// Mirrors engine/dsp/Biquad.kt matchedPeaking (Vicanek 2016): poles mapped exactly, zeros matched to
// the analog band at DC, the centre and Nyquist. BiquadChecks measures the Kotlin against the analog
// curve; the live EQ and the export must agree, so the two change together.
static BiquadCoefficients matchedPeaking(double f0, int sampleRate, double q, double gainDb) {
    if (gainDb == 0.0) return {};
    double w0 = 2.0 * M_PI * f0 / sampleRate;
    double g = std::pow(10.0, gainDb / 20.0);
    double zeta = 1.0 / (2.0 * q * std::sqrt(g));
    double decay = std::exp(-zeta * w0);
    double a1 = zeta <= 1.0 ? -2.0 * decay * std::cos(std::sqrt(1.0 - zeta * zeta) * w0)
                            : -2.0 * decay * std::cosh(std::sqrt(zeta * zeta - 1.0) * w0);
    double a2 = decay * decay;
    double sinHalf = std::sin(w0 / 2.0);
    double phi1 = sinHalf * sinHalf, phi0 = 1.0 - phi1, phi2 = 4.0 * phi0 * phi1;
    double aa0 = (1.0 + a1 + a2) * (1.0 + a1 + a2);
    double aa1 = (1.0 - a1 + a2) * (1.0 - a1 + a2);
    double aa2 = -4.0 * a2;
    double r1 = (aa0 * phi0 + aa1 * phi1 + aa2 * phi2) * g * g;
    double r2 = (-aa0 + aa1 + 4.0 * (phi0 - phi1) * aa2) * g * g;
    double bb0 = aa0;
    double bb2 = (r1 - r2 * phi1 - bb0) / (4.0 * phi1 * phi1);
    double bb1 = r2 + bb0 + 4.0 * (phi1 - phi0) * bb2;
    double sb1 = std::sqrt(std::max(bb1, 0.0));
    double w = 0.5 * (std::sqrt(bb0) + sb1);
    double b0 = 0.5 * (w + std::sqrt(std::max(w * w + bb2, 0.0)));
    double b1 = 0.5 * (std::sqrt(bb0) - sb1);
    double b2 = -bb2 / (4.0 * b0);
    return {b0, b1, b2, a1, a2};
}

BiquadCoefficients biquad(int kind, double hz, int sampleRate, double q, double gainDb) {
    double f = std::min(std::max(hz, 1.0), sampleRate * 0.499);
    double qq = std::max(q, 0.025);
    double w0 = 2.0 * M_PI * f / sampleRate;
    double cw = std::cos(w0), sw = std::sin(w0);
    double alpha = sw / (2.0 * qq);
    double a = std::pow(10.0, gainDb / 40.0);
    double b0, b1, b2, a0, a1, a2;
    switch (kind) {
        case 0: b0 = (1 - cw) / 2; b1 = 1 - cw; b2 = (1 - cw) / 2; a0 = 1 + alpha; a1 = -2 * cw; a2 = 1 - alpha; break;
        case 1: b0 = (1 + cw) / 2; b1 = -(1 + cw); b2 = (1 + cw) / 2; a0 = 1 + alpha; a1 = -2 * cw; a2 = 1 - alpha; break;
        case 2: b0 = alpha; b1 = 0; b2 = -alpha; a0 = 1 + alpha; a1 = -2 * cw; a2 = 1 - alpha; break;
        case 3: b0 = 1; b1 = -2 * cw; b2 = 1; a0 = 1 + alpha; a1 = -2 * cw; a2 = 1 - alpha; break;
        case 4: b0 = 1 - alpha; b1 = -2 * cw; b2 = 1 + alpha; a0 = 1 + alpha; a1 = -2 * cw; a2 = 1 - alpha; break;
        default:
        case 5: return matchedPeaking(f, sampleRate, qq, gainDb);
        case 6: {
            double s = 2 * std::sqrt(a) * alpha;
            b0 = a * ((a + 1) - (a - 1) * cw + s); b1 = 2 * a * ((a - 1) - (a + 1) * cw); b2 = a * ((a + 1) - (a - 1) * cw - s);
            a0 = (a + 1) + (a - 1) * cw + s; a1 = -2 * ((a - 1) + (a + 1) * cw); a2 = (a + 1) + (a - 1) * cw - s;
            break;
        }
        case 7: {
            double s = 2 * std::sqrt(a) * alpha;
            b0 = a * ((a + 1) + (a - 1) * cw + s); b1 = -2 * a * ((a - 1) + (a + 1) * cw); b2 = a * ((a + 1) + (a - 1) * cw - s);
            a0 = (a + 1) - (a - 1) * cw + s; a1 = 2 * ((a - 1) - (a + 1) * cw); a2 = (a + 1) - (a - 1) * cw - s;
            break;
        }
    }
    return {b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0};
}

static float timeConstant(double ms, int rate) { return ms <= 0 ? 1.0f : (float) (1.0 - std::exp(-1.0 / (rate * ms / 1000.0))); }

void Follower::set(double attackMs, double releaseMs, int rate) { attack = timeConstant(attackMs, rate); release = timeConstant(releaseMs, rate); }

static float compressionDb(float levelDb, float thresholdDb, float ratio, float kneeDb) {
    float over = levelDb - thresholdDb, half = kneeDb / 2;
    if (over <= -half) return 0;
    if (kneeDb <= 0 || over >= half) return over - over / ratio;
    float t = over + half;
    return (1.0f - 1.0f / ratio) * t * t / (2 * kneeDb);
}

void Compressor::configure(float threshold, float r, float knee, float attackMs, float releaseMs, float makeup, int rate) {
    thresholdDb = threshold; ratio = r; kneeDb = knee; makeupDb = makeup; follower.set(attackMs, releaseMs, rate);
}

void Compressor::process(float* buf, int frames, int channels) {
    float makeup = dbToLinear(makeupDb);
    for (int f = 0; f < frames; f++) {
        float peak = 0;
        for (int c = 0; c < channels; c++) { float a = std::fabs(buf[f * channels + c]); if (a > peak) peak = a; }
        float env = follower.process(peak);
        float cut = compressionDb(linearToDb(env), thresholdDb, ratio, kneeDb);
        reductionDb = -cut;
        float g = dbToLinear(-cut) * makeup;
        for (int c = 0; c < channels; c++) buf[f * channels + c] *= g;
    }
}

void Limiter::configure(float ceilingDb, float lookaheadMs, float releaseMs, int rate, int ch) {
    ceiling = dbToLinear(ceilingDb);
    window = std::max(1, (int) (lookaheadMs * rate / 1000));
    depth = window - 1;
    release = timeConstant(releaseMs, rate);
    channels = ch;
    delay.assign((size_t) window * channels, 0.0f);
    dqFrame.assign((size_t) window + 1, 0);
    dqGain.assign((size_t) window + 1, 1.0f);
    dqHead = 0; dqSize = 0;
    minima.assign((size_t) window, 1.0f);
    sum = window;
    frame = 0; at = 0; gain = 1.0f;
}

void Limiter::process(float* buf, int frames) {
    const int cap = window + 1;
    for (int f = 0; f < frames; f++) {
        float peak = 0;
        for (int c = 0; c < channels; c++) { float a = std::fabs(buf[f * channels + c]); if (a > peak) peak = a; }
        float need = peak > ceiling ? ceiling / peak : 1.0f;
        while (dqSize > 0 && dqGain[(dqHead + dqSize - 1) % cap] >= need) dqSize--;
        int back = (dqHead + dqSize) % cap;
        dqFrame[back] = frame; dqGain[back] = need; dqSize++;
        if (dqFrame[dqHead] <= frame - window) { dqHead = (dqHead + 1) % cap; dqSize--; }
        float lowest = dqGain[dqHead];
        int slot = (int) (frame % window);
        sum += lowest - minima[slot];
        minima[slot] = lowest;
        float bound = (float) (sum / window); if (bound > 1.0f) bound = 1.0f;
        gain = bound < gain ? bound : gain + release * (bound - gain);
        int outAt = (at + window - depth) % window;
        for (int c = 0; c < channels; c++) {
            delay[(size_t) at * channels + c] = buf[f * channels + c];
            float y = delay[(size_t) outAt * channels + c] * gain;
            if (y > ceiling) y = ceiling; else if (y < -ceiling) y = -ceiling;
            buf[f * channels + c] = y;
        }
        at = (at + 1) % window;
        frame++;
    }
}

void Gate::configure(float threshold, float range, float attackMs, float releaseMs, float holdMs, int rate) {
    thresholdDb = threshold; rangeDb = range;
    open = timeConstant(attackMs, rate); close = timeConstant(releaseMs, rate);
    holdSamples = (int) (holdMs * rate / 1000);
    follower.set(0.5, 30.0, rate);
}

void Gate::process(float* buf, int frames, int channels) {
    for (int f = 0; f < frames; f++) {
        float peak = 0;
        for (int c = 0; c < channels; c++) { float a = std::fabs(buf[f * channels + c]); if (a > peak) peak = a; }
        float levelDb = linearToDb(follower.process(peak));
        if (!isOpen && levelDb > thresholdDb) { isOpen = true; openFor = 0; }
        else if (isOpen && levelDb < thresholdDb - hysteresisDb) { if (++openFor > holdSamples) isOpen = false; }
        else if (isOpen) openFor = 0;
        float target;
        if (isOpen) target = 1.0f;
        else {
            float under = thresholdDb - levelDb;
            float cut = under <= 0 ? 0 : std::min(under * (ratio - 1.0f), rangeDb);
            target = dbToLinear(-cut);
        }
        gain = target > gain ? gain + open * (target - gain) : gain + close * (target - gain);
        for (int c = 0; c < channels; c++) buf[f * channels + c] *= gain;
    }
}

void Eq::configure(const std::vector<BiquadCoefficients>& coefficients) {
    bands = std::min((int) coefficients.size(), 16);
    for (int c = 0; c < 2; c++) for (int b = 0; b < bands; b++) filters[c][b].c = coefficients[b];
}

void Eq::process(float* buf, int frames, int channels) {
    if (bands == 0) return;
    for (int c = 0; c < channels && c < 2; c++) {
        for (int f = 0; f < frames; f++) {
            float x = buf[f * channels + c];
            for (int b = 0; b < bands; b++) x = filters[c][b].process(x);
            buf[f * channels + c] = x;
        }
    }
}

} // namespace stave
