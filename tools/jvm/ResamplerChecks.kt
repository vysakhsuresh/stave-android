import com.layerbit.stave.engine.dsp.Gain
import com.layerbit.stave.engine.dsp.Resampler
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

object ResamplerChecks : Suite {
    override val name = "resampler"

    private fun freqAndLevel(signal: FloatArray, rate: Int, from: Int, to: Int): Pair<Double, Double> {
        // Zero crossings for the frequency, RMS for the level.
        var crossings = 0
        for (i in from + 1 until to) if (signal[i - 1] < 0 && signal[i] >= 0) crossings++
        val seconds = (to - from).toDouble() / rate
        var s = 0.0
        for (i in from until to) s += signal[i].toDouble() * signal[i]
        return Pair(crossings / seconds, sqrt(s / (to - from)) * sqrt(2.0))
    }

    override fun run() {
        section("44.1k to 48k keeps the tone's frequency, level and length")
        val inRate = 44_100; val outRate = 48_000
        val tone = FloatArray(inRate) { i -> (0.5 * sin(2 * PI * 1000.0 * i / inRate)).toFloat() }
        val out = Resampler(inRate, outRate).convert(tone)
        check(out.size == outRate) { "length ${out.size}" }
        val (hz, level) = freqAndLevel(out, outRate, 2000, outRate - 2000)
        near(hz, 1000.0, 2.0, "frequency")
        near(level, 0.5, 0.005, "amplitude")

        section("downsampling 96k to 48k with a tone above the new nyquist removed")
        val hiRate = 96_000
        val mixed = FloatArray(hiRate) { i -> (0.5 * sin(2 * PI * 1000.0 * i / hiRate) + 0.5 * sin(2 * PI * 30_000.0 * i / hiRate)).toFloat() }
        val down = Resampler(hiRate, 48_000).convert(mixed)
        val (_, lvl) = freqAndLevel(down, 48_000, 2000, 46_000)
        near(lvl, 0.5, 0.02, "only the 1 kHz survives at its level")
        // The 30 kHz would alias to 18 kHz; measure its residue by correlation.
        var alias = 0.0
        for (i in 2000 until 46_000) alias += down[i] * sin(2 * PI * 18_000.0 * i / 48_000)
        alias = Math.abs(alias * 2 / 44_000)
        check(Gain.linearToDb(alias) < -60.0) { "alias residue ${Gain.linearToDb(alias)} dB" }

        section("identity and interleaved")
        val same = Resampler(48_000, 48_000).convert(tone)
        check(same.contentEquals(tone)) { "same rate copies" }
        val stereo = FloatArray(2000) { i -> if (i % 2 == 0) 0.25f else -0.25f }
        val st = Resampler(48_000, 96_000).convertInterleaved(stereo, 2)
        check(st.size == 4000) { "stereo length ${st.size}" }
        near(st[1000].toDouble(), 0.25, 0.01, "left stays left")
        near(st[1001].toDouble(), -0.25, 0.01, "right stays right")
    }
}
