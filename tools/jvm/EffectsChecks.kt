import com.layerbit.stave.engine.dsp.Chorus
import com.layerbit.stave.engine.dsp.Crusher
import com.layerbit.stave.engine.dsp.Delay
import com.layerbit.stave.engine.dsp.DelayLine
import com.layerbit.stave.engine.dsp.Gain
import com.layerbit.stave.engine.dsp.Phaser
import com.layerbit.stave.engine.dsp.Reverb
import com.layerbit.stave.engine.dsp.Saturator
import com.layerbit.stave.engine.dsp.Tremolo
import com.layerbit.stave.engine.dsp.VocalPresets
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

object EffectsChecks : Suite {
    override val name = "effects"

    override fun run() {
        val rate = 48_000
        section("a delay line reads back what went in, that many samples ago")
        val line = DelayLine(100)
        for (i in 0 until 50) line.push(i.toFloat())
        near(line.read(1f), 49f, 1e-6f, "one back"); near(line.read(10f), 40f, 1e-6f, "ten back"); near(line.read(10.5f), 39.5f, 1e-6f, "a fractional delay interpolates")

        section("an echo arrives at its time and its repeats fade by the feedback")
        val d = Delay(rate, timeMs = 100.0, feedback = 0.5f, mix = 0.5f, dampHz = 20_000.0)
        val out = FloatArray(rate) { i -> d.process(if (i == 0) 1f else 0f) }
        near(out[0], 0.5f, 1e-6f, "the dry half of the impulse")
        val t1 = (0.1 * rate).toInt(); val t2 = (0.2 * rate).toInt()
        val e1 = out.copyOfRange(t1 - 2, t1 + 3).maxOf { abs(it) }
        check(e1 > 0.4f && e1 < 0.6f) { "first echo near 0.5: $e1" }
        // The repeats go through the damping filter, and a second-order low-pass even at
        // 20 kHz spreads an impulse over a few samples (its first tap is 0.69), so the
        // second echo's *peak* is not half the first's - its area is, since the filter's
        // gain at DC is one. Measuring the peak here once read the damping as a feedback
        // fault.
        val a1 = out.copyOfRange(t1 - 8, t1 + 12).sum(); val a2 = out.copyOfRange(t2 - 8, t2 + 12).sum()
        check(abs(a2 / a1 - 0.5f) < 0.03f) { "the second echo's area is half the first's: $a2 / $a1" }
        check(out.copyOfRange(t1 + 50, t2 - 50).maxOf { abs(it) } < 0.05f) { "quiet between echoes" }

        section("chorus and tremolo keep the level roughly, reverb has a tail that decays")
        val tone = FloatArray(rate) { i -> (0.3 * sin(2 * PI * 440.0 * i / rate)).toFloat() }
        val ch = Chorus(rate)
        val chorused = FloatArray(rate) { ch.process(tone[it]) }
        val before = Gain.rms(tone, rate / 2, rate); val after = Gain.rms(chorused, rate / 2, rate)
        check(after > before * 0.5f && after < before * 1.6f) { "chorus level $after from $before" }
        val tr = Tremolo(rate, 5.0, 1.0f)
        val trem = FloatArray(rate) { tr.process(tone[it]) }
        var mn = 1f; var mx = 0f
        for (w in 0 until 20) { val r = Gain.rms(trem, w * rate / 20, (w + 1) * rate / 20); mn = minOf(mn, r); mx = maxOf(mx, r) }
        check(mn < mx * 0.4f) { "the level wobbles: $mn..$mx" }
        val rv = Reverb(rate, decay = 0.84f, damping = 0.3f, mix = 1f, preDelayMs = 5.0)
        val tail = FloatArray(rate * 3) { i -> rv.process(if (i < 100) 0.5f else 0f) }
        val early = Gain.rms(tail, rate / 4, rate / 2); val late = Gain.rms(tail, 2 * rate, 2 * rate + rate / 4)
        check(early > 0.0005f) { "there is a tail: $early" }
        check(late < early * 0.35f) { "and it decays: $late vs $early" }
        check(tail.all { it.isFinite() && abs(it) < 2f }) { "the reverb is stable" }

        section("saturation bends, the crusher steps")
        val sat = Saturator(4f, 1f)
        near(sat.process(0f), 0f, 1e-6f, "zero stays zero")
        check(sat.process(0.9f) < 0.9f * 1.3f && sat.process(0.9f) > 0.5f) { "a loud sample is bent, not amplified: ${sat.process(0.9f)}" }
        check(abs(sat.process(0.05f) - 0.05f * 4f / Math.tanh(4.0).toFloat()) < 0.02f) { "a quiet sample passes almost linearly" }
        val cr = Crusher(3, 1)
        val steps = (0..100).map { cr.process(it / 100f) }.toSet()
        check(steps.size <= 5) { "3 bits give at most four positive steps: $steps" }

        section("every preset builds for both channels and keeps the level in bounds")
        val quiet = FloatArray(rate / 2) { i -> (0.2 * sin(2 * PI * 220.0 * i / rate)).toFloat() }
        for (p in VocalPresets.all) {
            val outp = VocalPresets.apply(p, listOf(quiet, quiet), rate)
            check(outp.size == 2 && outp[0].size == quiet.size) { "${p.id} shape" }
            val peak = outp.maxOf { a -> a.maxOf { abs(it) } }
            check(peak.isFinite() && peak < 1.5f) { "${p.id} peak $peak" }
            check(Gain.rms(outp[0], rate / 4, rate / 2) > 0.01f) { "${p.id} is not silent" }
        }
        check(VocalPresets.byId("echo") != null && VocalPresets.byId("x") == null) { "by id" }

        section("a phaser: notches that move; frozen, it is an all-pass pair that cancels at its corner and passes elsewhere")
        val ph = Phaser(rate, rateHz = 0.0, depth = 0f, feedback = 0f, mix = 0.5f, stages = 2, lowHz = 1000.0, highHz = 1000.0)
        // With the LFO still and the sweep at its middle the corner sits at 1 kHz: two all-pass stages turn 1 kHz by 180 degrees, so dry + wet cancels there.
        fun level(hz: Double, f: (Float) -> Float): Double { val n = rate; var e = 0.0; for (i in 0 until n) { val y = f(sin(2 * PI * hz * i / rate).toFloat()); if (i > n / 2) e += y * y }; return 10 * Math.log10(e / (n / 2) + 1e-20) }
        val atCorner = level(1000.0) { ph.process(it) }
        val ph2 = Phaser(rate, rateHz = 0.0, depth = 0f, feedback = 0f, mix = 0.5f, stages = 2, lowHz = 1000.0, highHz = 1000.0)
        val far = level(100.0) { ph2.process(it) }
        check(far - atCorner > 30) { "the notch at the corner is ${far - atCorner} dB deep against 100 Hz" }
        check(abs(far - 10 * Math.log10(0.5)) < 0.5) { "away from the notch the level is the sine's own (-3 dB for a sine's power): $far" }
        val moving = Phaser(rate, rateHz = 2.0, depth = 1f, feedback = 0.3f, mix = 0.5f)
        val swept = FloatArray(rate) { moving.process(sin(2 * PI * 1000.0 * it / rate).toFloat()) }
        val sweptEarly = Gain.rms(swept, rate / 4, rate / 4 + 2000); val sweptLater = Gain.rms(swept, rate / 4 + 12_000, rate / 4 + 14_000)
        check(abs(Gain.linearToDb(sweptEarly) - Gain.linearToDb(sweptLater)) > 1) { "with the LFO moving a 1 kHz tone's level changes over a quarter cycle: ${Gain.linearToDb(sweptEarly)} then ${Gain.linearToDb(sweptLater)}" }
        check(swept.none { it.isNaN() } && swept.maxOf { abs(it) } < 1.5f) { "stable with feedback" }
    }
}
