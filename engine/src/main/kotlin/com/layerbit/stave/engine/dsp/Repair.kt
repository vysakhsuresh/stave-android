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
     * Clicks and crackle. Each sample is predicted from the [ORDER] before it (linear prediction
     * fitted to the 2048 samples around it); what the prediction misses is the residual. Tones
     * leave almost none, noise leaves its own level everywhere, and a click leaves a spike. A
     * spike is mended only if it is both far above the residual's robust level (its median)
     * and far above the largest residual in the 10 ms around it: an attack - a cymbal, a
     * consonant, a snare - keeps the residual high after it starts, a click does not.
     *
     * The first version compared the second difference with its local mean. Every mastered
     * record has attacks sharper than that, and a 320 kbps song came back with seven "clicks"
     * a second flattened into it (2 November). Each run, grown by a sample either side, is
     * replaced by a cubic Hermite curve through the clean neighbours.
     * [sensitivity] 0..1: higher finds more (and risks mending transients that were meant).
     */
    fun declick(samples: FloatArray, sensitivity: Float = 0.5f, maxLength: Int = 64): List<Click> {
        val n = samples.size
        if (n < 4 * ORDER) return emptyList()
        val s = sensitivity.coerceIn(0f, 1f)
        val e = residual(samples)
        val level = robustLevel(e)
        val spike = 14f - 8f * s      // times the median-based deviation: 10 at the default
        val isolation = 4f - 2f * s   // times the largest residual nearby: 3 at the default
        val near = 480
        val found = ArrayList<Click>()
        var i = ORDER
        while (i < n - 2) {
            // Never below -60 dBFS: in a fade to silence the residual is next to nothing and a
            // rounding step in the last bits looked like a click.
            val bar = maxOf(spike * level[i / HOP], 0.001f)
            if (abs(e[i]) <= bar) { i++; continue }
            // The run: on while the residual stays above half the bar, with gaps of two at most.
            var end = i
            var k = i + 1
            while (k < n - 2 && k - i < maxLength) {
                if (abs(e[k]) > bar * 0.5f) end = k else if (k - end > 2) break
                k++
            }
            var peak = 0f
            for (j in i..end) peak = maxOf(peak, abs(e[j]))
            // The prediction is spoiled for ORDER samples after a click; that ringing is the
            // click's own, so it is left out of "nearby".
            var around = 0f
            for (j in (i - near).coerceAtLeast(0) until (i - 3).coerceAtLeast(0)) around = maxOf(around, abs(e[j]))
            for (j in (end + ORDER + 3).coerceAtMost(n) until (end + near).coerceAtMost(n)) around = maxOf(around, abs(e[j]))
            // A note starting leaves the residual busier after it than before; a click leaves the
            // music as it found it. Ten spikes in a 320 kbps song were plucks and consonants that
            // passed the isolation test and failed this one.
            val before = medianAbs(e, (i - 3 - 256).coerceAtLeast(0), (i - 3).coerceAtLeast(0))
            val after = medianAbs(e, (end + ORDER + 3).coerceAtMost(n), (end + ORDER + 3 + 256).coerceAtMost(n))
            val attack = after > ONSET_RISE * before
            if (end - i < maxLength && peak > isolation * around && !attack) {
                val start = (i - 1).coerceAtLeast(2)
                val stop = (end + 2).coerceAtMost(n - 2)
                interpolate(samples, start, stop)
                found += Click(start, stop - start)
            }
            i = end + ORDER + 1
        }
        return found
    }

    private const val ORDER = 16
    private const val HOP = 256
    private const val ONSET_RISE = 1.25f

    // The median, not the RMS: the samples next to a click carry its ringing and would lift an RMS.
    private fun medianAbs(x: FloatArray, from: Int, to: Int): Float {
        if (to <= from) return 0f
        val v = FloatArray(to - from) { abs(x[from + it]) }
        v.sort()
        return v[v.size / 2]
    }

    /** Forward linear-prediction residual, coefficients refitted every [HOP] samples on a Hann-windowed 2048 around them. */
    private fun residual(x: FloatArray): FloatArray {
        val n = x.size
        val e = FloatArray(n)
        val span = 2048
        val win = DoubleArray(span) { 0.5 - 0.5 * cos(2 * PI * (it + 0.5) / span) }
        val r = DoubleArray(ORDER + 1)
        val frame = DoubleArray(span)
        var a = DoubleArray(ORDER + 1)
        var b = 0
        while (b < n) {
            val from = (b + HOP / 2 - span / 2).coerceIn(0, maxOf(0, n - span))
            val len = minOf(span, n - from)
            for (k in 0 until len) frame[k] = x[from + k] * win[k * span / len]
            for (lag in 0..ORDER) { var acc = 0.0; for (k in lag until len) acc += frame[k] * frame[k - lag]; r[lag] = acc }
            // A little white noise on lag 0 keeps Levinson stable on a pure tone or silence.
            r[0] = r[0] * (1 + 1e-4) + 1e-12
            a = levinson(r)
            for (i in b until minOf(b + HOP, n)) {
                var pred = 0.0
                for (k in 1..ORDER) if (i - k >= 0) pred -= a[k] * x[i - k]
                e[i] = (x[i] - pred).toFloat()
            }
            b += HOP
        }
        return e
    }

    private fun levinson(r: DoubleArray): DoubleArray {
        val a = DoubleArray(ORDER + 1); a[0] = 1.0
        val tmp = DoubleArray(ORDER + 1)
        var err = r[0]
        for (m in 1..ORDER) {
            var acc = r[m]
            for (k in 1 until m) acc += a[k] * r[m - k]
            val refl = -acc / err
            for (k in 1 until m) tmp[k] = a[k] + refl * a[m - k]
            for (k in 1 until m) a[k] = tmp[k]
            a[m] = refl
            err *= (1 - refl * refl)
            if (err <= 0) break
        }
        return a
    }

    /** The residual's deviation per [HOP], from the median magnitude over the 2048 around it (1.4826 x MAD). */
    private fun robustLevel(e: FloatArray): FloatArray {
        val n = e.size
        val out = FloatArray((n + HOP - 1) / HOP)
        val span = 2048
        val buf = FloatArray(span)
        for (h in out.indices) {
            val from = (h * HOP + HOP / 2 - span / 2).coerceIn(0, maxOf(0, n - span))
            val len = minOf(span, n - from)
            for (k in 0 until len) buf[k] = abs(e[from + k])
            buf.sort(0, len)
            out[h] = (1.4826f * buf[len / 2]).coerceAtLeast(1e-6f)
        }
        return out
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
