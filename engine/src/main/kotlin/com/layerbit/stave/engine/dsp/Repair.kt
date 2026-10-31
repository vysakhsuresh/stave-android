package com.layerbit.stave.engine.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The repair tools: each finds one kind of damage and mends it from the samples either side.
 * Pure, offline, in place on a mono channel, each checked by injecting its fault into a clean
 * tone and measuring what is left (RepairChecks).
 */
object Repair {

    /** Where a click was found and how long it ran, so the sheet can list and the strip can mark them. */
    data class Click(val position: Int, val length: Int)

    /**
     * Clicks and crackle: a sample run whose second difference stands far above the local
     * median is a discontinuity no acoustic sound makes. Each run, grown by a sample either side,
     * is replaced by a cubic Hermite curve through the clean neighbours.
     * [sensitivity] 0..1: higher finds more (and risks mending transients that were meant).
     */
    fun declick(samples: FloatArray, sensitivity: Float = 0.5f, maxLength: Int = 64): List<Click> {
        val n = samples.size
        if (n < 16) return emptyList()
        // Second difference: a click is a kink, music is smooth at the sample scale.
        val d2 = FloatArray(n)
        for (i in 1 until n - 1) d2[i] = abs(samples[i + 1] - 2 * samples[i] + samples[i - 1])
        // Local scale from a sliding window's mean absolute second difference.
        val window = 512
        val threshold = 14f - 10f * sensitivity.coerceIn(0f, 1f)   // 4x the local mean at full sensitivity, 14x at none
        val found = ArrayList<Click>()
        var i = 1
        var sum = 0f
        var count = 0
        val head = minOf(window, n)
        for (k in 0 until head) { sum += d2[k]; count++ }
        while (i < n - 1) {
            // Keep the window centred, roughly.
            val from = (i - window / 2).coerceAtLeast(0)
            val to = (from + window).coerceAtMost(n)
            if (i % 64 == 0) { sum = 0f; count = 0; for (k in from until to) { sum += d2[k]; count++ } }
            val local = (sum / count.coerceAtLeast(1)).coerceAtLeast(1e-5f)
            if (d2[i] > local * threshold) {
                var end = i + 1
                while (end < n - 1 && end - i < maxLength && d2[end] > local * threshold * 0.5f) end++
                val start = (i - 1).coerceAtLeast(1)
                val stop = (end + 1).coerceAtMost(n - 2)
                interpolate(samples, start, stop)
                found += Click(start, stop - start)
                i = stop + 1
            } else i++
        }
        return found
    }

    /**
     * Replaces [from, to) with a cubic Hermite curve through the two samples before and the two
     * after, so the mend has the neighbours' slope as well as their level.
     */
    fun interpolate(samples: FloatArray, from: Int, to: Int) {
        val n = samples.size
        val a = from - 1; val b = to
        if (a < 1 || b > n - 2 || to <= from) return
        val p0 = samples[a]; val p1 = samples[b]
        val m0 = (samples[a] - samples[a - 1]); val m1 = (samples[b + 1] - samples[b])
        val span = (b - a).toFloat()
        for (i in from until to) {
            val t = (i - a) / span
            val t2 = t * t; val t3 = t2 * t
            samples[i] = (2 * t3 - 3 * t2 + 1) * p0 + (t3 - 2 * t2 + t) * m0 * span + (-2 * t3 + 3 * t2) * p1 + (t3 - t2) * m1 * span
        }
    }

    /**
     * Clipped runs - samples sitting at or above [thresholdOfFullScale] for two or more in a
     * row - replaced by a curve through their neighbours, which puts back some of the peak the
     * converter cut off. The result can exceed 1.0; the caller normalises after.
     */
    fun declip(samples: FloatArray, thresholdOfFullScale: Float = 0.985f): Int {
        val n = samples.size
        var mended = 0
        var i = 1
        while (i < n - 1) {
            if (abs(samples[i]) >= thresholdOfFullScale) {
                var end = i
                while (end < n - 1 && abs(samples[end]) >= thresholdOfFullScale) end++
                if (end - i >= 2 && i >= 2 && end <= n - 2) {
                    peakInterpolate(samples, i, end)
                    mended++
                }
                i = end + 1
            } else i++
        }
        return mended
    }

    /** A clipped run becomes a parabola-ish arc continuing the slopes in and out of it. */
    private fun peakInterpolate(samples: FloatArray, from: Int, to: Int) {
        val slopeIn = samples[from - 1] - samples[from - 2]
        val slopeOut = samples[to + 1] - samples[to]
        val span = (to - from + 1).toFloat()
        val p0 = samples[from - 1]; val p1 = samples[to]
        for (i in from until to) {
            val t = (i - from + 1) / span
            val t2 = t * t; val t3 = t2 * t
            // A cubic through the two flat ends with the slopes the signal arrived and left with:
            // for a sine it reaches within a few percent of the peak the converter cut off.
            samples[i] = (2 * t3 - 3 * t2 + 1) * p0 + (t3 - 2 * t2 + t) * slopeIn * span + (-2 * t3 + 3 * t2) * p1 + (t3 - t2) * slopeOut * span
        }
    }

    /** Takes a DC offset out: the mean over the buffer, which an AC-coupled chain never carried. */
    fun removeDc(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        var s = 0.0
        for (v in samples) s += v
        val mean = (s / samples.size).toFloat()
        for (i in samples.indices) samples[i] -= mean
        return mean
    }

    /**
     * The mains fundamental in [samples]: 50 or 60 Hz, or a few hertz either side as a generator
     * drifts, found by Goertzel over 45..65 Hz at a quarter-hertz step. Returns the frequency
     * and its level in dBFS, or null when nothing there stands 20 dB above its neighbours.
     */
    fun findHum(samples: FloatArray, sampleRate: Int): Pair<Double, Double>? {
        val n = minOf(samples.size, sampleRate * 4)
        if (n < sampleRate) return null
        // Hann-windowed, so a neighbouring tone's sidelobes do not fill the band; the coherent
        // gain of a Hann window is one half, hence the 4/n in the amplitude.
        val window = Windows.hann(n)
        val powers = ArrayList<Double>()
        var bestHz = 0.0; var best = 0.0
        var hz = 45.0
        while (hz <= 65.0) {
            val p = goertzelPower(samples, window, n, hz, sampleRate)
            powers += p
            if (p > best) { best = p; bestHz = hz }
            hz += 0.25
        }
        val median = powers.sorted()[powers.size / 2]
        if (best <= 0.0 || best < median * 1000.0) return null   // 30 dB above the band's typical bin
        val amplitude = 4 * sqrt(best) / n
        return bestHz to Gain.linearToDb(amplitude)
    }

    private fun goertzelPower(samples: FloatArray, window: FloatArray, n: Int, hz: Double, sampleRate: Int): Double {
        val w = 2 * PI * hz / sampleRate
        val coefficient = 2 * cos(w)
        var s0: Double; var s1 = 0.0; var s2 = 0.0
        for (i in 0 until n) {
            s0 = samples[i] * window[i] + coefficient * s1 - s2
            s2 = s1; s1 = s0
        }
        return s1 * s1 + s2 * s2 - coefficient * s1 * s2
    }

    /**
     * Notches at [fundamentalHz] and its harmonics up to [harmonics] (or the Nyquist), each a
     * narrow biquad at [q], run forwards over the buffer. Hum is steady, so a narrow notch takes
     * it and leaves a voice an octave away untouched.
     */
    fun dehum(samples: FloatArray, sampleRate: Int, fundamentalHz: Double, harmonics: Int = 8, q: Double = 35.0) {
        val coefficients = (1..harmonics).map { fundamentalHz * it }.filter { it < sampleRate * 0.45 }
            .map { BiquadCoefficients.of(FilterKind.NOTCH, it, sampleRate, q) }
        if (coefficients.isEmpty()) return
        BiquadCascade(coefficients).process(samples)
    }
}
