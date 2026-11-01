// The render graph: one Oboe output stream, an optional input stream, a snapshot of the session
// read lock-free by the callback, and the transport. ARCHITECTURE.md draws it.
#pragma once
#include <atomic>
#include <memory>
#include <mutex>
#include <string>
#include <vector>
#include <oboe/Oboe.h>
#include "Dsp.h"
#include "Recorder.h"
#include "LiveTune.h"
#include "Snapshot.h"

namespace stave {

struct TrackState {
    Ramp volume, panL, panR;
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
    void play() { playing = true; }
    void pause() { playing = false; }
    bool isPlaying() const { return playing.load(); }
    void seek(int64_t frame) { position.store(frame < 0 ? 0 : frame); }
    int64_t getPosition() const { return position.load(); }
    void setLoop(int64_t start, int64_t end, bool enabled) { loopStart = start; loopEnd = end; loopOn = enabled; }
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
    std::mutex streamLock;

    std::atomic<Snapshot*> current{nullptr};
    std::vector<std::shared_ptr<Snapshot>> alive;   // owns every snapshot handed to the callback
    std::atomic<uint32_t> seenGeneration{0};
    std::mutex snapshotLock;

    std::atomic<bool> playing{false};
    std::atomic<int64_t> position{0};
    std::atomic<int64_t> loopStart{0}, loopEnd{0};
    std::atomic<bool> loopOn{false}, stopAtEnd{true};
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
