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

        // The error a converter leaves on a clean tone, against the tone computed at the new rate.
        // The first version took the nearest of 512 kernel phases: about -57 dB of error at 10 kHz,
        // which a quiet cymbal can carry into hearing. Interpolating between phases puts it below
        // what 16 bits hold.
        section("44.1k to 48k leaves a 10 kHz tone clean to -105 dB")
        fun errorDb(hz: Double): Double {
            val src = FloatArray(inRate) { i -> (0.5 * sin(2 * PI * hz * i / inRate)).toFloat() }
            val got = Resampler(inRate, outRate).convert(src)
            var sig = 0.0; var err = 0.0
            for (i in 4000 until outRate - 4000) { val ideal = 0.5 * sin(2 * PI * hz * i / outRate); sig += ideal * ideal; val d = got[i] - ideal; err += d * d }
            return 10 * kotlin.math.log10(err / sig)
        }
        errorDb(10_000.0).let { check(it < -105.0) { "10 kHz error $it dB" } }
        errorDb(1_000.0).let { check(it < -105.0) { "1 kHz error $it dB" } }
        section("and the top of the band is flat to 19 kHz")
        errorDb(19_000.0).let { check(it < -90.0) { "19 kHz error $it dB (the band rolls off too early)" } }

        section("in blocks it is the same as whole")
        val music = FloatArray(inRate * 2) { i -> val f = i / 2; (0.3 * sin(2 * PI * 440.0 * f / inRate) + (if (i % 2 == 0) 0.2 else -0.1) * sin(2 * PI * 9_000.0 * f / inRate)).toFloat() }
        val whole = Resampler(inRate, outRate).convertInterleaved(music, 2)
        val stream = Resampler(inRate, outRate).stream(2)
        val parts = ArrayList<FloatArray>()
        var at = 0
        for (b in listOf(1, 7, 4096, 333, 10_000)) { val n = minOf(b, inRate - at); parts += stream.push(music.copyOfRange(at * 2, (at + n) * 2), n); at += n }
        while (at < inRate) { val n = minOf(8192, inRate - at); parts += stream.push(music.copyOfRange(at * 2, (at + n) * 2), n); at += n }
        parts += stream.finish()
        val joined = FloatArray(parts.sumOf { it.size }).also { o -> var k = 0; for (p in parts) { p.copyInto(o, k); k += p.size } }
        check(joined.size == whole.size) { "streamed ${joined.size} samples, whole ${whole.size}" }
        for (i in whole.indices) check(joined[i] == whole[i]) { "sample $i: ${joined[i]} streamed, ${whole[i]} whole" }
        val minute = FloatArray(44_100 * 60 * 2) { (0.3 * sin(it * 0.01)).toFloat() }
        val t0 = System.nanoTime()
        Resampler(44_100, 48_000).convertInterleaved(minute, 2)
        println("      a stereo minute 44.1k to 48k in ${(System.nanoTime() - t0) / 1_000_000} ms; errors at 1k/10k/19k: ${"%.1f".format(java.util.Locale.ROOT, errorDb(1_000.0))} / ${"%.1f".format(java.util.Locale.ROOT, errorDb(10_000.0))} / ${"%.1f".format(java.util.Locale.ROOT, errorDb(19_000.0))} dB")
    }
}
