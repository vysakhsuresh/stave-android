package com.layerbit.stave.engine.wave

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The waveform as the screen draws it: for every zoom, a column's lowest sample, highest sample
 * and RMS, from a pyramid of levels each holding one bucket per 2^k samples.
 *
 * Built once per channel per file and cached on disk (the app's PeakStore), so scrubbing an hour
 * of audio at any zoom reads a few thousand floats, never the samples. Level 0 is 256 samples a
 * bucket (fine enough that below it the strip draws samples themselves); each level above halves
 * the count. Querying a window picks the finest level with at most two buckets a pixel and
 * gathers down to the columns asked for - the loudest min and max of each column rather than an
 * average, so a transient is not swallowed by the quiet either side of it (PeaksChecks).
 */
class Peaks private constructor(
    val sampleRate: Int,
    val totalSamples: Long,
    /** levels[k] holds triples (min, max, rms) per bucket of BASE << k samples. */
    private val levels: List<FloatArray>
) {
    val levelCount: Int get() = levels.size

    fun bucketSamples(level: Int): Long = BASE.toLong() shl level
    fun bucketCount(level: Int): Int = levels[level].size / 3

    /** One drawn column: the lowest and highest sample and the RMS under it. */
    class Column(val min: Float, val max: Float, val rms: Float)

    /**
     * [columns] columns over [fromSample, toSample). Outside the audio a column is silent.
     */
    fun columns(fromSample: Long, toSample: Long, columns: Int): Array<Column> {
        require(columns > 0)
        val span = max(1L, toSample - fromSample)
        val samplesPerColumn = span.toDouble() / columns
        // The finest level whose bucket is no bigger than half a column, so each column has at
        // least two buckets to choose its extremes from - or level 0 when columns are finer than that.
        var level = 0
        while (level + 1 < levels.size && bucketSamples(level + 1) * 2 <= samplesPerColumn) level++
        val data = levels[level]
        val bucket = bucketSamples(level)
        val count = bucketCount(level)
        return Array(columns) { c ->
            val s0 = fromSample + (c * samplesPerColumn).toLong()
            val s1 = fromSample + ((c + 1) * samplesPerColumn).toLong()
            val b0 = (s0 / bucket).toInt()
            val b1 = max(b0 + 1, ((s1 + bucket - 1) / bucket).toInt())
            var lo = 0f; var hi = 0f; var sq = 0.0; var n = 0
            for (b in max(b0, 0) until min(b1, count)) {
                val mn = data[b * 3]; val mx = data[b * 3 + 1]; val r = data[b * 3 + 2]
                if (mn < lo) lo = mn
                if (mx > hi) hi = mx
                sq += r.toDouble() * r; n++
            }
            Column(lo, hi, if (n == 0) 0f else sqrt(sq / n).toFloat())
        }
    }

    /**
     * [columns] the same as [columns], written as min, max, rms triples into [out] (at least
     * 3 x columns long) instead of one object per column: the strip asks this of every lane every
     * frame, and 24 lanes made the UI thread spend 12 ms a frame playing (1 Nov 2026).
     */
    fun columnsInto(fromSample: Long, toSample: Long, columns: Int, out: FloatArray) {
        require(columns > 0 && out.size >= columns * 3)
        val span = max(1L, toSample - fromSample)
        val samplesPerColumn = span.toDouble() / columns
        var level = 0
        while (level + 1 < levels.size && bucketSamples(level + 1) * 2 <= samplesPerColumn) level++
        val data = levels[level]
        val bucket = bucketSamples(level)
        val count = bucketCount(level)
        for (c in 0 until columns) {
            val s0 = fromSample + (c * samplesPerColumn).toLong()
            val s1 = fromSample + ((c + 1) * samplesPerColumn).toLong()
            val b0 = (s0 / bucket).toInt()
            val b1 = max(b0 + 1, ((s1 + bucket - 1) / bucket).toInt())
            var lo = 0f; var hi = 0f; var sq = 0.0; var n = 0
            for (b in max(b0, 0) until min(b1, count)) {
                val mn = data[b * 3]; val mx = data[b * 3 + 1]; val r = data[b * 3 + 2]
                if (mn < lo) lo = mn
                if (mx > hi) hi = mx
                sq += r.toDouble() * r; n++
            }
            out[c * 3] = lo; out[c * 3 + 1] = hi; out[c * 3 + 2] = if (n == 0) 0f else sqrt(sq / n).toFloat()
        }
    }

    /** The loudest absolute sample in the whole file, for normalising the drawing. */
    val peak: Float by lazy {
        val top = levels.last()
        var p = 0f
        for (b in 0 until top.size / 3) { p = max(p, max(abs(top[b * 3]), abs(top[b * 3 + 1]))) }
        p
    }

    /** Serialises to a flat float array: header (rate, total lo, total hi, level count), then each level's length and data. */
    fun toFloats(): FloatArray {
        val size = 4 + levels.sumOf { it.size + 1 }
        val out = FloatArray(size)
        out[0] = sampleRate.toFloat()
        out[1] = (totalSamples and 0xFFFFFF).toFloat()
        out[2] = (totalSamples ushr 24).toFloat()
        out[3] = levels.size.toFloat()
        var at = 4
        for (l in levels) { out[at++] = l.size.toFloat(); l.copyInto(out, at); at += l.size }
        return out
    }

    /** The incremental builder: feed samples as they are decoded, finish when the file ends. */
    class Builder(val sampleRate: Int) {
        private val level0 = ArrayList<Float>()
        private var mn = Float.MAX_VALUE; private var mx = -Float.MAX_VALUE; private var sq = 0.0; private var n = 0
        private var total = 0L

        fun add(samples: FloatArray, from: Int = 0, to: Int = samples.size) {
            for (i in from until to) {
                val v = samples[i]
                if (v < mn) mn = v
                if (v > mx) mx = v
                sq += v.toDouble() * v
                n++
                total++
                if (n == BASE) flush()
            }
        }

        private fun flush() {
            if (n == 0) return
            level0 += mn; level0 += mx; level0 += sqrt(sq / n).toFloat()
            mn = Float.MAX_VALUE; mx = -Float.MAX_VALUE; sq = 0.0; n = 0
        }

        fun build(): Peaks {
            flush()
            val levels = ArrayList<FloatArray>()
            var current = FloatArray(level0.size) { level0[it] }
            levels += current
            // Each level above: pairs of buckets folded into one, until one bucket is left.
            while (current.size / 3 > 1) {
                val buckets = current.size / 3
                val next = FloatArray(((buckets + 1) / 2) * 3)
                var b = 0
                while (b < buckets) {
                    val a = b; val c = min(b + 1, buckets - 1)
                    val k = (b / 2) * 3
                    next[k] = min(current[a * 3], current[c * 3])
                    next[k + 1] = max(current[a * 3 + 1], current[c * 3 + 1])
                    val ra = current[a * 3 + 2]; val rc = current[c * 3 + 2]
                    next[k + 2] = if (a == c) ra else sqrt((ra.toDouble() * ra + rc.toDouble() * rc) / 2).toFloat()
                    b += 2
                }
                current = next
                levels += current
            }
            return Peaks(sampleRate, total, levels)
        }
    }

    companion object {
        /** Samples per bucket at the finest level. */
        const val BASE = 256

        fun of(samples: FloatArray, sampleRate: Int): Peaks = Builder(sampleRate).also { it.add(samples) }.build()

        fun fromFloats(data: FloatArray): Peaks {
            val rate = data[0].toInt()
            val total = data[1].toLong() or (data[2].toLong() shl 24)
            val count = data[3].toInt()
            val levels = ArrayList<FloatArray>()
            var at = 4
            for (l in 0 until count) {
                val size = data[at++].toInt()
                levels += data.copyOfRange(at, at + size)
                at += size
            }
            return Peaks(rate, total, levels)
        }
    }
}
