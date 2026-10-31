package com.layerbit.stave.engine.dsp

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Time and pitch changed independently: a phase vocoder with identity phase locking (Laroche
 * and Dolson), for the engine's own stretching where Rubber Band is not on the path - the
 * JVM suites, the preview of a stretch, and the fallback on a device whose native library is
 * missing. Pitch shift is a stretch by the inverse followed by a resample back to length.
 *
 * Checked by stretching a 440 Hz tone 2x and reading its length and pitch back (TimePitchChecks).
 */
object TimePitch {

    /**
     * [signal] stretched by [ratio] (2.0 is twice as long, 0.5 half) at [sampleRate]. Mono.
     * Transients are kept sharper by [transientHop]: when a frame's spectral flux jumps, the
     * phase is reset to the analysis phase for that frame.
     */
    fun stretch(signal: FloatArray, ratio: Double, sampleRate: Int, size: Int = 2048): FloatArray {
        if (signal.isEmpty() || ratio <= 0.0) return FloatArray(0)
        if (ratio == 1.0) return signal.copyOf()
        val analysisHop = size / 4
        val synthesisHop = (analysisHop * ratio)
        val window = Windows.hann(size)
        val fft = Fft.of(size)
        val bins = size / 2 + 1
        val outLength = (signal.size * ratio).toInt() + size
        val out = FloatArray(outLength)
        val norm = FloatArray(outLength)
        val re = FloatArray(size); val im = FloatArray(size)
        val lastPhase = FloatArray(bins)
        val sumPhase = FloatArray(bins)
        val expected = FloatArray(bins) { k -> (2 * PI * k * analysisHop / size).toFloat() }
        val lastMag = FloatArray(bins)
        var position = 0.0
        var outPos = 0.0
        var first = true
        while (position + size <= signal.size + size) {
            val start = position.toInt()
            for (i in 0 until size) {
                val j = start + i
                re[i] = if (j < signal.size) signal[j] * window[i] else 0f
                im[i] = 0f
            }
            fft.forward(re, im)
            // Spectral flux for the transient test.
            var flux = 0f; var total = 0f
            val mag = FloatArray(bins); val phase = FloatArray(bins)
            for (k in 0 until bins) {
                mag[k] = hypot(re[k], im[k]); phase[k] = atan2(im[k], re[k])
                val d = mag[k] - lastMag[k]; if (d > 0) flux += d; total += mag[k]
            }
            val transient = !first && total > 0 && flux / total > 0.6f
            // Peaks in the magnitude spectrum lock the phases of the bins around them.
            val peaks = ArrayList<Int>()
            for (k in 1 until bins - 1) if (mag[k] > mag[k - 1] && mag[k] >= mag[k + 1] && mag[k] > 1e-6f) peaks += k
            if (first || transient) {
                for (k in 0 until bins) sumPhase[k] = phase[k]
            } else {
                var p = 0
                for (k in 0 until bins) {
                    // The nearest peak owns this bin's phase advance.
                    while (p < peaks.size - 1 && kotlin.math.abs(peaks[p + 1] - k) < kotlin.math.abs(peaks[p] - k)) p++
                    val peak = if (peaks.isEmpty()) k else peaks[p]
                    var delta = phase[peak] - lastPhase[peak] - expected[peak]
                    delta -= (2 * PI * Math.round(delta / (2 * PI))).toFloat()
                    val trueFreq = expected[peak] + delta
                    val advance = (trueFreq * synthesisHop / analysisHop).toFloat()
                    if (k == peak) sumPhase[k] += advance
                    else sumPhase[k] = sumPhase[peak] + (phase[k] - phase[peak])
                }
                // The peak advances were applied before their followers read them above; redo
                // the followers against the advanced peak phase.
                p = 0
                for (k in 0 until bins) {
                    while (p < peaks.size - 1 && kotlin.math.abs(peaks[p + 1] - k) < kotlin.math.abs(peaks[p] - k)) p++
                    val peak = if (peaks.isEmpty()) k else peaks[p]
                    if (k != peak) sumPhase[k] = sumPhase[peak] + (phase[k] - phase[peak])
                }
            }
            for (k in 0 until bins) { lastPhase[k] = phase[k]; lastMag[k] = mag[k] }
            for (k in 0 until bins) { re[k] = mag[k] * cos(sumPhase[k]); im[k] = mag[k] * sin(sumPhase[k]) }
            for (k in 1 until size / 2) { re[size - k] = re[k]; im[size - k] = -im[k] }
            im[0] = 0f; im[size / 2] = 0f
            fft.inverse(re, im)
            val o = outPos.toInt()
            for (i in 0 until size) {
                val j = o + i
                if (j < outLength) { out[j] += re[i] * window[i]; norm[j] += window[i] * window[i] }
            }
            first = false
            position += analysisHop
            outPos += synthesisHop
        }
        val length = (signal.size * ratio).toInt()
        val result = FloatArray(length)
        for (i in 0 until length) result[i] = if (norm[i] > 1e-3f) out[i] / norm[i] else 0f
        return result
    }

    /** [signal] shifted by [semitones] at the same length: stretched by the inverse factor and resampled back. */
    fun pitchShift(signal: FloatArray, semitones: Double, sampleRate: Int): FloatArray {
        if (semitones == 0.0) return signal.copyOf()
        val factor = Math.pow(2.0, semitones / 12.0)
        val stretched = stretch(signal, factor, sampleRate)
        // Resample by 1/factor: a rate change the resampler is happy to express as two integers.
        val inRate = (sampleRate * factor).toInt()
        val resampled = Resampler(inRate, sampleRate).convert(stretched)
        return if (resampled.size >= signal.size) resampled.copyOf(signal.size) else resampled.copyOf(signal.size)
    }

    /** A speed change with the pitch following, as tape does: a plain resample. */
    fun speed(signal: FloatArray, factor: Double, sampleRate: Int): FloatArray =
        Resampler((sampleRate * factor).toInt().coerceAtLeast(1), sampleRate).convert(signal)

    /** The fractional part helper the resampler shares. */
    internal fun frac(x: Double): Double = x - floor(x)
}
