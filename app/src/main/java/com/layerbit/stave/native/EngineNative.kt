package com.layerbit.stave.native

/**
 * The JNI surface of libstave.so: the Oboe render graph (app/src/main/cpp/graph). Handles are
 * native pointers as longs; 0 is failure. Everything here is raw: audio/Transport is the API.
 */
object EngineNative {
    val loaded: Boolean by lazy {
        runCatching { System.loadLibrary("stave") }
            .onFailure { android.util.Log.w("StaveGraph", "libstave did not load", it) }
            .isSuccess
    }

    @JvmStatic external fun create(sampleRate: Int): Long
    @JvmStatic external fun destroy(engine: Long)
    @JvmStatic external fun start(engine: Long): Boolean
    @JvmStatic external fun stop(engine: Long)
    @JvmStatic external fun sampleRate(engine: Long): Int
    @JvmStatic external fun framesPerBurst(engine: Long): Int
    @JvmStatic external fun outputLatencyMs(engine: Long): Double
    @JvmStatic external fun inputLatencyMs(engine: Long): Double
    @JvmStatic external fun xruns(engine: Long): Int

    @JvmStatic external fun setSnapshot(engine: Long, bytes: ByteArray): Boolean

    @JvmStatic external fun play(engine: Long)
    @JvmStatic external fun pause(engine: Long)
    @JvmStatic external fun isPlaying(engine: Long): Boolean
    @JvmStatic external fun seek(engine: Long, frame: Long)
    @JvmStatic external fun position(engine: Long): Long
    @JvmStatic external fun setLoop(engine: Long, start: Long, end: Long, on: Boolean)
    @JvmStatic external fun setStopAtEnd(engine: Long, on: Boolean)

    @JvmStatic external fun setTrackVolume(engine: Long, track: Int, gain: Float)
    @JvmStatic external fun setTrackPan(engine: Long, track: Int, pan: Float)
    @JvmStatic external fun setMasterVolume(engine: Long, gain: Float)
    @JvmStatic external fun clearOverrides(engine: Long)

    /** Fills [out] with L/R peaks per track, then the master's; returns how many values were written. Clears on read. */
    @JvmStatic external fun readMeters(engine: Long, out: FloatArray): Int

    /** [preset] 0 unprocessed, 1 voice recognition. */
    @JvmStatic external fun openInput(engine: Long, channels: Int, preset: Int): Boolean
    @JvmStatic external fun closeInput(engine: Long)
    @JvmStatic external fun setMonitor(engine: Long, on: Boolean, gain: Float)
    @JvmStatic external fun startRecording(engine: Long, path: String): Boolean
    @JvmStatic external fun stopRecording(engine: Long)
    @JvmStatic external fun isRecording(engine: Long): Boolean
    @JvmStatic external fun recordedFrames(engine: Long): Long
    @JvmStatic external fun droppedFrames(engine: Long): Long
    @JvmStatic external fun inputPeak(engine: Long): Float
    /** The newest input samples (mono, newest last) into [out]; how many were copied. For the tuner. */
    @JvmStatic external fun readInputTap(engine: Long, out: FloatArray): Int
    @JvmStatic external fun setMetronome(engine: Long, on: Boolean, bpm: Double, beatsPerBar: Int, gain: Float)
    /** Live pitch correction on the monitored input: the scale as a 12-bit mask from the root, strength 0..1, the retune time; recordTuned sends it to the take too. */
    @JvmStatic external fun setLiveTune(engine: Long, on: Boolean, root: Int, scaleMask: Int, strength: Float, retuneMs: Float, recordTuned: Boolean)
    @JvmStatic external fun liveTuneHz(engine: Long): Float
    @JvmStatic external fun liveTuneTargetHz(engine: Long): Float
    @JvmStatic external fun setRecordPaused(engine: Long, paused: Boolean)
    @JvmStatic external fun scheduleClick(engine: Long, atFrame: Long)

    /** Renders [from, to) of the current snapshot to a raw interleaved stereo float file. Returns frames, or negative. */
    @JvmStatic external fun renderOffline(engine: Long, from: Long, to: Long, path: String): Long
}

/** Rubber Band, offline: a whole interleaved buffer stretched and shifted. */
object StretchNative {
    @JvmStatic external fun stretch(interleaved: FloatArray, channels: Int, sampleRate: Int, ratio: Double, semitones: Double, formant: Boolean): FloatArray
}

/** TinySoundFont: an SF2/SF3 bank rendering to interleaved stereo. */
object SoundFontNative {
    @JvmStatic external fun open(path: String, sampleRate: Int): Long
    @JvmStatic external fun close(font: Long)
    @JvmStatic external fun presetCount(font: Long): Int
    @JvmStatic external fun presetName(font: Long, index: Int): String
    @JvmStatic external fun noteOn(font: Long, preset: Int, key: Int, velocity: Float)
    @JvmStatic external fun noteOff(font: Long, preset: Int, key: Int)
    @JvmStatic external fun allOff(font: Long)
    @JvmStatic external fun render(font: Long, out: FloatArray, frames: Int)
}
