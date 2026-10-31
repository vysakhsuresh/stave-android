package com.layerbit.stave.engine.sequencer

import kotlin.math.max

/**
 * Anything that takes notes and gives samples: the synths, the drum machine (pads by pitch
 * from 36, the General MIDI drum convention) and, in the app, a SoundFont through JNI. Stereo,
 * interleaved, [render] *replacing* what is in the buffer.
 */
interface Instrument {
    fun noteOn(pitch: Int, velocity: Int)
    fun noteOff(pitch: Int)
    fun allNotesOff()
    fun render(out: FloatArray, frames: Int)
    /** Whether anything still sounds: the renderer stops at the tail's end rather than at a guess. */
    val isSounding: Boolean
}

/**
 * Plays a note list through an instrument in blocks, at [bpm], from tick 0, and returns the
 * stereo result: the pattern's length plus whatever tail the instrument rings for, up to
 * [maxTailMs]. This is what "Render to track" and the MIDI-to-audio bounce run - the same
 * arithmetic the live player uses, so the file is what was heard.
 *
 * Note-ons and note-offs are scheduled to the sample within a block, not to the block edge:
 * with a 256-frame block a hat on the grid would otherwise land anywhere in 5 ms, which is
 * audible as a loose player. Each event's sample is rounded, and two notes at the same tick
 * start on the same sample.
 */
object PatternRenderer {

    const val BLOCK = 256

    fun render(notes: List<Note>, lengthTicks: Long, bpm: Double, sampleRate: Int, instrument: Instrument, maxTailMs: Int = 4000, gain: Float = 1f): FloatArray {
        require(bpm > 0 && sampleRate > 0) { "bpm $bpm at $sampleRate Hz" }
        val events = events(notes, bpm, sampleRate)
        val lengthFrames = TicksTime.ticksToSamples(lengthTicks, bpm, sampleRate = sampleRate)
        val tailLimit = lengthFrames + maxTailMs.toLong() * sampleRate / 1000
        val out = ArrayList<FloatArray>()
        val block = FloatArray(BLOCK * 2)
        var frame = 0L
        var e = 0
        var total = 0L
        while (true) {
            // Events inside this block, each applied at its own offset by rendering up to it first.
            var done = 0
            while (done < BLOCK) {
                val nextAt = if (e < events.size) events[e].frame - frame else Long.MAX_VALUE
                val run = if (nextAt <= 0) 0 else min(BLOCK - done, nextAt).toInt()
                if (run > 0) {
                    val part = FloatArray(run * 2)
                    instrument.render(part, run)
                    System.arraycopy(part, 0, block, done * 2, run * 2)
                    done += run
                    frame += run
                }
                while (e < events.size && events[e].frame <= frame) {
                    val ev = events[e++]
                    if (ev.on) instrument.noteOn(ev.pitch, ev.velocity) else instrument.noteOff(ev.pitch)
                }
                if (run == 0 && nextAt > 0) break
            }
            if (gain != 1f) for (i in 0 until done * 2) block[i] *= gain
            out += block.copyOf(done * 2)
            total += done
            if (e >= events.size && total >= lengthFrames && (!instrument.isSounding || total >= tailLimit)) break
            if (total >= tailLimit) break
        }
        val result = FloatArray((total * 2).toInt())
        var at = 0
        for (b in out) { System.arraycopy(b, 0, result, at, b.size); at += b.size }
        return result
    }

    private fun min(a: Int, b: Long): Long = if (a.toLong() < b) a.toLong() else b

    class Event(val frame: Long, val on: Boolean, val pitch: Int, val velocity: Int)

    /** Every note's on and off as a sample-stamped list, offs before ons at the same sample so a retriggered note restarts cleanly. */
    fun events(notes: List<Note>, bpm: Double, sampleRate: Int): List<Event> {
        val out = ArrayList<Event>(notes.size * 2)
        for (n in notes) {
            val start = TicksTime.ticksToSamples(n.startTick, bpm, sampleRate = sampleRate)
            val end = max(start + 1, TicksTime.ticksToSamples(n.endTick, bpm, sampleRate = sampleRate))
            out += Event(start, true, n.pitch, n.velocity)
            out += Event(end, false, n.pitch, n.velocity)
        }
        return out.sortedWith(compareBy<Event>({ it.frame }, { if (it.on) 1 else 0 }))
    }
}

/** The drum machine as an instrument: pitch 36 is pad 0, as General MIDI puts the kick. */
class DrumInstrument(val machine: com.layerbit.stave.engine.synth.DrumMachine) : Instrument {
    override fun noteOn(pitch: Int, velocity: Int) { val pad = pitch - BASE; if (pad in 0 until com.layerbit.stave.engine.synth.DrumKit.PADS) machine.trigger(pad, velocity) }
    override fun noteOff(pitch: Int) { val pad = pitch - BASE; if (pad in 0 until com.layerbit.stave.engine.synth.DrumKit.PADS) machine.release(pad) }
    override fun allNotesOff() { for (p in 0 until com.layerbit.stave.engine.synth.DrumKit.PADS) machine.release(p) }
    override fun render(out: FloatArray, frames: Int) = machine.render(out, frames)
    override val isSounding: Boolean get() = (0 until com.layerbit.stave.engine.synth.DrumKit.PADS).any { machine.isSounding(it) }
    companion object { const val BASE = 36 }
}

/** A polyphonic synth as an instrument. */
class SynthInstrument(val synth: com.layerbit.stave.engine.synth.PolyphonicSynth<*>) : Instrument {
    override fun noteOn(pitch: Int, velocity: Int) = synth.noteOn(pitch, velocity)
    override fun noteOff(pitch: Int) = synth.noteOff(pitch)
    override fun allNotesOff() = synth.allNotesOff()
    override fun render(out: FloatArray, frames: Int) = synth.render(out, frames)
    override val isSounding: Boolean get() = synth.activeVoices > 0
}
