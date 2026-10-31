package com.layerbit.stave.engine.dsp

import kotlin.math.abs
import kotlin.math.max

/** The numbers on a compressor's sheet. */
data class CompressorSettings(
    val thresholdDb: Double = -18.0,
    val ratio: Double = 3.0,
    val kneeDb: Double = 6.0,
    val attackMs: Double = 10.0,
    val releaseMs: Double = 120.0,
    val makeupDb: Double = 0.0,
    /** When true the makeup is chosen so a signal at the threshold stays where it was. */
    val autoMakeup: Boolean = false
)

/**
 * The gain computer every dynamics processor shares: how many dB to take off a level at
 * [levelDb] for a threshold, ratio and soft knee (Giannoulis, Massberg and Reiss, 2012).
 * Pure arithmetic, so it is checked against hand-worked numbers (DynamicsChecks).
 */
object GainComputer {
    fun compressionDb(levelDb: Double, thresholdDb: Double, ratio: Double, kneeDb: Double): Double {
        val over = levelDb - thresholdDb
        val half = kneeDb / 2
        if (over <= -half) return 0.0
        if (kneeDb <= 0.0 || over >= half) return over - over / ratio
        // Inside the knee the curve bends smoothly from slope 1 to slope 1/ratio.
        val t = over + half
        return (1.0 / ratio - 1.0) * t * t / (2 * kneeDb) * -1.0
    }

    /** An expander below the threshold: how many dB to take off, growing as the level falls, up to [rangeDb]. */
    fun expansionDb(levelDb: Double, thresholdDb: Double, ratio: Double, kneeDb: Double, rangeDb: Double): Double {
        val under = thresholdDb - levelDb
        val half = kneeDb / 2
        val cut = when {
            under <= -half -> 0.0
            kneeDb <= 0.0 || under >= half -> under * (ratio - 1.0)
            else -> { val t = under + half; (ratio - 1.0) * t * t / (2 * kneeDb) }
        }
        return cut.coerceIn(0.0, rangeDb)
    }
}

/**
 * A feed-forward compressor with a soft knee, the detector in the log domain so attack and
 * release are in dB per unit time as an engineer expects, and an optional look-ahead so a
 * transient is caught rather than chased. Per channel, with the detector linked across the
 * channels it is given so a stereo image does not wander.
 */
class Compressor(val sampleRate: Int, val channels: Int, settings: CompressorSettings, lookaheadMs: Double = 0.0) {
    var settings: CompressorSettings = settings
        set(value) { field = value; follower.setTimes(value.attackMs, value.releaseMs) }

    private val follower = EnvelopeFollower(sampleRate, settings.attackMs, settings.releaseMs)
    private val lookahead = (lookaheadMs * sampleRate / 1000).toInt()
    private val delay = Array(channels) { FloatArray(max(1, lookahead)) }
    private var delayAt = 0
    /** The reduction applied to the last frame, in dB (<= 0), for the meter. */
    var reductionDb = 0.0
        private set

    private fun makeupDb(): Double {
        val s = settings
        return if (s.autoMakeup) -GainComputer.compressionDb(0.0, s.thresholdDb, s.ratio, s.kneeDb) / 2 + s.makeupDb else s.makeupDb
    }

    /** Processes interleaved frames in place. */
    fun process(buffer: FloatArray, frames: Int = buffer.size / channels) {
        val s = settings
        val makeup = Gain.dbToLinear(makeupDb()).toFloat()
        var i = 0
        for (f in 0 until frames) {
            var peak = 0f
            for (c in 0 until channels) { val a = abs(buffer[i + c]); if (a > peak) peak = a }
            val env = follower.process(peak)
            val levelDb = Gain.linearToDb(env.toDouble())
            val cut = GainComputer.compressionDb(levelDb, s.thresholdDb, s.ratio, s.kneeDb)
            reductionDb = -cut
            val g = Gain.dbToLinear(-cut).toFloat() * makeup
            if (lookahead > 0) {
                for (c in 0 until channels) {
                    val d = delay[c]
                    val old = d[delayAt]
                    d[delayAt] = buffer[i + c]
                    buffer[i + c] = old * g
                }
                delayAt = (delayAt + 1) % lookahead
            } else {
                for (c in 0 until channels) buffer[i + c] *= g
            }
            i += channels
        }
    }

    fun reset() { follower.reset(); delay.forEach { it.fill(0f) }; delayAt = 0 }
}

/**
 * A brick-wall limiter: a look-ahead window finds the peak ahead, the gain is pulled down in
 * time to meet it and let go at the release, and the signal is delayed by the look-ahead so
 * nothing ever passes the ceiling. True-peak safe when fed through [TruePeak] (the ceiling is
 * lowered by the inter-sample margin the oversampler reports).
 */
class Limiter(val sampleRate: Int, val channels: Int, ceilingDb: Double = -1.0, lookaheadMs: Double = 5.0, releaseMs: Double = 60.0) {
    var ceilingDb = ceilingDb
    private val lookahead = max(1, (lookaheadMs * sampleRate / 1000).toInt())
    private val release = timeConstant(releaseMs, sampleRate)
    private val delay = Array(channels) { FloatArray(lookahead) }
    private val peaks = FloatArray(lookahead)
    private var at = 0
    private var gain = 1f
    var reductionDb = 0.0
        private set

    fun process(buffer: FloatArray, frames: Int = buffer.size / channels) {
        val ceiling = Gain.dbToLinear(ceilingDb).toFloat()
        var i = 0
        for (f in 0 until frames) {
            var peak = 0f
            for (c in 0 until channels) { val a = abs(buffer[i + c]); if (a > peak) peak = a }
            peaks[at] = peak
            // The loudest sample within the look-ahead decides the gain now, so the reduction has
            // arrived by the time that sample leaves the delay.
            var ahead = 0f
            for (p in peaks) if (p > ahead) ahead = p
            val wanted = if (ahead > ceiling) ceiling / ahead else 1f
            gain = if (wanted < gain) wanted else gain + release * (wanted - gain)
            reductionDb = Gain.linearToDb(gain.toDouble())
            for (c in 0 until channels) {
                val d = delay[c]
                val old = d[at]
                d[at] = buffer[i + c]
                var y = old * gain
                // The look-ahead is the guarantee; this clamp is for a rounding error, not a plan.
                if (y > ceiling) y = ceiling else if (y < -ceiling) y = -ceiling
                buffer[i + c] = y
            }
            at = (at + 1) % lookahead
            i += channels
        }
    }

    /** Samples of delay the limiter adds, so the caller can line the output up with the input. */
    val latency: Int get() = lookahead

    fun reset() { delay.forEach { it.fill(0f) }; peaks.fill(0f); at = 0; gain = 1f }
}

/** A noise gate with hysteresis and a hold, or an expander with a range: the same machine. */
class Gate(val sampleRate: Int, val channels: Int, var thresholdDb: Double = -45.0, var rangeDb: Double = 80.0, var ratio: Double = 10.0,
           attackMs: Double = 2.0, releaseMs: Double = 150.0, var holdMs: Double = 50.0, var hysteresisDb: Double = 4.0) {
    private val follower = EnvelopeFollower(sampleRate, 0.5, 30.0)
    private val open = timeConstant(attackMs, sampleRate)
    private val close = timeConstant(releaseMs, sampleRate)
    private var gain = 0f
    private var openFor = 0
    private var isOpen = false

    fun process(buffer: FloatArray, frames: Int = buffer.size / channels) {
        val holdSamples = (holdMs * sampleRate / 1000).toInt()
        var i = 0
        for (f in 0 until frames) {
            var peak = 0f
            for (c in 0 until channels) { val a = abs(buffer[i + c]); if (a > peak) peak = a }
            val levelDb = Gain.linearToDb(follower.process(peak).toDouble())
            // Opens at the threshold, closes only once the level has fallen a little further: a
            // signal sitting on the threshold does not chatter.
            if (!isOpen && levelDb > thresholdDb) { isOpen = true; openFor = 0 }
            else if (isOpen && levelDb < thresholdDb - hysteresisDb) { openFor += 1; if (openFor > holdSamples) isOpen = false }
            else if (isOpen) openFor = 0
            val target = if (isOpen) 1f else Gain.dbToLinear(-GainComputer.expansionDb(levelDb, thresholdDb, ratio, 0.0, rangeDb)).toFloat()
            gain = if (target > gain) gain + open * (target - gain) else gain + close * (target - gain)
            for (c in 0 until channels) buffer[i + c] *= gain
            i += channels
        }
    }

    fun reset() { follower.reset(); gain = 0f; isOpen = false; openFor = 0 }
}

/**
 * A de-esser: the sibilant band (a band-pass round [hz]) drives a compressor whose gain is
 * applied to that band alone, split off and summed back, so an "s" is softened and the rest of
 * the voice is untouched.
 */
class DeEsser(val sampleRate: Int, val channels: Int, var hz: Double = 6500.0, var thresholdDb: Double = -24.0, var ratio: Double = 4.0, val widthQ: Double = 1.2) {
    private val bandCoefficients get() = BiquadCoefficients.of(FilterKind.BAND_PASS, hz, sampleRate, widthQ)
    private val bands = Array(channels) { Biquad(bandCoefficients) }
    private val follower = EnvelopeFollower(sampleRate, 1.0, 40.0)
    var reductionDb = 0.0
        private set

    fun retune() { bands.forEach { it.coefficients = bandCoefficients } }

    fun process(buffer: FloatArray, frames: Int = buffer.size / channels) {
        var i = 0
        val band = FloatArray(channels)
        for (f in 0 until frames) {
            var peak = 0f
            for (c in 0 until channels) { band[c] = bands[c].process(buffer[i + c]); val a = abs(band[c]); if (a > peak) peak = a }
            val levelDb = Gain.linearToDb(follower.process(peak).toDouble())
            val cut = GainComputer.compressionDb(levelDb, thresholdDb, ratio, 3.0)
            reductionDb = -cut
            val g = Gain.dbToLinear(-cut).toFloat()
            // Take the band out and put back only what survives the compression.
            for (c in 0 until channels) buffer[i + c] = buffer[i + c] - band[c] + band[c] * g
            i += channels
        }
    }

    fun reset() { bands.forEach { it.reset() }; follower.reset() }
}
