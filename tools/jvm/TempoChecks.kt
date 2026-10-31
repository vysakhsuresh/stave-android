import com.layerbit.stave.engine.music.Tempo

object TempoChecks : Suite {
    override val name = "tempo"

    override fun run() {
        section("taps: the median interval, fumbles dropped")
        run {
            near(Tempo.bpmFromTaps(listOf(0L, 500L, 1000L, 1500L, 2000L)), 120.0, 1e-9, "even taps at 120")
            near(Tempo.bpmFromTaps(listOf(0L, 510L, 990L, 1500L, 2010L, 2500L)), 120.0, 1e-9, "jittered taps average out (510 480 510 510 490)")
            near(Tempo.bpmFromTaps(listOf(0L, 500L, 1000L, 2300L, 2800L, 3300L)), 120.0, 1e-9, "a missed tap (1300) is dropped")
            // A double tap: 500 20 480 500 - the median of four is the upper middle, 500, so 20 goes.
            near(Tempo.bpmFromTaps(listOf(0L, 500L, 520L, 1000L, 1500L)), 60_000.0 / (1480.0 / 3), 1e-6, "a double tap is dropped")
            near(Tempo.bpmFromTaps(listOf(2000L, 0L, 1000L)), 60.0, 1e-9, "taps are sorted first")
            near(Tempo.bpmFromTaps(listOf(0L, 600L, 1200L, 1800L)), 100.0, 1e-9, "100 BPM")
            check(Tempo.bpmFromTaps(emptyList()) == 0.0 && Tempo.bpmFromTaps(listOf(100L)) == 0.0) { "fewer than two taps is no tempo" }
            check(Tempo.bpmFromTaps(listOf(100L, 100L)) == 0.0) { "two taps at the same moment is no tempo" }
        }

        section("grid: a beat on the offset, the phase wrapped, accumulated without drift")
        run {
            check(Tempo.beatGrid(120.0, 0, 2001).contentEquals(longArrayOf(0, 500, 1000, 1500, 2000))) { "120 over two seconds: ${Tempo.beatGrid(120.0, 0, 2001).toList()}" }
            check(Tempo.beatGrid(120.0, 0, 2000).contentEquals(longArrayOf(0, 500, 1000, 1500))) { "the end is exclusive" }
            check(Tempo.beatGrid(120.0, 250, 2000).contentEquals(longArrayOf(250, 750, 1250, 1750))) { "an offset inside the first beat" }
            check(Tempo.beatGrid(120.0, 700, 2000).contentEquals(longArrayOf(200, 700, 1200, 1700))) { "an offset past the first beat is a phase: ${Tempo.beatGrid(120.0, 700, 2000).toList()}" }
            check(Tempo.beatGrid(120.0, -300, 2000).contentEquals(longArrayOf(200, 700, 1200, 1700))) { "a negative offset is the same phase" }
            val ninety = Tempo.beatGrid(90.0, 0, 20_001)
            check(ninety.size == 31 && ninety[3] == 2000L && ninety.last() == 20_000L) { "90 BPM: ${ninety.size} beats, [3]=${ninety[3]}, last=${ninety.last()}" }
            check(ninety[1] == 667L && ninety[2] == 1333L) { "666.67 ms rounds to 667 then 1333: ${ninety[1]}, ${ninety[2]}" }
            val hundred = Tempo.beatGrid(100.0, 0, 6001)
            check(hundred.size == 11 && hundred.last() == 6000L) { "100 BPM over six seconds: ${hundred.toList()}" }
            // A thousand beats at a period that does not divide: the last lands where arithmetic says, not where rounding each step would.
            val long = Tempo.beatGrid(70.0, 0, 1_000_000)
            check(long.size == 1167 && long[1000] == 857_143L) { "70 BPM over 1000 s: ${long.size} beats, [1000]=${long[1000]}" }
            check(Tempo.beatGrid(0.0, 0, 1000).isEmpty() && Tempo.beatGrid(120.0, 0, 0).isEmpty() && Tempo.beatGrid(-5.0, 0, 1000).isEmpty()) { "no tempo or no length is no grid" }
            near(Tempo.periodMs(120.0), 500.0, 1e-9, "period")
        }

        section("nearest beat within a reach")
        run {
            val grid = longArrayOf(0, 500, 1000)
            check(Tempo.nearestBeat(grid, 480, 50) == 500L) { "480 snaps to 500" }
            check(Tempo.nearestBeat(grid, 520, 50) == 500L) { "520 snaps to 500" }
            check(Tempo.nearestBeat(grid, 250, 50) == null) { "250 is out of reach" }
            check(Tempo.nearestBeat(grid, 250, 250) == 500L) { "a tie goes to the later beat" }
            check(Tempo.nearestBeat(grid, 1200, 300) == 1000L) { "past the end" }
            check(Tempo.nearestBeat(grid, -100, 100) == 0L) { "before the start" }
            check(Tempo.nearestBeat(grid, 1300, 299) == null) { "past the end and out of reach" }
            check(Tempo.nearestBeat(LongArray(0), 100, 1000) == null) { "an empty grid" }
            check(Tempo.nearestBeat(longArrayOf(700), 100, 1000) == 700L) { "a grid of one" }
            check(Tempo.nearestBeat(listOf(0L, 500L, 1000L), 480, 50) == 500L) { "the list form" }
        }

        section("quantise to a division of the beat")
        run {
            check(Tempo.quantise(1130, 120.0, 0, 4) == 1125L) { "1130 to sixteenths at 120 is ${Tempo.quantise(1130, 120.0, 0, 4)}" }
            check(Tempo.quantise(1130, 120.0, 0, 1) == 1000L) { "1130 to beats is ${Tempo.quantise(1130, 120.0, 0, 1)}" }
            check(Tempo.quantise(1260, 120.0, 0, 1) == 1500L) { "1260 to beats is ${Tempo.quantise(1260, 120.0, 0, 1)}" }
            check(Tempo.quantise(1130, 120.0, 30, 2) == 1030L) { "offset 30, eighths: ${Tempo.quantise(1130, 120.0, 30, 2)}" }
            check(Tempo.quantise(0, 120.0, 100, 1) == 100L) { "before the first beat snaps forward to it" }
            check(Tempo.quantise(450, 90.0, 0, 3) == 444L) { "triplets at 90: ${Tempo.quantise(450, 90.0, 0, 3)}" }
            check(Tempo.quantise(1000, 120.0, 0, 4) == 1000L) { "on the grid stays" }
            check(Tempo.quantise(1130, 0.0, 0, 4) == 1130L) { "no tempo, no change" }
            var threw = false
            try { Tempo.quantise(0, 120.0, 0, 0) } catch (e: IllegalArgumentException) { threw = true }
            check(threw) { "division 0 must be refused" }
        }
    }
}
