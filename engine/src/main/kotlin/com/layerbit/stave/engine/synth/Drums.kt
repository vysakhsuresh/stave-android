package com.layerbit.stave.engine.synth

import com.layerbit.stave.engine.dsp.FadeShape
import com.layerbit.stave.engine.dsp.Fades
import com.layerbit.stave.engine.dsp.Gain
import kotlin.math.pow

/*
 * The drum sampler: sixteen pads of samples, and the slicer that fills them from a loop.
 * The machine is the reference the Oboe graph's pad voices are ported from (DrumsChecks runs
 * it); the kit and its pads are values the project file stores by name.
 */

/** How a pad answers a finger lifting: a one-shot plays through, a gated pad releases, a loop goes round until released. */
enum class PadMode { ONE_SHOT, GATE, LOOP }

data class Pad(
    val name: String = "",
    /** Mono frames; null is an empty pad. */
    val sample: FloatArray? = null,
    val sampleRate: Int = 48000,
    /** 0 is no group; pads sharing a number stop each other, as an open and a closed hi-hat do. */
    val chokeGroup: Int = 0,
    val gainDb: Double = 0.0,
    val pan: Double = 0.0,
    val pitchSemitones: Double = 0.0,
    val envelope: AdsrSettings = AdsrSettings(0.0, 0.0, 1.0, 20.0),
    val mode: PadMode = PadMode.ONE_SHOT,
    val loopStart: Int = 0,
    val loopEnd: Int = -1
) {
    val isEmpty: Boolean get() = sample == null || sample.isEmpty()

    // A FloatArray compares by identity; a pad with the same frames is the same pad.
    override fun equals(other: Any?): Boolean = other is Pad && other.name == name && other.sampleRate == sampleRate &&
        other.chokeGroup == chokeGroup && other.gainDb == gainDb && other.pan == pan && other.pitchSemitones == pitchSemitones &&
        other.envelope == envelope && other.mode == mode && other.loopStart == loopStart && other.loopEnd == loopEnd &&
        ((sample == null && other.sample == null) || (sample != null && other.sample != null && sample.contentEquals(other.sample)))

    override fun hashCode(): Int = (name.hashCode() * 31 + chokeGroup) * 31 + (sample?.size ?: 0)
}

data class DrumKit(val name: String, val pads: List<Pad>) {
    init { require(pads.size == PADS) { "a kit has $PADS pads, not ${pads.size}" } }

    fun with(index: Int, pad: Pad): DrumKit = copy(pads = pads.mapIndexed { i, p -> if (i == index) pad else p })

    companion object {
        const val PADS = 16

        fun empty(name: String = "Kit"): DrumKit = DrumKit(name, List(PADS) { Pad("Pad ${it + 1}") })

        /** The slices of a loop laid onto the pads in order; past sixteen they are left off. */
        fun fromSlices(slices: List<FloatArray>, sampleRate: Int, name: String = "Sliced"): DrumKit =
            DrumKit(name, List(PADS) { i -> if (i < slices.size) Pad("Slice ${i + 1}", slices[i], sampleRate) else Pad("Pad ${i + 1}") })
    }
}

/**
 * Plays a kit. Each pad has two voices so a pad hit again while it sounds can fade its last
 * hit out in [CHOKE_MS] rather than cut it (a cut is a click); a choke group does the same to
 * every other pad in the group.
 */
class DrumMachine(kit: DrumKit, private val sampleRate: Int) {
    var kit: DrumKit = kit
        set(value) { field = value; rebuild() }

    private var voices: Array<Array<SamplerVoice?>> = Array(DrumKit.PADS) { arrayOfNulls<SamplerVoice>(2) }
    private val gains = FloatArray(DrumKit.PADS)
    private val panL = FloatArray(DrumKit.PADS)
    private val panR = FloatArray(DrumKit.PADS)
    private val ratios = DoubleArray(DrumKit.PADS)
    private var scratch = FloatArray(0)
    var gain = 1f

    init { rebuild() }

    private fun rebuild() {
        voices = Array(DrumKit.PADS) { i ->
            val pad = kit.pads[i]
            val s = pad.sample
            if (s == null || s.isEmpty()) arrayOfNulls(2)
            else Array(2) { SamplerVoice(s, pad.sampleRate, sampleRate, pad.envelope, 60, pad.mode == PadMode.LOOP, pad.loopStart, pad.loopEnd) }
        }
        for (i in 0 until DrumKit.PADS) {
            val pad = kit.pads[i]
            gains[i] = Gain.dbToLinear(pad.gainDb).toFloat()
            val (l, r) = Gain.panConstantPower(pad.pan.toFloat())
            panL[i] = l; panR[i] = r
            ratios[i] = 2.0.pow(pad.pitchSemitones / 12.0)
        }
    }

    fun trigger(pad: Int, velocity: Int = 127) {
        val pair = voices[pad]
        if (pair[0] == null) return
        val group = kit.pads[pad].chokeGroup
        if (group != 0) for (other in 0 until DrumKit.PADS) if (other != pad && kit.pads[other].chokeGroup == group) chokePad(other)
        // The voice that is free, else the one that has been going longer (the one not just started).
        val free = pair.indexOfFirst { it != null && !it.isActive }
        val index = if (free >= 0) free else { pair.forEach { it?.choke(CHOKE_MS) }; lastStarted[pad] xor 1 }
        lastStarted[pad] = index
        pair[index]!!.start(ratios[pad], velocity)
    }

    private val lastStarted = IntArray(DrumKit.PADS)

    /** A finger lifted: nothing for a one-shot, the envelope's release for the others. */
    fun release(pad: Int) {
        if (kit.pads[pad].mode == PadMode.ONE_SHOT) return
        voices[pad].forEach { if (it != null && it.isHeld) it.noteOff() }
    }

    fun chokePad(pad: Int) { voices[pad].forEach { it?.choke(CHOKE_MS) } }

    fun isSounding(pad: Int): Boolean = voices[pad].any { it != null && it.isActive }

    /** Writes [frames] frames of interleaved stereo into [out], replacing what was there. */
    fun render(out: FloatArray, frames: Int) {
        require(out.size >= frames * 2) { "stereo buffer of ${out.size} for $frames frames" }
        out.fill(0f, 0, frames * 2)
        if (scratch.size < frames) scratch = FloatArray(frames)
        for (p in 0 until DrumKit.PADS) {
            val pair = voices[p]
            if (pair.none { it != null && it.isActive }) continue
            scratch.fill(0f, 0, frames)
            for (v in pair) if (v != null && v.isActive) v.render(scratch, frames)
            val gl = gains[p] * panL[p] * gain; val gr = gains[p] * panR[p] * gain
            for (i in 0 until frames) { out[2 * i] += scratch[i] * gl; out[2 * i + 1] += scratch[i] * gr }
        }
    }

    companion object {
        /** Long enough to be no click, short enough that a closed hat still cuts the open one. */
        const val CHOKE_MS = 2.0
    }
}

/** Cuts a loop into pads. Every slice is faded in and out over [FADE_MS], because a cut on anything but a zero crossing clicks. */
object Slicer {
    const val FADE_MS = 2.0

    fun slice(samples: FloatArray, sampleRate: Int, onsets: List<Long>): List<FloatArray> {
        if (samples.isEmpty()) return emptyList()
        val bounds = (listOf(0L) + onsets.map { it.coerceIn(0L, samples.size.toLong()) } + listOf(samples.size.toLong())).distinct().sorted()
        val fade = (FADE_MS * sampleRate / 1000.0).toInt()
        return bounds.zipWithNext { a, b -> samples.copyOfRange(a.toInt(), b.toInt()) }
            .filter { it.isNotEmpty() }
            .onEach { s ->
                // Half the slice at most each way, so a slice shorter than two fades still peaks in the middle.
                val n = minOf(fade, s.size / 2)
                Fades.fadeIn(s, n, FadeShape.LINEAR)
                Fades.fadeOut(s, n, FadeShape.LINEAR)
            }
    }

    /** [count] slices of equal length, the last taking the remainder. */
    fun evenSlices(samples: FloatArray, sampleRate: Int, count: Int): List<FloatArray> {
        require(count >= 1) { "at least one slice" }
        val onsets = (1 until count).map { i -> samples.size.toLong() * i / count }
        return slice(samples, sampleRate, onsets)
    }
}
