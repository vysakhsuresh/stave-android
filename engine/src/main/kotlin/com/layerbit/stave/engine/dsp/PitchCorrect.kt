package com.layerbit.stave.engine.dsp

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pitch correction for one voice: the fundamental is tracked frame by frame, the nearest note
 * of the scale chosen, a retune speed decides how fast the voice is pulled there, and the
 * signal is resynthesised pitch-synchronously (TD-PSOLA): grains one period long, centred on
 * the pitch marks, overlapped at the corrected period. Mono, offline. Rubber Band on the phone
 * does the whole-clip shift; this is for a *moving* correction, which Rubber Band's offline
 * surface cannot express. Executed in PitchCorrectChecks.
 */
object PitchCorrect {

    /** Which notes are allowed: a key's scale or every semitone. [root] 0 = C. */
    data class Scale(val root: Int, val intervals: IntArray) {
        fun contains(pitchClass: Int): Boolean = intervals.contains(((pitchClass - root) % 12 + 12) % 12)

        /** The nearest MIDI note in the scale to [midi]. */
        fun nearest(midi: Double): Int {
            val r = midi.roundToInt()
            for (d in 0..6) {
                if (contains(((r + d) % 12 + 12) % 12) && (d == 0 || abs(midi - (r + d)) <= abs(midi - (r - d)) || !contains(((r - d) % 12 + 12) % 12))) return r + d
                if (contains(((r - d) % 12 + 12) % 12)) return r - d
            }
            return r
        }

        companion object {
            val CHROMATIC = Scale(0, IntArray(12) { it })
            fun major(root: Int) = Scale(root, intArrayOf(0, 2, 4, 5, 7, 9, 11))
            fun minor(root: Int) = Scale(root, intArrayOf(0, 2, 3, 5, 7, 8, 10))
            fun pentatonic(root: Int) = Scale(root, intArrayOf(0, 2, 4, 7, 9))
        }
    }

    data class Settings(
        val scale: Scale = Scale.CHROMATIC,
        /** 0 leaves the voice alone, 1 pins it to the note. */
        val strength: Float = 1f,
        /** How long the pull to the note takes; 0 is the hard, instant snap. */
        val retuneMs: Double = 20.0,
        /** Vibrato and scoops narrower than this many cents are left as they are. */
        val toleranceCents: Double = 0.0,
        val a4: Double = 440.0
    )

    /** The correction ratio per analysis hop (frames at [hop] samples), 1.0 where no pitch was found. */
    fun ratioTrack(mono: FloatArray, sampleRate: Int, settings: Settings, hop: Int = 256): FloatArray {
        val detector = PitchDetector(sampleRate, frameSize = 2048, minHz = 60.0, maxHz = 1200.0)
        val frames = (mono.size + hop - 1) / hop
        val ratios = FloatArray(frames) { 1f }
        var smoothedTarget: Double? = null
        val alpha = if (settings.retuneMs <= 0.0) 1.0 else 1.0 - Math.exp(-hop / (sampleRate * settings.retuneMs / 1000.0))
        for (f in 0 until frames) {
            val centre = f * hop
            val start = (centre - 1024).coerceIn(0, max(0, mono.size - 2048))
            val e = if (mono.size >= 2048) detector.detect(mono, start) else null
            if (e == null || e.clarity < 0.6f) { smoothedTarget = null; continue }
            val midi = Notes.hzToMidi(e.hz, settings.a4)
            val target = settings.scale.nearest(midi).toDouble()
            val cents = (target - midi) * 100
            if (abs(cents) < settings.toleranceCents) { ratios[f] = 1f; smoothedTarget = target; continue }
            val wanted = midi + (target - midi) * settings.strength
            smoothedTarget = if (smoothedTarget == null) wanted else smoothedTarget!! + (wanted - smoothedTarget!!) * alpha
            ratios[f] = Math.pow(2.0, (smoothedTarget!! - midi) / 12.0).toFloat()
        }
        return ratios
    }

    /**
     * [mono] with the pitch moved by [ratios] (per [hop]) through TD-PSOLA. Where the ratio is
     * one the output equals the input to the precision of the grain windows.
     */
    fun psola(mono: FloatArray, sampleRate: Int, ratios: FloatArray, hop: Int = 256): FloatArray {
        val n = mono.size
        if (n == 0) return mono
        val out = FloatArray(n)
        val norm = FloatArray(n)
        val detector = PitchDetector(sampleRate, frameSize = 2048, minHz = 60.0, maxHz = 1200.0)
        // Pitch marks: walk through the signal one period at a time, the period from the local detection.
        var pos = 0.0
        var period = sampleRate / 200.0
        var outPos = 0.0
        while (pos < n) {
            val f = (pos / hop).toInt().coerceIn(0, ratios.size - 1)
            val start = (pos - 1024).toInt().coerceIn(0, max(0, n - 2048))
            val e = if (n >= 2048) detector.detect(mono, start) else null
            if (e != null && e.clarity >= 0.6f) period = sampleRate / e.hz
            val ratio = ratios[f]
            // A grain two periods long, Hann-windowed, centred on the mark.
            val grainLen = (2 * period).toInt().coerceIn(32, 4096)
            val half = grainLen / 2
            val centre = pos.toInt()
            // Place at the output position; for ratio != 1 the output marks are spaced period/ratio apart.
            val outCentre = outPos.toInt()
            for (i in -half until half) {
                val src = centre + i
                val dst = outCentre + i
                if (src < 0 || src >= n || dst < 0 || dst >= n) continue
                val w = (0.5 - 0.5 * Math.cos(Math.PI * (i + half) / half)).toFloat()
                out[dst] += mono[src] * w
                norm[dst] += w
            }
            pos += period
            outPos += period / ratio
            // Keep output and input marks together over time: when the output runs ahead or behind by
            // more than a period, skip or repeat an input mark - that is what keeps the length.
            while (outPos - pos > period) { pos += period }
            while (pos - outPos > period) { pos -= period * 0.0; outPos += period / ratio; placeExtra(mono, out, norm, pos, outPos, period, n) }
        }
        for (i in 0 until n) out[i] = if (norm[i] > 1e-3f) out[i] / norm[i] else mono[i]
        return out
    }

    private fun placeExtra(mono: FloatArray, out: FloatArray, norm: FloatArray, pos: Double, outPos: Double, period: Double, n: Int) {
        val grainLen = (2 * period).toInt().coerceIn(32, 4096); val half = grainLen / 2
        val centre = pos.toInt(); val outCentre = outPos.toInt()
        for (i in -half until half) {
            val src = centre + i; val dst = outCentre + i
            if (src < 0 || src >= n || dst < 0 || dst >= n) continue
            val w = (0.5 - 0.5 * Math.cos(Math.PI * (i + half) / half)).toFloat()
            out[dst] += mono[src] * w; norm[dst] += w
        }
    }

    /** The whole correction: track, decide, resynthesise. */
    fun correct(mono: FloatArray, sampleRate: Int, settings: Settings): FloatArray {
        val ratios = ratioTrack(mono, sampleRate, settings)
        if (ratios.all { abs(it - 1f) < 1e-4f }) return mono.copyOf()
        return psola(mono, sampleRate, ratios)
    }

    /** Cents from [hz] to the nearest note of [scale], for the tuner's needle. */
    fun centsToScale(hz: Double, scale: Scale, a4: Double = 440.0): Double {
        val midi = Notes.hzToMidi(hz, a4)
        return (scale.nearest(midi) - midi) * 100
    }

    @Suppress("unused") private fun log2(x: Double) = ln(x) / ln(2.0)
    @Suppress("unused") private fun clampInt(x: Int, lo: Int, hi: Int) = min(max(x, lo), hi)
}
