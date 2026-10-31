import com.layerbit.stave.engine.dsp.Gain
import com.layerbit.stave.engine.dsp.SpectralEdit
import kotlin.math.PI
import kotlin.math.sin

object SpectralEditChecks : Suite {
    override val name = "spectral"

    private fun level(signal: FloatArray, hz: Double, rate: Int, from: Int, to: Int): Double {
        var s = 0.0
        for (i in from until to) s += signal[i] * sin(2 * PI * hz * i / rate)
        return s * 2 / (to - from)
    }

    override fun run() {
        val rate = 48_000
        section("a box over a whistle takes the whistle out and leaves the voice-band tone alone")
        val n = rate * 3
        val low = FloatArray(n) { i -> (0.3 * sin(2 * PI * 300.0 * i / rate)).toFloat() }
        val whistle = FloatArray(n) { i -> val t = i.toDouble() / rate; if (t in 1.0..2.0) (0.3 * sin(2 * PI * 3000.0 * i / rate)).toFloat() else 0f }
        val mixed = FloatArray(n) { low[it] + whistle[it] }
        val box = SpectralEdit.Box(rate.toLong(), 2L * rate, 2500.0, 3500.0)
        val out = SpectralEdit.attenuate(mixed, rate, box, -60.0)
        check(out.size == n) { "length" }
        val whistleAfter = level(out, 3000.0, rate, (1.2 * rate).toInt(), (1.8 * rate).toInt())
        check(Gain.linearToDb(Math.abs(whistleAfter)) < Gain.linearToDb(0.3) - 40) { "the whistle is 40 dB down: ${Gain.linearToDb(Math.abs(whistleAfter))}" }
        near(level(out, 300.0, rate, (1.2 * rate).toInt(), (1.8 * rate).toInt()), 0.3, 0.01, "the 300 Hz tone under the box is untouched")
        near(level(out, 300.0, rate, 4800, rate / 2), 0.3, 0.01, "outside the box in time nothing changed")
        var worst = 0f
        for (i in 4800 until rate / 2) worst = maxOf(worst, Math.abs(out[i] - mixed[i]))
        check(worst < 1e-3f) { "samples before the box are the same: $worst" }

        section("gain 0 dB is the identity")
        val same = SpectralEdit.attenuate(mixed, rate, box, 0.0)
        var diff = 0f
        for (i in 2000 until n - 2000) diff = maxOf(diff, Math.abs(same[i] - mixed[i]))
        check(diff < 1e-3f) { "0 dB changed samples by $diff" }

        section("interpolation fills a gap from its neighbours")
        val clean = FloatArray(n) { i -> (0.3 * sin(2 * PI * 300.0 * i / rate)).toFloat() }
        val damaged = clean.copyOf()
        for (i in (1.4 * rate).toInt() until (1.6 * rate).toInt()) damaged[i] += (0.4 * sin(2 * PI * 300.0 * i / rate + 1.0)).toFloat()   // a burst in the same band
        val filled = SpectralEdit.interpolate(damaged, rate, SpectralEdit.Box((1.38 * rate).toLong(), (1.62 * rate).toLong(), 200.0, 400.0))
        val before = level(damaged, 300.0, rate, (1.45 * rate).toInt(), (1.55 * rate).toInt())
        val after = level(filled, 300.0, rate, (1.45 * rate).toInt(), (1.55 * rate).toInt())
        check(Math.abs(after - 0.3) < Math.abs(before - 0.3) * 0.5) { "the burst's excess is at least halved: $before -> $after" }

        section("the picture")
        val spec = SpectralEdit.spectrogramDb(low.copyOfRange(0, rate), rate)
        check(spec.size > 40 && spec[0].size == SpectralEdit.SIZE / 2 + 1) { "frames ${spec.size}, bins ${spec[0].size}" }
        // 300 Hz falls between bins 12 and 13; the louder of the two carries the tone, a little under
        // its level by the window's scalloping loss.
        val k300 = (300.0 * SpectralEdit.SIZE / rate).toInt()
        val peak = maxOf(spec[20][k300], spec[20][k300 + 1]).toDouble()
        near(peak, Gain.linearToDb(0.3), 1.6, "a -10.5 dBFS tone reads near -10.5 in its bin")
        check(spec[20][k300 + 40] < peak - 50) { "and the bins far away are quiet" }
        near(SpectralEdit.binHz(k300, rate), 300.0, rate.toDouble() / SpectralEdit.SIZE, "bin hz")
    }
}
