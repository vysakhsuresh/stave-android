package com.layerbit.stave.engine.dsp

import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tan

/** What a loudness meter reads at one moment, and over a whole programme. */
data class LoudnessReading(
    val momentaryLufs: Double,
    val shortTermLufs: Double,
    val integratedLufs: Double,
    val rangeLu: Double,
    val truePeakDbtp: Double,
    val samplePeakDbfs: Double
)

/** A platform's delivery target: what the Loudness tool normalises to and the Finish sheet names. */
data class LoudnessTarget(val id: String, val label: String, val lufs: Double, val truePeakDbtp: Double, val note: String) {
    companion object {
        val PODCAST_STEREO = LoudnessTarget("podcast", "Podcast (stereo)", -16.0, -1.0, "Apple Podcasts and most hosts ask for -16 LUFS stereo, -19 mono.")
        val PODCAST_MONO = LoudnessTarget("podcast_mono", "Podcast (mono)", -19.0, -1.0, "Mono speech reads 3 LU louder on a stereo meter, so mono targets -19.")
        val STREAMING = LoudnessTarget("streaming", "Streaming music", -14.0, -1.0, "Spotify, YouTube, Tidal and Amazon normalise to about -14 LUFS.")
        val APPLE_MUSIC = LoudnessTarget("apple", "Apple Music", -16.0, -1.0, "Apple's Sound Check sits at -16 LUFS.")
        // Named by their standards, which is how broadcast people ask for them; the note says
        // "television and radio". "Broadcast (EBU R128)" shrank below its grid's text at a large font.
        val BROADCAST = LoudnessTarget("ebu", "EBU R128", -23.0, -1.0, "European television and radio: -23 LUFS, -1 dBTP.")
        val ATSC = LoudnessTarget("atsc", "ATSC A/85", -24.0, -2.0, "North American television: -24 LKFS, -2 dBTP.")
        val CD = LoudnessTarget("cd", "Loud (club / CD)", -9.0, -0.3, "As loud as a limiter allows; streaming services will turn it down again.")
        val all = listOf(PODCAST_STEREO, PODCAST_MONO, STREAMING, APPLE_MUSIC, BROADCAST, ATSC, CD)
        fun byId(id: String?): LoudnessTarget = all.firstOrNull { it.id == id } ?: STREAMING
    }
}

/**
 * ITU-R BS.1770-4 / EBU R128, the way every broadcast meter measures: K-weighted (a high shelf
 * for the head's acoustics and a high-pass for the ear's), summed over channels with the
 * surround weights, 400 ms blocks at 75% overlap for the momentary reading, 3 s for the
 * short-term, and the integrated value gated at an absolute -70 LUFS and a relative -10 LU so
 * silence and room tone do not drag a programme's number down.
 *
 * The filter coefficients are derived for any sample rate from the analogue prototypes the
 * standard's 48 kHz tables came from (the derivation pyloudnorm and libebur128 share), so a
 * 44.1 kHz file and a 96 kHz file read the same.
 *
 * Checked against the standard's own fact that a 0 dBFS 1 kHz sine reads -3.01 LUFS, and
 * that a -20 dBFS one reads -23.01 (LoudnessChecks).
 */
class LoudnessMeter(val sampleRate: Int, val channels: Int) {
    private val shelf = BiquadCoefficients.highShelfBs1770(sampleRate)
    private val highPass = BiquadCoefficients.highPassBs1770(sampleRate)
    private val stage1 = Array(channels) { Biquad(shelf) }
    private val stage2 = Array(channels) { Biquad(highPass) }
    private val weights = DoubleArray(channels) { c -> if (channels >= 5 && (c == 3 || c == 4)) 1.41 else 1.0 }

    /** 100 ms steps: a momentary block is four of them, a short-term block thirty. */
    private val stepSamples = sampleRate / 10
    private var stepSum = DoubleArray(channels)
    private var stepCount = 0
    private val steps = ArrayList<DoubleArray>()      // per 100 ms step: the mean square per channel
    private val truePeak = TruePeak(channels)
    private var samplePeak = 0f

    /** Feeds interleaved samples; the readings update as whole 100 ms steps complete. */
    fun process(interleaved: FloatArray, frames: Int = interleaved.size / channels) {
        truePeak.process(interleaved, frames)
        var i = 0
        for (f in 0 until frames) {
            for (c in 0 until channels) {
                val x = interleaved[i + c]
                val a = if (x < 0) -x else x
                if (a > samplePeak) samplePeak = a
                val y = stage2[c].process(stage1[c].process(x))
                stepSum[c] += y.toDouble() * y
            }
            stepCount++
            if (stepCount == stepSamples) {
                steps += DoubleArray(channels) { c -> stepSum[c] / stepSamples }
                stepSum = DoubleArray(channels)
                stepCount = 0
            }
            i += channels
        }
    }

    private fun lufsOf(meanSquares: DoubleArray): Double {
        var s = 0.0
        for (c in 0 until channels) s += weights[c] * meanSquares[c]
        return if (s <= 0.0) -Double.MAX_VALUE else -0.691 + 10 * log10(s)
    }

    /** The mean square per channel over the last [count] steps, or null when there are fewer. */
    private fun block(endExclusive: Int, count: Int): DoubleArray? {
        if (endExclusive < count) return null
        val out = DoubleArray(channels)
        for (k in endExclusive - count until endExclusive) for (c in 0 until channels) out[c] += steps[k][c] / count
        return out
    }

    val momentaryLufs: Double get() = block(steps.size, 4)?.let(::lufsOf) ?: -Double.MAX_VALUE
    val shortTermLufs: Double get() = block(steps.size, 30)?.let(::lufsOf) ?: -Double.MAX_VALUE

    /** Every 400 ms block's loudness at 100 ms hops, the gating units of the standard. */
    private fun momentaryBlocks(): List<Double> {
        val out = ArrayList<Double>(steps.size)
        for (end in 4..steps.size) block(end, 4)?.let { out += lufsOf(it) }
        return out
    }

    private fun meanSquareOfBlocks(blocks: List<Pair<Int, DoubleArray>>): Double {
        var s = 0.0
        for ((_, ms) in blocks) { var w = 0.0; for (c in 0 until channels) w += weights[c] * ms[c]; s += w }
        return s / blocks.size
    }

    /** The integrated loudness, gated as the standard gates it. -inf (as -Double.MAX_VALUE) with nothing above the absolute gate. */
    val integratedLufs: Double
        get() {
            val blocks = ArrayList<Pair<Int, DoubleArray>>()
            for (end in 4..steps.size) block(end, 4)?.let { blocks += end to it }
            val aboveAbsolute = blocks.filter { lufsOf(it.second) > ABSOLUTE_GATE }
            if (aboveAbsolute.isEmpty()) return -Double.MAX_VALUE
            val relativeGate = -0.691 + 10 * log10(meanSquareOfBlocks(aboveAbsolute)) - 10.0
            val kept = aboveAbsolute.filter { lufsOf(it.second) > relativeGate }
            if (kept.isEmpty()) return -Double.MAX_VALUE
            return -0.691 + 10 * log10(meanSquareOfBlocks(kept))
        }

    /** Loudness range (EBU Tech 3342): short-term blocks, absolute gate -70, relative gate -20, the 10th to 95th percentile spread. */
    val rangeLu: Double
        get() {
            val st = ArrayList<Pair<Double, DoubleArray>>()
            for (end in 30..steps.size) block(end, 30)?.let { st += lufsOf(it) to it }
            val above = st.filter { it.first > ABSOLUTE_GATE }
            if (above.size < 2) return 0.0
            val relative = -0.691 + 10 * log10(above.map { p -> var w = 0.0; for (c in 0 until channels) w += weights[c] * p.second[c]; w }.average()) - 20.0
            val kept = above.map { it.first }.filter { it > relative }.sorted()
            if (kept.size < 2) return 0.0
            val lo = kept[((kept.size - 1) * 0.10).toInt()]
            val hi = kept[((kept.size - 1) * 0.95).toInt()]
            return hi - lo
        }

    val truePeakDbtp: Double get() = truePeak.dbtp
    val samplePeakDbfs: Double get() = Gain.linearToDb(samplePeak.toDouble())

    fun reading(): LoudnessReading = LoudnessReading(momentaryLufs, shortTermLufs, integratedLufs, rangeLu, truePeakDbtp, samplePeakDbfs)

    fun reset() {
        stage1.forEach { it.reset() }; stage2.forEach { it.reset() }
        stepSum = DoubleArray(channels); stepCount = 0; steps.clear(); truePeak.reset(); samplePeak = 0f
    }

    companion object {
        const val ABSOLUTE_GATE = -70.0

        /** One-shot: the integrated loudness of a whole interleaved buffer. */
        fun integrated(interleaved: FloatArray, sampleRate: Int, channels: Int): Double =
            LoudnessMeter(sampleRate, channels).also { it.process(interleaved) }.integratedLufs

        /**
         * The loudness a source is heard at in the mix: the graph plays a mono source on both
         * channels at unity, so it is measured as two identical channels (3 LU over its mono
         * reading). Measured as mono, Clean up voice left a mono take 3 dB over its target.
         */
        fun integratedAsPlayed(interleaved: FloatArray, sampleRate: Int, channels: Int): Double =
            if (channels == 1) integrated(FloatArray(interleaved.size * 2) { interleaved[it / 2] }, sampleRate, 2)
            else integrated(interleaved, sampleRate, channels)
    }
}

/** The two K-weighting stages for any sample rate, from the analogue prototypes behind BS.1770's 48 kHz tables. */
fun BiquadCoefficients.Companion.highShelfBs1770(sampleRate: Int): BiquadCoefficients {
    val f0 = 1681.974450955533
    val gainDb = 3.999843853973347
    val q = 0.7071752369554196
    val k = tan(PI * f0 / sampleRate)
    val vh = 10.0.pow(gainDb / 20.0)
    val vb = vh.pow(0.4996667741545416)
    val a0 = 1.0 + k / q + k * k
    val b0 = (vh + vb * k / q + k * k) / a0
    val b1 = 2.0 * (k * k - vh) / a0
    val b2 = (vh - vb * k / q + k * k) / a0
    val a1 = 2.0 * (k * k - 1.0) / a0
    val a2 = (1.0 - k / q + k * k) / a0
    return BiquadCoefficients(b0, b1, b2, a1, a2)
}

fun BiquadCoefficients.Companion.highPassBs1770(sampleRate: Int): BiquadCoefficients {
    val f0 = 38.13547087602444
    val q = 0.5003270373238773
    val k = tan(PI * f0 / sampleRate)
    val a0 = 1.0 + k / q + k * k
    val a1 = 2.0 * (k * k - 1.0) / a0
    val a2 = (1.0 - k / q + k * k) / a0
    return BiquadCoefficients(1.0, -2.0, 1.0, a1, a2)
}

/** Quick statistics a tool reads before deciding anything: peak, RMS and a gated RMS. */
object Levels {
    /** The RMS of the frames above [gateDbfs], so a take with long pauses is not read as quiet because of them. */
    fun gatedRms(samples: FloatArray, sampleRate: Int, gateDbfs: Double = -50.0, frameMs: Int = 50): Float {
        val frame = (sampleRate * frameMs / 1000).coerceAtLeast(1)
        val gate = Gain.dbToLinear(gateDbfs)
        var sum = 0.0
        var counted = 0L
        var i = 0
        while (i < samples.size) {
            val end = minOf(i + frame, samples.size)
            var s = 0.0
            for (j in i until end) s += samples[j].toDouble() * samples[j]
            if (sqrt(s / (end - i)) >= gate) { sum += s; counted += (end - i) }
            i = end
        }
        return if (counted == 0L) 0f else sqrt(sum / counted).toFloat()
    }
}
