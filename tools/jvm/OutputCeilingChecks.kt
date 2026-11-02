import com.layerbit.stave.engine.dsp.OutputCeiling
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

object OutputCeilingChecks : Suite {
    override val name = "ceiling"

    private const val RATE = 48_000

    /** A stereo tone at [level], interleaved, with a different phase each side. */
    private fun tone(level: Float, frames: Int, hz: Double = 997.0) = FloatArray(frames * 2) { i ->
        val f = i / 2; (level * sin(2 * PI * hz * f / RATE + if (i % 2 == 0) 0.0 else 0.7)).toFloat()
    }

    /** Runs [x] through a ceiling in blocks of [block] frames, as the callback hands them over. */
    private fun run(x: FloatArray, block: Int = 192, ceiling: OutputCeiling = OutputCeiling(RATE, 2)): FloatArray {
        val y = x.copyOf(); var f = 0; val frames = x.size / 2
        while (f < frames) {
            val n = minOf(block, frames - f)
            val b = y.copyOfRange(f * 2, (f + n) * 2); ceiling.process(b, n); b.copyInto(y, f * 2); f += n
        }
        return y
    }

    override fun run() {
        val w = OutputCeiling(RATE, 2).lookahead

        section("under the ceiling it is a millisecond of delay and nothing else, bit for bit")
        run {
            val x = tone(0.95f, RATE)
            val y = run(x)
            for (i in 0 until x.size - w * 2) check(y[i + w * 2] == x[i]) { "sample $i changed: ${x[i]} became ${y[i + w * 2]}" }
            check(y.take(w * 2).all { it == 0f }) { "the first millisecond is the empty delay" }
        }

        section("a song decoded over full scale never leaves it, and the gain never steps")
        run {
            // A 1.045 peak, as Kalimba.mp3 decodes, on hits every 100 ms over a quieter bed.
            val x = tone(0.6f, RATE)
            for (hit in 0 until 10) for (k in 0 until 240) { val f = hit * 4800 + 1000 + k; val e = (1.0 - abs(k - 120) / 120.0).toFloat(); x[f * 2] += 0.445f * e * x[f * 2].let { if (it >= 0) 1f else -1f }; x[f * 2 + 1] += 0.445f * e * x[f * 2 + 1].let { if (it >= 0) 1f else -1f } }
            val peakIn = x.maxOf { abs(it) }
            check(peakIn > 1.0f) { "the fixture goes over: $peakIn" }
            val y = run(x)
            val peakOut = y.maxOf { abs(it) }
            check(peakOut <= 0.999f) { "out at $peakOut" }
            // The gain is the output over the delayed input; its steepest step per frame.
            var steepest = 0f; var prev = 1f
            for (f in w until RATE) {
                val i = x[(f - w) * 2]; if (abs(i) < 0.05f) continue
                val g = y[f * 2] / i
                if (f > w) steepest = maxOf(steepest, abs(g - prev)); prev = g
            }
            // A hard clip would step the gain by the whole overshoot (0.04) in one frame.
            check(steepest < 0.004f) { "the gain stepped by $steepest in a frame" }
            println("      peak in ${"%.3f".format(java.util.Locale.ROOT, peakIn)}, out ${"%.4f".format(java.util.Locale.ROOT, peakOut)}, steepest gain step ${"%.5f".format(java.util.Locale.ROOT, steepest)}")
        }

        section("after an over the output is bit-exact again within half a second")
        run {
            val x = tone(0.6f, RATE * 2)
            for (k in 0 until 480) x[(2000 + k) * 2] *= 1.8f
            val y = run(x)
            val from = 2000 + 480 + RATE / 2
            for (f in from until RATE * 2 - w) { check(y[(f + w) * 2] == x[f * 2] && y[(f + w) * 2 + 1] == x[f * 2 + 1]) { "frame $f still changed" } }
        }

        section("the block size the callback happens to use changes nothing")
        run {
            val x = tone(1.3f, RATE / 4)
            val a = run(x, 192); val b = run(x, 1); val c = run(x, 964)
            check(a.contentEquals(b) && a.contentEquals(c)) { "blocks of 192, 1 and 964 frames differ" }
        }

        section("reset empties the delay: nothing from before a pause comes out after it")
        run {
            val c = OutputCeiling(RATE, 2)
            val loud = tone(0.9f, 500); c.process(loud, 500)
            c.reset()
            val silence = FloatArray(w * 4); c.process(silence, w * 2)
            check(silence.all { it == 0f }) { "the music before the reset came out after it" }
        }
    }
}
