// The render graph: one Oboe output stream, an optional input stream, a snapshot of the session
// read lock-free by the callback, and the transport. ARCHITECTURE.md draws it.
#pragma once
#include <algorithm>
#include <atomic>
#include <chrono>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>
#include <oboe/Oboe.h>
#include "Dsp.h"
#include "Recorder.h"
#include "LiveTune.h"
#include "Snapshot.h"

namespace stave {

struct TrackState {
    Ramp volume, panL, panR;
    Ramp audible;   // 1 heard, 0 muted or soloed out: a mute ramps over a block instead of cutting
    Eq eq; Compressor comp; Gate gate; Limiter lim;
    bool hasEq = false, hasComp = false, hasGate = false, hasLim = false;
    Meter meter;
    std::vector<float> buffer;   // interleaved stereo, one block
    uint32_t configuredFor = 0xFFFFFFFFu;
};

class Engine : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
public:
    explicit Engine(int preferredSampleRate);
    ~Engine() override;

    bool start();
    void stop();
    int sampleRate() const { return rate; }
    int framesPerBurst() const { return burst; }
    double outputLatencyMs();
    double inputLatencyMs();
    int xruns() const { return xrunCount.load(); }

    // Snapshots. Called from the UI thread; parses, opens sources, swaps atomically. Old
    // snapshots are retired once the callback has moved on.
    bool setSnapshot(const uint8_t* bytes, size_t length);

    // Transport, all atomic.
    // Pause and a seek while playing are requests the callback carries out over its next buffer:
    // a ramp of up to FADE_FRAMES down, then the stop or the jump, and after a jump or a fresh
    // Play a ramp up. Cutting a loud signal in one sample is a click; a player that pops on
    // every pause does not sound like the file. With no stream running they act at once.
    void play() { if (!playing.load()) fadeInRequested.store(true); stopRequested.store(false); playing = true; }
    void pause() { if (playing.load() && running.load()) stopRequested.store(true); else playing = false; }
    // An offline render drives the graph as if playing; the screen, polling, followed it to the
    // song's end and stayed there after "Read the song". It is shown the state from before.
    bool isPlaying() const { return offlineActive.load() ? shownPlaying.load() : playing.load(); }
    void seek(int64_t frame) { int64_t f = frame < 0 ? 0 : frame; if (playing.load() && running.load()) seekTo.store(f); else position.store(f); }
    int64_t getPosition() const { return offlineActive.load() ? shownPosition.load() : position.load(); }
    void setLoop(int64_t start, int64_t end, bool enabled) { loopStart = start; loopEnd = end; loopOn = enabled; }
    // Play stops by itself here (a selection heard once); -1 for no stop short of the end.
    void setPlayEnd(int64_t end) { playEnd = end; }
    // Debug: what the output callback hands the speaker, kept for a diff against the source.
    void captureStart(int seconds) { std::lock_guard<std::mutex> owner(renderLock); capturing = false; capture.assign((size_t) std::max(1, seconds) * (size_t) rate * 2, 0.0f); captureAt = 0; capturing = true; }
    // A pause is a request the next callback fades out; stopping the capture straight after asking
    // cut it off before the fade, so the capture never showed the ramp it exists to check. Waits
    // (a debug build, at most 100 ms) for that callback; the lock then waits out its end.
    size_t captureStop() {
        for (int i = 0; i < 50 && stopRequested.load() && running.load(); i++) std::this_thread::sleep_for(std::chrono::milliseconds(2));
        std::lock_guard<std::mutex> owner(renderLock); capturing = false; return captureAt.load();
    }
    const float* captureData() const { return capture.data(); }
    void setStopAtEnd(bool v) { stopAtEnd = v; }

    // Live overrides for a fader under the finger: applied until the next snapshot carries the value.
    void setTrackVolume(int track, float gainLinear);
    void setTrackPan(int track, float pan);
    void setMasterVolume(float gainLinear) { masterOverride.store(gainLinear); }
    void clearOverrides();

    // Meters: per track L/R peak since the last read, then master L/R, then input L/R; cleared on read.
    int readMeters(float* out, int capacity);

    // Input, monitoring, recording.
    // deviceId is Android's AudioDeviceInfo id for a chosen input (a USB interface, a Bluetooth mic); 0 lets the system choose.
    bool openInput(int channels, int preset, int deviceId = 0);
    void closeInput();
    void setMonitor(bool on, float gainLinear) { monitorOn = on; monitorGain = gainLinear; }
    bool startRecording(const std::string& path);
    void stopRecording();
    int64_t recordedFrames() const { return recorder.framesWritten(); }
    int64_t droppedFrames() const { return recorder.framesDropped(); }
    bool recording() const { return recorder.recording(); }
    float inputPeak() { float p = inPeak.exchange(0.0f); return p; }
    // The most recent input samples (mono, the newest last), for the tuner; returns how many were copied.
    int readInputTap(float* out, int capacity);
    // Live pitch correction on the monitored input (LiveTune.h); recordTuned sends the corrected voice to the take too.
    void setLiveTune(bool on, int rootPc, int scaleMask, float strength, float retuneMs, bool recordTuned) { liveTune.setParams(on, rootPc, scaleMask, strength, retuneMs); this->recordTuned.store(recordTuned); }
    float liveTuneHz() const { return liveTune.detectedHz(); }
    float liveTuneTargetHz() const { return liveTune.targetHz(); }
    void setRecordPaused(bool paused) { recorder.setPaused(paused); }

    // Metronome: a click on every beat, accented on the bar.
    void setMetronome(bool on, double bpm, int beatsPerBar, float gainLinear) { metroOn = on; metroBpm = bpm; metroBeats = beatsPerBar; metroGain = gainLinear; }

    // A test tone out of the speaker for the loopback latency measurement: one click at [when].
    void scheduleClick(int64_t atFrame) { clickAt.store(atFrame); }

    // Offline: renders [from, to) of the current snapshot into [out] (interleaved stereo),
    // calling the graph as the callback would. Not while the stream is running.
    int64_t renderOffline(int64_t from, int64_t to, std::vector<float>& out);

    // Oboe callbacks.
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream, void* audioData, int32_t numFrames) override;
    void onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) override;

private:
    int rate;
    int burst = 192;
    std::shared_ptr<oboe::AudioStream> out, in;
    // Raises the output buffer a burst at a time whenever the stream underruns (Oboe's own).
    // The buffer was fixed at two bursts and never grew: on the moto the fast mixer found the
    // track empty 322 times to 99 full while a song played, which is what "the sound is
    // terrible" was. Created with the stream, called from the callback, as Oboe intends.
    std::unique_ptr<oboe::LatencyTuner> latencyTuner;
    bool safeLatency = false;
    std::mutex streamLock;

    std::atomic<Snapshot*> current{nullptr};
    std::vector<std::shared_ptr<Snapshot>> alive;   // owns every snapshot handed to the callback
    std::atomic<uint32_t> seenGeneration{0};
    std::mutex snapshotLock;

    std::atomic<bool> playing{false};
    std::atomic<bool> stopRequested{false}, fadeInRequested{false}, running{false};
    std::atomic<int64_t> seekTo{-1};
    bool fadeInNext = false;          // the callback's own: a jump just happened, ramp the next buffer up
    bool blockWasPlaying = false;     // processBlock's own: false makes the mute ramps start where they stand
    static constexpr int FADE_FRAMES = 256;
    std::atomic<int64_t> position{0};
    std::atomic<bool> offlineActive{false}, shownPlaying{false}; std::atomic<int64_t> shownPosition{0};
    std::atomic<int64_t> loopStart{0}, loopEnd{0};
    std::atomic<bool> loopOn{false}, stopAtEnd{true};
    std::atomic<int64_t> playEnd{-1};
    std::vector<float> capture; std::atomic<size_t> captureAt{0}; std::atomic<bool> capturing{false};
    std::atomic<int> xrunCount{0};

    std::vector<TrackState> trackStates;
    TrackState master;
    std::vector<std::atomic<float>*> volumeOverrides, panOverrides;
    std::atomic<float> masterOverride{-1.0f};
    std::vector<float> busScratch;   // interleaved stereo, per bus
    std::vector<float> masterBuf;
    // processBlock's state (position, playing, the track and master buffers and their DSP) has
    // one owner at a time: the output callback, or renderOffline. The callback only try_locks
    // (it never waits) and plays silence while a render holds it. Without this the callback
    // ran processBlock alongside the render on the phone: the first export came out 192 frames
    // (one burst) late with bursts mixed in twice at +6 dB, and the play-head jumped on its own.
    std::mutex renderLock;

    // Input ring for monitoring: the input callback writes, the output callback reads.
    std::vector<float> inRing; std::atomic<size_t> inHead{0}, inTail{0}; int inChannels = 1;
    std::atomic<bool> monitorOn{false}; std::atomic<float> monitorGain{1.0f};
    std::atomic<float> inPeak{0.0f};
    // A tap of the input for analysis on the UI side: a ring the input callback writes and Kotlin reads whole.
    std::vector<float> tap; std::atomic<size_t> tapHead{0};
    Recorder recorder;
    LiveTune liveTune;
    std::atomic<bool> recordTuned{false};
    // Scratch for the tuned input, sized in openInput: the mono the tuner works on and it copied back to every channel.
    std::vector<float> tuneMono, tuneOut;
    class InputCallback;
    std::unique_ptr<InputCallback> inputCallback;

    std::atomic<bool> metroOn{false}; std::atomic<double> metroBpm{120.0}; std::atomic<int> metroBeats{4}; std::atomic<float> metroGain{0.5f};
    std::atomic<int64_t> clickAt{-1};
    double clickPhase = 0; int clickRemaining = 0; float clickLevel = 0;

    void processBlock(float* outInterleaved, int frames);
    void renderTrack(const Snapshot& s, int t, int64_t pos, int frames, float* buf);
    void configureStates(const Snapshot& s);
    void mixInto(float* dst, const float* src, int frames, float gain);
    void addClicks(float* buf, int64_t pos, int frames);
    void retire();
    friend class InputCallback;
};

} // namespace stave
