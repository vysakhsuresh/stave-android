package com.layerbit.stave.engine.music

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Where things start: the transients, for "split at transients" and the slicer.
 *
 * The same spectral-flux envelope the beat tracker reads, peak-picked rather than
 * fitted to a tempo - a slicer wants every hit, on the grid or off it. The threshold
 * is adaptive: a peak counts when it stands clear of the median of the second around
 * it, so a quiet passage keeps its onsets and a loud one is not read as one long onset.
 *
 * "Clear" is [DELTA] times the background's own spread - the local upper quartile over
 * the median, which the onsets themselves cannot inflate - and never less than [FLOOR]
 * in the envelope's units. Both halves were measured in: with the spread taken as the
 * envelope's global deviation, eight clicks in silence set the deviation and the
 * quietest of them fell under two of it; with no floor, a held note's spectral ripple,
 * a thousandth of a unit, was the whole envelope and read as onsets.
 */
object Onsets {
    /** Two hits closer than this are one: nothing a slicer cuts is shorter. */
    const val MIN_GAP_MS = 50

    /** How far above the local median a peak must stand, in local upper quartiles over the median. */
    const val DELTA = 2.0f

    /**
     * The least a peak must stand above the median, in raw flux. Measured at the
     * detector's own 8 kHz over ten seconds of white noise at 0.3 and at 0.9 (the
     * compression is logarithmic, so the level barely moves it): the tallest peak over
     * the local median was 3.9, 4.0 and 5.2 on three seeds, with the local quartile
     * spread under one unit - so the spread rule cannot hold noise back and this floor
     * must. A click at -40 dB in near silence stands at eleven or more, and a hit over
     * a sustained pad at twenty-seven or more. Three, the first value, let nine noise
     * peaks through in ten seconds.
     */
    const val FLOOR = 6.0f

    /** The stretch the local median is taken over, centred on the frame. */
    const val MEDIAN_SPAN_SECONDS = 1.0

    /** Onset positions in samples of [samples], at [sampleRate], ascending. */
    fun detect(samples: FloatArray, sampleRate: Int): List<Long> {
        if (sampleRate <= 0 || samples.size < BeatDetector.WINDOW * 2) return emptyList()
        val signal = AnalysisSignal.of(samples, sampleRate)
        if (signal.samples.size < BeatDetector.WINDOW * 2) return emptyList()
        val envelope = BeatDetector.rawOnsetEnvelope(signal.samples, signal.rate)
        val framesPerSecond = signal.rate.toDouble() / BeatDetector.HOP
        return pick(envelope, framesPerSecond).map { frame -> signal.toSourceSample(BeatDetector.frameToSample(frame.toDouble())) }
    }

    /**
     * The frames of the raw [envelope] that are onsets: local maxima standing over the
     * local median by the larger of [delta] local quartile spreads and [floor], at least
     * [MIN_GAP_MS] apart (the larger of two close peaks is kept).
     */
    fun pick(envelope: FloatArray, framesPerSecond: Double, delta: Float = DELTA, floor: Float = FLOOR): List<Int> {
        val n = envelope.size
        if (n == 0) return emptyList()
        // Rounded up, so two kept onsets are never closer than the minimum: at 62.5 frames
        // a second 50 ms is 3.125 frames, and three frames would let 48 ms through.
        val minGap = max(1, ceil(MIN_GAP_MS / 1000.0 * framesPerSecond).toInt())
        val halfSpan = max(1, (MEDIAN_SPAN_SECONDS / 2 * framesPerSecond).toInt())
        val candidates = ArrayList<Int>()
        for (t in 0 until n) {
            val v = envelope[t]
            if (v <= 0f) continue
            var isMax = true
            var d = max(0, t - minGap)
            val end = min(n - 1, t + minGap)
            while (d <= end) {
                if (d != t && envelope[d] > v) { isMax = false; break }
                d++
            }
            if (!isMax) continue
            val (median, upperQuartile) = background(envelope, t, halfSpan)
            if (v >= median + max(delta * (upperQuartile - median), floor)) candidates.add(t)
        }
        val kept = ArrayList<Int>()
        for (c in candidates) {
            if (kept.isNotEmpty() && c - kept.last() < minGap) {
                if (envelope[c] > envelope[kept.last()]) kept[kept.lastIndex] = c
            } else kept.add(c)
        }
        return kept
    }

    /** The median and the upper quartile of the envelope around [at]. */
    private fun background(values: FloatArray, at: Int, halfSpan: Int): Pair<Float, Float> {
        val from = max(0, at - halfSpan)
        val to = min(values.size - 1, at + halfSpan)
        val slice = values.copyOfRange(from, to + 1)
        slice.sort()
        return slice[slice.size / 2] to slice[slice.size * 3 / 4]
    }
}
