import com.layerbit.stave.engine.dsp.Chorus
import com.layerbit.stave.engine.dsp.Crusher
import com.layerbit.stave.engine.dsp.Delay
import com.layerbit.stave.engine.dsp.DelayLine
import com.layerbit.stave.engine.dsp.Gain
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
        val e1 = out.copyOfRange(t1 - 2, t1 + 3).maxOf { abs(it) }; val e2 = out.copyOfRange(t2 - 2, t2 + 3).maxOf { abs(it) }
        check(e1 > 0.4f && e1 < 0.6f) { "first echo near 0.5: $e1" }
        check(abs(e2 / e1 - 0.5f) < 0.1f) { "the second is half the first: $e2 / $e1" }
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
    }
}
