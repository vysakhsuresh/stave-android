package com.layerbit.stave.engine.music

import com.layerbit.stave.engine.dsp.Fft
import com.layerbit.stave.engine.dsp.Notes
import com.layerbit.stave.engine.dsp.Stft
import com.layerbit.stave.engine.dsp.Windows
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Pitch-class energy, twelve bins a frame: what the chord and key detectors read.
 *
 * Each STFT bin between [LOW_HZ] and [HIGH_HZ] is credited to the pitch class its
 * frequency is nearest to, and the frame is normalised to sum to one, so a chord's
 * shape is the same loud or quiet. Below 55 Hz the bins are too coarse to name a note
 * and above 4 kHz what is left is mostly harmonics and hiss, both of which smear the
 * shape rather than sharpen it.
 */
object Chroma {
    const val LOW_HZ = 55.0
    const val HIGH_HZ = 4000.0

    /**
     * The widest a bin may be. A semitone at 55 Hz is 3.3 Hz; wider bins would straddle
     * two classes at the bottom of the range. At 44.1 kHz this is a 16384-point frame
     * (370 ms), which is also what sets how sharply a chord change can be placed.
     */
    const val BIN_HZ = 3.0

    /** Frame [i] of the result is centred on `i * hopMs` milliseconds. */
    fun frames(samples: FloatArray, sampleRate: Int, hopMs: Int = 100): List<FloatArray> {
        require(sampleRate > 0) { "sample rate must be positive" }
        require(hopMs > 0) { "hop must be positive" }
        if (samples.isEmpty()) return emptyList()
        val hop = max(1, (hopMs * sampleRate / 1000.0).roundToInt())
        val size = frameSize(sampleRate, hop)
        val stft = Stft(size, hop, Windows.hann(size))
        val classOf = IntArray(stft.bins) { k -> pitchClassOf(stft.binHz(k, sampleRate)) }
        val wanted = (samples.size + hop - 1) / hop
        // Padded by half a frame so frame i is centred on its own time rather than
        // starting there; a chord change then lands on the frame it happens in, not
        // the one half a window later.
        val padded = FloatArray(samples.size + size / 2)
        samples.copyInto(padded, size / 2)
        val out = ArrayList<FloatArray>(wanted)
        stft.analyze(padded) { re, im, frame ->
            if (frame < wanted) {
                val c = FloatArray(12)
                for (k in 0 until stft.bins) {
                    val pc = classOf[k]
                    if (pc >= 0) c[pc] += re[k] * re[k] + im[k] * im[k]
                }
                normalise(c)
                out.add(c)
            }
        }
        return out
    }

    /** The frame the STFT uses at [sampleRate]: bins no wider than [BIN_HZ], and never shorter than the hop. */
    fun frameSize(sampleRate: Int, hop: Int): Int =
        max(Fft.ceilPow2((sampleRate / BIN_HZ).roundToInt()), Fft.ceilPow2(hop))

    /** The pitch class (0 = C) nearest [hz], or -1 outside the range read. */
    fun pitchClassOf(hz: Double): Int {
        if (hz < LOW_HZ || hz > HIGH_HZ) return -1
        return Notes.hzToMidi(hz).roundToInt().mod(12)
    }

    /** Scales [c] to sum to one in place; a frame with nothing in it stays all zeros. */
    fun normalise(c: FloatArray) {
        var total = 0f
        for (v in c) total += v
        if (total <= 1e-10f) { c.fill(0f); return }
        for (i in c.indices) c[i] /= total
    }

    /** The frames summed: the whole piece's pitch-class profile, which the key is read from. */
    fun sum(frames: List<FloatArray>): FloatArray {
        val total = FloatArray(12)
        for (f in frames) for (i in 0 until 12) total[i] += f[i]
        return total
    }
}
