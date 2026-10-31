package com.layerbit.stave.engine.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Sample-rate conversion by a windowed sinc, polyphase where the ratio is rational.
 *
 * Two uses: a file at one rate landing in a session at another, and the 4x oversampling the
 * true-peak meter needs. Both want the same thing - a low-pass at the lower Nyquist with a
 * stop band well under the noise floor - and a Kaiser window at beta 8.6 gives about -90 dB,
 * which is below what a 16-bit file can hold. Checked by resampling a tone and reading its
 * frequency and level back (ResamplerChecks).
 */
class Resampler(val inRate: Int, val outRate: Int, taps: Int = 48, beta: Double = 8.6) {
    init { require(inRate > 0 && outRate > 0) }
    private val ratio = outRate.toDouble() / inRate
    private val cutoff = minOf(inRate, outRate) * 0.46 / inRate   // as a fraction of the input rate
    private val half = taps / 2
    private val phases = 512
    /** One sinc per fractional phase, so the inner loop is a dot product. */
    private val table: Array<FloatArray> = Array(phases) { p ->
        val frac = p.toDouble() / phases
        val window = Windows.kaiser(taps + 1, beta)
        FloatArray(taps) { i ->
            val x = (i - half) - frac
            val sinc = if (abs(x) < 1e-9) 2 * cutoff else sin(2 * PI * cutoff * x) / (PI * x)
            (sinc * window[i]).toFloat()
        }.also { k ->
            var s = 0f; for (v in k) s += v
            for (i in k.indices) k[i] /= s
        }
    }

    /** Converts a whole mono buffer. The output is round(n * ratio) samples. */
    fun convert(input: FloatArray): FloatArray {
        if (inRate == outRate) return input.copyOf()
        val outLength = Math.round(input.size * ratio).toInt()
        val out = FloatArray(outLength)
        for (j in 0 until outLength) {
            val position = j / ratio
            val centre = position.toInt()
            val frac = position - centre
            val kernel = table[(frac * phases).toInt().coerceIn(0, phases - 1)]
            var acc = 0f
            var idx = centre - half
            for (t in 0 until kernel.size) {
                if (idx in input.indices) acc += input[idx] * kernel[t]
                idx++
            }
            out[j] = acc
        }
        return out
    }

    /** Converts interleaved [channels] channels. */
    fun convertInterleaved(input: FloatArray, channels: Int): FloatArray {
        if (channels == 1) return convert(input)
        val frames = input.size / channels
        val mono = FloatArray(frames)
        var out: FloatArray? = null
        for (c in 0 until channels) {
            for (f in 0 until frames) mono[f] = input[f * channels + c]
            val converted = convert(mono)
            if (out == null) out = FloatArray(converted.size * channels)
            for (f in converted.indices) out[f * channels + c] = converted[f]
        }
        return out ?: FloatArray(0)
    }
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
