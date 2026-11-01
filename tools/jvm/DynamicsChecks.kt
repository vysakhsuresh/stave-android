import com.layerbit.stave.engine.dsp.Compressor
import com.layerbit.stave.engine.dsp.CompressorSettings
import com.layerbit.stave.engine.dsp.DeEsser
import com.layerbit.stave.engine.dsp.Gain
import com.layerbit.stave.engine.dsp.GainComputer
import com.layerbit.stave.engine.dsp.Gate
import com.layerbit.stave.engine.dsp.Limiter
import com.layerbit.stave.engine.dsp.PeakMeter
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

object DynamicsChecks : Suite {
    override val name = "dynamics"

    private fun tone(hz: Double, dbfs: Double, seconds: Double, rate: Int, channels: Int = 1): FloatArray {
        val a = Gain.dbToLinear(dbfs).toFloat()
        val n = (seconds * rate).toInt()
        return FloatArray(n * channels) { i -> a * sin(2 * PI * hz * (i / channels) / rate).toFloat() }
    }

    private fun peakDb(buffer: FloatArray, from: Int, to: Int): Double = Gain.linearToDb(Gain.peak(buffer, from, to).toDouble())

    override fun run() {
        val rate = 48_000
        section("gain computer, hand-worked")
        near(GainComputer.compressionDb(-10.0, -20.0, 4.0, 0.0), 7.5, 1e-9, "10 dB over at 4:1 takes 7.5 dB")
        near(GainComputer.compressionDb(-30.0, -20.0, 4.0, 6.0), 0.0, 1e-9, "well under the threshold takes nothing")
        near(GainComputer.compressionDb(-20.0, -20.0, 4.0, 6.0), 0.5625, 1e-9, "at the threshold inside a 6 dB knee: (1/4-1)*9/12 = -0.5625 -> 0.5625 cut")
        near(GainComputer.compressionDb(0.0, -20.0, 2.0, 6.0), 10.0, 1e-9, "20 over at 2:1 past the knee")
        near(GainComputer.expansionDb(-60.0, -40.0, 3.0, 0.0, 80.0), 40.0, 1e-9, "20 under at 3:1 takes 40 more")
        near(GainComputer.expansionDb(-60.0, -40.0, 3.0, 0.0, 10.0), 10.0, 1e-9, "capped by the range")

        section("compressor brings a loud tone down by the law")
        val loud = tone(1000.0, -6.0, 1.0, rate)
        val comp = Compressor(rate, 1, CompressorSettings(thresholdDb = -20.0, ratio = 4.0, kneeDb = 0.0, attackMs = 5.0, releaseMs = 50.0))
        comp.process(loud)
        // Steady state: -6 dBFS is 14 over, 10.5 off by the law. A peak follower with a 5 ms attack
        // rides about a decibel under a sine's crest, so the measured cut is a little less; the
        // tolerance allows that and no more.
        near(peakDb(loud, rate / 2, rate), -16.5, 1.2, "steady-state output level")
        check(comp.reductionDb < -8.5 && comp.reductionDb > -11.5) { "reported reduction ${comp.reductionDb}" }
        val quiet = tone(1000.0, -30.0, 0.5, rate)
        Compressor(rate, 1, CompressorSettings(thresholdDb = -20.0, ratio = 4.0, kneeDb = 0.0)).process(quiet)
        near(peakDb(quiet, rate / 4, rate / 2), -30.0, 0.1, "under the threshold nothing changes")

        section("stereo compressor is linked")
        val stereo = FloatArray(rate * 2)
        for (i in 0 until rate) { stereo[i * 2] = Gain.dbToLinear(-6.0).toFloat() * sin(2 * PI * 500 * i / rate).toFloat(); stereo[i * 2 + 1] = 0.01f * sin(2 * PI * 500 * i / rate).toFloat() }
        Compressor(rate, 2, CompressorSettings(thresholdDb = -20.0, ratio = 4.0, kneeDb = 0.0)).process(stereo)
        val leftDb = peakDb(FloatArray(rate / 2) { stereo[(rate / 2 + it) * 2] }, 0, rate / 2)
        val rightDb = peakDb(FloatArray(rate / 2) { stereo[(rate / 2 + it) * 2 + 1] }, 0, rate / 2)
        near(leftDb, -16.5, 1.2, "left compressed")
        near(rightDb - leftDb, -34.0, 0.1, "the quiet right channel takes exactly the same cut (linked)")

        section("limiter never passes its ceiling, even on a step")
        val burst = FloatArray(rate) { i -> if (i in 10_000..11_000) 0.9f else 0.05f * sin(0.1 * i).toFloat() }
        val lim = Limiter(rate, 1, ceilingDb = -3.0, lookaheadMs = 5.0)
        lim.process(burst)
        val ceiling = Gain.dbToLinear(-3.0).toFloat()
        var worst = 0f; for (v in burst) worst = maxOf(worst, abs(v))
        check(worst <= ceiling + 1e-4f) { "limiter let $worst past ${ceiling}" }
        near(peakDb(burst, 10_000 + lim.latency + 100, 11_000 + lim.latency - 100), -3.0, 0.05, "the burst sits at the ceiling")
        near(peakDb(burst, 30_000, 40_000), Gain.linearToDb(0.05), 0.2, "the quiet part is untouched after release")

        section("gate closes on silence, opens on signal, holds through a short dip")
        val gated = FloatArray(rate * 2) { i ->
            val t = i.toDouble() / rate
            val level = if (t < 0.5) 0.001f else if (t < 1.0) 0.3f else if (t < 1.02) 0.0f else if (t < 1.5) 0.3f else 0.001f
            level * sin(2 * PI * 300 * t).toFloat()
        }
        // An 80 ms release: the detector falls 50 dB in about 170 ms, the hold adds 50, and the
        // gain then closes on its own time constant, so by 400 ms the tail is 15 dB further down.
        Gate(rate, 1, thresholdDb = -30.0, rangeDb = 60.0, releaseMs = 80.0, holdMs = 50.0).process(gated)
        check(peakDb(gated, (0.2 * rate).toInt(), (0.45 * rate).toInt()) < -75.0) { "closed on the quiet head: ${peakDb(gated, (0.2 * rate).toInt(), (0.45 * rate).toInt())}" }
        near(peakDb(gated, (0.7 * rate).toInt(), (0.95 * rate).toInt()), Gain.linearToDb(0.3), 0.1, "open on the tone")
        near(peakDb(gated, (1.1 * rate).toInt(), (1.45 * rate).toInt()), Gain.linearToDb(0.3), 0.1, "still open after a 20 ms dip, held")
        check(peakDb(gated, (1.9 * rate).toInt(), (2.0 * rate).toInt()) < -75.0) { "closed again on the quiet tail: ${peakDb(gated, (1.9 * rate).toInt(), (2.0 * rate).toInt())}" }

        section("de-esser softens the band and leaves the rest")
        val mix = FloatArray(rate) { i -> (0.3 * sin(2 * PI * 200 * i / rate) + 0.3 * sin(2 * PI * 6500 * i / rate)).toFloat() }
        val before = mix.copyOf()
        DeEsser(rate, 1, hz = 6500.0, thresholdDb = -30.0, ratio = 6.0).process(mix)
        // Measure each component by correlating with its own sine over the last half second.
        fun level(buf: FloatArray, hz: Double): Double { var s = 0.0; for (i in rate / 2 until rate) s += buf[i] * sin(2 * PI * hz * i / rate); return s * 2 / (rate / 2) }
        near(level(mix, 200.0), level(before, 200.0), 0.02, "the 200 Hz body is left alone")
        check(level(mix, 6500.0) < level(before, 6500.0) * 0.6) { "the sibilant band came down: ${level(mix, 6500.0)} from ${level(before, 6500.0)}" }

        section("peak meter ballistics")
        val meter = PeakMeter(rate, fallDbPerSecond = 20.0, holdMs = 500.0)
        meter.process(FloatArray(100) { 0.5f })
        near(meter.peakDb.toDouble(), Gain.linearToDb(0.5), 0.05, "reads the peak")
        meter.process(FloatArray(rate) { 0f })
        near(meter.peakDb.toDouble(), Gain.linearToDb(0.5) - 20.0, 0.1, "falls 20 dB in a second")
        check(!meter.clipped) { "not clipped" }
        meter.process(floatArrayOf(1f))
        check(meter.clipped) { "clipped at full scale" }

        section("block by block equals all at once (the offline tools stream whole songs through these)")
        val r = 48_000
        val song = FloatArray(r * 2 * 3) { i -> val t = (i / 2).toDouble() / r; (0.9 * sin(2 * PI * 110 * t) * (if ((i / 2) % 9000 < 3000) 1.0 else 0.2) + (if (i % 2 == 0) 0.05 else -0.05)).toFloat() }
        fun blocks(p: (FloatArray, Int) -> Unit): FloatArray { val out = song.copyOf(); val b = FloatArray(8192 * 2); var pos = 0; val n = out.size / 2; while (pos < n) { val m = minOf(8192, n - pos); System.arraycopy(out, pos * 2, b, 0, m * 2); p(b, m); System.arraycopy(b, 0, out, pos * 2, m * 2); pos += m }; return out }
        fun whole(p: (FloatArray, Int) -> Unit): FloatArray { val out = song.copyOf(); p(out, out.size / 2); return out }
        val lim1 = Limiter(r, 2, -1.0); val lim2 = Limiter(r, 2, -1.0)
        check(blocks { b, n -> lim1.process(b, n) }.contentEquals(whole { b, n -> lim2.process(b, n) })) { "limiter in blocks differs from whole" }
        val cs = CompressorSettings(thresholdDb = -20.0, ratio = 4.0)
        val c1 = Compressor(r, 2, cs); val c2 = Compressor(r, 2, cs)
        check(blocks { b, n -> c1.process(b, n) }.contentEquals(whole { b, n -> c2.process(b, n) })) { "compressor in blocks differs from whole" }
        val g1 = Gate(r, 2, -30.0, 40.0); val g2 = Gate(r, 2, -30.0, 40.0)
        check(blocks { b, n -> g1.process(b, n) }.contentEquals(whole { b, n -> g2.process(b, n) })) { "gate in blocks differs from whole" }
        val m1 = com.layerbit.stave.engine.dsp.LoudnessMeter(r, 2); blocks { b, n -> m1.process(b, n) }
        val m2 = com.layerbit.stave.engine.dsp.LoudnessMeter(r, 2); whole { b, n -> m2.process(b, n) }
        near(m1.integratedLufs, m2.integratedLufs, 1e-9, "loudness in blocks")
        val d1 = DeEsser(r, 2); val d2 = DeEsser(r, 2)
        check(blocks { b, n -> d1.process(b, n) }.contentEquals(whole { b, n -> d2.process(b, n) })) { "de-esser in blocks differs from whole" }
        val t1 = com.layerbit.stave.engine.dsp.TransientShaper(r, 2, 0.5, -0.5); val t2 = com.layerbit.stave.engine.dsp.TransientShaper(r, 2, 0.5, -0.5)
        check(blocks { b, n -> t1.process(b, n) }.contentEquals(whole { b, n -> t2.process(b, n) })) { "transient shaper in blocks differs from whole" }
        val mb1 = com.layerbit.stave.engine.dsp.MultibandCompressor(r, 2); val mb2 = com.layerbit.stave.engine.dsp.MultibandCompressor(r, 2)
        check(blocks { b, n -> mb1.process(b, n) }.contentEquals(whole { b, n -> mb2.process(b, n) })) { "multiband in blocks differs from whole" }
    }
}
