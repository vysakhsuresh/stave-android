import com.layerbit.stave.engine.dsp.LoudnessMeter
import com.layerbit.stave.engine.dsp.Resampler
import com.layerbit.stave.engine.export.ExportChain
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * The export chain in blocks (ExportChain): it must give what the whole-song arithmetic gives,
 * line up frame for frame, land on the target, and not care how big its blocks are.
 */
object ExportChainChecks : Suite {
    override val name = "exportchain"

    private class Mem(val data: FloatArray, override val channels: Int) : ExportChain.Source {
        override val frames: Long get() = (data.size / channels).toLong()
        override fun read(from: Long, count: Int, into: FloatArray) { data.copyInto(into, 0, (from * channels).toInt(), ((from + count) * channels).toInt()) }
    }

    private class Staged(val channels: Int) : ExportChain.Stage {
        var data = FloatArray(0); var size = 0
        override fun append(block: FloatArray, frames: Int) {
            val need = size + frames * channels
            if (need > data.size) data = data.copyOf(maxOf(need, data.size * 2))
            block.copyInto(data, size, 0, frames * channels); size = need
        }
        override fun read(from: Long, count: Int, into: FloatArray) { data.copyInto(into, 0, (from * channels).toInt(), ((from + count) * channels).toInt()) }
        override fun overwrite(from: Long, block: FloatArray, frames: Int) { block.copyInto(data, (from * channels).toInt(), 0, frames * channels) }
        fun out() = data.copyOf(size)
    }

    private fun run(input: FloatArray, inCh: Int, plan: ExportChain.Plan, block: Int = ExportChain.BLOCK): Pair<FloatArray, ExportChain.Report> {
        val st = Staged(plan.outChannels)
        val r = ExportChain.run(Mem(input, inCh), st, plan, block)
        return st.out() to r
    }

    /** Quiet music: two tones and a slow swell, stereo, about -30 LUFS, with a snap at [snapAt]. */
    private fun quiet(frames: Int, rate: Int, snapAt: Int = -1): FloatArray = FloatArray(frames * 2) { i ->
        val f = i / 2
        val swell = 0.5 + 0.5 * sin(2 * PI * 0.5 * f / rate)
        var v = 0.03 * swell * (sin(2 * PI * 220.0 * f / rate) + (if (i % 2 == 0) 0.6 else 0.3) * sin(2 * PI * 3_300.0 * f / rate))
        if (f == snapAt) v += 0.2
        v.toFloat()
    }

    override fun run() {
        val rate = 48_000
        val music = quiet(rate * 4, rate, snapAt = 100_000)

        section("with nothing asked of it, the file is the mix exactly")
        val (same, r0) = run(music, 2, ExportChain.Plan(rate, rate, 2))
        check(same.contentEquals(music)) { "a plain export changed the mix" }
        check(r0.frames == (music.size / 2).toLong()) { "frames ${r0.frames}" }

        section("mono is the average of the two sides")
        val (mono, _) = run(music, 2, ExportChain.Plan(rate, rate, 1))
        check(mono.size == music.size / 2) { "mono length ${mono.size}" }
        for (f in mono.indices) check(mono[f] == (music[f * 2] + music[f * 2 + 1]) / 2f) { "mono frame $f" }

        section("to a target: on the loudness, under the ceiling, and the report tells the truth")
        val plan = ExportChain.Plan(rate, rate, 2, targetLufs = -14.0, ceilingDbtp = -1.0)
        val (loud, r1) = run(music, 2, plan)
        check(loud.size == music.size) { "a target changed the length: ${loud.size / 2} frames for ${music.size / 2}" }
        val read = LoudnessMeter(rate, 2).also { it.process(loud) }.reading()
        near(read.integratedLufs, -14.0, 0.5, "integrated loudness")
        check(read.truePeakDbtp <= -1.0 + 0.02) { "true peak ${read.truePeakDbtp} dBTP over -1" }
        near(r1.integratedLufs, read.integratedLufs, 0.01, "reported loudness")
        near(r1.truePeakDbtp, read.truePeakDbtp, 0.01, "reported true peak")

        section("a peak between samples is trimmed under the ceiling")
        // A quarter-rate tone sampled 45 degrees off its crests: every sample is at 0.707 of the true
        // peak, so a limiter that holds samples passes a true peak 3 dB over. The trim is what catches it.
        // (A 12 kHz tone reads loud on the K-weighting, so the target is set high enough to reach the limiter.)
        val crest = FloatArray(rate * 2 * 2) { i -> (0.3 * sin(2 * PI * 12_000.0 * (i / 2) / rate + PI / 4)).toFloat() }
        val (hot, rh) = run(crest, 2, ExportChain.Plan(rate, rate, 2, targetLufs = 0.0, ceilingDbtp = -6.0))
        val hotRead = LoudnessMeter(rate, 2).also { it.process(hot) }.reading()
        check(hotRead.truePeakDbtp <= -6.0 + 0.02) { "true peak ${hotRead.truePeakDbtp} dBTP over -6" }
        near(rh.truePeakDbtp, hotRead.truePeakDbtp, 0.01, "reported true peak")

        section("the limiter's look-ahead does not move the music")
        // The snap is the loudest frame; after the gain it must still be the loudest, at its own frame.
        var at = 0; var best = 0f
        for (f in 0 until loud.size / 2) { val a = abs(loud[f * 2]); if (a > best) { best = a; at = f } }
        check(abs(at - 100_000) <= 1) { "the snap moved to frame $at" }

        section("a new rate: the same samples the whole-song converter gives, and the right length")
        val cd = 44_100
        val src = quiet(cd * 3, cd)
        val (conv, r2) = run(src, 2, ExportChain.Plan(cd, rate, 2))
        val whole = Resampler(cd, rate).convertInterleaved(src, 2)
        check(conv.size == whole.size) { "converted ${conv.size} samples, whole ${whole.size}" }
        for (i in whole.indices) check(conv[i] == whole[i]) { "sample $i ${conv[i]} vs ${whole[i]}" }
        check(r2.frames == Math.round(cd * 3 * rate.toDouble() / cd)) { "frames ${r2.frames}" }

        section("the block size changes nothing")
        val full = ExportChain.Plan(cd, rate, 1, targetLufs = -16.0, ceilingDbtp = -1.0, ditherBits = 16)
        val (a, ra) = run(src, 2, full, block = 8192)
        val (b, rb) = run(src, 2, full, block = 997)
        check(a.size == b.size) { "${a.size} vs ${b.size}" }
        for (i in a.indices) check(a[i] == b[i]) { "sample $i ${a[i]} vs ${b[i]} with blocks of 8192 and 997" }
        near(ra.integratedLufs, rb.integratedLufs, 1e-9, "same reading")

        section("16-bit dither is a least step of noise, no more")
        val (plain, _) = run(music, 2, ExportChain.Plan(rate, rate, 2))
        val (dith, _) = run(music, 2, ExportChain.Plan(rate, rate, 2, ditherBits = 16))
        val lsb = 1.0 / 32768
        var changed = 0
        for (i in plain.indices) { val d = abs(dith[i] - plain[i]); check(d <= 1.0001 * lsb) { "sample $i moved ${d / lsb} LSB" }; if (d > 0) changed++ }
        check(changed > plain.size / 2) { "dither touched only $changed of ${plain.size}" }
    }
}
