import com.layerbit.stave.engine.dsp.BiquadCoefficients
import com.layerbit.stave.engine.dsp.CompressorSettings
import com.layerbit.stave.engine.dsp.EqBand
import com.layerbit.stave.engine.dsp.EqMatch
import com.layerbit.stave.engine.dsp.FilterKind
import com.layerbit.stave.engine.dsp.Gain
import com.layerbit.stave.engine.dsp.LinkwitzRiley
import com.layerbit.stave.engine.dsp.MasteringReport
import com.layerbit.stave.engine.dsp.MultibandCompressor
import com.layerbit.stave.engine.dsp.ParametricEq
import com.layerbit.stave.engine.dsp.Stereo
import com.layerbit.stave.engine.dsp.TransientShaper
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/** The mastering pieces: a crossover that sums flat, a multiband that compresses one band alone, a shaper that moves onsets and tails, EQ match recovering a known EQ, width and correlation. */
object MasteringChecks : Suite {
    override val name = "mastering"

    private const val RATE = 48_000

    private fun noise(n: Int, seed: Int, level: Float = 0.3f): FloatArray { val r = Random(seed); return FloatArray(n) { ((r.nextDouble() * 2 - 1) * level).toFloat() } }
    private fun tone(hz: Double, n: Int, level: Float = 0.5f): FloatArray = FloatArray(n) { (sin(2 * PI * hz * it / RATE) * level).toFloat() }
    private fun rmsDb(a: FloatArray, from: Int = 0, to: Int = a.size): Double = Gain.linearToDb(Gain.rms(a, from, to).toDouble())

    /** The level of a tone at [hz] after [f], in dB relative to the input, measured on the steady part. */
    private fun through(hz: Double, f: (FloatArray) -> Unit): Double {
        val x = tone(hz, RATE)
        val y = x.copyOf(); f(y)
        return rmsDb(y, RATE / 2) - rmsDb(x, RATE / 2)
    }

    override fun run() {
        section("the crossover's two sides sum to the input within 0.05 dB at every frequency, and each side is down 6 dB at the crossover")
        for (hz in listOf(50.0, 150.0, 300.0, 1000.0, 3000.0, 8000.0, 15_000.0)) {
            val lr = LinkwitzRiley(1000.0, RATE)
            val sum = through(hz) { a -> for (i in a.indices) { lr.split(a[i]); a[i] = lr.low + lr.high } }
            check(abs(sum) < 0.05) { "low + high at $hz Hz is $sum dB" }
        }
        val lr = LinkwitzRiley(1000.0, RATE)
        val lowAt = through(1000.0) { a -> for (i in a.indices) { lr.split(a[i]); a[i] = lr.low } }
        near(lowAt.toFloat(), -6f, 0.2f, "the low side at the crossover")
        val lr2 = LinkwitzRiley(1000.0, RATE)
        val highFar = through(100.0) { a -> for (i in a.indices) { lr2.split(a[i]); a[i] = lr2.high } }
        check(highFar < -70) { "the high side at a tenth of the crossover: $highFar dB (fourth order is 80 dB a decade)" }

        section("the multiband: every band at ratio 1 is transparent; a band compressed alone leaves the others within 0.1 dB")
        val flat = List(3) { CompressorSettings(ratio = 1.0, thresholdDb = -60.0, kneeDb = 0.0) }
        for (hz in listOf(60.0, 150.0, 800.0, 3000.0, 10_000.0)) {
            val mb = MultibandCompressor(RATE, 1, 150.0, 3000.0, flat)
            val d = through(hz) { a -> mb.process(a) }
            check(abs(d) < 0.1) { "three bands at ratio 1 changed $hz Hz by $d dB" }
        }
        // Crossovers close together, where the low band still has level at the upper one: this is
        // the case the low band's phase alignment through the upper crossover exists for.
        for (hz in listOf(600.0, 800.0, 1000.0, 1200.0, 1600.0)) {
            val close = MultibandCompressor(RATE, 1, 800.0, 1200.0, flat)
            val d = through(hz) { a -> close.process(a) }
            check(abs(d) < 0.1) { "crossovers at 800 and 1200: $hz Hz changed by $d dB" }
        }
        val midOnly = listOf(flat[0], CompressorSettings(thresholdDb = -30.0, ratio = 4.0, kneeDb = 0.0, attackMs = 1.0, releaseMs = 50.0), flat[2])
        val lowWith = through(60.0) { a -> MultibandCompressor(RATE, 1, 150.0, 3000.0, midOnly).process(a) }
        val midWith = through(800.0) { a -> MultibandCompressor(RATE, 1, 150.0, 3000.0, midOnly).process(a) }
        val highWith = through(10_000.0) { a -> MultibandCompressor(RATE, 1, 150.0, 3000.0, midOnly).process(a) }
        check(abs(lowWith) < 0.1 && abs(highWith) < 0.1) { "compressing the mid band moved the low by $lowWith and the high by $highWith dB" }
        // A -6 dBFS tone sits 24 dB over a -30 threshold: at 4:1 it comes down 18 dB.
        near(midWith.toFloat(), -18f, 1.0f, "the mid band compressed by 18 dB")
        val stereo = MultibandCompressor(RATE, 2, settings = flat)
        val t440 = tone(440.0, RATE)
        val inter = FloatArray(RATE * 2) { i -> if (i % 2 == 0) t440[i / 2] else t440[i / 2] * 0.5f }
        val copy = inter.copyOf(); stereo.process(copy, RATE)
        val l = FloatArray(RATE) { copy[2 * it] }; val r = FloatArray(RATE) { copy[2 * it + 1] }
        near(rmsDb(l, RATE / 2) - rmsDb(r, RATE / 2), 6.02, 0.1, "stereo: the channels keep their 6 dB difference through the bands")

        section("the transient shaper: attack +1 makes the onset louder and leaves the decay, sustain -1 quietens the decay and leaves the onset")
        // A struck note: a 2 kHz tone that starts at once and dies away with a 150 ms time constant.
        // Sustain acts where the slow follower sits above the fast one, which is a decay; a held
        // tone has no decay and the first fixture (a tone held flat) asked sustain to act on nothing.
        val burst = FloatArray(RATE) { i -> if (i < RATE / 2) (sin(2 * PI * 2000.0 * i / RATE) * 0.5 * Math.exp(-i / (0.15 * RATE))).toFloat() else 0f }
        fun shaped(attack: Double, sustain: Double): FloatArray { val a = burst.copyOf(); TransientShaper(RATE, 1, attack, sustain).process(a); return a }
        val onsetMs = RATE * 3 / 1000; val tailFrom = RATE * 200 / 1000; val tailTo = RATE * 450 / 1000
        val plain = shaped(0.0, 0.0)
        check(plain.contentEquals(burst)) { "at zero the shaper is a wire" }
        val attacked = shaped(1.0, 0.0)
        check(rmsDb(attacked, 0, onsetMs) > rmsDb(burst, 0, onsetMs) + 3) { "attack +1 lifted the first 3 ms by ${rmsDb(attacked, 0, onsetMs) - rmsDb(burst, 0, onsetMs)} dB" }
        check(abs(rmsDb(attacked, tailFrom, tailTo) - rmsDb(burst, tailFrom, tailTo)) < 0.3) { "and left the tail: ${rmsDb(attacked, tailFrom, tailTo) - rmsDb(burst, tailFrom, tailTo)} dB" }
        val sustained = shaped(0.0, -1.0)
        check(rmsDb(sustained, tailFrom, tailTo) < rmsDb(burst, tailFrom, tailTo) - 1) { "sustain -1 quietened the tail by ${rmsDb(burst, tailFrom, tailTo) - rmsDb(sustained, tailFrom, tailTo)} dB" }
        check(abs(rmsDb(sustained, 0, onsetMs) - rmsDb(burst, 0, onsetMs)) < 0.5) { "and left the onset" }
        check(attacked.maxOf { abs(it) } <= 0.5 * Gain.dbToLinear(TransientShaper.MAX_DB) + 1e-3) { "the boost is capped" }
        // A held tone is neither an onset nor a decay once it has settled, so neither control may
        // move it. With the followers on the rectified samples each settled to its own fraction
        // of the peak and attack +1 lifted a held note by 0.4 dB; the detector in front is the fix.
        val held = tone(2000.0, RATE)
        for ((a, su) in listOf(1.0 to 0.0, 0.0 to -1.0, -1.0 to 1.0)) {
            val h = held.copyOf(); TransientShaper(RATE, 1, a, su).process(h)
            val moved = rmsDb(h, RATE / 2) - rmsDb(held, RATE / 2)
            check(abs(moved) < 0.3) { "attack $a sustain $su moved a held tone by $moved dB" }
        }

        section("EQ match: a reference that is the source through +6 dB at 1 kHz asks for about +6 dB at 1 kHz and nothing elsewhere")
        val source = noise(RATE * 4, 21)
        val known = ParametricEq(listOf(EqBand(FilterKind.PEAKING, 1000.0, 6.0, 1.0)), RATE, 1)
        val reference = source.copyOf(); known.processChannel(0, reference)
        val ps = EqMatch.profile(source, RATE); val pr = EqMatch.profile(reference, RATE)
        val diff = EqMatch.difference(ps, pr)
        val band1k = EqMatch.bandOf(1000.0)
        check(diff[band1k] > 3.5 && diff[band1k] < 6.5) { "the 1 kHz band reads ${diff[band1k]} dB (the band is wider than the filter, so under 6)" }
        for (b in 0 until EqMatch.BANDS) if (abs(b - band1k) > 2) check(abs(diff[b]) < 1.2) { "band $b (${EqMatch.centreHz(b).toInt()} Hz) reads ${diff[b]} dB where nothing changed" }
        val bands = EqMatch.match(ps, pr, RATE)
        check(bands.any { abs(it.hz - EqMatch.centreHz(band1k)) < 1 && it.gainDb > 3 }) { "the match has a lifted band at 1 kHz: $bands" }
        // Applying the match to the source brings its profile within 1.5 dB of the reference in every band.
        val matched = source.copyOf(); ParametricEq(bands, RATE, 1).processChannel(0, matched)
        val after = EqMatch.difference(EqMatch.profile(matched, RATE), pr)
        for (b in 0 until EqMatch.BANDS) check(abs(after[b]) < 1.5) { "after matching, band $b is still ${after[b]} dB off" }
        check(EqMatch.match(ps, ps, RATE).isEmpty()) { "a source matched to itself asks for nothing" }
        val loud = source.map { it * 4f }.toFloatArray()
        check(EqMatch.match(ps, EqMatch.profile(loud, RATE), RATE).isEmpty()) { "a reference 12 dB louder but the same colour asks for nothing" }
        val extreme = EqMatch.match(ps, DoubleArray(EqMatch.BANDS) { if (it == 0) 40.0 else 0.0 }, RATE)
        check(extreme.all { abs(it.gainDb) <= EqMatch.MAX_DB + 1e-9 }) { "gains are capped at ${EqMatch.MAX_DB} dB" }

        section("width: 0 is mono, 1 is a wire, 2 doubles the side; correlation reads 1, 0 and -1")
        val lch = noise(RATE, 1); val rch = noise(RATE, 2)
        val l0 = lch.copyOf(); val r0 = rch.copyOf(); Stereo.width(l0, r0, 0.0)
        check(l0.contentEquals(r0)) { "width 0 is mono" }
        for (i in 0 until 100) near(l0[i], (lch[i] + rch[i]) / 2, 1e-6f, "mono is the mid")
        val l1 = lch.copyOf(); val r1 = rch.copyOf(); Stereo.width(l1, r1, 1.0)
        for (i in 0 until 100) { near(l1[i], lch[i], 1e-6f, "width 1 left"); near(r1[i], rch[i], 1e-6f, "width 1 right") }
        val l2 = lch.copyOf(); val r2 = rch.copyOf(); Stereo.width(l2, r2, 2.0)
        for (i in 0 until 100) near(l2[i] - r2[i], 2 * (lch[i] - rch[i]), 1e-5f, "width 2 doubles the side")
        near(Stereo.correlation(lch, lch).toFloat(), 1f, 1e-6f, "identical channels")
        near(Stereo.correlation(lch, lch.map { -it }.toFloatArray()).toFloat(), -1f, 1e-6f, "inverted channels")
        check(abs(Stereo.correlation(lch, rch)) < 0.05) { "independent noise: ${Stereo.correlation(lch, rch)}" }
        near(Stereo.balanceDb(lch, lch.map { it * 0.5f }.toFloatArray()).toFloat(), -6.02f, 0.05f, "a right channel at half reads -6 dB")

        section("the report: a bright mix reads more high than low, a wide one a low correlation")
        val high = tone(8000.0, RATE); val wide = listOf(noise(RATE, 5), noise(RATE, 6))
        val bright = MasteringReport.of(listOf(high, high), RATE)
        check(bright.highDb > bright.lowDb + 40 && bright.correlation > 0.999) { "an 8 kHz tone in both channels: high ${bright.highDb}, low ${bright.lowDb}, correlation ${bright.correlation}" }
        val wideReport = MasteringReport.of(wide, RATE)
        check(abs(wideReport.correlation) < 0.05 && abs(wideReport.dcOffset) < 0.01) { "independent noise: correlation ${wideReport.correlation}, dc ${wideReport.dcOffset}" }
        check(wideReport.loudness.integratedLufs > -30 && wideReport.loudness.integratedLufs < -5) { "and a loudness that is a number: ${wideReport.loudness.integratedLufs}" }
        @Suppress("UNUSED_VARIABLE") val unused = BiquadCoefficients.SQRT_HALF
    }
}
