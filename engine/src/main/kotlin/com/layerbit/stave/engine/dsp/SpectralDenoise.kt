package com.layerbit.stave.engine.dsp

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Noise taken out from under a sound: a fan, traffic, an air conditioner, tape hiss.
 *
 * A noise profile - the mean power per bin - is learned either from a region the person says
 * is noise alone ([NoiseProfile.learn]) or, with no such region, by minimum statistics over the
 * whole file ([NoiseProfile.estimate]: the smoothed power's minimum over about a second,
 * scaled up since a minimum sits below the mean of the noise it was taken from). The gain per
 * bin is Wiener's with the decision-directed estimate of the signal's share (Ephraim and Malah),
 * which is what stops the warble ("musical noise") of a gain worked out afresh every frame;
 * smoothed across neighbouring bins and never below a floor, so a voice is not gated into holes.
 *
 * Checked by adding white noise to a tone and measuring the noise left (DenoiseChecks).
 */
class NoiseProfile(val bins: Int, val power: FloatArray) {
    companion object {
        /** The mean power per bin over [region], a stretch with nothing but the noise in it. */
        fun learn(stft: Stft, region: FloatArray): NoiseProfile {
            val acc = FloatArray(stft.bins)
            var frames = 0
            stft.analyze(region) { re, im, _ ->
                for (k in 0 until stft.bins) acc[k] += re[k] * re[k] + im[k] * im[k]
                frames++
            }
            if (frames > 0) for (k in acc.indices) acc[k] /= frames
            return NoiseProfile(stft.bins, acc)
        }

        /** Minimum statistics over the whole signal: the floor a bin never goes under is noise. */
        fun estimate(stft: Stft, signal: FloatArray, sampleRate: Int): NoiseProfile {
            val bins = stft.bins
            val smoothed = FloatArray(bins)
            val minimum = FloatArray(bins) { Float.MAX_VALUE }
            val windowFrames = max(1, (1.2 * sampleRate / stft.hop).toInt())
            // Sub-window minima, so the floor follows noise that gets quieter within a second or so.
            val subWindows = 6
            val subMins = Array(subWindows) { FloatArray(bins) { Float.MAX_VALUE } }
            var current = 0
            var inSub = 0
            val subLength = max(1, windowFrames / subWindows)
            var frames = 0
            stft.analyze(signal) { re, im, _ ->
                for (k in 0 until bins) {
                    val p = re[k] * re[k] + im[k] * im[k]
                    smoothed[k] = 0.7f * smoothed[k] + 0.3f * p
                    if (smoothed[k] < subMins[current][k]) subMins[current][k] = smoothed[k]
                }
                inSub++
                if (inSub >= subLength) { inSub = 0; current = (current + 1) % subWindows; subMins[current].fill(Float.MAX_VALUE) }
                frames++
            }
            for (k in 0 until bins) {
                var m = Float.MAX_VALUE
                for (s in subMins) if (s[k] < m) m = s[k]
                minimum[k] = if (m == Float.MAX_VALUE) 0f else m * BIAS
            }
            return NoiseProfile(bins, minimum)
        }

        /** A minimum under-reads the noise's mean by about this much. */
        const val BIAS = 2.2f
    }
}

class SpectralDenoiser(val sampleRate: Int, size: Int = 2048, hop: Int = size / 4) {
    val stft = Stft(size, hop, Windows.hann(size))

    /**
     * Returns [signal] with [profile]'s noise reduced by up to [reductionDb] (a floor on the
     * gain, so the result never has holes) at [strength] 0..1 of the full subtraction.
     */
    fun process(signal: FloatArray, profile: NoiseProfile, reductionDb: Double = 20.0, strength: Float = 1f): FloatArray {
        val bins = stft.bins
        val floor = Gain.dbToLinear(-reductionDb).toFloat()
        val prior = FloatArray(bins) { 1f }
        val gainPrev = FloatArray(bins) { 1f }
        val gain = FloatArray(bins)
        val smooth = FloatArray(bins)
        val over = 1f + 1.5f * strength.coerceIn(0f, 1f)   // oversubtraction: a little more than the measured noise
        return stft.process(signal) { re, im, _ ->
            for (k in 0 until bins) {
                val p = re[k] * re[k] + im[k] * im[k]
                val noise = profile.power[k] * over + 1e-12f
                // Decision-directed a-priori SNR: mostly last frame's estimate, a little of this frame's.
                val posterior = p / noise
                val estimate = 0.98f * prior[k] * gainPrev[k] * gainPrev[k] * posterior.coerceAtLeast(0f) + 0.02f * max(posterior - 1f, 0f)
                val snr = max(estimate, 0.003f)
                prior[k] = snr / max(posterior, 1e-6f)
                val wiener = snr / (1f + snr)
                gain[k] = max(wiener, floor)
            }
            // Smoothed across three bins so a lone bin cannot flicker.
            for (k in 0 until bins) {
                val a = gain[max(k - 1, 0)]; val b = gain[k]; val c = gain[min(k + 1, bins - 1)]
                smooth[k] = (a + 2 * b + c) * 0.25f
            }
            for (k in 0 until bins) {
                val g = 1f - strength + strength * smooth[k]
                re[k] *= g; im[k] *= g
                gainPrev[k] = smooth[k]
            }
            stft.mirror(re, im)
        }
    }

    /** The noise left in a profile, as a level: what the sheet shows beside "Noise floor". */
    fun floorDbfs(profile: NoiseProfile): Double {
        var s = 0.0
        for (p in profile.power) s += p
        // Power per bin back to an RMS level, undoing the window's and the transform's scale.
        val rms = sqrt(s) / stft.size * 2.0
        return Gain.linearToDb(rms)
    }
}
