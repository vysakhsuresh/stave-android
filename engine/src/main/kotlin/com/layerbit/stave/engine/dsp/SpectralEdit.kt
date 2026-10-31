package com.layerbit.stave.engine.dsp

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Editing in time and frequency: a region of the spectrogram turned down, or filled in from
 * what is either side of it, leaving the rest of the sound alone. A cough, a phone ring, a
 * chair squeak live in a box no time-domain cut can reach without taking the voice with it.
 *
 * The spectrogram the screen draws and the edits are the same STFT (2048 at 75% overlap), so a
 * box drawn over the picture is the box that is changed. Executed in SpectralEditChecks.
 */
object SpectralEdit {
    const val SIZE = 2048
    const val HOP = SIZE / 4

    /** A box in the picture: samples along, hertz up. */
    data class Box(val fromSample: Long, val toSample: Long, val lowHz: Double, val highHz: Double)

    /**
     * The magnitude spectrogram of [samples] in dBFS, [bins] rows per frame, frames at [HOP]:
     * what the canvas paints. Columns are frames; the first frame starts at sample 0.
     */
    fun spectrogramDb(samples: FloatArray, sampleRate: Int, size: Int = SIZE, hop: Int = HOP): Array<FloatArray> {
        val stft = Stft(size, hop, Windows.hann(size))
        val frames = ArrayList<FloatArray>()
        val norm = size * Windows.coherentGain(Windows.hann(size)) / 2.0   // a full-scale sine reads 0 dB
        stft.analyze(samples) { re, im, _ ->
            val row = FloatArray(stft.bins)
            for (k in 0 until stft.bins) { val m = hypot(re[k].toDouble(), im[k].toDouble()) / norm; row[k] = if (m <= 1e-9) -180f else (20 * Math.log10(m)).toFloat() }
            frames += row
        }
        return frames.toTypedArray()
    }

    /** Bin k's frequency for the drawn spectrogram. */
    fun binHz(k: Int, sampleRate: Int, size: Int = SIZE): Double = k.toDouble() * sampleRate / size

    /**
     * [samples] with every STFT bin inside [box] scaled by [gainDb] (negative turns it down,
     * -60 removes it), the edges of the box feathered over two bins and two frames so the
     * change does not ring. [boxOffset] is where [samples] start on the box's own clock.
     */
    fun attenuate(samples: FloatArray, sampleRate: Int, box: Box, gainDb: Double, boxOffset: Long = 0): FloatArray {
        val stft = Stft(SIZE, HOP, Windows.hann(SIZE))
        val g = Gain.dbToLinear(gainDb).toFloat()
        val kLo = stft.binOf(box.lowHz, sampleRate); val kHi = stft.binOf(box.highHz, sampleRate)
        val fLo = ((box.fromSample - boxOffset).toDouble() / HOP); val fHi = ((box.toSample - boxOffset).toDouble() / HOP)
        return stft.process(samples) { re, im, frame ->
            // Frames are centred on frame*HOP (after the STFT's own padding); feather a frame either side.
            val ft = frameWeight(frame.toDouble(), fLo, fHi)
            if (ft <= 0f) return@process
            for (k in max(0, kLo - 2)..min(stft.bins - 1, kHi + 2)) {
                val kw = binWeight(k, kLo, kHi)
                val w = 1f - ft * kw * (1f - g)
                re[k] *= w; im[k] *= w
            }
            stft.mirror(re, im)
        }
    }

    /**
     * The box filled in from the frames just before and after it (linear interpolation of
     * magnitude, phase kept), the way a scratch on a photo is filled from its surroundings. For a
     * sound that stopped and started - a click, a cough - this is what makes it never have been.
     */
    fun interpolate(samples: FloatArray, sampleRate: Int, box: Box, boxOffset: Long = 0): FloatArray {
        val stft = Stft(SIZE, HOP, Windows.hann(SIZE))
        val kLo = stft.binOf(box.lowHz, sampleRate); val kHi = stft.binOf(box.highHz, sampleRate)
        val fLo = ((box.fromSample - boxOffset).toDouble() / HOP).toInt(); val fHi = ((box.toSample - boxOffset).toDouble() / HOP).toInt()
        if (fHi <= fLo) return samples.copyOf()
        // First pass: the magnitudes of the frames either side of the box.
        val frames = stft.frameCount(samples.size + SIZE - HOP)
        val before = FloatArray(stft.bins); val after = FloatArray(stft.bins)
        var gotBefore = false; var gotAfter = false
        stft.analyze(samples) { re, im, frame ->
            // analyze() frames start at 0 with no padding; process() pads by SIZE-HOP, i.e. 3 frames. Align by that.
            val f = frame + 3
            if (f == fLo - 1) { for (k in 0 until stft.bins) before[k] = hypot(re[k], im[k]); gotBefore = true }
            if (f == fHi + 1) { for (k in 0 until stft.bins) after[k] = hypot(re[k], im[k]); gotAfter = true }
        }
        if (!gotBefore && !gotAfter) return attenuate(samples, sampleRate, box, -60.0, boxOffset)
        if (!gotBefore) before.indices.forEach { before[it] = after[it] }
        if (!gotAfter) after.indices.forEach { after[it] = before[it] }
        @Suppress("UNUSED_VARIABLE") val unused = frames
        return stft.process(samples) { re, im, frame ->
            if (frame < fLo || frame > fHi) return@process
            val t = ((frame - fLo + 1).toDouble() / (fHi - fLo + 2)).toFloat()
            for (k in max(0, kLo)..min(stft.bins - 1, kHi)) {
                val wanted = before[k] * (1 - t) + after[k] * t
                val have = hypot(re[k], im[k])
                val scale = if (have > 1e-9f) wanted / have else 0f
                re[k] *= scale; im[k] *= scale
            }
            stft.mirror(re, im)
        }
    }

    private fun frameWeight(frame: Double, lo: Double, hi: Double): Float = when {
        frame < lo - 1 || frame > hi + 1 -> 0f
        frame < lo -> (frame - (lo - 1)).toFloat()
        frame > hi -> ((hi + 1) - frame).toFloat()
        else -> 1f
    }.coerceIn(0f, 1f)

    private fun binWeight(k: Int, lo: Int, hi: Int): Float = when {
        k < lo - 2 || k > hi + 2 -> 0f
        k < lo -> (k - (lo - 3)) / 3f
        k > hi -> ((hi + 3) - k) / 3f
        else -> 1f
    }.coerceIn(0f, 1f)
}
