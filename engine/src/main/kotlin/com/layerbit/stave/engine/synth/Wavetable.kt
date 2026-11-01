package com.layerbit.stave.engine.synth

import com.layerbit.stave.engine.dsp.FilterKind
import com.layerbit.stave.engine.dsp.Notes
import kotlin.math.PI
import kotlin.math.sin

/**
 * A wavetable: a bank of single-cycle tables and a position that morphs between neighbours.
 * Each table is built band-limited for the octave it will be read at (the mip levels below),
 * so a saw read at a high note is a saw with its aliasing partials left out, not a buzz. The
 * tables are additive sums, which is why they are clean: nothing here is sampled.
 */
class Wavetable(val tables: List<FloatArray>, val name: String, /** The highest harmonic any table carries: what decides the mip level a note reads. */ val maxHarmonic: Int = 64) {
    init { require(tables.isNotEmpty() && tables.all { it.size == SIZE }) { "tables of $SIZE" } }

    /** The sample at [phase] (0..1) for a [position] (0..1) across the tables, linear in both. */
    fun read(phase: Double, position: Double): Float {
        val p = (phase - Math.floor(phase)) * SIZE
        val i0 = p.toInt() % SIZE; val i1 = (i0 + 1) % SIZE; val f = (p - p.toLong()).toFloat()
        val t = position.coerceIn(0.0, 1.0) * (tables.size - 1)
        val a = t.toInt().coerceIn(0, tables.size - 1); val b = (a + 1).coerceAtMost(tables.size - 1); val g = (t - a).toFloat()
        val ta = tables[a]; val tb = tables[b]
        val sa = ta[i0] * (1 - f) + ta[i1] * f
        val sb = tb[i0] * (1 - f) + tb[i1] * f
        return sa * (1 - g) + sb * g
    }

    companion object {
        const val SIZE = 2048

        /** A table from its harmonic amplitudes (index 1 is the fundamental), normalised to a peak of 1. */
        fun fromHarmonics(amplitudes: DoubleArray, maxHarmonic: Int = amplitudes.size - 1): FloatArray {
            val out = FloatArray(SIZE)
            for (h in 1..minOf(maxHarmonic, amplitudes.size - 1)) {
                val a = amplitudes[h]
                if (a == 0.0) continue
                for (i in 0 until SIZE) out[i] += (a * sin(2 * PI * h * i / SIZE)).toFloat()
            }
            var peak = 0f; for (v in out) if (kotlin.math.abs(v) > peak) peak = kotlin.math.abs(v)
            if (peak > 0f) for (i in 0 until SIZE) out[i] /= peak
            return out
        }

        fun sine(): FloatArray = fromHarmonics(doubleArrayOf(0.0, 1.0))
        fun saw(maxHarmonic: Int = 64): FloatArray = fromHarmonics(DoubleArray(maxHarmonic + 1) { h -> if (h == 0) 0.0 else 1.0 / h }, maxHarmonic)
        fun square(maxHarmonic: Int = 64): FloatArray = fromHarmonics(DoubleArray(maxHarmonic + 1) { h -> if (h == 0 || h % 2 == 0) 0.0 else 1.0 / h }, maxHarmonic)
        fun triangle(maxHarmonic: Int = 64): FloatArray = fromHarmonics(DoubleArray(maxHarmonic + 1) { h -> if (h == 0 || h % 2 == 0) 0.0 else (if ((h / 2) % 2 == 0) 1.0 else -1.0) / (h.toDouble() * h) }, maxHarmonic)
        /** A vowel-ish table: a few strong low harmonics, as a voice's "ah". */
        fun vocal(): FloatArray = fromHarmonics(doubleArrayOf(0.0, 1.0, 0.7, 0.9, 0.4, 0.6, 0.2, 0.3, 0.1, 0.15, 0.05))
        /** Metallic: odd and inharmonic-looking spacing by weighting every third harmonic. */
        fun bell(): FloatArray = fromHarmonics(DoubleArray(25) { h -> if (h == 0) 0.0 else if (h % 3 == 1) 1.0 / h else 0.08 / h })

        /** The bank the Beats tab offers: the morph runs sine → triangle → square → saw → vocal → bell. */
        fun standard(): Wavetable = Wavetable(listOf(sine(), triangle(), square(), saw(), vocal(), bell()), "Standard")

        /** The same bank with its harmonics capped, for notes an octave band up: the mip level for [maxHarmonic]. */
        fun standard(maxHarmonic: Int): Wavetable = Wavetable(listOf(sine(), triangle(maxHarmonic), square(maxHarmonic), saw(maxHarmonic), vocal(), bell()), "Standard", maxHarmonic)
    }
}

/** A wavetable voice's sheet: the morph position, its sweep by the envelope, and the shape of the note. */
data class WavetablePatch(
    val position: Double = 0.5,
    /** How far the envelope sweeps the position (positive opens toward the last table). */
    val positionEnvelope: Double = 0.3,
    val filter: FilterKind = FilterKind.LOW_PASS,
    val cutoffHz: Double = 4000.0,
    val resonance: Double = 0.15,
    val amp: AdsrSettings = AdsrSettings(5.0, 200.0, 0.7, 250.0),
    val sweep: AdsrSettings = AdsrSettings(1.0, 300.0, 0.0, 200.0),
    val detuneCents: Double = 6.0,
    val gain: Double = 0.45
)

/**
 * A wavetable voice: two readers of the bank a few cents apart, the morph position swept by
 * its own envelope, a state-variable filter and an amplitude envelope. The bank read is the
 * mip level whose harmonics stay under Nyquist for the note - the first table is the full
 * bank, the rest cap the harmonics at 32, 16 and 8 for the octaves above middle C.
 */
class WavetableVoice(patch: WavetablePatch, private val sampleRate: Int, private val banks: List<Wavetable> = MIPS) : SynthVoice {
    var patch: WavetablePatch = patch
        set(value) { field = value; configure() }

    private var phaseA = 0.0; private var phaseB = 0.0
    private var incA = 0.0; private var incB = 0.0
    private var bank: Wavetable = banks[0]
    private val filter = Svf(sampleRate, patch.filter)
    private val amp = Adsr(patch.amp, sampleRate)
    private val sweep = Adsr(patch.sweep, sampleRate)
    private var velocityGain = 1f
    private var sinceCutoff = 0

    override var pitch: Int = 60
        private set
    override val isActive: Boolean get() = amp.isActive
    override val isHeld: Boolean get() = amp.isHeld
    /** The harmonic cap of the bank the current note reads: what the suite checks the mip choice by. */
    val bankMaxHarmonic: Int get() = bank.maxHarmonic

    private fun configure() {
        filter.kind = patch.filter
        amp.set(patch.amp); sweep.set(patch.sweep)
        tune()
    }

    private fun tune() {
        val hz = Notes.midiToHz(pitch.toDouble())
        incA = hz / sampleRate
        incB = Notes.midiToHz(pitch + patch.detuneCents / 100.0) / sampleRate
        // The highest harmonic that stays under Nyquist decides the mip level.
        val allowed = (sampleRate / 2.0 / hz).toInt()
        bank = banks.firstOrNull { b -> b.maxHarmonic <= allowed } ?: banks.last()
    }

    override fun noteOn(pitch: Int, velocity: Int) {
        this.pitch = pitch.coerceIn(0, 127)
        velocityGain = velocity.coerceIn(1, 127) / 127f
        configure()
        phaseA = 0.0; phaseB = 0.0
        filter.reset(); amp.gate(true); sweep.gate(true)
        sinceCutoff = CUTOFF_EVERY
    }
    override fun noteOff() { amp.gate(false); sweep.gate(false) }
    override fun kill() { amp.reset(); sweep.reset() }

    override fun render(out: FloatArray, frames: Int) {
        val g = (patch.gain * velocityGain).toFloat()
        for (i in 0 until frames) {
            val e = amp.next()
            if (!amp.isActive) break
            val sw = sweep.next()
            val position = (patch.position + patch.positionEnvelope * sw).coerceIn(0.0, 1.0)
            if (++sinceCutoff >= CUTOFF_EVERY) { sinceCutoff = 0; filter.setResonance(patch.cutoffHz, patch.resonance) }
            val s = (bank.read(phaseA, position) + bank.read(phaseB, position)) * 0.5f
            phaseA += incA; if (phaseA >= 1.0) phaseA -= 1.0
            phaseB += incB; if (phaseB >= 1.0) phaseB -= 1.0
            out[i] += filter.process(s) * e * g
        }
    }

    companion object {
        const val CUTOFF_EVERY = 16
        /** Full (64 harmonics), then 32, 16 and 8: a saw at A5 (880 Hz, 27 harmonics under 24 kHz) reads the 16 table. */
        val MIPS: List<Wavetable> = listOf(Wavetable.standard(), Wavetable.standard(32), Wavetable.standard(16), Wavetable.standard(8))
    }
}

class WavetableSynth(patch: WavetablePatch = WavetablePatch(), sampleRate: Int, voices: Int = 8) :
    PolyphonicSynth<WavetableVoice>(List(voices) { WavetableVoice(patch, sampleRate) }, sampleRate) {
    var patch: WavetablePatch = patch
        set(value) { field = value; for (v in voices) v.patch = value }
}
