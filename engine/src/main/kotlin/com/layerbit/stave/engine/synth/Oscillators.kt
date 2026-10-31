package com.layerbit.stave.engine.synth

import com.layerbit.stave.engine.dsp.BiquadCoefficients
import com.layerbit.stave.engine.dsp.FilterKind
import com.layerbit.stave.engine.dsp.Notes
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan
import kotlin.random.Random

/*
 * The reference synthesis engine: every oscillator, envelope, filter and voice the Oboe graph
 * plays is written here first in plain Kotlin so its arithmetic can be executed and measured
 * (SynthChecks), then ported line for line. Phases are in cycles (0..1), not radians, so a
 * phase-modulation index in radians is divided by 2π once at the operator and never again.
 */

enum class Waveform { SAW, SQUARE, TRIANGLE, SINE, NOISE }

/**
 * Polynomial band-limited step and corner corrections (Välimäki, Pekonen and Nam 2012). The
 * four-point form - a cubic B-spline's integral - rather than the two-point one: a 1 kHz saw's
 * harmonic just above Nyquist is only 28 dB under its fundamental, and the two-point residual
 * takes a further 8 dB off it where the four-point takes 17, which is the difference between an
 * alias at -36 dB and one under -40 (SynthChecks measures it; a naive saw puts it at -28).
 */
internal object PolyBlep {
    /** The residual of a unit step at d = 0 (in samples from the step), nonzero over -2..2: the band-limited step minus the ideal one. */
    fun stepResidual(d: Double): Double = when {
        d <= -2.0 || d >= 2.0 -> 0.0
        d < -1.0 -> { val u = 2.0 + d; u * u * u * u / 24.0 }
        d < 0.0 -> 0.5 + 2.0 * d / 3.0 - d * d * d / 3.0 - d * d * d * d / 8.0
        d < 1.0 -> -0.5 + 2.0 * d / 3.0 - d * d * d / 3.0 + d * d * d * d / 8.0
        else -> { val u = 2.0 - d; -u * u * u * u / 24.0 }
    }

    /** Its integral: the residual of a unit slope change (a corner), even in d, zero at ±2. */
    fun cornerResidual(d: Double): Double {
        val a = if (d < 0) -d else d
        return when {
            a >= 2.0 -> 0.0
            a > 1.0 -> { val u = 2.0 - a; u * u * u * u * u / 120.0 }
            else -> 7.0 / 30.0 - a / 2.0 + a * a / 3.0 - a * a * a * a / 12.0 + a * a * a * a * a / 40.0
        }
    }

    /** What to add at phase [t] for a step of -1 to +1 at phase 0, with the step at phase 1 (the next cycle's) included; [dt] is the phase increment. */
    fun step(t: Double, dt: Double): Double {
        if (dt <= 0.0) return 0.0
        return 2.0 * (stepResidual(t / dt) + stepResidual((t - 1.0) / dt))
    }

    /** What to add at phase [t] for a slope change of [deltaPerCycle] (output units per cycle) at phase 0, in output units. */
    fun corner(t: Double, dt: Double, deltaPerCycle: Double): Double {
        if (dt <= 0.0) return 0.0
        return deltaPerCycle * dt * (cornerResidual(t / dt) + cornerResidual((t - 1.0) / dt))
    }
}

/** One periodic source. [phase] is in cycles; [setFrequency] fixes the increment, [next] gives a sample and advances. */
abstract class Oscillator {
    var phase: Double = 0.0
        set(value) { field = value - Math.floor(value) }
    /** Cycles per sample. */
    var increment: Double = 0.0
        protected set

    open fun setFrequency(hz: Double, sampleRate: Int) {
        increment = (hz / sampleRate).coerceIn(0.0, 0.5)
    }

    abstract fun next(): Float

    fun reset(phase: Double = 0.0) { this.phase = phase }

    protected fun advance() {
        var p = phase + increment
        if (p >= 1.0) p -= 1.0
        phase = p
    }

    companion object {
        fun of(waveform: Waveform, seed: Long = 1L): Oscillator = when (waveform) {
            Waveform.SAW -> SawOscillator()
            Waveform.SQUARE -> SquareOscillator()
            Waveform.TRIANGLE -> TriangleOscillator()
            Waveform.SINE -> SineOscillator()
            Waveform.NOISE -> NoiseOscillator(seed)
        }
    }
}

/** A rising ramp from -1 to 1 that falls at phase 0; the fall is band-limited. */
class SawOscillator : Oscillator() {
    override fun next(): Float {
        val t = phase
        val v = (2.0 * t - 1.0) - PolyBlep.step(t, increment)
        advance()
        return v.toFloat()
    }
}

/** +1 for [pulseWidth] of the cycle, -1 for the rest, both edges band-limited. */
class SquareOscillator(pulseWidth: Double = 0.5) : Oscillator() {
    /** Held off the ends: at 0 or 1 the two edges meet and the wave is silence. */
    var pulseWidth: Double = pulseWidth.coerceIn(0.05, 0.95)
        set(value) { field = value.coerceIn(0.05, 0.95) }

    override fun next(): Float {
        val t = phase
        val naive = if (t < pulseWidth) 1.0 else -1.0
        var fall = t - pulseWidth
        if (fall < 0.0) fall += 1.0
        val v = naive + PolyBlep.step(t, increment) - PolyBlep.step(fall, increment)
        advance()
        return v.toFloat()
    }
}

/** -1 at phase 0 rising to +1 at a half, both corners band-limited by the integrated residual. */
class TriangleOscillator : Oscillator() {
    override fun next(): Float {
        val t = phase
        val naive = if (t < 0.5) 4.0 * t - 1.0 else 3.0 - 4.0 * t
        var top = t - 0.5
        if (top < 0.0) top += 1.0
        // The slope goes from -4 to +4 per cycle at the trough and back at the peak.
        val v = naive + PolyBlep.corner(t, increment, 8.0) - PolyBlep.corner(top, increment, 8.0)
        advance()
        return v.toFloat()
    }
}

class SineOscillator : Oscillator() {
    override fun next(): Float {
        val v = sin(2.0 * PI * phase)
        advance()
        return v.toFloat()
    }
}

/** White noise, uniform in -1..1, from a seed so a rendered take can be rendered again. */
class NoiseOscillator(seed: Long = 1L) : Oscillator() {
    private val random = Random(seed)
    override fun next(): Float = (random.nextDouble() * 2.0 - 1.0).toFloat()
}

/** The four numbers of an envelope as a patch stores them. */
data class AdsrSettings(val attackMs: Double = 5.0, val decayMs: Double = 100.0, val sustain: Double = 0.7, val releaseMs: Double = 200.0) {
    init {
        require(attackMs >= 0.0 && decayMs >= 0.0 && releaseMs >= 0.0) { "envelope times are not negative" }
        require(sustain in 0.0..1.0) { "sustain $sustain out of 0..1" }
    }
}

/**
 * Attack, decay, sustain, release. The attack is a straight line, which the ear hears as a
 * quickening; decay and release are exponential, each reaching a thousandth of the way (-60 dB)
 * at its stated time, which is the convention every hardware envelope has settled on. A
 * retrigger attacks from the level it is at rather than from zero, so a repeated note never clicks.
 */
class Adsr(attackMs: Double, decayMs: Double, sustain: Double, releaseMs: Double, private val sampleRate: Int) {
    constructor(settings: AdsrSettings, sampleRate: Int) : this(settings.attackMs, settings.decayMs, settings.sustain, settings.releaseMs, sampleRate)

    enum class Stage { IDLE, ATTACK, DECAY, SUSTAIN, RELEASE }

    var stage: Stage = Stage.IDLE
        private set
    var level: Float = 0f
        private set
    val isActive: Boolean get() = stage != Stage.IDLE
    val isHeld: Boolean get() = stage == Stage.ATTACK || stage == Stage.DECAY || stage == Stage.SUSTAIN

    private var attackStep = 0f
    private var decaySamples = 0
    private var decayCoefficient = 0f
    private var sustain = 0f
    private var releaseCoefficient = 0f
    private var decayed = 0

    init { set(attackMs, decayMs, sustain, releaseMs) }

    fun set(settings: AdsrSettings) = set(settings.attackMs, settings.decayMs, settings.sustain, settings.releaseMs)

    fun set(attackMs: Double, decayMs: Double, sustain: Double, releaseMs: Double) {
        val attackSamples = samples(attackMs)
        attackStep = if (attackSamples == 0) 1f else 1f / attackSamples
        decaySamples = samples(decayMs)
        decayCoefficient = coefficient(decaySamples)
        this.sustain = sustain.coerceIn(0.0, 1.0).toFloat()
        releaseCoefficient = coefficient(samples(releaseMs))
    }

    private fun samples(ms: Double): Int = (ms * sampleRate / 1000.0).toInt().coerceAtLeast(0)

    /** The one-pole coefficient that leaves a thousandth of the distance after [n] samples. */
    private fun coefficient(n: Int): Float = if (n == 0) 1f else (1.0 - exp(ln(0.001) / n)).toFloat()

    fun gate(on: Boolean) {
        if (on) { stage = Stage.ATTACK; decayed = 0 }
        else if (stage != Stage.IDLE) stage = Stage.RELEASE
    }

    fun next(): Float {
        when (stage) {
            Stage.IDLE -> return 0f
            Stage.ATTACK -> {
                level += attackStep
                if (level >= 1f) { level = 1f; stage = if (decaySamples == 0) Stage.SUSTAIN else Stage.DECAY; if (stage == Stage.SUSTAIN) level = sustain }
            }
            Stage.DECAY -> {
                level += (sustain - level) * decayCoefficient
                // Snapped at the stated time: the exponential never arrives on its own, and a
                // sustain the filter envelope reads would otherwise drift a thousandth for ever.
                if (++decayed >= decaySamples) { level = sustain; stage = Stage.SUSTAIN }
            }
            Stage.SUSTAIN -> level = sustain
            Stage.RELEASE -> {
                level -= level * releaseCoefficient
                if (level < SILENT) { level = 0f; stage = Stage.IDLE }
            }
        }
        return level
    }

    fun reset() { stage = Stage.IDLE; level = 0f }

    companion object {
        /** -80 dB: where a release is declared over and the voice freed. */
        const val SILENT = 1e-4f
    }
}

/**
 * A state-variable filter in Andrew Simper's trapezoidal form (Cytomic, 2013): one tan() per
 * coefficient change, stable to Nyquist, and its cutoff can move every sample without a thump,
 * which the Chamberlin form cannot promise above a quarter of the rate. Low, high and band
 * pass, and the notch as their sum, read off the same state.
 */
class Svf(private val sampleRate: Int, var kind: FilterKind = FilterKind.LOW_PASS) {
    private var g = 0.0
    private var k = 0.0
    private var a1 = 0.0
    private var a2 = 0.0
    private var a3 = 0.0
    private var ic1eq = 0.0
    private var ic2eq = 0.0

    var cutoffHz: Double = 1000.0
        private set
    var q: Double = BiquadCoefficients.SQRT_HALF
        private set

    init { set(cutoffHz, q) }

    fun set(cutoffHz: Double, q: Double = this.q) {
        this.cutoffHz = cutoffHz.coerceIn(5.0, sampleRate * 0.49)
        this.q = q.coerceIn(0.1, 40.0)
        g = tan(PI * this.cutoffHz / sampleRate)
        k = 1.0 / this.q
        a1 = 1.0 / (1.0 + g * (g + k))
        a2 = g * a1
        a3 = g * a2
    }

    fun setResonance(cutoffHz: Double, resonance: Double) = set(cutoffHz, qOf(resonance))

    fun process(x: Float): Float {
        val v0 = x.toDouble()
        val v3 = v0 - ic2eq
        val v1 = a1 * ic1eq + a2 * v3
        val v2 = ic2eq + a2 * ic1eq + a3 * v3
        ic1eq = 2.0 * v1 - ic1eq
        ic2eq = 2.0 * v2 - ic2eq
        val y = when (kind) {
            FilterKind.LOW_PASS -> v2
            FilterKind.BAND_PASS -> v1
            FilterKind.HIGH_PASS -> v0 - k * v1 - v2
            FilterKind.NOTCH -> v0 - k * v1
            else -> throw IllegalArgumentException("$kind is a biquad's, not a state-variable filter's")
        }
        return y.toFloat()
    }

    fun reset() { ic1eq = 0.0; ic2eq = 0.0 }

    companion object {
        /** A resonance knob 0..1 to Q: 0.5 (gentle) rising to about 20, with Butterworth near a tenth of the travel. */
        fun qOf(resonance: Double): Double = 0.5 * 2.0.pow(resonance.coerceIn(0.0, 1.0) * 5.3)
    }
}

/** What a polyphonic allocator needs of a voice. [render] adds mono samples into [out] from index 0. */
interface SynthVoice {
    val pitch: Int
    /** Sounding: gated, or a release still ringing. */
    val isActive: Boolean
    /** Between noteOn and noteOff. */
    val isHeld: Boolean
    fun noteOn(pitch: Int, velocity: Int)
    fun noteOff()
    /** Silence at once, for a panic or a reset. */
    fun kill()
    fun render(out: FloatArray, frames: Int)
}

/**
 * Hands notes to voices. A held pitch played again retriggers its own voice; otherwise a free
 * voice; otherwise the oldest note still ringing after its key was let go; otherwise the oldest
 * held note is stolen - in each case the note a player is least likely to still be listening to.
 */
class VoiceAllocator<V : SynthVoice>(val voices: List<V>) {
    private val started = LongArray(voices.size)
    private var counter = 0L

    fun noteOn(pitch: Int, velocity: Int): V {
        var index = voices.indexOfFirst { it.isHeld && it.pitch == pitch }
        if (index < 0) index = voices.indexOfFirst { !it.isActive }
        if (index < 0) index = started.indices.filter { !voices[it].isHeld }.minByOrNull { started[it] } ?: -1
        if (index < 0) index = started.indices.minByOrNull { started[it] } ?: 0
        started[index] = ++counter
        voices[index].noteOn(pitch, velocity)
        return voices[index]
    }

    fun noteOff(pitch: Int) { for (v in voices) if (v.isHeld && v.pitch == pitch) v.noteOff() }
    fun allNotesOff() { for (v in voices) if (v.isHeld) v.noteOff() }
    fun kill() { for (v in voices) v.kill() }

    val activeCount: Int get() = voices.count { it.isActive }
    val heldPitches: List<Int> get() = voices.filter { it.isHeld }.map { it.pitch }
}

/** The polyphony and the stereo output every synth here shares; a subclass supplies the voices. */
abstract class PolyphonicSynth<V : SynthVoice>(voices: List<V>, val sampleRate: Int) {
    val allocator = VoiceAllocator(voices)
    val voices: List<V> get() = allocator.voices
    private var scratch = FloatArray(0)

    /** Output gain and a constant-power pan, -1..1. */
    var gain = 1f
    var pan = 0f

    fun noteOn(pitch: Int, velocity: Int) { allocator.noteOn(pitch, velocity) }
    fun noteOff(pitch: Int) = allocator.noteOff(pitch)
    fun allNotesOff() = allocator.allNotesOff()
    val activeVoices: Int get() = allocator.activeCount
    val heldPitches: List<Int> get() = allocator.heldPitches

    /** Writes [frames] frames of interleaved stereo into [out], replacing what was there. */
    fun render(out: FloatArray, frames: Int) {
        require(out.size >= frames * 2) { "stereo buffer of ${out.size} for $frames frames" }
        if (scratch.size < frames) scratch = FloatArray(frames)
        scratch.fill(0f, 0, frames)
        for (v in voices) if (v.isActive) v.render(scratch, frames)
        val (l, r) = com.layerbit.stave.engine.dsp.Gain.panConstantPower(pan)
        val gl = l * gain; val gr = r * gain
        for (i in 0 until frames) { out[2 * i] = scratch[i] * gl; out[2 * i + 1] = scratch[i] * gr }
    }
}

/** A subtractive voice's patch. [mix] 0 is the first oscillator alone, 1 the second alone. */
data class SubtractivePatch(
    val osc1: Waveform = Waveform.SAW,
    val osc2: Waveform = Waveform.SQUARE,
    val osc2Semitones: Int = 0,
    val osc2DetuneCents: Double = 7.0,
    val mix: Double = 0.5,
    val pulseWidth: Double = 0.5,
    val filter: FilterKind = FilterKind.LOW_PASS,
    val cutoffHz: Double = 2000.0,
    val resonance: Double = 0.2,
    /** How far the filter envelope opens the cutoff at its peak, in octaves. */
    val filterEnvOctaves: Double = 2.0,
    /** How much of the note's distance from middle C the cutoff follows, 0..1. */
    val keyTrack: Double = 0.5,
    val amp: AdsrSettings = AdsrSettings(),
    val filterEnv: AdsrSettings = AdsrSettings(1.0, 300.0, 0.0, 200.0),
    val gain: Double = 0.5
)

/** Two oscillators into a state-variable filter under two envelopes. Phases restart at each note, so a rendered take is the same take twice. */
class Voice(patch: SubtractivePatch, private val sampleRate: Int) : SynthVoice {
    var patch: SubtractivePatch = patch
        set(value) { field = value; configure() }

    private var osc1: Oscillator = Oscillator.of(patch.osc1)
    private var osc2: Oscillator = Oscillator.of(patch.osc2, 2L)
    private val filter = Svf(sampleRate, patch.filter)
    private val amp = Adsr(patch.amp, sampleRate)
    private val filterEnv = Adsr(patch.filterEnv, sampleRate)
    private var velocityGain = 1f
    private var baseCutoff = patch.cutoffHz
    private var sinceCutoff = 0

    override var pitch: Int = 60
        private set
    override val isActive: Boolean get() = amp.isActive
    override val isHeld: Boolean get() = amp.isHeld

    private fun configure() {
        if (!waveformMatches(osc1, patch.osc1)) osc1 = Oscillator.of(patch.osc1)
        if (!waveformMatches(osc2, patch.osc2)) osc2 = Oscillator.of(patch.osc2, 2L)
        (osc1 as? SquareOscillator)?.pulseWidth = patch.pulseWidth
        (osc2 as? SquareOscillator)?.pulseWidth = patch.pulseWidth
        filter.kind = patch.filter
        amp.set(patch.amp)
        filterEnv.set(patch.filterEnv)
        tune()
    }

    private fun waveformMatches(o: Oscillator, w: Waveform) = when (w) {
        Waveform.SAW -> o is SawOscillator
        Waveform.SQUARE -> o is SquareOscillator
        Waveform.TRIANGLE -> o is TriangleOscillator
        Waveform.SINE -> o is SineOscillator
        Waveform.NOISE -> o is NoiseOscillator
    }

    private fun tune() {
        osc1.setFrequency(Notes.midiToHz(pitch.toDouble()), sampleRate)
        osc2.setFrequency(Notes.midiToHz(pitch + patch.osc2Semitones + patch.osc2DetuneCents / 100.0), sampleRate)
        baseCutoff = patch.cutoffHz * 2.0.pow(patch.keyTrack * (pitch - 60) / 12.0)
    }

    override fun noteOn(pitch: Int, velocity: Int) {
        this.pitch = pitch.coerceIn(0, 127)
        velocityGain = (velocity.coerceIn(1, 127) / 127f)
        configure()
        osc1.reset(); osc2.reset()
        filter.reset()
        amp.gate(true)
        filterEnv.gate(true)
        sinceCutoff = CUTOFF_EVERY
    }

    override fun noteOff() { amp.gate(false); filterEnv.gate(false) }

    override fun kill() { amp.reset(); filterEnv.reset() }

    override fun render(out: FloatArray, frames: Int) {
        val mix = patch.mix.toFloat()
        val g = (patch.gain * velocityGain).toFloat()
        for (i in 0 until frames) {
            val e = amp.next()
            if (!amp.isActive) break
            val fe = filterEnv.next()
            // The cutoff follows the envelope at control rate: a tan() a sample would be a
            // quarter of the voice's cost for a change no one can hear below 3 kHz a second.
            if (++sinceCutoff >= CUTOFF_EVERY) {
                sinceCutoff = 0
                filter.setResonance(baseCutoff * 2.0.pow(patch.filterEnvOctaves * fe), patch.resonance)
            }
            val s = osc1.next() * (1f - mix) + osc2.next() * mix
            out[i] += filter.process(s) * e * g
        }
    }

    companion object { const val CUTOFF_EVERY = 16 }
}

class PolySynth(patch: SubtractivePatch = SubtractivePatch(), sampleRate: Int, voices: Int = 8) :
    PolyphonicSynth<Voice>(List(voices) { Voice(patch, sampleRate) }, sampleRate) {
    var patch: SubtractivePatch = patch
        set(value) { field = value; for (v in voices) v.patch = value }
}

/**
 * One FM operator. [level] is its amplitude when it is a carrier and its modulation index in
 * radians when it modulates another - the two readings a DX7 keeps on one slider too. [ratio]
 * multiplies the note's frequency unless [fixedHz] is set.
 */
data class FmOperator(val ratio: Double = 1.0, val level: Double = 1.0, val envelope: AdsrSettings = AdsrSettings(2.0, 400.0, 0.6, 300.0), val fixedHz: Double? = null)

/**
 * Four operators in one of four arrangements, by [algorithm]: 0 is a chain (4 into 3 into 2
 * into 1); 1 is two pairs (2 into 1, 4 into 3); 2 is three modulators into one carrier; 3 is
 * four carriers, which is additive. Every modulator has a higher number than what it feeds, so
 * the voice computes them in descending order and no operator reads a stale sample.
 */
data class FmPatch(val algorithm: Int = 0, val operators: List<FmOperator> = List(4) { FmOperator() }, val gain: Double = 0.5) {
    init {
        require(algorithm in 0..3) { "algorithm $algorithm out of 0..3" }
        require(operators.size == 4) { "four operators, not ${operators.size}" }
    }
}

object FmAlgorithms {
    /** Which operators (0-based) modulate operator [op] under [algorithm]. */
    fun inputs(algorithm: Int, op: Int): List<Int> = when (algorithm) {
        0 -> if (op < 3) listOf(op + 1) else emptyList()
        1 -> when (op) { 0 -> listOf(1); 2 -> listOf(3); else -> emptyList() }
        2 -> if (op == 0) listOf(1, 2, 3) else emptyList()
        else -> emptyList()
    }

    fun carriers(algorithm: Int): List<Int> = when (algorithm) {
        0 -> listOf(0)
        1 -> listOf(0, 2)
        2 -> listOf(0)
        else -> listOf(0, 1, 2, 3)
    }
}

class FmVoice(patch: FmPatch, private val sampleRate: Int) : SynthVoice {
    var patch: FmPatch = patch
        set(value) { field = value; configure() }

    private val phases = DoubleArray(4)
    private val increments = DoubleArray(4)
    private val raw = DoubleArray(4)
    private val envelopes = Array(4) { Adsr(patch.operators[it].envelope, sampleRate) }
    private var carriers = FmAlgorithms.carriers(patch.algorithm)
    private var velocityGain = 1f

    override var pitch: Int = 60
        private set
    override val isActive: Boolean get() = carriers.any { envelopes[it].isActive }
    override val isHeld: Boolean get() = carriers.any { envelopes[it].isHeld }

    private fun configure() {
        carriers = FmAlgorithms.carriers(patch.algorithm)
        for (i in 0 until 4) envelopes[i].set(patch.operators[i].envelope)
        tune()
    }

    private fun tune() {
        val hz = Notes.midiToHz(pitch.toDouble())
        for (i in 0 until 4) {
            val op = patch.operators[i]
            increments[i] = ((op.fixedHz ?: (hz * op.ratio)) / sampleRate).coerceIn(0.0, 0.5)
        }
    }

    override fun noteOn(pitch: Int, velocity: Int) {
        this.pitch = pitch.coerceIn(0, 127)
        velocityGain = velocity.coerceIn(1, 127) / 127f
        configure()
        phases.fill(0.0); raw.fill(0.0)
        for (e in envelopes) e.gate(true)
    }

    override fun noteOff() { for (e in envelopes) e.gate(false) }
    override fun kill() { for (e in envelopes) e.reset() }

    override fun render(out: FloatArray, frames: Int) {
        val ops = patch.operators
        val alg = patch.algorithm
        val g = (patch.gain * velocityGain / carriers.size).toFloat()
        for (i in 0 until frames) {
            if (!isActive) break
            var sum = 0.0
            for (k in 3 downTo 0) {
                var modulation = 0.0
                for (j in FmAlgorithms.inputs(alg, k)) modulation += ops[j].level * raw[j]
                raw[k] = envelopes[k].next() * sin(2.0 * PI * phases[k] + modulation)
                phases[k] += increments[k]
                if (phases[k] >= 1.0) phases[k] -= 1.0
            }
            for (c in carriers) sum += ops[c].level * raw[c]
            out[i] += (sum * g).toFloat()
        }
    }
}

class FmSynth(patch: FmPatch = FmPatch(), sampleRate: Int, voices: Int = 8) :
    PolyphonicSynth<FmVoice>(List(voices) { FmVoice(patch, sampleRate) }, sampleRate) {
    var patch: FmPatch = patch
        set(value) { field = value; for (v in voices) v.patch = value }
}

/**
 * Plays a mono sample at a pitch ratio, interpolating between frames, optionally round a loop,
 * under an envelope. [choke] fades it out in a few milliseconds for a drum pad's choke group,
 * which is a stop and not a release.
 */
class SamplerVoice(
    val sample: FloatArray,
    /** The sample's own rate; the voice renders at [outputRate]. */
    val sampleRate: Int,
    private val outputRate: Int,
    envelope: AdsrSettings = AdsrSettings(0.0, 0.0, 1.0, 20.0),
    val rootPitch: Int = 60,
    var loop: Boolean = false,
    loopStart: Int = 0,
    loopEnd: Int = -1
) : SynthVoice {
    val loopStart: Int = loopStart.coerceIn(0, (sample.size - 1).coerceAtLeast(0))
    val loopEnd: Int = if (loopEnd < 0) sample.size else loopEnd.coerceIn(this.loopStart + 1, sample.size)
    private val adsr = Adsr(envelope, outputRate)
    private var position = 0.0
    private var ratio = 1.0
    private var playing = false
    private var velocityGain = 1f
    private var chokeGain = 1f
    private var chokeStep = 0f

    override var pitch: Int = rootPitch
        private set
    override val isActive: Boolean get() = playing && adsr.isActive
    override val isHeld: Boolean get() = playing && adsr.isHeld
    val ratioPlaying: Double get() = ratio

    override fun noteOn(pitch: Int, velocity: Int) {
        this.pitch = pitch
        start(2.0.pow((pitch - rootPitch) / 12.0), velocity)
    }

    /** Starts from the first frame at [pitchRatio] times the sample's own speed (the rate difference is folded in). */
    fun start(pitchRatio: Double, velocity: Int = 127) {
        ratio = pitchRatio * sampleRate / outputRate
        velocityGain = velocity.coerceIn(1, 127) / 127f
        position = 0.0
        playing = sample.isNotEmpty()
        chokeGain = 1f; chokeStep = 0f
        adsr.gate(true)
    }

    override fun noteOff() = adsr.gate(false)
    override fun kill() { playing = false; adsr.reset() }

    fun choke(ms: Double) {
        if (!playing) return
        val n = (ms * outputRate / 1000.0).toInt().coerceAtLeast(1)
        chokeStep = 1f / n
    }

    override fun render(out: FloatArray, frames: Int) {
        if (!playing) return
        val last = sample.size - 1
        for (i in 0 until frames) {
            val e = adsr.next()
            if (!adsr.isActive) { playing = false; return }
            val p = position
            var index = p.toInt()
            if (index >= last) {
                if (index > last || p > last) { playing = false; return }
                out[i] += sample[last] * e * velocityGain * chokeGain
            } else {
                val frac = (p - index).toFloat()
                val s = sample[index] + (sample[index + 1] - sample[index]) * frac
                out[i] += s * e * velocityGain * chokeGain
            }
            if (chokeStep > 0f) {
                chokeGain -= chokeStep
                if (chokeGain <= 0f) { playing = false; adsr.reset(); return }
            }
            position = p + ratio
            if (loop && position >= loopEnd) position -= (loopEnd - loopStart)
        }
    }
}
