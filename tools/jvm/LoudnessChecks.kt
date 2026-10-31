import com.layerbit.stave.engine.dsp.Gain
import com.layerbit.stave.engine.dsp.Levels
import com.layerbit.stave.engine.dsp.LoudnessMeter
import com.layerbit.stave.engine.dsp.LoudnessTarget
import com.layerbit.stave.engine.dsp.TruePeak
import com.layerbit.stave.engine.dsp.highPassBs1770
import com.layerbit.stave.engine.dsp.highShelfBs1770
import com.layerbit.stave.engine.dsp.BiquadCoefficients
import kotlin.math.PI
import kotlin.math.sin

object LoudnessChecks : Suite {
    override val name = "loudness"

    private fun sine(hz: Double, dbfs: Double, seconds: Double, rate: Int, channels: Int): FloatArray {
        val a = Gain.dbToLinear(dbfs).toFloat()
        val frames = (seconds * rate).toInt()
        return FloatArray(frames * channels) { i -> a * sin(2 * PI * hz * (i / channels) / rate).toFloat() }
    }

    override fun run() {
        section("the standard's own facts, at three sample rates")
        for (rate in listOf(44_100, 48_000, 96_000)) {
            val fullScale = LoudnessMeter.integrated(sine(1000.0, 0.0, 4.0, rate, 1), rate, 1)
            near(fullScale, -3.01, 0.1, "0 dBFS 1 kHz mono reads -3.01 LUFS at $rate")
            val twenty = LoudnessMeter.integrated(sine(1000.0, -20.0, 4.0, rate, 1), rate, 1)
            near(twenty, -23.01, 0.1, "-20 dBFS 1 kHz mono reads -23.01 LUFS at $rate")
        }

        section("k-weighting shape")
        val rate = 48_000
        val shelf = BiquadCoefficients.highShelfBs1770(rate)
        val hp = BiquadCoefficients.highPassBs1770(rate)
        near(shelf.magnitudeDb(10_000.0, rate), 3.99, 0.15, "the shelf lifts the highs by 4 dB")
        near(shelf.magnitudeDb(100.0, rate), 0.0, 0.05, "and leaves the lows")
        check(hp.magnitudeDb(20.0, rate) < -6.0) { "the RLB high-pass takes the sub-bass: ${hp.magnitudeDb(20.0, rate)}" }
        near(hp.magnitudeDb(1000.0, rate) + shelf.magnitudeDb(1000.0, rate), 0.691, 0.05, "+0.69 dB at 1 kHz: the reason the standard subtracts 0.691")

        section("stereo: the same signal on two channels reads 3 LU louder")
        val mono = LoudnessMeter.integrated(sine(1000.0, -20.0, 4.0, rate, 1), rate, 1)
        val stereo = LoudnessMeter.integrated(sine(1000.0, -20.0, 4.0, rate, 2), rate, 2)
        near(stereo - mono, 3.01, 0.05, "two correlated channels")

        section("gating: silence between does not lower the number")
        val tone = sine(1000.0, -20.0, 3.0, rate, 1)
        val withSilence = FloatArray(tone.size * 2)
        tone.copyInto(withSilence, 0)
        val gated = LoudnessMeter.integrated(withSilence, rate, 1)
        near(gated, -23.01, 0.3, "a 3 s tone then 3 s of silence still reads -23 (the blocks straddling the end are kept, as the standard keeps them)")
        // Quiet room tone 30 dB under the programme is dropped by the relative gate too.
        val room = FloatArray(tone.size) { i -> (Gain.dbToLinear(-50.0) * sin(2 * PI * 300 * i / rate)).toFloat() }
        val programme = FloatArray(tone.size * 2); tone.copyInto(programme, 0); room.copyInto(programme, tone.size)
        near(LoudnessMeter.integrated(programme, rate, 1), -23.01, 0.3, "room tone under the relative gate is ignored")

        section("momentary, short-term and range")
        val meter = LoudnessMeter(rate, 1)
        meter.process(sine(1000.0, -20.0, 1.0, rate, 1))
        meter.process(sine(1000.0, -30.0, 4.0, rate, 1))
        near(meter.momentaryLufs, -33.01, 0.3, "momentary reads the last 400 ms")
        check(meter.shortTermLufs < -30.0 && meter.shortTermLufs > -34.0) { "short-term over the last 3 s: ${meter.shortTermLufs}" }
        check(meter.rangeLu >= 0.0) { "range is a non-negative spread" }
        val loudSoft = LoudnessMeter(rate, 1)
        loudSoft.process(sine(1000.0, -10.0, 6.0, rate, 1)); loudSoft.process(sine(1000.0, -25.0, 6.0, rate, 1))
        check(loudSoft.rangeLu > 8.0) { "a 15 dB step gives a wide range: ${loudSoft.rangeLu}" }
        check(meter.reading().integratedLufs < -20.0) { "reading() carries the integrated value" }

        section("true peak sees between the samples")
        // A full-scale sine whose peaks fall between samples: sample peak under 0, true peak about 0.
        val n = 4800
        val tricky = FloatArray(n) { i -> sin(2 * PI * (rate / 4.0) * i / rate + PI / 4).toFloat() }
        val samplePeak = Gain.linearToDb(Gain.peak(tricky).toDouble())
        val tp = TruePeak.of(tricky, 1)
        check(samplePeak < -2.9) { "sample peak hides the true peak: $samplePeak" }
        near(tp, 0.0, 0.3, "true peak finds it")
        // A step overshoots through any band-limiting filter (Gibbs), as it does through a real
        // DAC, so the level is ramped in over 200 samples and then held.
        val dc = FloatArray(2000) { i -> 0.5f * minOf(1f, i / 200f) }
        near(TruePeak.of(dc, 1), Gain.linearToDb(0.5), 0.05, "a held level reads as itself")

        section("normalising gains and targets")
        near(Gain.normaliseLoudness(-23.0, -16.0).toDouble(), Gain.dbToLinear(7.0), 1e-6, "7 dB up")
        near(Gain.normalisePeak(0.5f, -1f).toDouble(), Gain.dbToLinear(-1.0) / 0.5, 1e-6, "peak to -1 dBFS")
        check(LoudnessTarget.byId("podcast").lufs == -16.0 && LoudnessTarget.byId("nonsense") === LoudnessTarget.STREAMING) { "targets" }
        check(LoudnessTarget.all.map { it.id }.toSet().size == LoudnessTarget.all.size) { "target ids unique" }

        section("gated rms")
        val pauses = FloatArray(rate * 2) { i -> if (i < rate) (0.1 * sin(2 * PI * 400 * i / rate)).toFloat() else 0f }
        near(Levels.gatedRms(pauses, rate).toDouble(), 0.1 / Math.sqrt(2.0), 0.002, "a second of tone then silence reads the tone's RMS")
    }
}
