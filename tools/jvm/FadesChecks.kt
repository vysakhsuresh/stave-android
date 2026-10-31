import com.layerbit.stave.engine.dsp.Dither
import com.layerbit.stave.engine.dsp.FadeShape
import com.layerbit.stave.engine.dsp.Fades
import com.layerbit.stave.engine.dsp.GainEnvelope
import kotlin.math.abs

object FadesChecks : Suite {
    override val name = "fades"

    override fun run() {
        section("every shape starts silent and ends at unity")
        for (shape in FadeShape.entries) {
            near(shape.gainIn(0f), 0f, 1e-6f, "$shape in at 0")
            near(shape.gainIn(1f), 1f, 1e-6f, "$shape in at 1")
            near(shape.gainOut(0f), 1f, 1e-6f, "$shape out at 0")
            near(shape.gainOut(1f), 0f, 1e-6f, "$shape out at 1")
            var last = -1f
            for (i in 0..20) { val g = shape.gainIn(i / 20f); check(g >= last - 1e-6f) { "$shape monotonic" }; last = g }
        }
        section("equal power sums to constant power across a crossfade")
        for (i in 0..10) {
            val t = i / 10f
            val a = FadeShape.EQUAL_POWER.gainOut(t); val b = FadeShape.EQUAL_POWER.gainIn(t)
            near(a * a + b * b, 1f, 1e-5f, "power at $t")
        }
        section("linear sums to constant amplitude")
        for (i in 0..10) { val t = i / 10f; near(FadeShape.LINEAR.gainOut(t) + FadeShape.LINEAR.gainIn(t), 1f, 1e-6f, "amplitude at $t") }

        section("fade in and out applied in place")
        val buf = FloatArray(100) { 1f }
        Fades.fadeIn(buf, 10, FadeShape.LINEAR)
        near(buf[0], 0f, 1e-6f, "first sample"); near(buf[5], 0.5f, 1e-6f, "middle of fade"); near(buf[10], 1f, 1e-6f, "after the fade")
        Fades.fadeOut(buf, 10, FadeShape.LINEAR)
        near(buf[99], 0.1f, 1e-6f, "last sample is one step from silence"); near(buf[89], 1f, 1e-6f, "before the fade out")
        check(Fades.fadeIn(FloatArray(5) { 1f }, 50, FadeShape.LINEAR) == Unit) { "a fade longer than the clip is clamped, not thrown" }

        section("crossfade length and the join")
        val a = FloatArray(100) { 1f }; val b = FloatArray(100) { -1f }
        val x = Fades.crossfade(a, b, 20, FadeShape.LINEAR)
        check(x.size == 180) { "a + b - overlap: ${x.size}" }
        near(x[79], 1f, 1e-6f, "before the join"); near(x[90], 0f, 1e-5f, "middle of a linear crossfade of opposite signs"); near(x[100], -1f, 1e-6f, "after")

        section("gain envelope interpolates in dB and holds past its ends")
        val env = GainEnvelope(listOf(GainEnvelope.Point(100, 0.0), GainEnvelope.Point(300, -20.0)))
        near(env.gainDbAt(0), 0.0, 1e-9, "holds before"); near(env.gainDbAt(200), -10.0, 1e-9, "halfway in dB"); near(env.gainDbAt(1000), -20.0, 1e-9, "holds after")
        near(env.gainAt(300).toDouble(), 0.1, 1e-6, "linear at -20")
        val shifted = env.shifted(50)
        near(shifted.gainDbAt(150), -10.0, 1e-9, "shifted by a head trim")
        check(env.with(GainEnvelope.Point(200, -5.0)).points.size == 3 && env.without(100).points.size == 1) { "edit points" }
        check(GainEnvelope().gainAt(5) == 1f) { "empty is unity" }

        section("dither noise is at one LSB, triangular, zero mean")
        val d = Dither(16)
        var sum = 0.0; var maxDev = 0.0
        for (i in 0 until 20_000) { val v = d.apply(0f); sum += v; maxDev = maxOf(maxDev, abs(v).toDouble()) }
        near(sum / 20_000, 0.0, 2e-6, "mean")
        check(maxDev <= 1.0 / 32768 + 1e-9) { "tpdf peaks at one LSB: $maxDev" }
        check(maxDev > 0.9 / 32768) { "and it does reach near one LSB: $maxDev" }
        check(Dither(16).quantise(1f) == 32767 && Dither(16).quantise(-1f) >= -32768) { "quantise clamps" }
        near(Dither.noiseFloorDb(16), -98.08, 0.01, "16-bit floor")
    }
}
