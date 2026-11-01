package com.layerbit.stave.engine.dsp

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The control-rate rules of live pitch correction, in one place so they can be executed here
 * and mirrored line for line in `graph/LiveTune.cpp`, which runs them in the audio callback.
 * The C++ cannot be run on the JVM; this is the reference it is read against, and the first
 * phone build checks the two agree by singing a scale through both (PENDING_TESTS).
 *
 * Three rules: which note of the scale a detected pitch is pulled to (with hysteresis, so a
 * note sung on the boundary between two scale notes does not flap between them); how far it is
 * pulled ([strength], 1 being all the way); and how fast the correction follows ([retuneMs],
 * the time to get 63% of the way, as a one-pole per analysis hop).
 */
object LiveTuneRules {
    /** A scale as the twelve pitch classes allowed, bit `pc` set for pitch class `pc` (0 = C). */
    fun mask(scale: PitchCorrect.Scale): Int { var m = 0; for (pc in 0 until 12) if (scale.contains(pc)) m = m or (1 shl pc); return m }

    /** The hysteresis: the old target holds until the sung note is this far past the midpoint toward the next. */
    const val HOLD_SEMITONES = 0.6

    /**
     * The scale note (MIDI) a sung [detectedMidi] is pulled to. The nearest allowed note by
     * default; the [previous] target when the voice is still within [HOLD_SEMITONES] of it, so a
     * vibrato across a boundary stays on one note - the flapping was audible as a trill on the
     * first try without it. A mask with no notes falls back to chromatic.
     */
    fun targetMidi(detectedMidi: Double, mask: Int, previous: Int?): Int {
        val allowed = if (mask and 0xFFF == 0) 0xFFF else mask
        if (previous != null && (allowed shr previous.mod(12)) and 1 == 1 && abs(detectedMidi - previous) <= HOLD_SEMITONES) return previous
        val rounded = detectedMidi.roundToInt()
        var best = rounded; var bestDistance = Double.MAX_VALUE
        for (candidate in rounded - 6..rounded + 6) {
            if ((allowed shr candidate.mod(12)) and 1 == 0) continue
            val d = abs(candidate - detectedMidi)
            if (d < bestDistance) { bestDistance = d; best = candidate }
        }
        return best
    }

    /** The pitch ratio that moves [detectedHz] toward [targetHz] by [strength] of the way (in cents, so half strength is half the cents). */
    fun correctionRatio(detectedHz: Double, targetHz: Double, strength: Double): Double {
        if (detectedHz <= 0.0 || targetHz <= 0.0) return 1.0
        val full = targetHz / detectedHz
        return full.pow(strength.coerceIn(0.0, 1.0)).coerceIn(0.5, 2.0)
    }

    /** The one-pole coefficient that reaches 63% of a change in [retuneMs], applied once per [hopSamples] at [sampleRate]; 0 ms is at once. */
    fun smoothing(retuneMs: Double, hopSamples: Int, sampleRate: Int): Double {
        if (retuneMs <= 0.0) return 1.0
        return 1.0 - exp(-hopSamples.toDouble() / (retuneMs / 1000.0 * sampleRate))
    }

    /** One step of the smoothing: the current ratio moved toward the wanted one by [coefficient]; both in the log domain, so up and down are symmetric. */
    fun follow(current: Double, wanted: Double, coefficient: Double): Double =
        exp(ln(current) + (ln(wanted) - ln(current)) * coefficient.coerceIn(0.0, 1.0))

    fun hzToMidi(hz: Double, a4: Double = 440.0): Double = 69.0 + 12.0 * ln(hz / a4) / ln(2.0)
    fun midiToHz(midi: Double, a4: Double = 440.0): Double = a4 * 2.0.pow((midi - 69.0) / 12.0)
}
