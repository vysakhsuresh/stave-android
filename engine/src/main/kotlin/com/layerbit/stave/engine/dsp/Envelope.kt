package com.layerbit.stave.engine.dsp

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/** The one-pole smoothing coefficient for a time constant: after [ms] the follower has moved 63% of the way. */
fun timeConstant(ms: Double, sampleRate: Int): Float =
    if (ms <= 0.0) 1f else (1.0 - exp(-1.0 / (sampleRate * ms / 1000.0))).toFloat()

/**
 * A level follower with separate attack and release, in either the peak or the RMS sense.
 * Every dynamics processor reads its signal through one of these.
 */
class EnvelopeFollower(sampleRate: Int, attackMs: Double, releaseMs: Double, val rms: Boolean = false) {
    private var attack = timeConstant(attackMs, sampleRate)
    private var release = timeConstant(releaseMs, sampleRate)
    private val rate = sampleRate
    var level = 0f
        private set

    fun setTimes(attackMs: Double, releaseMs: Double) {
        attack = timeConstant(attackMs, rate)
        release = timeConstant(releaseMs, rate)
    }

    /** Feeds one sample and returns the followed level (linear, >= 0). */
    fun process(x: Float): Float {
        val input = if (rms) x * x else abs(x)
        val e = level
        level = if (input > e) e + attack * (input - e) else e + release * (input - e)
        return if (rms) sqrt(level) else level
    }

    fun reset() { level = 0f }
}

/** The ballistics a meter needs: a peak that falls at a steady rate in dB per second, and a hold. */
class PeakMeter(private val sampleRate: Int, fallDbPerSecond: Double = 20.0, holdMs: Double = 1500.0) {
    private val fallPerSample = (fallDbPerSecond / sampleRate).toFloat()
    private val holdSamples = (holdMs * sampleRate / 1000).toInt()
    var peakDb = Gain.SILENCE_DB.toFloat()
        private set
    var holdDb = Gain.SILENCE_DB.toFloat()
        private set
    private var held = 0
    var clipped = false
        private set

    fun process(buffer: FloatArray, from: Int = 0, to: Int = buffer.size) {
        var p = 0f
        for (i in from until to) { val a = abs(buffer[i]); if (a > p) p = a }
        if (p >= 1f) clipped = true
        val db = Gain.linearToDb(p)
        val n = to - from
        peakDb = maxOf(db, peakDb - fallPerSample * n)
        if (db >= holdDb) { holdDb = db; held = 0 } else {
            held += n
            if (held > holdSamples) holdDb = peakDb
        }
    }

    fun resetClip() { clipped = false }
}
