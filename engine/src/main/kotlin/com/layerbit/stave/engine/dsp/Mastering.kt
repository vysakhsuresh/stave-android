package com.layerbit.stave.engine.dsp

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * A Linkwitz-Riley fourth-order crossover: two second-order Butterworth sections in series on
 * each side. Its two outputs sum to the input in magnitude at every frequency (the sum is an
 * all-pass), which is what lets a multiband processor split a mix, work on the bands and add
 * them back without a dip or a bump at the crossover - a first- or second-order split cannot.
 */
class LinkwitzRiley(hz: Double, sampleRate: Int) {
    private val lp = BiquadCascade(List(2) { BiquadCoefficients.of(FilterKind.LOW_PASS, hz, sampleRate, BiquadCoefficients.SQRT_HALF) })
    private val hp = BiquadCascade(List(2) { BiquadCoefficients.of(FilterKind.HIGH_PASS, hz, sampleRate, BiquadCoefficients.SQRT_HALF) })
    var low = 0f
        private set
    var high = 0f
        private set

    /** Splits one sample into [low] and [high]. */
    fun split(x: Float) { low = lp.process(x); high = hp.process(x) }
    fun reset() { lp.reset(); hp.reset() }
}

/**
 * Three bands, each through its own [Compressor] with the detector linked across channels,
 * summed back. The low band is passed through the upper crossover's two sides and summed, so it
 * carries the same phase turn the mid and high bands got from that crossover. With the
 * crossovers at 150 and 3000 Hz that changes nothing the suite can see (the low band is a
 * hundred dB down by 3 kHz); it is for crossovers dragged close together: at 800 and 1200 Hz
 * the three summed 1.8 dB short at 600 Hz without it (MasteringChecks, the fault put back).
 */
class MultibandCompressor(
    val sampleRate: Int,
    val channels: Int,
    var lowHz: Double = 150.0,
    var highHz: Double = 3000.0,
    settings: List<CompressorSettings> = List(3) { CompressorSettings() }
) {
    init { require(settings.size == 3) { "three bands" } }
    val compressors = settings.map { Compressor(sampleRate, channels, it) }
    private val lower = Array(channels) { LinkwitzRiley(lowHz, sampleRate) }
    private val upper = Array(channels) { LinkwitzRiley(highHz, sampleRate) }
    private val lowAlign = Array(channels) { LinkwitzRiley(highHz, sampleRate) }
    private var bands = Array(3) { FloatArray(0) }

    /** Interleaved frames in place. */
    fun process(buffer: FloatArray, frames: Int = buffer.size / channels) {
        if (bands[0].size < frames * channels) bands = Array(3) { FloatArray(frames * channels) }
        var i = 0
        for (f in 0 until frames) {
            for (c in 0 until channels) {
                val x = buffer[i + c]
                val lo = lower[c]; lo.split(x)
                val al = lowAlign[c]; al.split(lo.low)
                bands[0][i + c] = al.low + al.high
                val up = upper[c]; up.split(lo.high)
                bands[1][i + c] = up.low
                bands[2][i + c] = up.high
            }
            i += channels
        }
        for (b in 0 until 3) compressors[b].process(bands[b], frames)
        for (k in 0 until frames * channels) buffer[k] = bands[0][k] + bands[1][k] + bands[2][k]
    }

    /** The reduction each band applied on the last frame, for the three meters. */
    fun reductionsDb(): DoubleArray = DoubleArray(3) { compressors[it].reductionDb }

    fun reset() { compressors.forEach { it.reset() }; lower.forEach { it.reset() }; upper.forEach { it.reset() }; lowAlign.forEach { it.reset() } }
}

/**
 * A transient shaper: the difference between a fast and a slow envelope says whether the
 * sound is arriving (fast above slow) or ringing (slow above fast); [attack] scales the first
 * into a boost or a cut of the onset, [sustain] the second into a boost or a cut of the tail.
 * Both in -1..1: +1 adds the whole difference in dB, -1 takes it away, 0 leaves the sound.
 * No threshold, so it works on a quiet hit as on a loud one - which is the point of it over a
 * compressor.
 */
class TransientShaper(val sampleRate: Int, val channels: Int, var attack: Double = 0.0, var sustain: Double = 0.0) {
    // Both followers read one detector's envelope, not the samples: run on the rectified
    // samples each settled to its own fraction of a steady tone's peak (a follower's rest level
    // depends on its attack-to-release ratio), so they disagreed by 0.7 dB on a held note and
    // "attack" lifted every sustained sound.
    private val detector = EnvelopeFollower(sampleRate, 0.1, 10.0, rms = true)
    private val fast = EnvelopeFollower(sampleRate, 1.0, 25.0)
    private val slow = EnvelopeFollower(sampleRate, 25.0, 300.0)

    fun process(buffer: FloatArray, frames: Int = buffer.size / channels) {
        var i = 0
        for (f in 0 until frames) {
            var peak = 0f
            for (c in 0 until channels) { val a = abs(buffer[i + c]); if (a > peak) peak = a }
            val env = detector.process(peak)
            val fastDb = Gain.linearToDb(fast.process(env).toDouble().coerceAtLeast(1e-6))
            val slowDb = Gain.linearToDb(slow.process(env).toDouble().coerceAtLeast(1e-6))
            val diff = fastDb - slowDb
            val gainDb = (if (diff > 0) attack * diff else sustain * -diff).coerceIn(-MAX_DB, MAX_DB)
            val g = Gain.dbToLinear(gainDb).toFloat()
            for (c in 0 until channels) buffer[i + c] *= g
            i += channels
        }
    }

    fun reset() { detector.reset(); fast.reset(); slow.reset() }

    companion object { const val MAX_DB = 18.0 }
}

/**
 * EQ match: the long-term spectrum of a mix in [BANDS] bands, and the peaking filters that
 * move one spectrum toward another. The two profiles are compared with their means taken off,
 * because level is loudness normalisation's job and a reference 6 dB louder is not a reference
 * 6 dB brighter. Gains are held to [MAX_DB] so a thin reference cannot ask for a 20 dB shelf.
 */
object EqMatch {
    const val BANDS = 16
    const val LOW_HZ = 40.0
    const val HIGH_HZ = 16_000.0
    const val MAX_DB = 12.0
    /** The bands are 0.58 of an octave apart; this Q gives each about that width so neighbours overlap smoothly. */
    const val Q = 1.6

    fun centreHz(band: Int): Double = LOW_HZ * (HIGH_HZ / LOW_HZ).pow(band.toDouble() / (BANDS - 1))

    private fun edgeHz(band: Int): Double = LOW_HZ * (HIGH_HZ / LOW_HZ).pow((band - 0.5) / (BANDS - 1))

    /** The average magnitude in dB per band of [mono] at [sampleRate], power-averaged over the frames. */
    fun profile(mono: FloatArray, sampleRate: Int): DoubleArray {
        val size = 4096
        val stft = Stft(size, size / 2, Windows.hann(size))
        val power = DoubleArray(BANDS)
        val counts = IntArray(BANDS)
        var frames = 0
        stft.analyze(mono) { re, im, _ ->
            for (k in 1 until stft.bins) {
                val hz = stft.binHz(k, sampleRate)
                val b = bandOf(hz)
                if (b < 0) continue
                power[b] += re[k].toDouble() * re[k] + im[k].toDouble() * im[k]
                if (frames == 0) counts[b]++
            }
            frames++
        }
        return DoubleArray(BANDS) { b -> if (frames == 0 || counts[b] == 0) -120.0 else 10 * log10(power[b] / frames / counts[b] + 1e-20) }
    }

    /** Which band a frequency falls in, or -1 outside the range. */
    fun bandOf(hz: Double): Int {
        if (hz < edgeHz(0) || hz >= edgeHz(BANDS)) return -1
        val b = (ln(hz / LOW_HZ) / ln(HIGH_HZ / LOW_HZ) * (BANDS - 1) + 0.5).toInt()
        return b.coerceIn(0, BANDS - 1)
    }

    /** [reference] minus [source], each with its mean removed: how far each band has to move. */
    fun difference(source: DoubleArray, reference: DoubleArray): DoubleArray {
        val sm = source.average(); val rm = reference.average()
        return DoubleArray(BANDS) { (reference[it] - rm) - (source[it] - sm) }
    }

    /**
     * The filters that move [source] toward [reference] by [strength] (1 is all the way), one
     * peaking band per band that needs more than [minDb]. The gains are solved jointly, not read
     * off the difference one band at a time: at this Q each filter reaches well into the
     * neighbouring centres, and gains set band by band left the matched mix 1.7 dB off in band 7
     * (the suite's "after matching" check). A damped iteration on the cascade's own response at
     * the centres converges in a dozen rounds; one round leaves the 1 kHz band at under half
     * its gain.
     */
    fun match(source: DoubleArray, reference: DoubleArray, sampleRate: Int, strength: Double = 1.0, minDb: Double = 0.25): List<EqBand> {
        val wanted = difference(source, reference).map { it * strength.coerceIn(0.0, 1.0) }
        val g = DoubleArray(BANDS)
        for (round in 0 until SOLVE_ROUNDS) {
            val filters = (0 until BANDS).map { b -> BiquadCoefficients.of(FilterKind.PEAKING, centreHz(b), sampleRate, Q, g[b]) }
            for (i in 0 until BANDS) {
                val have = filters.sumOf { it.magnitudeDb(centreHz(i), sampleRate) }
                g[i] = (g[i] + SOLVE_DAMPING * (wanted[i] - have)).coerceIn(-MAX_DB, MAX_DB)
            }
        }
        return (0 until BANDS).mapNotNull { b -> if (abs(g[b]) < minDb) null else EqBand(FilterKind.PEAKING, centreHz(b), g[b], Q) }
    }

    const val SOLVE_ROUNDS = 12
    const val SOLVE_DAMPING = 0.6
}

/** Mid/side width, and what a meter says about the stereo picture. */
object Stereo {
    /** Scales the side signal by [amount] in place: 0 is mono, 1 is as it was, 2 is twice as wide (may not survive mono). */
    fun width(left: FloatArray, right: FloatArray, amount: Double) {
        val a = amount.coerceIn(0.0, 3.0).toFloat()
        val n = min(left.size, right.size)
        for (i in 0 until n) {
            val m = (left[i] + right[i]) * 0.5f
            val s = (left[i] - right[i]) * 0.5f * a
            left[i] = m + s
            right[i] = m - s
        }
    }

    /** Pearson correlation of the two channels: 1 is mono, 0 unrelated, -1 out of phase. */
    fun correlation(left: FloatArray, right: FloatArray): Double {
        val n = min(left.size, right.size)
        if (n == 0) return 1.0
        var sl = 0.0; var sr = 0.0; var sll = 0.0; var srr = 0.0; var slr = 0.0
        for (i in 0 until n) { val l = left[i].toDouble(); val r = right[i].toDouble(); sl += l; sr += r; sll += l * l; srr += r * r; slr += l * r }
        val cov = slr / n - (sl / n) * (sr / n)
        val vl = sll / n - (sl / n).pow(2); val vr = srr / n - (sr / n).pow(2)
        if (vl <= 1e-18 || vr <= 1e-18) return 1.0
        return (cov / sqrt(vl * vr)).coerceIn(-1.0, 1.0)
    }

    /** Right against left in dB: positive leans right. */
    fun balanceDb(left: FloatArray, right: FloatArray): Double {
        val l = Gain.rms(left).toDouble(); val r = Gain.rms(right).toDouble()
        if (l <= 1e-9 && r <= 1e-9) return 0.0
        return Gain.linearToDb(max(r, 1e-9)) - Gain.linearToDb(max(l, 1e-9))
    }
}

/**
 * What the Finish tab's report says about a mix beyond its loudness: the stereo picture, the
 * balance of lows, mids and highs (the same three bands the multiband compressor splits), and
 * any DC. Read off a mixdown in one pass.
 */
data class MasteringReport(
    val loudness: LoudnessReading,
    val correlation: Double,
    val balanceDb: Double,
    val lowDb: Double,
    val midDb: Double,
    val highDb: Double,
    val dcOffset: Double
) {
    companion object {
        fun of(channels: List<FloatArray>, sampleRate: Int, lowHz: Double = 150.0, highHz: Double = 3000.0): MasteringReport {
            require(channels.isNotEmpty())
            val n = channels[0].size
            val ch = channels.size
            // Metered a block at a time: interleaving a six-minute song whole ran the phone out of
            // memory on top of the mixdown (1 Nov 2026). The meter is streaming (DynamicsChecks).
            val meter = LoudnessMeter(sampleRate, ch)
            val block = 8192
            val buf = FloatArray(block * ch)
            var pos = 0
            while (pos < n) {
                val m = minOf(block, n - pos)
                for (f in 0 until m) for (c in 0 until ch) buf[f * ch + c] = channels[c][pos + f]
                meter.process(buf, m)
                pos += m
            }
            val left = channels[0]; val right = channels.getOrElse(1) { channels[0] }
            // Band energies off the mono sum through the same crossovers.
            val lower = LinkwitzRiley(lowHz, sampleRate); val upper = LinkwitzRiley(highHz, sampleRate)
            var lo = 0.0; var mi = 0.0; var hi = 0.0; var dc = 0.0
            for (i in 0 until n) {
                val x = (left[i] + right[i]) * 0.5f
                dc += x
                lower.split(x); upper.split(lower.high)
                lo += lower.low.toDouble().pow(2); mi += upper.low.toDouble().pow(2); hi += upper.high.toDouble().pow(2)
            }
            fun db(e: Double) = if (n == 0) Gain.SILENCE_DB else 10 * log10(e / n + 1e-20)
            return MasteringReport(meter.reading(), Stereo.correlation(left, right), Stereo.balanceDb(left, right), db(lo), db(mi), db(hi), if (n == 0) 0.0 else dc / n)
        }
    }
}
