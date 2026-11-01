import com.layerbit.stave.engine.project.AutomationDraw
import com.layerbit.stave.engine.project.ValueTrack
import kotlin.math.abs

/** Drawing automation: the stroke is thinned to its corners, replaces only its own span, and leaves the curve outside it untouched. */
object AutomationChecks : Suite {
    override val name = "automation"

    private fun p(pos: Long, v: Double) = ValueTrack.Point(pos, v)

    override fun run() {
        section("a stroke's samples are sorted and one per position")
        val s = AutomationDraw.stroke(listOf(p(300, 1.0), p(100, 0.0), p(200, 0.5), p(200, 0.7)))
        check(s.map { it.position } == listOf(100L, 200L, 300L) && s[1].value == 0.7) { "sorted, the last value at a position wins: $s" }

        section("simplify: a straight ramp is its two ends; a corner is kept; the tolerance decides")
        val ramp = (0..100).map { p(it * 10L, it * 0.1) }
        check(AutomationDraw.simplify(ramp, 0.05).size == 2) { "a ramp is two points: ${AutomationDraw.simplify(ramp, 0.05).size}" }
        val corner = (0..50).map { p(it * 10L, it * 0.1) } + (51..100).map { p(it * 10L, 5.0 - (it - 50) * 0.1) }
        val simple = AutomationDraw.simplify(corner, 0.05)
        check(simple.size == 3 && simple[1].position == 500L) { "a roof is three points with the ridge kept: $simple" }
        val wobbly = (0..100).map { p(it * 10L, it * 0.1 + if (it % 2 == 0) 0.02 else -0.02) }
        check(AutomationDraw.simplify(wobbly, 0.05).size == 2 && AutomationDraw.simplify(wobbly, 0.01).size > 50) { "a wobble under the tolerance is thinned, over it kept" }
        check(AutomationDraw.simplify(ramp, 0.0) === ramp && AutomationDraw.simplify(ramp.take(2), 1.0).size == 2) { "edges" }
        // The thinned curve never strays from the drawn one by more than the tolerance.
        val thin = AutomationDraw.simplify(corner, 0.05)
        val vt = ValueTrack(thin)
        for (q in corner) check(abs(vt.valueAt(q.position, 0.0) - q.value) <= 0.05 + 1e-9) { "thinned curve ${vt.valueAt(q.position, 0.0)} vs drawn ${q.value} at ${q.position}" }

        section("replace: the stroke owns its span, the lane outside is untouched and still meets its old values at the edges")
        val lane = ValueTrack(listOf(p(0, -6.0), p(1000, 0.0), p(2000, -12.0), p(3000, 0.0)))
        val drawn = listOf(p(1200, -3.0), p(1500, -1.0), p(1800, -3.0))
        val out = AutomationDraw.replaceSpan(lane, drawn, 0.0)
        check(out.points.none { it.position in 1200..1800 && it !in drawn }) { "old points under the stroke are gone" }
        check(out.points.containsAll(listOf(p(0, -6.0), p(1000, 0.0), p(2000, -12.0), p(3000, 0.0))) ) { "points outside stay: ${out.points}" }
        near(out.valueAt(1199, 0.0).toFloat(), lane.valueAt(1199, 0.0).toFloat(), 1e-6f, "just before the stroke the lane reads what it did")
        near(out.valueAt(1801, 0.0).toFloat(), lane.valueAt(1801, 0.0).toFloat(), 1e-6f, "just after too")
        near(out.valueAt(500, 0.0).toFloat(), -3f, 1e-6f, "well before, the old ramp")
        near(out.valueAt(1500, 0.0).toFloat(), -1f, 1e-6f, "inside, the drawn value")
        check(out.points == out.sorted) { "points come back sorted" }

        section("replace on an empty lane: only the stroke, no pins (there is nothing to pin to)")
        val fresh = AutomationDraw.replaceSpan(ValueTrack(), drawn, -6.0)
        check(fresh.points == drawn) { "the stroke alone: ${fresh.points}" }
        check(AutomationDraw.replaceSpan(lane, emptyList(), 0.0) == lane) { "an empty stroke changes nothing" }

        section("a stroke over the lane's end: no pin after it, the last drawn value holds on")
        val tail = AutomationDraw.replaceSpan(lane, listOf(p(2500, -20.0), p(4000, -30.0)), 0.0)
        check(tail.points.none { it.position == 4001L } && tail.valueAt(5000, 0.0) == -30.0) { "holds the last drawn value: ${tail.points}" }
        check(tail.points.any { it.position == 2499L }) { "pinned before: ${tail.points}" }

        section("write: a fader move is one point at the play-head; the lane geometry maps and unmaps")
        val w = AutomationDraw.write(ValueTrack(listOf(p(100, 0.0))), 100, -6.0)
        check(w.points == listOf(p(100, -6.0))) { "replaces the point at that position" }
        near(AutomationDraw.toLane("volume", 12.0).toFloat(), 0f, 1e-6f, "+12 dB is the top"); near(AutomationDraw.toLane("volume", -60.0).toFloat(), 1f, 1e-6f, "-60 the bottom")
        near(AutomationDraw.fromLane("pan", 0.5).toFloat(), 0f, 1e-6f, "the middle is centre")
        for (v in listOf(-50.0, -6.0, 0.0, 9.0)) near(AutomationDraw.fromLane("volume", AutomationDraw.toLane("volume", v)).toFloat(), v.toFloat(), 1e-5f, "round trip $v")
        check(AutomationDraw.toleranceFor("volume") == 0.1 && AutomationDraw.toleranceFor("pan") == 0.01) { "tolerances" }
    }
}
