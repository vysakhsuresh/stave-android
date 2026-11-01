package com.layerbit.stave.engine.project

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Drawing automation with a finger: the stroke's samples become a handful of points that
 * replace what the lane had under the stroke and leave the rest of the curve exactly as it
 * was. Two things make that true: the value the lane had at the stroke's edges is pinned by a
 * point just outside each edge (so the segment that ran into the stroke still arrives at the
 * same value), and the stroke is thinned to the points that matter.
 */
object AutomationDraw {

    /** The stroke's raw samples sorted by position, one per position (the last wins), inside [from, to]. */
    fun stroke(samples: List<ValueTrack.Point>): List<ValueTrack.Point> {
        val byPosition = LinkedHashMap<Long, Double>()
        for (p in samples) byPosition[p.position] = p.value
        return byPosition.entries.map { ValueTrack.Point(it.key, it.value) }.sortedBy { it.position }
    }

    /**
     * Douglas-Peucker over (position, value): a point is kept when the curve without it would
     * pass more than [tolerance] (in the value's units) away from it. A finger's three hundred
     * samples over two seconds become the few corners a person would have set by hand, and the
     * file and the graph carry that many.
     */
    fun simplify(points: List<ValueTrack.Point>, tolerance: Double): List<ValueTrack.Point> {
        if (points.size <= 2 || tolerance <= 0.0) return points
        val keep = BooleanArray(points.size)
        keep[0] = true; keep[points.size - 1] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.add(0 to points.size - 1)
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            if (b - a < 2) continue
            var worst = -1.0; var at = -1
            for (i in a + 1 until b) {
                val d = deviation(points[i], points[a], points[b])
                if (d > worst) { worst = d; at = i }
            }
            if (worst > tolerance && at > 0) { keep[at] = true; stack.add(a to at); stack.add(at to b) }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }

    /** How far [p]'s value is from the line between [a] and [b] at p's position (vertical distance: time is never moved). */
    fun deviation(p: ValueTrack.Point, a: ValueTrack.Point, b: ValueTrack.Point): Double {
        if (b.position == a.position) return abs(p.value - a.value)
        val t = (p.position - a.position).toDouble() / (b.position - a.position)
        return abs(p.value - (a.value + (b.value - a.value) * t))
    }

    /**
     * [drawn] (already a [stroke], already simplified) replaces whatever [track] had from the
     * stroke's first position to its last. The lane's value just before and just after the
     * stroke is pinned with a point one sample outside each edge, unless a point already sits
     * there, so the segments either side still meet the values they had. [default] is the
     * lane's value when it has no points at all.
     */
    fun replaceSpan(track: ValueTrack, drawn: List<ValueTrack.Point>, default: Double): ValueTrack {
        if (drawn.isEmpty()) return track
        val from = drawn.first().position; val to = drawn.last().position
        val before = track.valueAt(max(0L, from - 1), default)
        val after = track.valueAt(to + 1, default)
        val kept = track.points.filter { it.position < from || it.position > to }
        val out = ArrayList<ValueTrack.Point>(kept.size + drawn.size + 2)
        out += kept
        if (track.points.isNotEmpty() && from > 0 && kept.none { it.position == from - 1 }) out += ValueTrack.Point(from - 1, before)
        out += drawn
        if (track.points.isNotEmpty() && kept.none { it.position == to + 1 } && kept.any { it.position > to }) out += ValueTrack.Point(to + 1, after)
        return ValueTrack(out.sortedBy { it.position })
    }

    /** A fader moved while playing: one point at the play-head, replacing any at that position; a flat default lane gets its first point. */
    fun write(track: ValueTrack, position: Long, value: Double): ValueTrack = track.with(ValueTrack.Point(position, value))

    /** The tolerance a parameter is drawn to: a tenth of a dB on volume, a hundredth on pan. */
    fun toleranceFor(param: String): Double = if (param == "volume") 0.1 else 0.01

    /** The lane's y (0 top, 1 bottom) for a value, and back; volume runs -60..+12 dB, pan -1..1. */
    fun toLane(param: String, value: Double): Double = if (param == "volume") 1.0 - (value.coerceIn(-60.0, 12.0) + 60.0) / 72.0 else 1.0 - (value.coerceIn(-1.0, 1.0) + 1.0) / 2.0
    fun fromLane(param: String, y: Double): Double { val f = 1.0 - min(1.0, max(0.0, y)); return if (param == "volume") -60.0 + 72.0 * f else -1.0 + 2.0 * f }
}
