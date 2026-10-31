#include "Dsp.h"
#include <algorithm>

namespace stave {

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
        case 5: b0 = 1 + alpha * a; b1 = -2 * cw; b2 = 1 - alpha * a; a0 = 1 + alpha / a; a1 = -2 * cw; a2 = 1 - alpha / a; break;
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
    lookahead = std::max(1, (int) (lookaheadMs * rate / 1000));
    release = timeConstant(releaseMs, rate);
    channels = ch;
    delay.assign((size_t) lookahead * channels, 0.0f);
    peaks.assign(lookahead, 0.0f);
    at = 0; gain = 1.0f;
}

void Limiter::process(float* buf, int frames) {
    for (int f = 0; f < frames; f++) {
        float peak = 0;
        for (int c = 0; c < channels; c++) { float a = std::fabs(buf[f * channels + c]); if (a > peak) peak = a; }
        peaks[at] = peak;
        float ahead = 0;
        for (float p : peaks) if (p > ahead) ahead = p;
        float wanted = ahead > ceiling ? ceiling / ahead : 1.0f;
        gain = wanted < gain ? wanted : gain + release * (wanted - gain);
        for (int c = 0; c < channels; c++) {
            float& slot = delay[(size_t) at * channels + c];
            float old = slot;
            slot = buf[f * channels + c];
            float y = old * gain;
            if (y > ceiling) y = ceiling; else if (y < -ceiling) y = -ceiling;
            buf[f * channels + c] = y;
        }
        at = (at + 1) % lookahead;
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
