package com.layerbit.stave.engine.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * An in-place radix-2 FFT with its twiddles computed once per size.
 *
 * Written here rather than pulled in because the whole of it is sixty lines and a dependency
 * would be a build risk for something this well defined. Every spectral tool in the engine - the
 * spectrogram, the denoiser, the phase vocoder, the chroma, the onset envelope - stands on it, so
 * it is checked against a DFT written the slow way (tools/jvm/FftChecks.kt).
 */
class Fft(val size: Int) {
    init {
        require(size >= 2 && (size and (size - 1)) == 0) { "FFT size must be a power of two, not $size" }
    }

    private val cosTable = DoubleArray(size / 2) { cos(2.0 * PI * it / size) }
    private val sinTable = DoubleArray(size / 2) { sin(2.0 * PI * it / size) }
    private val reversed = IntArray(size).also { table ->
        var bits = 0
        while ((1 shl bits) < size) bits++
        for (i in 0 until size) {
            var r = 0
            var v = i
            for (b in 0 until bits) { r = (r shl 1) or (v and 1); v = v shr 1 }
            table[i] = r
        }
    }

    /** Forward transform of [re] + i[im], in place. Both arrays must be [size] long. */
    fun forward(re: FloatArray, im: FloatArray) = transform(re, im, inverse = false)

    /** Inverse transform, in place, scaled by 1/size so forward then inverse is the identity. */
    fun inverse(re: FloatArray, im: FloatArray) {
        transform(re, im, inverse = true)
        val k = 1f / size
        for (i in 0 until size) { re[i] *= k; im[i] *= k }
    }

    private fun transform(re: FloatArray, im: FloatArray, inverse: Boolean) {
        require(re.size == size && im.size == size) { "buffers must be $size long" }
        for (i in 0 until size) {
            val j = reversed[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= size) {
            val half = len / 2
            val step = size / len
            var start = 0
            while (start < size) {
                var k = 0
                for (j in 0 until half) {
                    val wr = cosTable[k]
                    val wi = if (inverse) sinTable[k] else -sinTable[k]
                    val a = start + j
                    val b = a + half
                    val xr = re[b] * wr - im[b] * wi
                    val xi = re[b] * wi + im[b] * wr
                    re[b] = (re[a] - xr).toFloat()
                    im[b] = (im[a] - xi).toFloat()
                    re[a] = (re[a] + xr).toFloat()
                    im[a] = (im[a] + xi).toFloat()
                    k += step
                }
                start += len
            }
            len = len shl 1
        }
    }

    /**
     * The magnitude of the first half of the spectrum of the real signal [windowed] (which must
     * be [size] long), into [out] (size/2 + 1 bins). [re] and [im] are scratch the caller owns, so
     * an analysis over tens of thousands of frames allocates nothing per frame.
     */
    fun magnitudes(windowed: FloatArray, re: FloatArray, im: FloatArray, out: FloatArray) {
        windowed.copyInto(re)
        im.fill(0f)
        forward(re, im)
        for (k in out.indices) out[k] = hypot(re[k], im[k])
    }

    /** Power (magnitude squared) per bin, same contract as [magnitudes]. */
    fun powers(windowed: FloatArray, re: FloatArray, im: FloatArray, out: FloatArray) {
        windowed.copyInto(re)
        im.fill(0f)
        forward(re, im)
        for (k in out.indices) out[k] = re[k] * re[k] + im[k] * im[k]
    }

    companion object {
        private val cache = HashMap<Int, Fft>()

        /** One instance per size, since the tables are the expensive part. */
        @Synchronized fun of(size: Int): Fft = cache.getOrPut(size) { Fft(size) }

        /** The smallest power of two at or above [n]. */
        fun ceilPow2(n: Int): Int {
            var p = 1
            while (p < n) p = p shl 1
            return p
        }
    }
}

/** The windows a spectral tool chooses between. Periodic forms, so overlap-add sums evenly. */
object Windows {
    fun hann(size: Int): FloatArray = FloatArray(size) { i -> (0.5 - 0.5 * cos(2.0 * PI * i / size)).toFloat() }

    /** The square root of Hann: analysis and synthesis both, at 50% overlap, give back the input exactly. */
    fun sqrtHann(size: Int): FloatArray = FloatArray(size) { i -> sqrt(0.5 - 0.5 * cos(2.0 * PI * i / size)).toFloat() }

    fun hamming(size: Int): FloatArray = FloatArray(size) { i -> (0.54 - 0.46 * cos(2.0 * PI * i / size)).toFloat() }

    /** Blackman-Harris, 4-term: the quietest sidelobes, for measuring one tone beside another. */
    fun blackmanHarris(size: Int): FloatArray = FloatArray(size) { i ->
        val x = 2.0 * PI * i / size
        (0.35875 - 0.48829 * cos(x) + 0.14128 * cos(2 * x) - 0.01168 * cos(3 * x)).toFloat()
    }

    /** Kaiser, by its beta: the resampler's sinc wears it. */
    fun kaiser(size: Int, beta: Double): FloatArray {
        val denominator = besselI0(beta)
        return FloatArray(size) { i ->
            val r = 2.0 * i / (size - 1) - 1.0
            (besselI0(beta * sqrt((1.0 - r * r).coerceAtLeast(0.0))) / denominator).toFloat()
        }
    }

    /** The zeroth-order modified Bessel function, by its series; converges fast for the betas used here. */
    fun besselI0(x: Double): Double {
        var sum = 1.0
        var term = 1.0
        val half = x / 2
        var k = 1
        while (k < 60) {
            term *= (half / k) * (half / k)
            sum += term
            if (term < sum * 1e-12) break
            k++
        }
        return sum
    }

    /** The sum of a window's samples: what a magnitude must be divided by to read a tone's amplitude. */
    fun coherentGain(window: FloatArray): Float {
        var s = 0.0
        for (w in window) s += w
        return (s / window.size).toFloat()
    }
}
