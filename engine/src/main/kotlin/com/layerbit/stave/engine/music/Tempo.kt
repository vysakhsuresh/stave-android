package com.layerbit.stave.engine.music

import kotlin.math.abs
import kotlin.math.roundToLong

/** Tempo arithmetic with no audio in it: taps, grids, snapping. */
object Tempo {
    /** An interval this far from the median tap is a fumble, not a beat. */
    const val TAP_OUTLIER = 0.3

    /**
     * The tempo someone tapped. The intervals are measured against their median and
     * the ones well off it dropped - a missed tap reads as a double interval and a
     * double tap as a tiny one, and either would pull a mean a long way - then the
     * mean of what is left, which is steadier than the median alone. Fewer than two
     * taps is no tempo: 0.
     */
    fun bpmFromTaps(tapTimesMs: List<Long>): Double {
        if (tapTimesMs.size < 2) return 0.0
        val sorted = tapTimesMs.sorted()
        val intervals = sorted.zipWithNext { a, b -> (b - a).toDouble() }.filter { it > 0 }
        if (intervals.isEmpty()) return 0.0
        val median = intervals.sorted()[intervals.size / 2]
        val kept = intervals.filter { abs(it - median) <= median * TAP_OUTLIER }
        val mean = kept.sum() / kept.size
        return 60_000.0 / mean
    }

    /** One beat at [bpm], in milliseconds. */
    fun periodMs(bpm: Double): Double = 60_000.0 / bpm

    /**
     * Every beat in [0, lengthMs) with a beat on [offsetMs]. The offset is a phase, so a
     * grid asked for from 700 ms at 120 BPM starts at 200: the beats before the offset
     * are as real as the ones after it. Accumulated in a double so the thousandth beat
     * is where it should be, not where rounding each step would have put it.
     */
    fun beatGrid(bpm: Double, offsetMs: Long, lengthMs: Long): LongArray {
        if (bpm <= 0.0 || lengthMs <= 0) return LongArray(0)
        val period = periodMs(bpm)
        var at = offsetMs.toDouble().mod(period)
        val out = ArrayList<Long>()
        while (at < lengthMs) {
            out.add(at.roundToLong())
            at += period
        }
        return out.toLongArray()
    }

    /** The beat of [gridMs] (ascending) nearest [atMs], or null when none is within [withinMs]. */
    fun nearestBeat(gridMs: LongArray, atMs: Long, withinMs: Long): Long? {
        if (gridMs.isEmpty()) return null
        var lo = 0
        var hi = gridMs.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (gridMs[mid] < atMs) lo = mid + 1 else hi = mid
        }
        var best = gridMs[lo]
        if (lo > 0 && abs(gridMs[lo - 1] - atMs) < abs(best - atMs)) best = gridMs[lo - 1]
        return if (abs(best - atMs) <= withinMs) best else null
    }

    fun nearestBeat(beatsMs: List<Long>, atMs: Long, withinMs: Long): Long? = nearestBeat(beatsMs.toLongArray(), atMs, withinMs)

    /**
     * [ms] snapped to the nearest [division]th of a beat on the grid at [bpm] through
     * [offsetMs]: division 1 is beats, 2 eighths, 4 sixteenths, 3 triplets.
     */
    fun quantise(ms: Long, bpm: Double, offsetMs: Long, division: Int): Long {
        require(division >= 1) { "division must be at least 1" }
        if (bpm <= 0.0) return ms
        val step = periodMs(bpm) / division
        val steps = Math.round((ms - offsetMs) / step)
        return (offsetMs + steps * step).roundToLong()
    }
}
