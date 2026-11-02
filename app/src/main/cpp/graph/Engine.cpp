#include "Engine.h"
#include <algorithm>
#include <android/log.h>
#include <cmath>
#include <cstring>

#define TAG "StaveGraph"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

namespace stave {

static const int MAX_BLOCK = 4096;

class Engine::InputCallback : public oboe::AudioStreamDataCallback {
public:
    explicit InputCallback(Engine* e) : engine(e) {}
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream, void* data, int32_t frames) override {
        auto* in = (const float*) data;
        int ch = stream->getChannelCount();
        float peak = 0;
        for (int i = 0; i < frames * ch; i++) { float a = std::fabs(in[i]); if (a > peak) peak = a; }
        float old = engine->inPeak.load(std::memory_order_relaxed);
        while (peak > old && !engine->inPeak.compare_exchange_weak(old, peak)) {}
        // Into the analysis tap, as mono - the raw voice, so the tuner's needle reads what was sung, not the correction.
        if (!engine->tap.empty()) {
            size_t cap = engine->tap.size(); size_t h = engine->tapHead.load(std::memory_order_relaxed);
            for (int f = 0; f < frames; f++) { float m = 0; for (int c = 0; c < ch; c++) m += in[f * ch + c]; engine->tap[h] = m / ch; h = (h + 1) % cap; }
            engine->tapHead.store(h, std::memory_order_release);
        }
        // Live tune: the mono sum through the corrector, then copied to every channel; the recorder
        // takes the corrected voice only when asked (recordTuned), the monitor always does.
        const float* monitored = in;
        bool tuned = engine->liveTune.enabled() && (int) engine->tuneMono.size() >= frames && (int) engine->tuneOut.size() >= frames * ch;
        if (tuned) {
            float* mono = engine->tuneMono.data(); float* out = engine->tuneOut.data();
            for (int f = 0; f < frames; f++) { float m = 0; for (int c = 0; c < ch; c++) m += in[f * ch + c]; mono[f] = m / ch; }
            engine->liveTune.process(mono, frames);
            for (int f = 0; f < frames; f++) for (int c = 0; c < ch; c++) out[f * ch + c] = mono[f];
            monitored = out;
        }
        engine->recorder.push(tuned && engine->recordTuned.load(std::memory_order_relaxed) ? monitored : in, frames);
        // Into the monitor ring, as stereo.
        size_t cap = engine->inRing.size();
        if (cap > 0) {
            size_t h = engine->inHead.load(std::memory_order_relaxed);
            for (int f = 0; f < frames; f++) {
                float l = monitored[f * ch], r = ch > 1 ? monitored[f * ch + 1] : l;
                engine->inRing[h] = l; engine->inRing[(h + 1) % cap] = r;
                h = (h + 2) % cap;
            }
            engine->inHead.store(h, std::memory_order_release);
        }
        return oboe::DataCallbackResult::Continue;
    }
private:
    Engine* engine;
};

Engine::Engine(int preferredSampleRate) : rate(preferredSampleRate > 0 ? preferredSampleRate : 48000) {
    masterBuf.assign(MAX_BLOCK * 2, 0.0f);
    master.buffer.assign(MAX_BLOCK * 2, 0.0f);
}

Engine::~Engine() { stop(); closeInput(); recorder.stop(); for (auto* p : volumeOverrides) delete p; for (auto* p : panOverrides) delete p; }

bool Engine::start() {
    std::lock_guard<std::mutex> g(streamLock);
    if (out) return true;
    oboe::AudioStreamBuilder b;
    b.setDirection(oboe::Direction::Output)
     ->setPerformanceMode(lowLatencyOutput ? oboe::PerformanceMode::LowLatency : oboe::PerformanceMode::None)
     // Exclusive goes round the system mixer and its processing; only worth it for monitoring.
     ->setSharingMode(lowLatencyOutput ? oboe::SharingMode::Exclusive : oboe::SharingMode::Shared)
     ->setFormat(oboe::AudioFormat::Float)
     ->setChannelCount(2)
     ->setSampleRate(rate)
     // Only used when the device will not run at the session's rate (a 44.1k project on a 48k
     // phone): High keeps that conversion below hearing, for a few percent of a callback that
     // measured under 20% of a core at 24 tracks.
     ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::High)
     ->setUsage(oboe::Usage::Media)
     ->setContentType(oboe::ContentType::Music)
     ->setDataCallback(this)
     ->setErrorCallback(this);
    oboe::Result r = b.openStream(out);
    if (r != oboe::Result::OK) {
        // Exclusive is refused when another app holds the device; shared is the honest fallback.
        b.setSharingMode(oboe::SharingMode::Shared);
        r = b.openStream(out);
        if (r != oboe::Result::OK) { LOGW("output stream: %s", oboe::convertToText(r)); out.reset(); return false; }
    }
    rate = out->getSampleRate();
    burst = out->getFramesPerBurst();
    if (lowLatencyOutput) {
        // "Safer" in Settings starts at four bursts; either way the tuner grows it on an underrun.
        out->setBufferSizeInFrames(burst * (safeLatency ? 4 : 2));
        latencyTuner = std::make_unique<oboe::LatencyTuner>(*out);
    }
    r = out->requestStart();
    if (r != oboe::Result::OK) { LOGW("start: %s", oboe::convertToText(r)); out->close(); out.reset(); return false; }
    LOGI("output %d Hz, burst %d, buffer %d, %s, %s", rate, burst, out->getBufferSizeInFrames(), out->getSharingMode() == oboe::SharingMode::Exclusive ? "exclusive" : "shared",
         out->getPerformanceMode() == oboe::PerformanceMode::LowLatency ? "low latency" : "normal");
    running.store(true);
    return true;
}

void Engine::stop() {
    std::lock_guard<std::mutex> g(streamLock);
    running.store(false);
    // A pause or a seek asked for and never carried out by a callback is carried out here.
    if (stopRequested.exchange(false)) playing = false;
    { int64_t pending = seekTo.exchange(-1); if (pending >= 0) position.store(pending); }
    if (!out) return;
    out->requestStop();
    latencyTuner.reset();
    out->close();
    out.reset();
}

void Engine::onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) {
    // Headphones out, a route change: Oboe closed the stream; open a new one on the new route.
    LOGW("stream closed: %s; reopening", oboe::convertToText(error));
    running.store(false);
    if (stopRequested.exchange(false)) playing = false;
    { int64_t pending = seekTo.exchange(-1); if (pending >= 0) position.store(pending); }
    { std::lock_guard<std::mutex> g(streamLock); latencyTuner.reset(); out.reset(); }
    start();
}

double Engine::outputLatencyMs() {
    std::lock_guard<std::mutex> g(streamLock);
    if (!out) return 0;
    auto r = out->calculateLatencyMillis();
    return r ? r.value() : (double) out->getBufferSizeInFrames() * 1000.0 / rate;
}

double Engine::inputLatencyMs() {
    std::lock_guard<std::mutex> g(streamLock);
    if (!in) return 0;
    auto r = in->calculateLatencyMillis();
    return r ? r.value() : (double) in->getBufferSizeInFrames() * 1000.0 / rate;
}

void Engine::setLowLatencyOutput(bool on) {
    bool wasOpen;
    { std::lock_guard<std::mutex> g(streamLock); if (lowLatencyOutput == on) return; lowLatencyOutput = on; wasOpen = (bool) out; }
    if (wasOpen) { stop(); start(); }
}

bool Engine::openInput(int channels, int preset, int deviceId) {
    // Monitoring through headphones wants the short path; plain playback does not (Engine.h).
    setLowLatencyOutput(true);
    std::lock_guard<std::mutex> g(streamLock);
    if (in) return true;
    inputCallback = std::make_unique<InputCallback>(this);
    inChannels = channels < 1 ? 1 : (channels > 2 ? 2 : channels);
    inRing.assign((size_t) rate * 2, 0.0f);   // a second of stereo
    inHead = 0; inTail = 0;
    tap.assign(8192, 0.0f); tapHead = 0;
    // A tenth of a second of scratch for the live tuner: more than any input burst Oboe hands over.
    tuneMono.assign((size_t) rate / 10, 0.0f); tuneOut.assign((size_t) rate / 10 * inChannels, 0.0f);
    liveTune.configure(rate);
    oboe::AudioStreamBuilder b;
    b.setDirection(oboe::Direction::Input)
     ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
     ->setSharingMode(oboe::SharingMode::Exclusive)
     ->setFormat(oboe::AudioFormat::Float)
     ->setChannelCount(inChannels)
     ->setSampleRate(rate)
     ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
     ->setInputPreset(preset == 1 ? oboe::InputPreset::VoiceRecognition : oboe::InputPreset::Unprocessed)
     ->setDataCallback(inputCallback.get());
    if (deviceId > 0) b.setDeviceId(deviceId);
    oboe::Result r = b.openStream(in);
    // A chosen device that will not open (unplugged since the list was read) falls back to the system's choice rather than to no input.
    if (r != oboe::Result::OK && deviceId > 0) { b.setDeviceId(oboe::kUnspecified); r = b.openStream(in); }
    if (r != oboe::Result::OK) { b.setSharingMode(oboe::SharingMode::Shared); r = b.openStream(in); }
    if (r != oboe::Result::OK) { LOGW("input stream: %s", oboe::convertToText(r)); in.reset(); return false; }
    r = in->requestStart();
    if (r != oboe::Result::OK) { in->close(); in.reset(); return false; }
    return true;
}

void Engine::closeInput() {
    {
        std::lock_guard<std::mutex> g(streamLock);
        if (!in) return;
        in->requestStop(); in->close(); in.reset();
    }
    setLowLatencyOutput(false);
}

int Engine::readInputTap(float* out, int capacity) {
    if (tap.empty()) return 0;
    size_t cap = tap.size(); size_t h = tapHead.load(std::memory_order_acquire);
    int n = std::min((size_t) capacity, cap);
    // The newest sample is at h-1; copy the last n in order.
    size_t start = (h + cap - (size_t) n) % cap;
    for (int i = 0; i < n; i++) out[i] = tap[(start + i) % cap];
    return n;
}

bool Engine::startRecording(const std::string& path) { return recorder.start(path, rate, inChannels); }
void Engine::stopRecording() { recorder.stop(); }

bool Engine::setSnapshot(const uint8_t* bytes, size_t length) {
    std::lock_guard<std::mutex> g(snapshotLock);
    const Snapshot* previous = current.load();
    auto snap = Snapshot::parse(bytes, length, previous, rate);
    if (!snap) return false;
    // Overrides are per track index; grow to fit.
    while (volumeOverrides.size() < snap->tracks.size()) { volumeOverrides.push_back(new std::atomic<float>(-1.0f)); panOverrides.push_back(new std::atomic<float>(-99.0f)); }
    if (trackStates.size() < snap->tracks.size()) {
        // Grown on the UI thread; the callback only ever indexes within the snapshot it holds, and the
        // vector is not reallocated while a snapshot naming more tracks is current.
        std::vector<TrackState> grown(snap->tracks.size());
        for (size_t i = 0; i < trackStates.size(); i++) grown[i] = std::move(trackStates[i]);
        for (auto& st : grown) if (st.buffer.empty()) st.buffer.assign(MAX_BLOCK * 2, 0.0f);
        trackStates.swap(grown);
        busScratch.assign(snap->tracks.size() * MAX_BLOCK * 2, 0.0f);
    }
    alive.push_back(snap);
    current.store(snap.get());
    retire();
    return true;
}

void Engine::retire() {
    // Keep the current one and the one before it (the callback may still be inside it).
    while (alive.size() > 2) alive.erase(alive.begin());
}

void Engine::setTrackVolume(int track, float g) { if (track >= 0 && track < (int) volumeOverrides.size()) volumeOverrides[track]->store(g); }
void Engine::setTrackPan(int track, float p) { if (track >= 0 && track < (int) panOverrides.size()) panOverrides[track]->store(p); }
void Engine::clearOverrides() { for (auto* v : volumeOverrides) v->store(-1.0f); for (auto* p : panOverrides) p->store(-99.0f); masterOverride.store(-1.0f); }

int Engine::readMeters(float* outv, int capacity) {
    int n = 0;
    for (auto& st : trackStates) { if (n + 2 > capacity) break; outv[n++] = st.meter.peak[0]; outv[n++] = st.meter.peak[1]; st.meter.peak[0] = st.meter.peak[1] = 0; }
    if (n + 2 <= capacity) { outv[n++] = master.meter.peak[0]; outv[n++] = master.meter.peak[1]; master.meter.peak[0] = master.meter.peak[1] = 0; }
    return n;
}

static void configureInserts(TrackState& st, const std::vector<InsertSpec>& inserts, int rate) {
    st.hasEq = st.hasComp = st.hasGate = st.hasLim = false;
    for (const auto& ins : inserts) {
        if (!ins.enabled) continue;
        auto p = [&](size_t i, float d) { return i < ins.params.size() ? ins.params[i] : d; };
        switch (ins.kind) {
            case 1: st.eq.configure(ins.bands); st.hasEq = !ins.bands.empty(); break;
            case 2: st.comp.configure(p(0, -18), p(1, 3), p(2, 6), p(3, 10), p(4, 120), p(5, 0), rate); st.hasComp = true; break;
            case 3: st.lim.configure(p(0, -1), p(1, 5), p(2, 60), rate, 2); st.hasLim = true; break;
            case 4: st.gate.configure(p(0, -45), p(1, 80), p(2, 2), p(3, 150), p(4, 50), rate); st.hasGate = true; break;
            default: break;
        }
    }
}

void Engine::configureStates(const Snapshot& s) {
    for (size_t t = 0; t < s.tracks.size() && t < trackStates.size(); t++) {
        auto& st = trackStates[t];
        if (st.configuredFor == s.generation) continue;
        configureInserts(st, s.tracks[t].inserts, rate);
        st.configuredFor = s.generation;
    }
    if (master.configuredFor != s.generation) { configureInserts(master, s.masterInserts, rate); master.configuredFor = s.generation; }
}

static inline float fadeGain(int shape, float t) {
    if (t <= 0) return 0; if (t >= 1) return 1;
    switch (shape) {
        case 0: return t;
        default:
        case 1: return std::sin(t * (float) M_PI / 2);
        case 2: return 0.5f - 0.5f * std::cos(t * (float) M_PI);
        case 3: return std::sqrt(t);
        case 4: return t * t;
    }
}

static inline float envelopeGain(const std::vector<EnvelopePoint>& env, int64_t local) {
    if (env.empty()) return 1.0f;
    if (local <= env.front().position) return dbToLinear(env.front().gainDb);
    if (local >= env.back().position) return dbToLinear(env.back().gainDb);
    size_t lo = 0, hi = env.size() - 1;
    while (hi - lo > 1) { size_t mid = (lo + hi) / 2; if (env[mid].position <= local) lo = mid; else hi = mid; }
    float t = (float) (local - env[lo].position) / (float) (env[hi].position - env[lo].position);
    return dbToLinear(env[lo].gainDb + (env[hi].gainDb - env[lo].gainDb) * t);
}

void Engine::renderTrack(const Snapshot& s, int t, int64_t pos, int frames, float* buf) {
    std::fill(buf, buf + frames * 2, 0.0f);
    int first = s.clipsByTrackStart[t], last = s.clipsByTrackStart[t + 1];
    for (int ci = first; ci < last; ci++) {
        const Clip& c = s.clips[ci];
        if (c.muted || c.end() <= pos) continue;
        if (c.position >= pos + frames) break;
        const Source& src = *s.sources[c.source];
        if (!src.data) continue;
        int64_t from = std::max(pos, c.position), to = std::min(pos + frames, c.end());
        // The common case, a clip at its own rate, forwards, with no fade or envelope in this
        // block: the Hermite interpolation reduces to the sample itself, so copy with the gain.
        // At 24 tracks the per-sample interpolation took the audio thread to 35% of a core on the
        // moto (1 Nov 2026).
        if (c.stretch == 1.0 && !c.reversed && c.envelope.empty()
            && (c.fadeIn <= 0 || from - c.position >= c.fadeIn)
            && (c.fadeOut <= 0 || to - c.position <= c.length - c.fadeOut)) {
            int64_t s0 = c.offset + (from - c.position);
            const int sc = src.channels;
            for (int64_t tl = from; tl < to; tl++, s0++) {
                int o = (int) (tl - pos) * 2;
                if (s0 < 0 || s0 >= src.frames) continue;
                const float* p = src.data + s0 * sc;
                buf[o] += p[0] * c.gain;
                buf[o + 1] += (sc > 1 ? p[1] : p[0]) * c.gain;
            }
            continue;
        }
        for (int64_t tl = from; tl < to; tl++) {
            int64_t local = tl - c.position;
            double srcPos = c.reversed ? c.offset + (double) (c.length - 1 - local) / c.stretch : c.offset + (double) local / c.stretch;
            int64_t i0 = (int64_t) std::floor(srcPos);
            float frac = (float) (srcPos - i0);
            float g = c.gain;
            if (c.fadeIn > 0 && local < c.fadeIn) g *= fadeGain(c.fadeInShape, (float) local / (float) c.fadeIn);
            if (c.fadeOut > 0 && local >= c.length - c.fadeOut) g *= fadeGain(c.fadeOutShape, (float) (c.length - local) / (float) c.fadeOut);
            if (!c.envelope.empty()) g *= envelopeGain(c.envelope, local);
            int o = (int) (tl - pos) * 2;
            for (int ch = 0; ch < 2; ch++) {
                int sc = ch < src.channels ? ch : src.channels - 1;
                float v = hermite(src.sample(i0 - 1, sc), src.sample(i0, sc), src.sample(i0 + 1, sc), src.sample(i0 + 2, sc), frac);
                buf[o + ch] += v * g;
            }
        }
    }
}

void Engine::mixInto(float* dst, const float* src, int frames, float gain) {
    for (int i = 0; i < frames * 2; i++) dst[i] += src[i] * gain;
}

void Engine::addClicks(float* buf, int64_t pos, int frames) {
    // The metronome: a short decaying sine, 1 kHz on the beat and 1.5 kHz on the bar's first.
    bool metro = metroOn.load() && playing.load();
    int64_t click = clickAt.load();
    double beat = rate * 60.0 / metroBpm.load();
    int beatsPerBar = std::max(1, metroBeats.load());
    for (int f = 0; f < frames; f++) {
        int64_t tl = pos + f;
        if (metro) {
            int64_t k = (int64_t) std::floor(tl / beat);
            int64_t at = (int64_t) std::llround(k * beat);
            if (tl == at) { clickRemaining = rate / 40; clickPhase = 0; clickLevel = metroGain.load() * ((k % beatsPerBar == 0) ? 1.0f : 0.6f); }
        }
        if (click >= 0 && tl == click) { clickRemaining = rate / 40; clickPhase = 0; clickLevel = 0.8f; clickAt.store(-1); click = -1; }
        if (clickRemaining > 0) {
            float env = (float) clickRemaining / (rate / 40.0f);
            float v = std::sin((float) clickPhase) * env * clickLevel;
            clickPhase += 2 * M_PI * (clickLevel > 0.7f ? 1500.0 : 1000.0) / rate;
            buf[f * 2] += v; buf[f * 2 + 1] += v;
            clickRemaining--;
        }
    }
}

void Engine::processBlock(float* outBuf, int frames) {
    std::fill(outBuf, outBuf + frames * 2, 0.0f);
    Snapshot* s = current.load(std::memory_order_acquire);
    int64_t pos = position.load();
    if (s && playing.load()) {
        bool fresh = !blockWasPlaying;
        blockWasPlaying = true;
        configureStates(*s);
        size_t nTracks = s->tracks.size();
        std::fill(busScratch.begin(), busScratch.end(), 0.0f);
        std::fill(masterBuf.begin(), masterBuf.begin() + frames * 2, 0.0f);
        auto routeOf = [&](const Track& t) -> float* {
            if (t.output >= 0 && t.output < (int) nTracks && s->tracks[t.output].kind == 2) return &busScratch[(size_t) t.output * MAX_BLOCK * 2];
            return masterBuf.data();
        };
        // Tracks first, then buses in index order (a bus feeding a later bus works; the other way does not, and the UI keeps that order).
        for (int pass = 0; pass < 2; pass++) {
            for (size_t t = 0; t < nTracks && t < trackStates.size(); t++) {
                const Track& tr = s->tracks[t];
                bool isBus = tr.kind == 2;
                if ((pass == 0 && isBus) || (pass == 1 && !isBus) || tr.kind == 3) continue;
                auto& st = trackStates[t];
                float* buf = st.buffer.data();
                if (isBus) std::copy(busScratch.begin() + t * MAX_BLOCK * 2, busScratch.begin() + t * MAX_BLOCK * 2 + frames * 2, buf);
                else renderTrack(*s, (int) t, pos, frames, buf);
                bool heard = !tr.muted && (isBus || !s->anySolo || tr.solo);
                // A mute or a solo ramps over this block rather than cutting mid-wave; at the
                // start of playback (or of an offline render) it starts where it stands.
                float audibleTarget = heard ? 1.0f : 0.0f;
                if (fresh) st.audible.jump(audibleTarget);
                else if (st.audible.target != audibleTarget) st.audible.set(audibleTarget, frames);
                if (!heard && st.audible.current == 0.0f) { st.meter.peak[0] = st.meter.peak[1] = 0; continue; }
                if (st.hasGate) st.gate.process(buf, frames, 2);
                if (st.hasEq) st.eq.process(buf, frames, 2);
                if (st.hasComp) st.comp.process(buf, frames, 2);
                if (st.hasLim) st.lim.process(buf, frames);
                // A fader under the finger wins; else the automation at this block's start; else the static value.
                float vol = volumeOverrides[t]->load();
                if (vol < 0) vol = tr.volumeAuto.empty() ? tr.volume : dbToLinear(Track::at(tr.volumeAuto, pos, 0.0f));
                float pan = panOverrides[t]->load();
                if (pan < -2) pan = tr.panAuto.empty() ? tr.pan : Track::at(tr.panAuto, pos, 0.0f);
                float pl, pr; panGains(pan, pl, pr);
                st.volume.set(vol, frames); st.panL.set(pl * 1.41421356f, frames); st.panR.set(pr * 1.41421356f, frames);
                for (const auto& send : tr.sends) if (send.pre && send.to >= 0 && send.to < (int) nTracks) mixInto(&busScratch[(size_t) send.to * MAX_BLOCK * 2], buf, frames, send.gain);
                for (int f = 0; f < frames; f++) {
                    float v = st.volume.next() * st.audible.next(), l = st.panL.next(), r = st.panR.next();
                    buf[f * 2] *= v * l; buf[f * 2 + 1] *= v * r;
                    st.meter.feed(buf[f * 2], buf[f * 2 + 1]);
                }
                for (const auto& send : tr.sends) if (!send.pre && send.to >= 0 && send.to < (int) nTracks) mixInto(&busScratch[(size_t) send.to * MAX_BLOCK * 2], buf, frames, send.gain);
                mixInto(routeOf(tr), buf, frames, 1.0f);
            }
        }
        if (master.hasEq) master.eq.process(masterBuf.data(), frames, 2);
        if (master.hasComp) master.comp.process(masterBuf.data(), frames, 2);
        float mv = masterOverride.load(); if (mv < 0) mv = s->masterVolume;
        master.volume.set(mv, frames);
        for (int f = 0; f < frames; f++) { float v = master.volume.next(); masterBuf[f * 2] *= v; masterBuf[f * 2 + 1] *= v; }
        if (master.hasLim) master.lim.process(masterBuf.data(), frames);
        std::copy(masterBuf.begin(), masterBuf.begin() + frames * 2, outBuf);
        // Advance, looping or stopping at the end.
        int64_t next = pos + frames;
        if (loopOn.load() && loopEnd.load() > loopStart.load() && next >= loopEnd.load()) next = loopStart.load() + (next - loopEnd.load());
        else if (playEnd.load() > 0 && next >= playEnd.load()) { next = playEnd.load(); playing = false; playEnd = -1; }
        else if (stopAtEnd.load()) {
            int64_t length = 0;
            for (const auto& c : s->clips) length = std::max(length, c.end());
            if (length > 0 && next >= length) { next = length; playing = false; }
        }
        position.store(next);
        seenGeneration.store(s->generation);
    } else blockWasPlaying = false;
    addClicks(outBuf, pos, frames);
    // Monitoring: whatever the input callback put in the ring.
    if (monitorOn.load() && !inRing.empty()) {
        size_t cap = inRing.size(), t = inTail.load(std::memory_order_relaxed), h = inHead.load(std::memory_order_acquire);
        size_t avail = (h + cap - t) % cap;
        float g = monitorGain.load();
        size_t take = std::min(avail, (size_t) frames * 2);
        // Keep the ring short: skip ahead if it has run away.
        if (avail > (size_t) frames * 8) t = (h + cap - (size_t) frames * 2) % cap, take = frames * 2;
        for (size_t i = 0; i < take; i++) outBuf[i] += inRing[(t + i) % cap] * g;
        inTail.store((t + take) % cap, std::memory_order_release);
    }
    for (int f = 0; f < frames; f++) master.meter.feed(outBuf[f * 2], outBuf[f * 2 + 1]);
}

oboe::DataCallbackResult Engine::onAudioReady(oboe::AudioStream* stream, void* audioData, int32_t numFrames) {
    auto* outBuf = (float*) audioData;
    std::unique_lock<std::mutex> owner(renderLock, std::try_to_lock);
    if (!owner.owns_lock()) {
        // An offline render has the graph: silence, and nothing advances.
        std::fill(outBuf, outBuf + (size_t) numFrames * 2, 0.0f);
        return oboe::DataCallbackResult::Continue;
    }
    // Requests from the UI thread (Engine.h says why they are requests).
    bool wasPlaying = playing.load();
    bool stopping = stopRequested.exchange(false) && wasPlaying;
    int64_t jump = seekTo.exchange(-1);
    if (jump >= 0 && !wasPlaying) { position.store(jump); jump = -1; }
    bool rampUp = (fadeInRequested.exchange(false) || fadeInNext) && wasPlaying;
    fadeInNext = false;
    int done = 0;
    while (done < numFrames) {
        int n = std::min(numFrames - done, MAX_BLOCK);
        processBlock(outBuf + done * 2, n);
        done += n;
    }
    int ramp = std::min(numFrames, FADE_FRAMES);
    bool endedHere = wasPlaying && !playing.load() && !stopping;   // a selection or the song ran out
    if (stopping || jump >= 0 || endedHere) {
        // Down over the first [ramp] frames for a pause or a jump; over the last ones when the
        // play end fell inside this buffer, so the silence after it is reached smoothly.
        int from = endedHere ? numFrames - ramp : 0;
        for (int f = 0; f < numFrames; f++) {
            float g = f < from ? 1.0f : (f < from + ramp ? 1.0f - (float) (f - from + 1) / ramp : 0.0f);
            if (endedHere && f < from) continue;
            outBuf[f * 2] *= g; outBuf[f * 2 + 1] *= g;
        }
        if (stopping) playing = false;
        if (jump >= 0) { position.store(jump); if (!stopping) fadeInNext = true; }
    } else if (rampUp) {
        for (int f = 0; f < ramp; f++) { float g = (float) (f + 1) / ramp; outBuf[f * 2] *= g; outBuf[f * 2 + 1] *= g; }
    }
    // The debug capture is what the speaker is handed, ramps included.
    if (capturing.load(std::memory_order_relaxed)) {
        size_t at = captureAt.load(std::memory_order_relaxed), take = std::min((size_t) numFrames * 2, capture.size() - at);
        std::copy(outBuf, outBuf + take, capture.begin() + (long) at);
        captureAt.store(at + take, std::memory_order_relaxed);
    }
    if (latencyTuner) latencyTuner->tune();
    auto x = stream->getXRunCount();
    if (x) xrunCount.store(x.value());
    return oboe::DataCallbackResult::Continue;
}

int64_t Engine::renderOffline(int64_t from, int64_t to, const std::function<bool(const float*, int)>& sink) {
    // Waits out a callback already inside processBlock, then keeps it out until the end.
    std::lock_guard<std::mutex> owner(renderLock);
    Snapshot* s = current.load();
    if (!s || to <= from) return 0;
    bool wasPlaying = playing.load(); int64_t wasAt = position.load(); bool wasStop = stopAtEnd.load(); bool wasLoop = loopOn.load();
    int64_t wasEnd = playEnd.load();
    shownPlaying.store(wasPlaying); shownPosition.store(wasAt); offlineActive.store(true);
    playing = true; stopAtEnd = false; loopOn = false; playEnd = -1; position.store(from);
    blockWasPlaying = false;   // every mute ramp starts settled: the file is the mix, sample for sample
    for (auto& st : trackStates) st.configuredFor = 0xFFFFFFFFu;
    master.configuredFor = 0xFFFFFFFFu;
    std::vector<float> block((size_t) MAX_BLOCK * 2);
    int64_t at = from;
    bool ok = true;
    while (at < to && ok) {
        int n = (int) std::min<int64_t>(MAX_BLOCK, to - at);
        processBlock(block.data(), n);
        ok = sink(block.data(), n);
        at += n;
    }
    playing = wasPlaying; stopAtEnd = wasStop; loopOn = wasLoop; playEnd = wasEnd; position.store(wasAt);
    offlineActive.store(false);
    for (auto& st : trackStates) st.configuredFor = 0xFFFFFFFFu;
    master.configuredFor = 0xFFFFFFFFu;
    return ok ? to - from : -1;
}

} // namespace stave
