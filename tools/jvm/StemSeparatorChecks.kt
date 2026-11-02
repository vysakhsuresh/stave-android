import com.layerbit.stave.engine.dsp.StemSeparator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

object StemSeparatorChecks : Suite {
    override val name = "stems"

    private const val RATE = 44_100

    /** Noise in bursts: on for [onMs], off for [onMs], starting off when [late]. */
    private fun bursts(n: Int, onMs: Int, late: Boolean, seed: Int): FloatArray {
        val r = Random(seed); val period = RATE * onMs / 1000
        return FloatArray(n) { i -> val on = ((i / period) % 2 == 0) != late; if (on) ((r.nextDouble() * 2 - 1) * 0.3).toFloat() else 0f }
    }

    /**
     * An oracle: the true magnitude of each source, worked out here with a plain DFT framing of
     * its own (centred periodic-Hann frames), not the separator's code.
     */
    private fun oracle(sources: List<Pair<FloatArray, FloatArray>>): (Int, Int, FloatArray, FloatArray) -> Unit {
        val size = StemSeparator.SIZE; val hop = StemSeparator.HOP; val bins = StemSeparator.BINS
        val w = DoubleArray(size) { 0.5 - 0.5 * cos(2 * PI * it / size) }
        val fft = com.layerbit.stave.engine.dsp.Fft.of(size)
        return { stem, firstFrame, _, out ->
            out.fill(0f)
            val (l, r) = sources[stem]
            for (j in 0 until StemSeparator.WINDOW) {
                val f = firstFrame + j
                if (f >= StemSeparator.framesFor(l.size)) break
                for ((c, x) in listOf(l, r).withIndex()) {
                    val re = FloatArray(size); val im = FloatArray(size)
                    for (k in 0 until size) { val p = f * hop - size / 2 + k; re[k] = if (p in x.indices) (x[p] * w[k]).toFloat() else 0f }
                    fft.forward(re, im)
                    for (k in 0 until bins) out[(j * 2 + c) * bins + k] = sqrt(re[k] * re[k] + im[k] * im[k])
                }
            }
        }
    }

    private val spool = java.nio.file.Files.createTempDirectory("stave-stems").toFile()
    private var open = 0
    private var mostOpen = 0

    private fun separate(l: FloatArray, r: FloatArray, stems: Int, model: (Int, Int, FloatArray, FloatArray) -> Unit): List<Pair<FloatArray, FloatArray>> {
        val out = List(stems) { FloatArray(l.size) to FloatArray(l.size) }
        val at = IntArray(stems)
        val opener = { stem: Int ->
            open++; mostOpen = maxOf(mostOpen, open)
            object : StemSeparator.StemModel {
                override fun infer(firstFrame: Int, input: FloatArray, output: FloatArray) = model(stem, firstFrame, input, output)
                override fun close() { open-- }
            }
        }
        StemSeparator(stems, spool).run(l, r, opener, { s, a, b, count -> a.copyInto(out[s].first, at[s], 0, count); b.copyInto(out[s].second, at[s], 0, count); at[s] += count })
        for (s in 0 until stems) check(at[s] == l.size) { "stem $s came out ${at[s]} samples long, not ${l.size}" }
        check(open == 0) { "a model was left open" }
        check(spool.listFiles().isNullOrEmpty()) { "spool files were left behind: ${spool.listFiles()?.map { it.name }}" }
        return out
    }

    private fun errorDb(got: FloatArray, want: FloatArray): Double {
        var e = 0.0; var p = 0.0
        for (i in want.indices) { val d = (got[i] - want[i]).toDouble(); e += d * d; p += want[i].toDouble() * want[i] }
        return 10 * log10(e / p + 1e-30)
    }

    override fun run() {
        // Eight seconds: more frames than one model call holds, so a call boundary is crossed.
        val n = RATE * 8
        check(StemSeparator.framesFor(n) > StemSeparator.WINDOW) { "the fixture spans more than one call" }

        section("whatever the model says, the stems sum to the mix, and each is the mix's length")
        run {
            val l = bursts(n, 90, false, 1); val r = bursts(n, 90, true, 2)
            for (i in 0 until n) { l[i] += (0.2 * sin(2 * PI * 440 * i / RATE)).toFloat(); r[i] += (0.2 * sin(2 * PI * 660 * i / RATE)).toFloat() }
            val rnd = Random(9)
            val stems = separate(l, r, 4) { _, _, _, out -> for (k in out.indices) out[k] = rnd.nextFloat() }
            val sumL = FloatArray(n); val sumR = FloatArray(n)
            for ((a, b) in stems) for (i in 0 until n) { sumL[i] += a[i]; sumR[i] += b[i] }
            val e = maxOf(errorDb(sumL, l), errorDb(sumR, r))
            check(e < -80) { "the stems sum to the mix only to $e dB" }
            println("      four stems against the mix: ${"%.1f".format(java.util.Locale.ROOT, e)} dB")
        }

        section("where every model says nothing, the sound still goes to the stems, not nowhere")
        run {
            // The models' last layer is a ReLU: on the phone whole bins came back zero from all
            // four, the shares were 0/0, and the stems summed to the mix only to -25 dB.
            val l = bursts(n, 90, false, 11); val r = bursts(n, 90, true, 12)
            val stems = separate(l, r, 4) { stem, _, _, out ->
                for (k in out.indices) { val bin = k % StemSeparator.BINS; out[k] = if (bin > 300) 0f else (stem + 1).toFloat() }
            }
            val sumL = FloatArray(n); for ((a, _) in stems) for (i in 0 until n) sumL[i] += a[i]
            val e = errorDb(sumL, l)
            check(e < -80) { "with silent bins the stems sum to the mix only to $e dB" }
        }

        section("a model answering NaN or infinity spoils nothing: the stems stay finite and sum to the mix")
        run {
            val l = bursts(n, 90, false, 13); val r = bursts(n, 90, true, 14)
            val stems = separate(l, r, 4) { stem, _, _, out ->
                for (k in out.indices) out[k] = when { stem == 0 -> Float.NaN; stem == 1 && k % 7 == 0 -> Float.POSITIVE_INFINITY; else -> 0.5f }
            }
            check(stems.all { (a, b) -> a.all { it.isFinite() } && b.all { it.isFinite() } }) { "a stem came out with NaN or infinity in it" }
            val sumL = FloatArray(n); for ((a, _) in stems) for (i in 0 until n) sumL[i] += a[i]
            val e = errorDb(sumL, l)
            check(e < -80) { "with a NaN model the stems sum to the mix only to $e dB" }
        }

        section("with the true magnitudes, the stems come back as the sources: the masks sit on their own frames")
        run {
            // Two sources taking turns every 90 ms, so a mask applied a few frames off would put
            // one source's bursts into the other's stem.
            val aL = bursts(n, 90, false, 3); val aR = bursts(n, 90, false, 4)
            val bL = bursts(n, 90, true, 5); val bR = bursts(n, 90, true, 6)
            val mixL = FloatArray(n) { aL[it] + bL[it] }; val mixR = FloatArray(n) { aR[it] + bR[it] }
            val stems = separate(mixL, mixR, 2, oracle(listOf(aL to aR, bL to bR)))
            val ea = errorDb(stems[0].first, aL); val eb = errorDb(stems[1].second, bR)
            // Aligned, the oracle's masks get each source back to about -14 dB (the 93 ms frames
            // straddle the 90 ms turns); three hops off, as the first runner was, about -3 dB.
            check(ea < -10 && eb < -10) { "stem A off its source by $ea dB, B by $eb dB" }
            println("      oracle stems against their sources: A ${"%.1f".format(java.util.Locale.ROOT, ea)} dB, B ${"%.1f".format(java.util.Locale.ROOT, eb)} dB")
        }

        section("one model is open at a time (four together held 3.2 GB on the phone)")
        check(mostOpen == 1) { "$mostOpen models were open at once" }

        section("the spool's half floats keep a magnitude to a part in two thousand")
        run {
            var worst = 0.0
            var v = 1e-4f
            while (v < 60_000f) {
                val back = com.layerbit.stave.engine.dsp.Half.toFloat(com.layerbit.stave.engine.dsp.Half.of(v))
                worst = maxOf(worst, Math.abs(back - v) / v.toDouble()); v *= 1.013f
            }
            check(worst < 1.0 / 2000) { "half precision off by ${worst * 100} %" }
            check(com.layerbit.stave.engine.dsp.Half.toFloat(com.layerbit.stave.engine.dsp.Half.of(0f)) == 0f) { "zero" }
            check(com.layerbit.stave.engine.dsp.Half.toFloat(com.layerbit.stave.engine.dsp.Half.of(1e-9f)) < 1e-7f) { "a vanishing magnitude stays vanishing" }
            check(com.layerbit.stave.engine.dsp.Half.toFloat(com.layerbit.stave.engine.dsp.Half.of(1e9f)).isInfinite()) { "too large is infinite, not wrapped" }
        }

        section("a song shorter than one call, and an empty one")
        run {
            val l = bursts(RATE / 3, 50, false, 7); val r = l.copyOf()
            val stems = separate(l, r, 2) { _, _, _, out -> out.fill(1f) }
            val e = errorDb(FloatArray(l.size) { stems[0].first[it] + stems[1].first[it] }, l)
            check(e < -80) { "a third of a second: $e dB" }
            separate(FloatArray(0), FloatArray(0), 4) { _, _, _, _ -> }
        }
    }
}
