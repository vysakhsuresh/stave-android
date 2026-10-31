package com.layerbit.stave.engine.dsp

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

/** A note name for a frequency, and the other way, for the tuner and the piano roll. */
object Notes {
    private val names = arrayOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")

    fun midiToHz(midi: Double, a4: Double = 440.0): Double = a4 * 2.0.pow((midi - 69) / 12.0)
    fun hzToMidi(hz: Double, a4: Double = 440.0): Double = 69 + 12 * log2(hz / a4)

    /** "A4", "C♯3": the nearest note's name with its octave. */
    fun name(midi: Int): String = names[((midi % 12) + 12) % 12] + (midi / 12 - 1)

    /** Cents from [hz] to the nearest note; -50..50. */
    fun centsOff(hz: Double, a4: Double = 440.0): Double {
        val m = hzToMidi(hz, a4)
        return (m - m.roundToInt()) * 100
    }

    fun nearest(hz: Double, a4: Double = 440.0): Int = hzToMidi(hz, a4).roundToInt()
}

/**
 * The fundamental of a frame, by YIN (de Cheveigné and Kawahara, 2002): the cumulative mean
 * normalised difference, the first dip under a threshold, refined by a parabola. Good enough
 * for a tuner and for the autotune's tracking; the CREPE model on the phone is for when a
 * voice is thin against a backing. Checked against synthesised tones (PitchChecks).
 */
class PitchDetector(val sampleRate: Int, val frameSize: Int = 2048, val minHz: Double = 50.0, val maxHz: Double = 2000.0, val threshold: Float = 0.12f) {
    private val maxLag = (sampleRate / minHz).toInt().coerceAtMost(frameSize / 2)
    private val minLag = (sampleRate / maxHz).toInt().coerceAtLeast(2)
    private val difference = FloatArray(maxLag + 1)

    /** The result for one frame: the frequency and how clear it was (0..1), or null for no pitch. */
    data class Estimate(val hz: Double, val clarity: Float)

    fun detect(frame: FloatArray, offset: Int = 0): Estimate? {
        val n = frameSize.coerceAtMost(frame.size - offset)
        if (n < maxLag * 2) return null
        val half = n / 2
        // Difference function over lags.
        for (tau in 0..maxLag) {
            var s = 0f
            var i = 0
            while (i < half) {
                val d = frame[offset + i] - frame[offset + i + tau]
                s += d * d
                i++
            }
            difference[tau] = s
        }
        // Cumulative mean normalisation.
        var running = 0f
        difference[0] = 1f
        for (tau in 1..maxLag) {
            running += difference[tau]
            difference[tau] = if (running > 0f) difference[tau] * tau / running else 1f
        }
        // The first dip under the threshold, taken at its local minimum.
        var tau = minLag
        var best = -1
        while (tau < maxLag) {
            if (difference[tau] < threshold) {
                while (tau + 1 < maxLag && difference[tau + 1] < difference[tau]) tau++
                best = tau
                break
            }
            tau++
        }
        if (best < 0) {
            // No dip under the threshold: take the global minimum if it is at all convincing.
            var m = 1f
            for (t in minLag until maxLag) if (difference[t] < m) { m = difference[t]; best = t }
            if (best < 0 || m > 0.5f) return null
        }
        // Parabolic refinement between the neighbours.
        val refined = if (best in 1 until maxLag) {
            val a = difference[best - 1]; val b = difference[best]; val c = difference[best + 1]
            val denominator = a - 2 * b + c
            if (abs(denominator) > 1e-9f) best + (a - c) / (2 * denominator) else best.toFloat()
        } else best.toFloat()
        val clarity = (1f - difference[best]).coerceIn(0f, 1f)
        return Estimate(sampleRate / refined.toDouble(), clarity)
    }

    /** The pitch track of a whole signal: one estimate per [hop], null where there was none. */
    fun track(signal: FloatArray, hop: Int = frameSize / 4): List<Estimate?> {
        val out = ArrayList<Estimate?>()
        var start = 0
        while (start + frameSize <= signal.size) {
            out += detect(signal, start)
            start += hop
        }
        return out
    }

    companion object {
        /** The median of the non-null estimates, which is what a tuner needle settles on. */
        fun medianHz(track: List<Estimate?>): Double? {
            val values = track.filterNotNull().filter { it.clarity > 0.5f }.map { it.hz }.sorted()
            return if (values.isEmpty()) null else values[values.size / 2]
        }

        /** A smoothed version of a frequency in log space, so vibrato reads as a gentle wobble, not a jitter. */
        fun smoothLog(previous: Double?, current: Double, amount: Double = 0.3): Double =
            if (previous == null) current else Math.exp(ln(previous) + (ln(current) - ln(previous)) * amount)
    }
}
