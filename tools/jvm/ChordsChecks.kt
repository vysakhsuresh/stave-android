import com.layerbit.stave.engine.dsp.Notes
import com.layerbit.stave.engine.music.Chord
import com.layerbit.stave.engine.music.ChordChart
import com.layerbit.stave.engine.music.ChordSpan
import com.layerbit.stave.engine.music.KeyEstimate
import com.layerbit.stave.engine.music.Spelling
import com.layerbit.stave.engine.music.Chords
import com.layerbit.stave.engine.music.Chroma
import com.layerbit.stave.engine.music.GuitarShapes
import com.layerbit.stave.engine.music.Quality
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

object ChordsChecks : Suite {
    override val name = "chords"

    const val RATE = 44_100

    /** Sines at [midis] for [seconds], the level shared so a tetrad is no louder than a triad. */
    fun tones(midis: List<Int>, seconds: Double, rate: Int = RATE): FloatArray {
        val n = (seconds * rate).toInt()
        val out = FloatArray(n)
        val gain = 0.3 / midis.size
        for (m in midis) {
            val hz = Notes.midiToHz(m.toDouble())
            for (i in 0 until n) out[i] += (gain * sin(2 * PI * hz * i / rate)).toFloat()
        }
        return out
    }

    /** Each (notes, seconds) back to back. */
    fun progression(parts: List<Pair<List<Int>, Double>>, rate: Int = RATE): FloatArray {
        val pieces = parts.map { (notes, seconds) -> tones(notes, seconds, rate) }
        val out = FloatArray(pieces.sumOf { it.size })
        var at = 0
        for (p in pieces) { p.copyInto(out, at); at += p.size }
        return out
    }

    /** C, Am, F, G7 - a second each, voiced around middle C. */
    val C_AM_F_G7 = listOf(
        listOf(60, 64, 67) to 1.0,
        listOf(57, 60, 64) to 1.0,
        listOf(53, 57, 60) to 1.0,
        listOf(55, 59, 62, 65) to 1.0
    )

    private fun names(spans: List<com.layerbit.stave.engine.music.ChordSpan>) = spans.map { it.chord.name() }

    override fun run() {
        section("names")
        run {
            check(Chord(9, Quality.MAJOR).name() == "A") { "A" }
            check(Chord(9, Quality.MINOR).name() == "Am") { "Am" }
            check(Chord(7, Quality.DOMINANT7).name() == "G7") { "G7" }
            check(Chord(0, Quality.MAJOR7).name() == "Cmaj7") { "Cmaj7" }
            check(Chord(11, Quality.DIMINISHED).name() == "Bdim") { "Bdim" }
            check(Chord(10, Quality.MAJOR).name() == "B♭") { "B♭, as a chart spells it" }
            check(Chord(6, Quality.MINOR).name() == "F♯m") { "F♯m" }
            check(Chord(4, Quality.AUGMENTED).name() == "Eaug" && Chord(2, Quality.SUS2).name() == "Dsus2" && Chord(9, Quality.SUS4).name() == "Asus4" && Chord(4, Quality.MINOR7).name() == "Em7") { "the rest" }
            check(Chord.NONE.name() == "N.C." && Chord(5, Quality.NONE).name() == "N.C.") { "no chord has no root in its name" }
            check(Chord(0, Quality.MAJOR).pitchClasses() == setOf(0, 4, 7) && Chord(11, Quality.DOMINANT7).pitchClasses() == setOf(11, 3, 6, 9)) { "pitch classes" }
            check(Chords.states.size == 109 && Chords.states.last() == Chord.NONE) { "108 chords and no chord" }
        }

        section("a key spells its own black keys")
        run {
            fun key(root: Int, minor: Boolean) = KeyEstimate(root, minor, 1f)
            // The phone read a song in E♭ minor and listed "F♯" among D♭ and A♭ chords.
            check(Chord(6, Quality.MAJOR).name(key(3, true).spelling) == "G♭") { "E♭ minor writes G♭, not ${Chord(6, Quality.MAJOR).name(key(3, true).spelling)}" }
            check(key(3, true).name() == "E♭ minor") { key(3, true).name() }
            check(key(1, true).name() == "C♯ minor" && key(8, true).name() == "G♯ minor") { "C♯ and G♯ minor are sharp keys: ${key(1, true).name()}, ${key(8, true).name()}" }
            check(Chord(1, Quality.MINOR).name(key(4, false).spelling) == "C♯m") { "E major writes C♯m" }
            check(Chord(8, Quality.MAJOR).name(key(1, false).spelling) == "A♭" && key(1, false).name() == "D♭ major") { "D♭ major writes A♭" }
            check(key(6, false).name() == "F♯ major" && Chord(3, Quality.MINOR).name(key(6, false).spelling) == "D♯m") { "F♯ major writes D♯m" }
            check(key(10, true).name() == "B♭ minor" && key(2, true).spelling == Spelling.FLATS) { "B♭ minor; D minor is a flat key" }
            check(key(0, false).spelling == Spelling.CHART && key(9, true).spelling == Spelling.CHART) { "C major and A minor keep the chart's spelling" }
        }

        section("a chord chart rides through short gaps")
        run {
            fun span(a: Int, b: Int, root: Int, q: Quality) = ChordSpan(a, b, root, q, 0.8f)
            val nc = Quality.NONE
            val raw = listOf(
                span(0, 40, 0, nc),                       // a four-second intro: stays
                span(40, 80, 1, Quality.SUS2), span(80, 85, 0, nc), span(85, 120, 1, Quality.SUS2),   // one chord across a breath
                span(120, 130, 0, nc), span(130, 160, 9, Quality.MAJOR7),   // a breath before a change
                span(160, 200, 0, nc),                    // two seconds: a break, not a breath
                span(200, 230, 3, Quality.MINOR), span(230, 240, 0, nc)     // the tail stays
            )
            val chart = ChordChart.of(raw, 20)
            val shown = chart.map { "${it.chord.name()} ${it.startFrame}-${it.endFrame}" }
            val want = listOf("N.C. 0-40", "D♭sus2 40-130", "Amaj7 130-160", "N.C. 160-200", "E♭m 200-230", "N.C. 230-240")
            check(shown == want) { "chart $shown, wanted $want" }
            check(chart.zipWithNext().all { (a, b) -> a.endFrame == b.startFrame } && chart.first().startFrame == 0 && chart.last().endFrame == 240) { "the chart covers the song without a hole" }
            check(ChordChart.of(emptyList(), 20).isEmpty()) { "nothing is nothing" }
        }

        section("templates: a clean chord fits its own template first, noise fits none")
        run {
            for ((s, chord) in Chords.states.withIndex()) {
                if (chord.quality == Quality.NONE) continue
                val c = FloatArray(12)
                for (pc in chord.pitchClasses()) c[pc] = 1f
                Chroma.normalise(c)
                val fit = Chords.correlate(c)
                val best = fit.indices.maxByOrNull { fit[it] }!!
                // An augmented triad is three chords' notes and a sus2 is another sus4's, so the
                // best fit may be a different name for the same set; it must never be another set.
                check(best == s || Chords.states[best].pitchClasses() == chord.pitchClasses()) { "${chord.name()} read as ${Chords.states[best].name()}" }
                near(fit[s], 1f, 1e-5f, "${chord.name()} on itself")
            }
            val flat = FloatArray(12) { 1f / 12 }
            val fit = Chords.correlate(flat)
            check(fit.indices.maxByOrNull { fit[it] } == Chords.states.lastIndex) { "flat chroma did not read as no chord" }
            near(fit[0], 0.5f, 1e-5f, "a triad on flat chroma")
            val silent = Chords.correlate(FloatArray(12))
            check(silent.last() == 1f && silent.dropLast(1).all { it == 0f }) { "silence fits no chord fully and nothing else" }
        }

        section("C → Am → F → G7 comes back as four spans at the right moments")
        val chroma = Chroma.frames(progression(C_AM_F_G7), RATE)
        run {
            val spans = Chords.detect(chroma)
            check(names(spans) == listOf("C", "Am", "F", "G7")) { "read ${names(spans)} (${spans.map { "${it.startFrame}-${it.endFrame}" }})" }
            for ((i, span) in spans.withIndex()) {
                val startMs = span.startFrame * 100
                check(abs(startMs - i * 1000) <= 150) { "${span.chord.name()} started at $startMs ms, expected ${i * 1000}" }
                check(span.confidence > 0.8f) { "${span.chord.name()} at confidence ${span.confidence}" }
            }
            check(spans.last().endFrame == chroma.size) { "the last span ends at ${spans.last().endFrame} of ${chroma.size}" }
            println("      spans: ${spans.map { "${it.chord.name()} ${it.startFrame * 100}-${it.endFrame * 100} ms (${"%.2f".format(it.confidence)})" }}")
        }

        section("one frame of noise in the middle does not break a span")
        run {
            val noisy = chroma.mapTo(ArrayList()) { it.copyOf() }
            noisy[15] = FloatArray(12) { 1f / 12 }
            val spans = Chords.detect(noisy)
            check(names(spans) == listOf("C", "Am", "F", "G7")) { "a flat frame broke the spans: ${names(spans)}" }
            // And in the audio itself: a tenth of a second of white noise over the Am.
            val signal = progression(C_AM_F_G7)
            val rng = Random(11)
            for (i in (1.5 * RATE).toInt() until (1.6 * RATE).toInt()) signal[i] += ((rng.nextDouble() * 2 - 1) * 0.5).toFloat()
            val heard = Chords.detect(Chroma.frames(signal, RATE))
            check(names(heard) == listOf("C", "Am", "F", "G7")) { "a burst of noise broke the spans: ${names(heard)} ${heard.map { "${it.startFrame}-${it.endFrame}" }}" }
            // Without the smoothing the same frame flips to no chord - the Viterbi is doing the work.
            val unsmoothed = Chords.detect(noisy, selfTransition = 1.0 / Chords.states.size)
            check(unsmoothed.size > 4) { "without smoothing the noise frame should have shown: ${names(unsmoothed)}" }
        }

        section("silence is no chord, and an empty sequence is nothing")
        run {
            val spans = Chords.detect(List(10) { FloatArray(12) })
            check(spans.size == 1 && spans[0].quality == Quality.NONE && spans[0].frames == 10 && spans[0].confidence == 1f) { "silence read as ${names(spans)}" }
            check(Chords.detect(emptyList()).isEmpty()) { "an empty sequence" }
        }

        section("every guitar shape sounds its chord")
        run {
            check(GuitarShapes.known.size >= 30) { "only ${GuitarShapes.known.size} shapes" }
            for (chord in GuitarShapes.known) {
                val shape = chord.guitarShape()!!
                check(shape.size == 6 && shape.all { it in -1..4 }) { "${chord.name()} shape ${shape.toList()}" }
                val sounded = GuitarShapes.pitchClasses(shape)
                val wanted = chord.pitchClasses()
                check(sounded.all { it in wanted }) { "${chord.name()} ${shape.toList()} sounds ${sounded - wanted} outside the chord" }
                check(chord.root in sounded) { "${chord.name()} ${shape.toList()} has no root" }
                check((chord.root + chord.quality.intervals[1]).mod(12) in sounded) { "${chord.name()} ${shape.toList()} has no third (or second, or fourth)" }
                if (chord.quality.isSeventh) check((chord.root + chord.quality.intervals[3]).mod(12) in sounded) { "${chord.name()} ${shape.toList()} has no seventh" }
                check(sounded.size >= 3) { "${chord.name()} ${shape.toList()} sounds only ${sounded.size} classes" }
            }
            check(Chord(0, Quality.MAJOR).guitarShape()!!.contentEquals(intArrayOf(-1, 3, 2, 0, 1, 0))) { "C is x32010" }
            check(Chord(7, Quality.DOMINANT7).guitarShape()!!.contentEquals(intArrayOf(3, 2, 0, 0, 0, 1))) { "G7 is 320001" }
            check(Chord(1, Quality.MAJOR).guitarShape() == null && Chord.NONE.guitarShape() == null && Chord(11, Quality.MAJOR).guitarShape() == null) { "no open shape is offered where none is taught" }
            // The caller gets a copy: writing on it must not change the chart.
            Chord(0, Quality.MAJOR).guitarShape()!![0] = 9
            check(Chord(0, Quality.MAJOR).guitarShape()!![0] == -1) { "a returned shape is shared" }
        }
    }
}
