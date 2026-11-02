package com.layerbit.stave.audio

import com.layerbit.stave.engine.project.Session
import com.layerbit.stave.native.EngineNative
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * The graph from Kotlin's side: one engine for the process, the current project's snapshot
 * pushed to it on every change, play/pause/seek, and the position and meters read back on a
 * timer into flows the screens collect. Nothing here blocks the audio thread.
 */
class Transport(private val scope: CoroutineScope) {
    private var handle = 0L
    private var generation = 0
    private var projectDir: File? = null
    private var poller: Job? = null

    data class State(val playing: Boolean = false, val position: Long = 0, val recording: Boolean = false, val sampleRate: Int = 48_000, val latencyMs: Double = 0.0, val xruns: Int = 0, val inputPeak: Float = 0f)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val _meters = MutableStateFlow(FloatArray(0))
    /** L/R peaks per track then master, linear, cleared on each read. */
    val meters: StateFlow<FloatArray> = _meters.asStateFlow()

    val available: Boolean get() = EngineNative.loaded && handle != 0L
    val sampleRate: Int get() = if (handle != 0L) EngineNative.sampleRate(handle) else 48_000

    fun open(preferredRate: Int) {
        if (!EngineNative.loaded) return
        if (handle == 0L) handle = EngineNative.create(preferredRate)
        if (handle == 0L) return
        EngineNative.start(handle)
        _state.value = _state.value.copy(sampleRate = EngineNative.sampleRate(handle))
        poller?.cancel()
        poller = scope.launch {
            val buffer = FloatArray(64)
            while (isActive) {
                val h = handle
                if (h != 0L) {
                    // Every new underrun is logged with the output latency it led to: the only
                    // way to read dropouts on a phone without an ear on it.
                    val x = EngineNative.xruns(h)
                    if (x != _state.value.xruns) android.util.Log.i("StaveGraph", "underruns $x, output latency ${"%.1f".format(java.util.Locale.ROOT, EngineNative.outputLatencyMs(h))} ms")
                    val playing = EngineNative.isPlaying(h)
                    val rendered = EngineNative.position(h)
                    val latency = EngineNative.outputLatencyMs(h)
                    if (playing && !_state.value.playing) playStartedAt = rendered
                    // The engine's position is what it has rendered; what is heard is the output's
                    // latency behind it (tens of ms on the normal output path, Engine.h). The
                    // play-head shows what is heard, never before where the play began.
                    val heard = if (playing) maxOf(playStartedAt, rendered - (latency * _state.value.sampleRate / 1000).toLong()).coerceAtMost(rendered) else rendered
                    _state.value = _state.value.copy(
                        playing = playing, position = heard, recording = EngineNative.isRecording(h),
                        latencyMs = latency, xruns = EngineNative.xruns(h), inputPeak = EngineNative.inputPeak(h)
                    )
                    val n = EngineNative.readMeters(h, buffer)
                    _meters.value = buffer.copyOf(n)
                }
                delay(33)
            }
        }
    }

    fun close() {
        poller?.cancel(); poller = null
        if (handle != 0L) { EngineNative.stop(handle); EngineNative.destroy(handle); handle = 0L }
    }

    /** The session the graph should play, with its project folder for the source paths. */
    fun setSession(session: Session, dir: File) {
        if (handle == 0L) return
        projectDir = dir
        generation++
        EngineNative.setSnapshot(handle, SnapshotEncoder.encode(session, dir, generation))
        EngineNative.clearOverrides(handle)
    }

    fun play() { if (handle != 0L) EngineNative.play(handle) }
    fun pause() { if (handle != 0L) EngineNative.pause(handle) }
    fun toggle() { if (_state.value.playing) pause() else play() }
    fun seek(frame: Long) { if (handle != 0L) EngineNative.seek(handle, frame); _state.value = _state.value.copy(position = frame) }
    fun setLoop(range: LongRange?) { if (handle != 0L) EngineNative.setLoop(handle, range?.first ?: 0, range?.let { it.last + 1 } ?: 0, range != null) }
    /** Play stops by itself at [end] (a frame), or runs on when it is -1. */
    fun setPlayEnd(end: Long) { if (handle != 0L) EngineNative.setPlayEnd(handle, end) }
    /** The graph's position now, read straight from the engine (the state flow is polled at 30 Hz). */
    /** Where the sound being heard is, every frame: the rendered position less the output latency (see the poller). */
    fun livePosition(): Long {
        val h = handle
        if (h == 0L) return _state.value.position
        val rendered = EngineNative.position(h)
        if (!_state.value.playing) return rendered
        val lag = (_state.value.latencyMs * _state.value.sampleRate / 1000).toLong()
        return maxOf(playStartedAt, rendered - lag).coerceAtMost(rendered)
    }
    /** Debug builds: record what the callback plays (a diff against the source finds dropouts). */
    private var playStartedAt = 0L
    fun captureStart(seconds: Int) { if (handle != 0L) EngineNative.captureStart(handle, seconds) }
    fun captureSave(file: java.io.File): Long = if (handle != 0L) EngineNative.captureSave(handle, file.path) else -1
    fun setStopAtEnd(on: Boolean) { if (handle != 0L) EngineNative.setStopAtEnd(handle, on) }

    /** A fader under the finger: heard at once, folded into the next snapshot on release. */
    fun liveVolume(trackIndex: Int, gainLinear: Float) { if (handle != 0L) EngineNative.setTrackVolume(handle, trackIndex, gainLinear) }
    fun livePan(trackIndex: Int, pan: Float) { if (handle != 0L) EngineNative.setTrackPan(handle, trackIndex, pan) }
    fun liveMaster(gainLinear: Float) { if (handle != 0L) EngineNative.setMasterVolume(handle, gainLinear) }

    fun openInput(channels: Int, unprocessed: Boolean, deviceId: Int = 0): Boolean = handle != 0L && EngineNative.openInput(handle, channels, if (unprocessed) 0 else 1, deviceId)
    fun closeInput() { if (handle != 0L) EngineNative.closeInput(handle) }
    fun setMonitor(on: Boolean, gainLinear: Float) { if (handle != 0L) EngineNative.setMonitor(handle, on, gainLinear) }
    fun startRecording(path: File): Boolean = handle != 0L && EngineNative.startRecording(handle, path.path)
    fun stopRecording() { if (handle != 0L) EngineNative.stopRecording(handle) }
    fun recordedFrames(): Long = if (handle != 0L) EngineNative.recordedFrames(handle) else 0
    fun droppedFrames(): Long = if (handle != 0L) EngineNative.droppedFrames(handle) else 0
    fun setMetronome(on: Boolean, bpm: Double, beatsPerBar: Int, gainLinear: Float) { if (handle != 0L) EngineNative.setMetronome(handle, on, bpm, beatsPerBar, gainLinear) }
    fun scheduleClick(atFrame: Long) { if (handle != 0L) EngineNative.scheduleClick(handle, atFrame) }
    fun inputLatencyMs(): Double = if (handle != 0L) EngineNative.inputLatencyMs(handle) else 0.0
    /** The last [out].size input samples, mono; 0 when the input is closed. */
    fun readInputTap(out: FloatArray): Int = if (handle != 0L) EngineNative.readInputTap(handle, out) else 0
    fun setLiveTune(on: Boolean, root: Int, scaleMask: Int, strength: Float, retuneMs: Float, recordTuned: Boolean) { if (handle != 0L) EngineNative.setLiveTune(handle, on, root, scaleMask, strength, retuneMs, recordTuned) }
    fun liveTuneHz(): Float = if (handle != 0L) EngineNative.liveTuneHz(handle) else 0f
    fun liveTuneTargetHz(): Float = if (handle != 0L) EngineNative.liveTuneTargetHz(handle) else 0f
    fun setRecordPaused(paused: Boolean) { if (handle != 0L) EngineNative.setRecordPaused(handle, paused) }

    /** The current snapshot rendered to a raw stereo float file; not while playing. Frames, or negative. */
    fun renderOffline(from: Long, to: Long, out: File): Long = if (handle != 0L) EngineNative.renderOffline(handle, from, to, out.path) else -1
}
