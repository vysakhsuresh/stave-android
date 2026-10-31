import com.layerbit.stave.engine.music.Onsets
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

object OnsetsChecks : Suite {
    override val name = "onsets"

    /** Short bright clicks at [positionsMs] - a noisy burst with a 3 ms decay - over a faint noise floor. */
    fun clicks(positionsMs: List<Int>, seconds: Double, rate: Int, drone: Boolean = false, seed: Int = 5): FloatArray {
        val rng = Random(seed)
        val n = (seconds * rate).toInt()
        val out = FloatArray(n) { ((rng.nextDouble() * 2 - 1) * 0.005).toFloat() }
        for (p in positionsMs) {
            val start = (p / 1000.0 * rate).toInt()
            var i = 0
            while (i < rate * 0.02 && start + i < n) {
                val t = i.toDouble() / rate
                val env = exp(-t / 0.003)
                out[start + i] += ((0.5 * sin(2 * PI * 1500 * t) + 0.5 * (rng.nextDouble() * 2 - 1)) * env * 0.8).toFloat()
                i++
            }
        }
        if (drone) for (i in 0 until n) out[i] += (0.4 * sin(2 * PI * 110.0 * i / rate)).toFloat()
        return out
    }

    private val eight = listOf(100, 350, 520, 800, 1010, 1300, 1620, 1850)

    override fun run() {
        section("eight clicks in two seconds are found, and nothing else")
        for (rate in listOf(8000, 44_100)) {
            val found = Onsets.detect(clicks(eight, 2.0, rate), rate)
            val foundMs = found.map { it * 1000.0 / rate }
            check(found.size == 8) { "at $rate Hz found ${found.size} onsets: ${foundMs.map { it.toInt() }}" }
            var worst = 0.0
            for (p in eight) {
                val off = foundMs.minOf { abs(it - p) }
                if (off > worst) worst = off
                check(off <= 20.0) { "the click at $p ms at $rate Hz was found ${off.toInt()} ms away" }
            }
            check(found.zipWithNext { a, b -> b > a }.all { it }) { "onsets are not ascending" }
            println("      $rate Hz: ${foundMs.map { it.toInt() }}, worst $worst ms off")
        }

        section("clicks over a drone, and clicks at -40 dB, are still found; white noise has none")
        run {
            val found = Onsets.detect(clicks(eight, 2.0, 44_100, drone = true), 44_100)
            check(found.size == 8) { "over a drone found ${found.size}: ${found.map { it * 1000 / 44_100 }}" }
            val quiet = clicks(eight, 2.0, 44_100)
            for (i in quiet.indices) quiet[i] *= 0.01f
            val faint = Onsets.detect(quiet, 44_100)
            check(faint.size == 8) { "at -40 dB found ${faint.size}: ${faint.map { it * 1000 / 44_100 }}" }
            // Noise is loud, broadband and changes every frame; a level detector would call all of it onsets.
            // Two seeds: the second is the one whose tallest peak came nearest the floor
            // when it was measured (5.2 of the 6 allowed), so it is the one that would
            // show a floor lowered again.
            for ((seed, level) in listOf(3 to 0.3, 7 to 0.3, 7 to 0.9)) {
                val rng = Random(seed)
                val noise = FloatArray(44_100 * 10) { ((rng.nextDouble() * 2 - 1) * level).toFloat() }
                val inNoise = Onsets.detect(noise, 44_100)
                check(inNoise.isEmpty()) { "ten seconds of white noise (seed $seed at $level) had ${inNoise.size} onsets" }
            }
        }

        section("silence and a steady tone have no onsets but the tone's start")
        run {
            check(Onsets.detect(FloatArray(44_100), 44_100).isEmpty()) { "silence had onsets" }
            val tone = FloatArray(44_100 * 2) { (0.5 * sin(2 * PI * 440.0 * it / 44_100)).toFloat() }
            val found = Onsets.detect(tone, 44_100)
            check(found.size <= 1) { "a steady tone had ${found.size} onsets" }
            if (found.isNotEmpty()) check(found[0] < 44_100 / 10) { "a steady tone's one onset was at ${found[0]}" }
            check(Onsets.detect(FloatArray(0), 44_100).isEmpty() && Onsets.detect(FloatArray(100), 8000).isEmpty() && Onsets.detect(FloatArray(8000), 0).isEmpty()) { "degenerate inputs" }
        }

        section("two hits inside the minimum gap are one")
        run {
            val found = Onsets.detect(clicks(listOf(500, 530), 1.0, 44_100), 44_100)
            check(found.size == 1) { "two clicks 30 ms apart gave ${found.size} onsets" }
            val apart = Onsets.detect(clicks(listOf(500, 600), 1.0, 44_100), 44_100)
            check(apart.size == 2) { "two clicks 100 ms apart gave ${apart.size} onsets" }
        }

        section("pick: local maxima over the local median, the larger of two close peaks kept, the gap held")
        run {
            val fps = 62.5
            val env = FloatArray(200)
            // 10 and 12 are two frames apart, inside the 50 ms gap (4 frames at 62.5), so the
            // taller is kept; 100 is a peak under the floor and is not an onset.
            env[10] = 7f; env[12] = 9f; env[40] = 7f; env[100] = Onsets.FLOOR - 0.5f
            check(Onsets.pick(env, fps) == listOf(12, 40)) { "picked ${Onsets.pick(env, fps)}" }
            val tie = FloatArray(200)
            tie[10] = 7f; tie[12] = 7f; tie[40] = 7f
            check(Onsets.pick(tie, fps) == listOf(10, 40)) { "a tie picked ${Onsets.pick(tie, fps)}" }
            // Everything raised by a plateau: the median rises with it and a peak must still stand the floor clear of it.
            val raised = FloatArray(200) { 2f }
            raised[50] = 2f + Onsets.FLOOR - 1f; raised[120] = 2f + Onsets.FLOOR + 1f
            check(Onsets.pick(raised, fps) == listOf(120)) { "over a plateau picked ${Onsets.pick(raised, fps)}" }
            // A busy background: the upper quartile is up, so the step is two of that spread, and only the peak well over it counts.
            val busy = FloatArray(200) { i -> if (i % 4 == 0) 4f else 0f }
            busy[50] = 7f; busy[122] = 14f
            check(Onsets.pick(busy, fps) == listOf(122)) { "over a busy background picked ${Onsets.pick(busy, fps)}" }
            // The same two peaks over a quiet background are both onsets: the step is the floor alone.
            val calm = FloatArray(200)
            calm[50] = 7f; calm[122] = 14f
            check(Onsets.pick(calm, fps) == listOf(50, 122)) { "over a calm background picked ${Onsets.pick(calm, fps)}" }
            check(Onsets.pick(FloatArray(0), fps).isEmpty()) { "an empty envelope" }
            // The minimum gap is rounded up: 50 ms at 62.5 frames a second is 3.125 frames, and 3 would allow 48 ms.
            val close = FloatArray(200)
            close[10] = 7f; close[13] = 7f
            check(Onsets.pick(close, fps).size == 1) { "peaks 48 ms apart were both kept" }
            val farEnough = FloatArray(200)
            farEnough[10] = 7f; farEnough[14] = 7f
            check(Onsets.pick(farEnough, fps).size == 2) { "peaks 64 ms apart were merged" }
        }
    }
}
