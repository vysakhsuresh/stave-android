import com.layerbit.stave.engine.project.Ducking

object DuckingChecks : Suite {
    override val name = "ducking"

    override fun run() {
        section("a speech run dips the music and brings it back slowly")
        val frame = 960 // 20 ms at 48k
        val speech = BooleanArray(200) { it in 50 until 100 }   // speech from 1.0 s to 2.0 s
        val pts = Ducking.points(speech, frame, baseDb = -6.0, depthDb = -12.0, attackSamples = 9600, releaseSamples = 24_000, holdSamples = 14_400)
        val byPos = pts.associate { it.position to it.value }
        check(byPos[0L] == -6.0) { "starts at the base: ${pts.first()}" }
        check(byPos[48_000L - 9600] == -6.0 && byPos[48_000L] == -18.0) { "ramps down over the attack into the first word: $pts" }
        check(byPos[96_000L] == -18.0 && byPos[96_000L + 24_000] == -6.0) { "holds under speech, releases after: $pts" }
        check(pts == pts.sortedBy { it.position } && pts.map { it.position }.toSet().size == pts.size) { "ordered, no duplicates" }

        section("a short gap between words stays ducked, a long one does not")
        val gapped = BooleanArray(300) { it in 50 until 100 || it in 110 until 150 || it in 250 until 280 }
        val p2 = Ducking.points(gapped, frame, 0.0, -10.0, 4800, 24_000, holdSamples = 14_400)
        val between = p2.filter { it.position in 96_001L until 105_599L }
        check(between.isEmpty()) { "no release point inside a 200 ms gap: $between" }
        check(p2.any { it.position == 150L * frame + 24_000 && it.value == 0.0 }) { "released after the second run" }
        check(p2.any { it.position == 250L * frame && it.value == -10.0 }) { "ducked again for the third run" }

        section("no speech, no points; speech from the start starts ducked")
        check(Ducking.points(BooleanArray(100), frame, 0.0, -10.0, 100, 100, 100).isEmpty()) { "silence" }
        val fromStart = Ducking.points(BooleanArray(10) { true }, frame, 0.0, -10.0, 4800, 100, 100)
        check(fromStart.first().position == 0L && fromStart.first().value == -10.0) { "starts ducked: ${fromStart.first()}" }

        section("the speech mask bridges a single quiet frame")
        val mask = Ducking.speechMask(floatArrayOf(-50f, -20f, -60f, -20f, -50f, -50f), -30f)
        check(mask.toList() == listOf(false, true, true, true, false, false)) { mask.toList().toString() }
    }
}
