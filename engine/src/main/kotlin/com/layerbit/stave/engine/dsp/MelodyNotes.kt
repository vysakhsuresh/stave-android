package com.layerbit.stave.engine.dsp

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A sung or played single line as notes: the pitch track quantised to semitones, runs of the
 * same note joined, blips shorter than a minimum dropped, a gap of silence ending a note. What
 * "Notes to MIDI" gives the Beats tab for a melody; a polyphonic transcription is Basic Pitch's
 * job on the phone. Executed in MelodyChecks.
 */
object MelodyNotes {
    /** A note in samples on the analysed signal's clock, with the MIDI pitch and a 1..127 velocity from the level. */
    data class Note(val midi: Int, val start: Long, val end: Long, val velocity: Int)

    /**
     * [mono] at [sampleRate] to notes. [hop] is the analysis step; [minMs] the shortest note kept;
     * [glitchMs] a change of pitch shorter than this is absorbed into its neighbours.
     */
    fun extract(mono: FloatArray, sampleRate: Int, hop: Int = 512, minMs: Int = 80, glitchMs: Int = 40, a4: Double = 440.0): List<Note> {
        if (mono.size < 2048) return emptyList()
        val detector = PitchDetector(sampleRate, frameSize = 2048, minHz = 50.0, maxHz = 2000.0)
        val frames = (mono.size - 2048) / hop + 1
        // Per frame: the nearest MIDI note, or -1 for no pitch; and the frame's level.
        val pitch = IntArray(frames) { -1 }
        val level = FloatArray(frames)
        for (f in 0 until frames) {
            val start = f * hop
            val e = detector.detect(mono, start)
            if (e != null && e.clarity > 0.65f) pitch[f] = Notes.hzToMidi(e.hz, a4).roundToInt()
            level[f] = Gain.rms(mono, start, minOf(mono.size, start + 2048))
        }
        // Median of three, so one frame's octave slip does not split a note.
        val smoothed = IntArray(frames) { f ->
            if (f == 0 || f == frames - 1) pitch[f] else {
                val a = pitch[f - 1]; val b = pitch[f]; val c = pitch[f + 1]
                if (a == c && a >= 0) a else b
            }
        }
        val glitchFrames = maxOf(1, glitchMs * sampleRate / 1000 / hop)
        val minFrames = maxOf(1, minMs * sampleRate / 1000 / hop)
        // Runs of one pitch.
        val runs = ArrayList<IntArray>()   // [midi, startFrame, endFrameExclusive]
        var f = 0
        while (f < frames) {
            val p = smoothed[f]
            var g = f
            while (g < frames && smoothed[g] == p) g++
            runs += intArrayOf(p, f, g)
            f = g
        }
        // Absorb glitches: a short run between two longer runs of the same or a neighbouring note joins the one before.
        val merged = ArrayList<IntArray>()
        for (r in runs) {
            val length = r[2] - r[1]
            if (length < glitchFrames && merged.isNotEmpty() && r[0] >= 0 && merged.last()[0] >= 0) { merged.last()[2] = r[2]; continue }
            if (merged.isNotEmpty() && merged.last()[0] == r[0]) { merged.last()[2] = r[2]; continue }
            merged += r.copyOf()
        }
        val out = ArrayList<Note>()
        for (r in merged) {
            if (r[0] < 0 || r[2] - r[1] < minFrames) continue
            var peak = 0f
            for (k in r[1] until r[2]) peak = maxOf(peak, level[k])
            val velocity = (40 + 87 * (Gain.linearToDb(peak.toDouble()) + 40) / 40).toInt().coerceIn(1, 127)
            out += Note(r[0], r[1].toLong() * hop, r[2].toLong() * hop + 2048 / 2, velocity)
        }
        return out
    }

    /** Notes with every pitch moved by [semitones]. */
    fun transposed(notes: List<Note>, semitones: Int): List<Note> = notes.map { it.copy(midi = (it.midi + semitones).coerceIn(0, 127)) }

    @Suppress("unused") private fun same(a: Int, b: Int) = abs(a - b) == 0
}
