package com.layerbit.stave.engine.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** The shapes a fade or a crossfade can take. [gainIn] is the rising curve at a fraction 0..1. */
enum class FadeShape(val label: String) {
    LINEAR("Linear"), EQUAL_POWER("Smooth"), S_CURVE("S-curve"), EXPONENTIAL("Fast start"), LOGARITHMIC("Slow start");

    fun gainIn(t: Float): Float {
        val x = t.coerceIn(0f, 1f).toDouble()
        return when (this) {
            LINEAR -> x
            EQUAL_POWER -> sin(x * PI / 2)
            S_CURVE -> 0.5 - 0.5 * cos(x * PI)
            EXPONENTIAL -> sqrt(x)
            LOGARITHMIC -> x * x
        }.toFloat()
    }

    /** The falling curve, so a crossfade's two halves of the same shape sum sensibly. */
    fun gainOut(t: Float): Float = when (this) {
        EQUAL_POWER -> cos(t.coerceIn(0f, 1f).toDouble() * PI / 2).toFloat()
        else -> gainIn(1f - t)
    }
}

/** Fades and the clip-level gain envelope, applied in place on a channel's samples. */
object Fades {
    fun fadeIn(samples: FloatArray, length: Int, shape: FadeShape = FadeShape.EQUAL_POWER, from: Int = 0) {
        val n = length.coerceIn(0, samples.size - from)
        for (i in 0 until n) samples[from + i] *= shape.gainIn(i.toFloat() / n)
    }

    fun fadeOut(samples: FloatArray, length: Int, shape: FadeShape = FadeShape.EQUAL_POWER, end: Int = samples.size) {
        val n = length.coerceIn(0, end)
        val start = end - n
        for (i in 0 until n) samples[start + i] *= shape.gainOut(i.toFloat() / n)
    }

    /** [a]'s tail crossed into [b]'s head over [length] samples; the result is a + b - length long. */
    fun crossfade(a: FloatArray, b: FloatArray, length: Int, shape: FadeShape = FadeShape.EQUAL_POWER): FloatArray {
        val n = length.coerceIn(0, minOf(a.size, b.size))
        val out = FloatArray(a.size + b.size - n)
        a.copyInto(out, 0, 0, a.size - n)
        for (i in 0 until n) {
            val t = i.toFloat() / n
            out[a.size - n + i] = a[a.size - n + i] * shape.gainOut(t) + b[i] * shape.gainIn(t)
        }
        b.copyInto(out, a.size, n, b.size)
        return out
    }
}

/**
 * A gain envelope over a clip: points of (sample position, gain) joined by straight lines in dB,
 * which is how the ear hears a ramp as even. Between the first point and the clip's start, and
 * after the last, the gain holds. Kept sorted by position.
 */
class GainEnvelope(points: List<Point> = emptyList()) {
    data class Point(val position: Long, val gainDb: Double)

    val points: List<Point> = points.sortedBy { it.position }

    fun gainDbAt(position: Long): Double {
        if (points.isEmpty()) return 0.0
        if (position <= points.first().position) return points.first().gainDb
        if (position >= points.last().position) return points.last().gainDb
        var lo = 0; var hi = points.size - 1
        while (hi - lo > 1) { val mid = (lo + hi) / 2; if (points[mid].position <= position) lo = mid else hi = mid }
        val a = points[lo]; val b = points[hi]
        val t = (position - a.position).toDouble() / (b.position - a.position)
        return a.gainDb + (b.gainDb - a.gainDb) * t
    }

    fun gainAt(position: Long): Float = Gain.dbToLinear(gainDbAt(position)).toFloat()

    /** Applies the envelope to [samples] whose first sample sits at [startPosition]. */
    fun apply(samples: FloatArray, startPosition: Long) {
        if (points.isEmpty()) return
        for (i in samples.indices) samples[i] *= gainAt(startPosition + i)
    }

    fun with(point: Point): GainEnvelope = GainEnvelope(points.filter { it.position != point.position } + point)
    fun without(position: Long): GainEnvelope = GainEnvelope(points.filter { it.position != position })

    /** Shifted for a clip whose head was trimmed by [delta] samples (positive trims the start). */
    fun shifted(delta: Long): GainEnvelope = GainEnvelope(points.map { it.copy(position = it.position - delta) })
}

/** Dither for a reduction in bit depth: triangular PDF noise at one LSB peak, the plain honest kind. */
class Dither(bits: Int, seed: Long = 1234L) {
    private val lsb = 1.0 / (1L shl (bits - 1))
    private var state = seed
    private fun uniform(): Double {
        // xorshift64*, enough randomness for noise nobody is meant to hear a pattern in.
        state = state xor (state ushr 12); state = state xor (state shl 25); state = state xor (state ushr 27)
        return ((state * 2685821657736338717L) ushr 11).toDouble() / (1L shl 53)
    }

    /** Adds TPDF noise and returns the value to quantise, still in -1..1. */
    fun apply(x: Float): Float = (x + (uniform() - uniform()) * lsb).toFloat()

    /** Quantises to [bits] with dither, returning the integer code. */
    fun quantise(x: Float, bits: Int = 16): Int {
        val max = (1L shl (bits - 1)) - 1
        val v = Math.round(apply(x).coerceIn(-1f, 1f) * max)
        return v.toInt().coerceIn(-max.toInt() - 1, max.toInt())
    }

    companion object {
        /** A gain in dB a quantisation step is worth: the noise floor of [bits]. */
        fun noiseFloorDb(bits: Int): Double = -(6.02 * bits + 1.76)
        fun stepAt(bits: Int): Double = 2.0.pow(-(bits - 1))
    }
}
