package com.layerbit.stave.engine.dsp

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Decibels and the arithmetic every fader, meter and normaliser shares. */
object Gain {
    const val SILENCE_DB = -144.0

    fun dbToLinear(db: Double): Double = 10.0.pow(db / 20.0)
    fun dbToLinear(db: Float): Float = 10.0.pow(db / 20.0).toFloat()
    fun linearToDb(gain: Double): Double = if (gain <= 0.0) SILENCE_DB else 20.0 * log10(gain)
    fun linearToDb(gain: Float): Float = linearToDb(gain.toDouble()).toFloat()

    /** The largest absolute sample. */
    fun peak(samples: FloatArray, from: Int = 0, to: Int = samples.size): Float {
        var p = 0f
        for (i in from until to) { val a = abs(samples[i]); if (a > p) p = a }
        return p
    }

    fun rms(samples: FloatArray, from: Int = 0, to: Int = samples.size): Float {
        if (to <= from) return 0f
        var s = 0.0
        for (i in from until to) s += samples[i].toDouble() * samples[i]
        return sqrt(s / (to - from)).toFloat()
    }

    /** The linear gain that puts [peak] at [targetDbfs]; 1 when there is no signal to scale. */
    fun normalisePeak(peak: Float, targetDbfs: Float = -1f): Float =
        if (peak <= 0f) 1f else dbToLinear(targetDbfs) / peak

    /** The gain that moves [measuredLufs] to [targetLufs]: loudness is a logarithmic scale, so a difference in LU is a difference in dB. */
    fun normaliseLoudness(measuredLufs: Double, targetLufs: Double): Float =
        if (measuredLufs <= -100.0) 1f else dbToLinear(targetLufs - measuredLufs).toFloat()

    fun apply(samples: FloatArray, gain: Float) {
        if (gain == 1f) return
        for (i in samples.indices) samples[i] *= gain
    }

    /**
     * A constant-power pan: [pan] from -1 (left) to 1 (right) to the (left, right) gains, with
     * the centre at -3 dB each so a mono source keeps its loudness as it moves. The law every
     * DAW defaults to; a -6 dB law is offered on the mixer for people who know why.
     */
    fun panConstantPower(pan: Float): Pair<Float, Float> {
        val angle = (pan.coerceIn(-1f, 1f) + 1f) * 0.25 * Math.PI
        return Pair(cos(angle).toFloat(), sin(angle).toFloat())
    }

    /** The -6 dB linear law: the centre is half on each side and a hard-panned source is unity. */
    fun panLinear(pan: Float): Pair<Float, Float> {
        val p = (pan.coerceIn(-1f, 1f) + 1f) / 2f
        return Pair(1f - p, p)
    }

    /** A gain as a percentage for a fader label, the way Squish's Volume reads: 100% is unity. */
    fun percent(gain: Float): Int = Math.round(gain * 100f)
}
