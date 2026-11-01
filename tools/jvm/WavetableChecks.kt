import com.layerbit.stave.engine.synth.Wavetable
import com.layerbit.stave.engine.synth.WavetablePatch
import com.layerbit.stave.engine.synth.WavetableSynth
import com.layerbit.stave.engine.synth.WavetableVoice
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** The wavetable: tables built from harmonics and normalised, reading interpolates and morphs, a high note reads a capped bank so nothing aliases, the synth plays and decays. */
object WavetableChecks : Suite {
    override val name = "wavetable"

    private const val RATE = 48_000

    /** The amplitude of harmonic [h] in a single-cycle table, by one DFT bin. */
    private fun harmonic(table: FloatArray, h: Int): Double {
        var re = 0.0; var im = 0.0
        for (i in table.indices) { val a = 2 * PI * h * i / table.size; re += table[i] * cos(a); im += table[i] * sin(a) }
        return 2 * hypot(re, im) / table.size
    }

    override fun run() {
        section("tables: a sine is one harmonic, a saw falls as 1/h, a square has no even ones, all peak at 1")
        val sine = Wavetable.sine(); val saw = Wavetable.saw(); val square = Wavetable.square()
        near(harmonic(sine, 1).toFloat(), 1f, 1e-3f, "the sine's fundamental"); check(harmonic(sine, 2) < 1e-3) { "and nothing else" }
        near((harmonic(saw, 2) / harmonic(saw, 1)).toFloat(), 0.5f, 1e-3f, "saw: the second is half the first")
        near((harmonic(saw, 7) / harmonic(saw, 1)).toFloat(), 1f / 7, 1e-3f, "and the seventh a seventh")
        check(harmonic(square, 2) < 1e-3 && harmonic(square, 3) > 0.3 * harmonic(square, 1)) { "square: no second, a third" }
        for (t in listOf(sine, saw, square, Wavetable.triangle(), Wavetable.vocal(), Wavetable.bell())) near(t.maxOf { abs(it) }, 1f, 1e-4f, "peak 1")
        check(harmonic(Wavetable.saw(8), 9) < 1e-3 && harmonic(Wavetable.saw(8), 8) > 0.05) { "a capped saw stops at its cap" }

        section("read: interpolates between samples and morphs between tables")
        val bank = Wavetable.standard()
        near(bank.read(0.25, 0.0), 1f, 1e-3f, "a quarter into the sine is its peak")
        near(bank.read(0.5, 0.0), 0f, 1e-3f, "half way is zero")
        // Positions 0 and 0.2 are the sine and the triangle exactly (six tables, five steps); 0.1 is half way between them.
        val mid = bank.read(0.125, 0.1); val expected = 0.5f * (bank.read(0.125, 0.0) + bank.read(0.125, 0.2))
        near(mid, expected, 1e-4f, "half way between two neighbouring tables is their average")
        check(abs(bank.read(0.125, 0.0) - sin(2 * PI * 0.125).toFloat()) < 1e-3f) { "position 0 is the sine" }
        check(abs(bank.read(0.0, 1.0) - bank.read(1.0, 1.0)) < 1e-6f) { "phase wraps" }
        check(bank.read(0.123, 0.2) != bank.read(0.1235, 0.2)) { "interpolation moves between samples" }

        section("mips: the bank a note reads keeps every harmonic under Nyquist")
        val mips = WavetableVoice.MIPS
        check(mips.map { it.maxHarmonic } == listOf(64, 32, 16, 8)) { "four levels: ${mips.map { it.maxHarmonic }}" }
        // The voice itself: A5 (880 Hz) allows 27 harmonics under 24 kHz, so it reads the 16 bank, not the 32; A1 (55 Hz) the full one.
        // (A first version computed the choice inside the test instead of asking the voice, and a voice that always read the full bank passed it.)
        val probe = WavetableVoice(WavetablePatch(), RATE)
        probe.noteOn(81, 100); check(probe.bankMaxHarmonic == 16) { "A5 reads the ${probe.bankMaxHarmonic} bank, not 16" }
        probe.noteOn(33, 100); check(probe.bankMaxHarmonic == 64) { "A1 reads the ${probe.bankMaxHarmonic} bank, not 64" }
        probe.noteOn(105, 100); check(probe.bankMaxHarmonic == 8) { "C8 reads the ${probe.bankMaxHarmonic} bank, not 8" }

        section("the synth: a sine patch sounds at its pitch; moving the position brings in harmonics; it decays after release")
        // Position 0 with no sweep is the sine table: one rising crossing a cycle. A richer table crosses more often, so the pitch is read off this one.
        val synth = WavetableSynth(WavetablePatch(position = 0.0, positionEnvelope = 0.0, cutoffHz = 12_000.0, detuneCents = 0.0), RATE, voices = 4)
        val out = FloatArray(RATE * 2)
        synth.noteOn(69, 110)
        synth.render(out, RATE)
        val left = FloatArray(RATE) { out[2 * it] }
        var crossings = 0; for (i in RATE / 2 + 1 until RATE) if (left[i - 1] < 0f && left[i] >= 0f) crossings++
        check(abs(crossings - 220) <= 4) { "440 Hz over half a second is 220 rising crossings: $crossings" }
        check(left.maxOf { abs(it) } > 0.1f && left.maxOf { abs(it) } <= 1f) { "it sounds and does not clip: ${left.maxOf { abs(it) }}" }
        // The colour: at position 0.6 (the saw) the second harmonic is there; on the sine it is not.
        fun secondOverFirst(position: Double): Double {
            val sy = WavetableSynth(WavetablePatch(position = position, positionEnvelope = 0.0, cutoffHz = 12_000.0, detuneCents = 0.0), RATE, voices = 2)
            val buf = FloatArray(RATE * 2); sy.noteOn(69, 110); sy.render(buf, RATE)
            val mono = FloatArray(RATE / 2) { buf[2 * (RATE / 2 + it)] }
            fun mag(hz: Double): Double { var re = 0.0; var im = 0.0; for (i in mono.indices) { val a = 2 * PI * hz * i / RATE; re += mono[i] * cos(a); im += mono[i] * sin(a) }; return hypot(re, im) }
            return mag(880.0) / mag(440.0)
        }
        check(secondOverFirst(0.0) < 0.05 && secondOverFirst(0.6) > 0.3) { "second harmonic over first: sine ${secondOverFirst(0.0)}, saw ${secondOverFirst(0.6)}" }
        synth.noteOff(69)
        synth.render(out, RATE)
        check(!synth.voices.any { it.isActive } || abs(out[2 * (RATE - 1)]) < 1e-3f) { "quiet after the release" }
        synth.allNotesOff(); synth.render(out, RATE); check(synth.activeVoices == 0) { "released" }
    }
}
