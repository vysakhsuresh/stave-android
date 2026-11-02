package com.layerbit.stave.engine.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Sample-rate conversion by a windowed sinc, its kernel tabulated at [PHASES] fractional
 * positions and blended linearly between the two nearest.
 *
 * Uses: a file at one rate landing in a session at another, an export to another rate, the
 * speech models' 16 kHz. The first version took the nearest of 512 phases with 48 taps: about
 * -57 dB of error on a 10 kHz tone and a top octave rolling off from 17 kHz. Blending the
 * phases puts the error under -90 dB, and 128 taps under a Kaiser window at beta 10 keep the band
 * flat to about 19.5 kHz at 44.1k with the stop band near -100 dB. Checked by converting tones
 * and comparing them with the same tones computed at the new rate (ResamplerChecks).
 */
class Resampler(val inRate: Int, val outRate: Int, taps: Int = 128, beta: Double = 10.0) {
    init { require(inRate > 0 && outRate > 0) }
    private val ratio = outRate.toDouble() / inRate
    private val cutoff = minOf(inRate, outRate) * 0.47 / inRate   // as a fraction of the input rate
    private val half = taps / 2
    private val width = taps
    /** One kernel per fractional phase, plus the one at a whole sample so blending never runs off the end. */
    private val table: Array<FloatArray> = run {
        val window = Windows.kaiser(taps + 1, beta)
        Array(PHASES + 1) { p ->
            val frac = p.toDouble() / PHASES
            val k = DoubleArray(taps) { i ->
                val x = (i - half) - frac
                val sinc = if (abs(x) < 1e-12) 2 * cutoff else sin(2 * PI * cutoff * x) / (PI * x)
                sinc * window[i]
            }
            val s = k.sum()
            FloatArray(taps) { (k[it] / s).toFloat() }
        }
    }

    /** Output sample [j], reading input frame i through [read] (0 outside the signal). */
    private inline fun sampleAt(j: Long, read: (Long) -> Float): Float {
        val position = j / ratio
        val centre = Math.floor(position).toLong()
        val p = (position - centre) * PHASES
        val pi = p.toInt().coerceIn(0, PHASES - 1)
        val t = (p - pi).toFloat()
        val k0 = table[pi]; val k1 = table[pi + 1]
        var acc = 0.0
        var idx = centre - half
        for (i in 0 until width) {
            val x = read(idx)
            if (x != 0f) acc += x * (k0[i] + t * (k1[i] - k0[i]))
            idx++
        }
        return acc.toFloat()
    }

    private fun outLength(inFrames: Long): Long = Math.round(inFrames * ratio)

    /** Converts a whole mono buffer. The output is round(n * ratio) samples. */
    fun convert(input: FloatArray): FloatArray {
        if (inRate == outRate) return input.copyOf()
        val n = input.size.toLong()
        return FloatArray(outLength(n).toInt()) { j -> sampleAt(j.toLong()) { i -> if (i in 0 until n) input[i.toInt()] else 0f } }
    }

    /** Converts interleaved [channels] channels. */
    fun convertInterleaved(input: FloatArray, channels: Int): FloatArray {
        if (channels == 1) return convert(input)
        if (inRate == outRate) return input.copyOf()
        val frames = input.size / channels
        val outFrames = outLength(frames.toLong()).toInt()
        val out = FloatArray(outFrames * channels)
        for (c in 0 until channels) for (j in 0 until outFrames)
            out[j * channels + c] = sampleAt(j.toLong()) { i -> if (i in 0 until frames) input[(i * channels + c).toInt()] else 0f }
        return out
    }

    /**
     * The same conversion fed in blocks: [Stream.push] returns every output sample whose kernel
     * the input so far covers, [Stream.finish] the rest. The concatenation equals
     * [convertInterleaved] of the whole, sample for sample, so a long export never holds the song.
     */
    inner class Stream(val channels: Int) {
        private var buffer = FloatArray(0)      // interleaved input kept from [bufferStart]
        private var bufferStart = 0L            // input frame index of buffer's first frame
        private var received = 0L
        private var next = 0L                   // next output frame to produce

        fun push(interleaved: FloatArray, frames: Int = interleaved.size / channels): FloatArray {
            if (inRate == outRate) { received += frames; next += frames; return interleaved.copyOf(frames * channels) }
            append(interleaved, frames)
            // Output j needs input up to floor(j / ratio) + half - 1 + 1.
            var last = next
            while (Math.floor(last / ratio).toLong() - half + width <= received) last++
            return produce(last, ended = false)
        }

        fun finish(): FloatArray {
            if (inRate == outRate) return FloatArray(0)
            return produce(outLength(received), ended = true)
        }

        private fun append(x: FloatArray, frames: Int) {
            // Drop what no future output can reach: below centre(next) - half.
            val keepFrom = (Math.floor(next / ratio).toLong() - half - 1).coerceAtLeast(bufferStart)
            val drop = ((keepFrom - bufferStart) * channels).toInt().coerceIn(0, buffer.size)
            val kept = buffer.size - drop
            val grown = FloatArray(kept + frames * channels)
            buffer.copyInto(grown, 0, drop, buffer.size)
            x.copyInto(grown, kept, 0, frames * channels)
            buffer = grown
            bufferStart += drop / channels
            received += frames
        }

        private fun produce(until: Long, ended: Boolean): FloatArray {
            val count = (until - next).coerceAtLeast(0).toInt()
            val out = FloatArray(count * channels)
            val have = bufferStart + buffer.size / channels
            for (c in 0 until channels) for (k in 0 until count) {
                out[k * channels + c] = sampleAt(next + k) { i ->
                    if (i < 0 || i >= have || i < bufferStart || (ended && i >= received)) 0f
                    else buffer[((i - bufferStart) * channels + c).toInt()]
                }
            }
            next += count
            return out
        }
    }

    fun stream(channels: Int) = Stream(channels)

    private companion object { const val PHASES = 512 }
}

/**
 * The true-peak reading of ITU-R BS.1770-4: the signal oversampled by four through a 48-tap
 * low-pass and the largest absolute value read off the oversampled signal. A sample peak
 * can sit 3 dB under a true peak on a hot master; a limiter that trusts samples alone clips
 * on the way to a DAC.
 */
class TruePeak(val channels: Int) {
    private val coefficients: Array<FloatArray> = run {
        val taps = 48
        val window = Windows.kaiser(taps, 8.0)
        // Four phases of a half-band-at-the-original-Nyquist low-pass.
        Array(4) { p ->
            FloatArray(taps / 4) { i ->
                val n = i * 4 + p
                val x = (n - taps / 2 + 0.5) / 4.0
                val sinc = if (abs(x) < 1e-9) 1.0 else sin(PI * x) / (PI * x)
                (sinc * window[n]).toFloat()
            }
        }.also { phasesArr ->
            // Each phase sums to one so a DC input reads as itself.
            for (k in phasesArr) { var s = 0f; for (v in k) s += v; for (i in k.indices) k[i] /= s }
        }
    }
    private val history = Array(channels) { FloatArray(12) }
    private val at = IntArray(channels)
    /** The loudest oversampled value seen since [reset], per channel, linear. */
    val peak = FloatArray(channels)

    fun process(interleaved: FloatArray, frames: Int = interleaved.size / channels) {
        var i = 0
        for (f in 0 until frames) {
            for (c in 0 until channels) {
                val h = history[c]
                h[at[c]] = interleaved[i + c]
                var best = peak[c]
                for (phase in coefficients) {
                    var acc = 0f
                    var idx = at[c]
                    for (t in phase.indices) {
                        acc += h[idx] * phase[t]
                        idx = if (idx == 0) h.size - 1 else idx - 1
                    }
                    val a = abs(acc)
                    if (a > best) best = a
                }
                peak[c] = best
                at[c] = (at[c] + 1) % h.size
            }
            i += channels
        }
    }

    /** The true peak across all channels in dBTP. */
    val dbtp: Double get() = Gain.linearToDb(peak.maxOrNull()?.toDouble() ?: 0.0)

    fun reset() { peak.fill(0f); history.forEach { it.fill(0f) }; at.fill(0) }

    companion object {
        /** One-shot: the true peak of a whole interleaved buffer in dBTP. */
        fun of(interleaved: FloatArray, channels: Int): Double = TruePeak(channels).also { it.process(interleaved) }.dbtp
    }
}
