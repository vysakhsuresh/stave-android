import com.layerbit.stave.engine.music.BeatDetector
import com.layerbit.stave.engine.music.BeatMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * The beat tracker against click tracks at known tempos. Ported from Squish's BeatChecks,
 * whose fixtures and bounds were set by measuring the detector; the first section is the
 * tighter bound this engine asks for (a BPM and 20 ms), the rest is the port.
 */
object BeatsChecks : Suite {
    override val name = "beats"

    private const val RATE = 8000

    /**
     * A synthetic track: kick on every beat, snare on 2 and 4, hats on eighths, a
     * sustained bass note underneath and noise over the top.
     *
     * The bass is the point of the fixture. A plain click track can be found by a
     * level detector, so passing on one proves nothing. A sustained low note means the
     * overall loudness barely moves when the snare lands, and only something looking at
     * the spectrum will see it.
     */
    fun track(bpm: Double, seconds: Double, seed: Int = 7, withBass: Boolean = true, plain: Boolean = false, rate: Int = RATE): FloatArray {
        val rng = Random(seed)
        val n = (rate * seconds).toInt()
        val out = FloatArray(n)
        val beat = 60.0 / bpm

        fun hit(atSec: Double, freq: Double, decay: Double, gain: Double, noisy: Double = 0.0) {
            val start = (atSec * rate).toInt()
            var i = 0
            while (i < rate * 0.25 && start + i < n) {
                val t = i.toDouble() / rate
                val env = exp(-t / decay)
                val tone = sin(2 * PI * freq * t)
                val noise = (rng.nextDouble() * 2 - 1) * noisy
                out[start + i] += ((tone * (1 - noisy) + noise) * env * gain).toFloat()
                i++
            }
        }

        var b = 0
        var at = 0.0
        while (at < seconds) {
            if (plain) {
                // The same hit on every beat and nothing else. The only periodicity
                // in this is the beat, so there is no octave left to argue about.
                hit(at, 55.0, 0.09, 0.9)
            } else {
                when (b % 4) {
                    0 -> hit(at, 55.0, 0.09, 0.9)                   // kick, downbeat
                    2 -> hit(at, 55.0, 0.09, 0.7)                   // kick
                    else -> hit(at, 190.0, 0.07, 0.6, noisy = 0.7)  // snare
                }
                hit(at + beat / 2, 6000.0, 0.02, 0.22, noisy = 0.9) // off-beat hat
            }
            at += beat
            b++
        }

        if (withBass) {
            for (i in 0 until n) {
                val t = i.toDouble() / rate
                out[i] += (0.45 * sin(2 * PI * 82.0 * t)).toFloat()
            }
        }
        for (i in 0 until n) out[i] += ((rng.nextDouble() * 2 - 1) * 0.02).toFloat()
        return out
    }

    private fun truth(bpm: Double, untilMs: Double): List<Double> {
        val period = 60_000.0 / bpm
        return generateSequence(0.0) { it + period }.takeWhile { it < untilMs }.toList()
    }

    override fun run() {
        section("click tracks at 100 and 140: the tempo to a BPM, every beat to 20 ms, at 8 and 44.1 kHz")
        for (bpm in listOf(100.0, 140.0)) for (rate in listOf(RATE, 44_100)) {
            val map = BeatDetector.detect(track(bpm, 14.0, plain = true, rate = rate), rate)
            near(map.bpm.toDouble(), bpm, 1.0, "tempo of the $bpm click track at $rate Hz")
            check(map.confidence > 0.5f) { "the $bpm click track at $rate Hz scored only ${map.confidence}" }
            var worst = 0L
            for (t in truth(bpm, 13_500.0)) {
                val nearest = map.beatsMs.minByOrNull { abs(it - t) }!!
                val off = abs(nearest - Math.round(t))
                if (off > worst) worst = off
                check(off <= 20) { "the beat at ${t.toInt()} ms of the $bpm track at $rate Hz was found at $nearest, ${off} ms off" }
            }
            println("      $bpm BPM at $rate Hz: ${map.bpm} BPM, worst beat $worst ms off, ${map.beatsMs.size} beats")
        }

        // Two different assertions, because the question has two different answers.
        //
        // On a pattern with off-beat hi-hats the tempo is genuinely ambiguous: two
        // people tapping along to a slow track with busy hats will disagree by an
        // octave, and so will two beat trackers. What is *not* negotiable is that the
        // beats land on the music - so the check there is metrical consistency, which
        // is what anyone cutting to the track actually needs.
        //
        // On a pattern with nothing between the beats there is no ambiguity left, and
        // the exact tempo is demanded.
        //
        // 60 and 65 are here because of the correlation array's length. The duple
        // score reads the correlation at four times a candidate period, and the
        // array was sized to three times the *slowest* period - so for anything
        // under about 80 BPM that term came back zero while its double at twice the
        // tempo still had it, and the score leaned towards the faster reading of
        // exactly the slow tracks the octave weighting is there to settle.
        val tempos = listOf(60.0, 65.0, 70.0, 90.0, 100.0, 120.0, 128.0, 140.0, 150.0, 174.0)

        section("with off-beat hats the tempo may come back at an octave, but the beats land on the music")
        for (bpm in tempos) {
            val map = BeatDetector.detect(track(bpm, 14.0), RATE)
            val ratio = map.bpm / bpm
            check(map.confidence > 0.25f) { "tempo $bpm scored only ${map.confidence} confidence" }

            // Whatever level it counted at, it must be a simple metrical relation to
            // the truth - not merely "some number".
            val nearest = listOf(0.25, 1.0 / 3, 0.5, 1.0, 2.0, 3.0, 4.0).minByOrNull { abs(it - ratio) }!!
            check(abs(ratio - nearest) < 0.04) { "tempo $bpm came back ${map.bpm}, a ratio of $ratio - not a metrical level at all" }

            // And the beats have to land on the music. Counting faster than the truth
            // means every true beat has a beat on it; counting slower means every
            // detected beat sits on a true one. Either way, a cut lands on the beat.
            val period = 60_000.0 / bpm
            val truth = truth(bpm, 13_500.0)
            val slop = maxOf(45.0, period * 0.12)
            if (nearest >= 1.0) {
                val covered = truth.count { e -> map.beatsMs.any { abs(it - e) <= slop } }
                check(covered.toDouble() / truth.size > 0.9) { "tempo $bpm: only ${covered * 100 / truth.size}% of the true beats had a beat on them" }
            } else {
                val onGrid = map.beatsMs.count { b -> truth.any { abs(b - it) <= slop } }
                check(onGrid.toDouble() / map.beatsMs.size > 0.9) { "tempo $bpm: only ${onGrid * 100 / map.beatsMs.size}% of its beats were on the grid" }
            }

            // Even spacing. A tracker that loses the pulse and regains it passes the
            // coverage test above and fails this one.
            val gaps = map.beatsMs.zipWithNext { a, b -> (b - a).toDouble() }
            if (gaps.isNotEmpty()) {
                val expected = period / nearest
                val worst = gaps.maxOf { abs(it - expected) }
                check(worst < expected * 0.2) { "tempo $bpm: a gap was ${worst.toInt()}ms off an expected ${expected.toInt()}ms" }
            }
        }

        section("four on the floor, nothing in between: the tempo is not ambiguous")
        for (bpm in tempos) {
            val map = BeatDetector.detect(track(bpm, 14.0, plain = true), RATE)
            val err = abs(map.bpm - bpm)
            check(err < maxOf(2.0, bpm * 0.02)) { "unambiguous $bpm came back ${map.bpm}" }
            val period = 60_000.0 / bpm
            val truth = truth(bpm, 13_500.0)
            val slop = maxOf(45.0, period * 0.12)
            val covered = truth.count { e -> map.beatsMs.any { abs(it - e) <= slop } }
            check(covered.toDouble() / truth.size > 0.92) { "unambiguous $bpm: only ${covered * 100 / truth.size}% of beats found within ${slop.toInt()}ms" }
        }

        section("halving and doubling stay on the same grid and keep the downbeat")
        run {
            val map = BeatDetector.detect(track(128.0, 16.0), RATE)
            val half = map.halved()
            check(abs(half.bpm - map.bpm / 2) < 0.01f) { "halved reported ${half.bpm} from ${map.bpm}" }
            check(half.beatsMs.all { it in map.beatsMs }) { "halving invented a beat that was not there" }
            check(half.beatsMs.size in (map.beatsMs.size / 2 - 1)..(map.beatsMs.size / 2 + 1)) { "halving left ${half.beatsMs.size} of ${map.beatsMs.size}" }

            val twice = map.doubled()
            check(abs(twice.bpm - map.bpm * 2) < 0.01f) { "doubled reported ${twice.bpm}" }
            check(map.beatsMs.all { it in twice.beatsMs }) { "doubling lost one of the original beats" }
            val gaps = twice.beatsMs.zipWithNext { a, b -> b - a }
            val spread = (gaps.maxOrNull() ?: 0L) - (gaps.minOrNull() ?: 0L)
            // A frame is 16ms at this rate, which is as fine as a midpoint can land.
            check(spread <= 20L) { "doubling produced uneven gaps, spread ${spread}ms" }
            check(map.halved().doubled().beatsMs.size >= map.beatsMs.size - 2) { "halving then doubling lost the grid" }

            // A list too short to scale is handed back whole - *including its tempo
            // and its downbeat*. That is the right answer for the list and a trap for
            // a caller: Squish's scaleBeats once took the new bpm and phase from an
            // empty grid, so x2 on a song doubled the dots and left the card's tempo
            // where it was. Anything that reads a scaled map's tempo back must scale
            // a list that has beats in it.
            val empty = BeatMap(emptyList(), 120f, 0.8f, 2)
            check(empty.doubled() == empty) { "an empty map was changed by doubling: ${empty.doubled()}" }
            check(empty.halved() == empty) { "an empty map was changed by halving: ${empty.halved()}" }
            val one = BeatMap(listOf(500L), 120f, 0.8f, 0)
            check(one.doubled() == one) { "a one-beat map was changed by doubling" }
            val three = BeatMap(listOf(0L, 500L, 1000L), 120f, 0.8f, 0)
            check(three.halved() == three) { "a three-beat map was changed by halving" }
            val four = BeatMap(listOf(0L, 500L, 1000L, 1500L), 120f, 0.8f, 0)
            check(four.halved().bpm == 60f && four.halved().beatsMs == listOf(0L, 1000L)) { "a four-beat map halved to ${four.halved()}" }
            check(four.doubled().bpm == 240f && four.doubled().beatsMs.size == 7) { "a four-beat map doubled to ${four.doubled()}" }

            // The bar comes with the beats. Halving kept the indices congruent to the
            // downbeat and then said the downbeat was at 0, which is only true when it
            // was on the first or second beat. On the third or fourth it moved the bar
            // by half of one. Doubling left the offset outside the 0..3 every other
            // producer of the field maintains.
            val sixteen = (0..15).map { it * 500L }
            for (d in 0..3) {
                val m = BeatMap(sixteen, 120f, 0.9f, d)
                val wasDownbeatAt = m.beatsMs[d]
                val h = m.halved()
                check(h.downbeatOffset in 0..3) { "halving put the downbeat at ${h.downbeatOffset}, outside a bar" }
                check(h.beatsMs.getOrNull(h.downbeatOffset) == wasDownbeatAt) {
                    "halving with the downbeat on beat ${d + 1} moved it from $wasDownbeatAt to ${h.beatsMs.getOrNull(h.downbeatOffset)}"
                }
                val x2 = m.doubled()
                check(x2.downbeatOffset in 0..3) { "doubling put the downbeat at ${x2.downbeatOffset}, outside a bar" }
                check(x2.beatsMs.contains(wasDownbeatAt)) { "doubling lost the beat that was the downbeat" }
                // The old downbeat's new index need not *be* the offset - with the downbeat on
                // the third beat it lands at 4, and the offset is kept inside 0..3 - but the
                // two must name the same bar phase: congruent modulo a bar.
                val oldAt = x2.beatsMs.indexOf(wasDownbeatAt)
                check((oldAt - x2.downbeatOffset).mod(4) == 0) { "doubling with the downbeat on beat ${d + 1} put the bar at index ${x2.downbeatOffset}, the old downbeat is at $oldAt" }
                check(x2.every(4).isNotEmpty() && x2.every(4).all { it in x2.beatsMs }) { "doubling marks a bar on no beat" }
                check(h.every(4).all { it in h.beatsMs }) { "halving marks a bar on no beat" }
            }
            check(BeatMap(sixteen, 120f, 0.9f, 1).every(4) == listOf(500L, 2500L, 4500L, 6500L)) { "every(4) from the second beat" }
            check(BeatMap(sixteen, 120f, 0.9f, 6).downbeatOffset == 6 && BeatMap(sixteen, 120f, 0.9f, 0).withDownbeat(6).downbeatOffset == 2) { "withDownbeat wraps into the bar" }
        }

        section("a sustained bass note is not what it locks onto")
        run {
            val withBass = BeatDetector.detect(track(128.0, 14.0, withBass = true), RATE)
            val without = BeatDetector.detect(track(128.0, 14.0, withBass = false), RATE)
            check(abs(withBass.bpm - without.bpm) < 2f) { "a sustained bass note moved the answer from ${without.bpm} to ${withBass.bpm}" }
        }

        section("nothing rhythmic in it: silence is an empty map, noise a low confidence")
        run {
            val rng = Random(3)
            val noise = FloatArray(RATE * 10) { (rng.nextDouble() * 2 - 1).toFloat() * 0.3f }
            val map = BeatDetector.detect(noise, RATE)
            check(map.confidence < 0.35f) { "white noise reported ${map.confidence} confidence at ${map.bpm} BPM" }

            val quiet = BeatDetector.detect(FloatArray(RATE * 10), RATE)
            check(quiet.isEmpty) { "silence reported ${quiet.beatsMs.size} beats at ${quiet.bpm} BPM" }
            check(quiet.confidence < 0.2f) { "silence reported ${quiet.confidence} confidence" }
            check(quiet.bpm == 0f) { "silence reported a tempo of ${quiet.bpm}" }
        }

        section("degenerate inputs do not throw")
        run {
            check(BeatDetector.detect(FloatArray(0), RATE).isEmpty) { "empty input produced beats" }
            check(BeatDetector.detect(FloatArray(100), RATE).isEmpty) { "a too-short input produced beats" }
            check(BeatDetector.detect(FloatArray(RATE), 0).isEmpty) { "a zero sample rate produced beats" }
            check(BeatDetector.detect(FloatArray(4096), 44_100).isEmpty) { "a tenth of a second at 44.1 kHz produced beats" }
        }

        section("bars: every(4) takes every fourth beat from the downbeat")
        run {
            val map = BeatDetector.detect(track(120.0, 16.0), RATE)
            val bars = map.every(4)
            check(bars.size in (map.beatsMs.size / 4 - 1)..(map.beatsMs.size / 4 + 1)) { "every(4) returned ${bars.size} of ${map.beatsMs.size} beats" }
            val gaps = bars.zipWithNext { a, b -> b - a }
            gaps.forEach { check(abs(it - 2000L) < 300L) { "a bar was ${it}ms, expected about 2000ms at 120 BPM" } }
            check(map.every(1).size == map.beatsMs.size) { "every(1) dropped beats" }
            // The fixture's loudest hit is the kick on beat one, so the downbeat is on it.
            check(abs(map.beatsMs[map.downbeatOffset] % 2000) < 60 || abs(map.beatsMs[map.downbeatOffset] % 2000 - 2000) < 60) {
                "the downbeat was put on ${map.beatsMs[map.downbeatOffset]} ms, not on a bar line"
            }
        }

        section("a minute at 44.1 kHz in good time")
        run {
            val signal = track(124.0, 60.0, rate = 44_100)
            val started = System.nanoTime()
            val map = BeatDetector.detect(signal, 44_100)
            val ms = (System.nanoTime() - started) / 1_000_000
            println("      60 s at 44.1 kHz: ${map.bpm} BPM, ${map.beatsMs.size} beats, confidence ${map.confidence}, $ms ms")
            check(ms < 8000) { "a minute of 44.1 kHz took $ms ms to analyse" }
            val ratio = map.bpm / 124.0
            val level = listOf(0.5, 1.0, 2.0).minByOrNull { abs(it - ratio) }!!
            check(abs(ratio - level) < 0.02) { "a minute at 124 BPM came back ${map.bpm}" }
            check(map.beatsMs.last() > 58_000 && map.beatsMs.first() < 1000) { "the beats do not cover the minute: ${map.beatsMs.first()}..${map.beatsMs.last()}" }
        }
    }
}
