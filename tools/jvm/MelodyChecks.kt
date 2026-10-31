import com.layerbit.stave.engine.dsp.MelodyNotes
import com.layerbit.stave.engine.dsp.Notes
import kotlin.math.PI
import kotlin.math.sin

object MelodyChecks : Suite {
    override val name = "melody"

    override fun run() {
        val rate = 48_000
        section("a three-note tune comes back as three notes at their pitches and times")
        val plan = listOf(60 to 0.6, 64 to 0.5, 67 to 0.7)   // C4, E4, G4
        val total = (plan.sumOf { it.second } * rate).toInt() + rate / 4
        val signal = FloatArray(total)
        var at = 0
        for ((midi, secs) in plan) {
            val n = (secs * rate).toInt(); val hz = Notes.midiToHz(midi.toDouble())
            for (i in 0 until n) signal[at + i] = (0.4 * sin(2 * PI * hz * i / rate) * (if (i < 500) i / 500.0 else if (i > n - 500) (n - i) / 500.0 else 1.0)).toFloat()
            at += n
        }
        val notes = MelodyNotes.extract(signal, rate)
        check(notes.size == 3) { "three notes: ${notes.map { it.midi }}" }
        check(notes.map { it.midi } == listOf(60, 64, 67)) { "pitches ${notes.map { it.midi }}" }
        check(kotlin.math.abs(notes[1].start - (0.6 * rate).toLong()) < rate / 20) { "the second note starts near 0.6 s: ${notes[1].start}" }
        check(notes.all { it.velocity in 60..127 }) { "velocities from the level: ${notes.map { it.velocity }}" }
        check(notes[2].end <= total.toLong() + 2048) { "the last note ends inside the signal" }

        section("a glitch of one frame does not split a note, silence gives nothing")
        val glitchy = signal.copyOf()
        for (i in 0 until 600) glitchy[12_000 + i] = (0.4 * sin(2 * PI * Notes.midiToHz(72.0) * i / rate)).toFloat()
        val n2 = MelodyNotes.extract(glitchy, rate)
        check(n2.size == 3) { "the 12 ms blip is absorbed: ${n2.map { it.midi }}" }
        check(MelodyNotes.extract(FloatArray(rate), rate).isEmpty()) { "silence" }
        check(MelodyNotes.transposed(notes, 12).map { it.midi } == listOf(72, 76, 79)) { "transposed" }
    }
}
