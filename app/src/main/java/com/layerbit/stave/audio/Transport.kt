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
                    _state.value = _state.value.copy(
                        playing = EngineNative.isPlaying(h), position = EngineNative.position(h), recording = EngineNative.isRecording(h),
                        latencyMs = EngineNative.outputLatencyMs(h), xruns = EngineNative.xruns(h), inputPeak = EngineNative.inputPeak(h)
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
    fun setStopAtEnd(on: Boolean) { if (handle != 0L) EngineNative.setStopAtEnd(handle, on) }

    /** A fader under the finger: heard at once, folded into the next snapshot on release. */
    fun liveVolume(trackIndex: Int, gainLinear: Float) { if (handle != 0L) EngineNative.setTrackVolume(handle, trackIndex, gainLinear) }
    fun livePan(trackIndex: Int, pan: Float) { if (handle != 0L) EngineNative.setTrackPan(handle, trackIndex, pan) }
    fun liveMaster(gainLinear: Float) { if (handle != 0L) EngineNative.setMasterVolume(handle, gainLinear) }

    fun openInput(channels: Int, unprocessed: Boolean): Boolean = handle != 0L && EngineNative.openInput(handle, channels, if (unprocessed) 0 else 1)
    fun closeInput() { if (handle != 0L) EngineNative.closeInput(handle) }
    fun setMonitor(on: Boolean, gainLinear: Float) { if (handle != 0L) EngineNative.setMonitor(handle, on, gainLinear) }
    fun startRecording(path: File): Boolean = handle != 0L && EngineNative.startRecording(handle, path.path)
    fun stopRecording() { if (handle != 0L) EngineNative.stopRecording(handle) }
    fun recordedFrames(): Long = if (handle != 0L) EngineNative.recordedFrames(handle) else 0
    fun droppedFrames(): Long = if (handle != 0L) EngineNative.droppedFrames(handle) else 0
    fun setMetronome(on: Boolean, bpm: Double, beatsPerBar: Int, gainLinear: Float) { if (handle != 0L) EngineNative.setMetronome(handle, on, bpm, beatsPerBar, gainLinear) }
    fun scheduleClick(atFrame: Long) { if (handle != 0L) EngineNative.scheduleClick(handle, atFrame) }
    fun inputLatencyMs(): Double = if (handle != 0L) EngineNative.inputLatencyMs(handle) else 0.0

    /** The current snapshot rendered to a raw stereo float file; not while playing. Frames, or negative. */
    fun renderOffline(from: Long, to: Long, out: File): Long = if (handle != 0L) EngineNative.renderOffline(handle, from, to, out.path) else -1
}
