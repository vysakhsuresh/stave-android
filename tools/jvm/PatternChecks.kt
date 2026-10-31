import com.layerbit.stave.engine.json.Json
import com.layerbit.stave.engine.sequencer.Arpeggiator
import com.layerbit.stave.engine.sequencer.Arrangement
import com.layerbit.stave.engine.sequencer.Note
import com.layerbit.stave.engine.sequencer.NoteEdits
import com.layerbit.stave.engine.sequencer.NoteIds
import com.layerbit.stave.engine.sequencer.Pattern
import com.layerbit.stave.engine.sequencer.Placement
import com.layerbit.stave.engine.sequencer.SequencerJson
import com.layerbit.stave.engine.sequencer.Step
import com.layerbit.stave.engine.sequencer.Ticks
import kotlin.math.abs

/** The sequencer model: ticks, steps, the edits, the arpeggiator, the arrangement, the file. Every number below is worked by hand at 960 ppq. */
object PatternChecks : Suite {
    override val name = "patterns"

    private fun n(id: String, pitch: Int, start: Long, length: Long = 240, velocity: Int = 100) = Note(id, pitch, velocity, start, length)

    override fun run() {
        section("ticks: 960 a quarter, 3840 a bar, every division whole")
        check(Ticks.PER_QUARTER == 960L && Ticks.PER_BAR == 3840L) { "the grid" }
        check(Ticks.perStep(4) == 960L && Ticks.perStep(8) == 480L && Ticks.perStep(16) == 240L && Ticks.perStep(32) == 120L) { "straight divisions" }
        check(Ticks.perStep(12) == 320L && Ticks.perStep(24) == 160L && Ticks.perStep(48) == 80L) { "triplet divisions" }
        near(Ticks.toBeats(1440), 1.5, 1e-12, "beats of a dotted quarter"); check(Ticks.fromBeats(2.25) == 2160L) { "ticks from beats" }

        section("a note refuses what no synth can play")
        check(runCatching { Note("x", 128, 100, 0, 1) }.isFailure) { "pitch 128" }
        check(runCatching { Note("x", 60, 0, 0, 1) }.isFailure) { "velocity 0 is a note off, not a note" }
        check(runCatching { Note("x", 60, 100, 0, 0) }.isFailure) { "zero length" }
        check(runCatching { Note("x", 60, 100, 0, 1, ratchet = 5) }.isFailure) { "ratchet 5" }
        check(n("a", 60, 0, 240).overlaps(n("b", 60, 239)) && !n("a", 60, 0, 240).overlaps(n("b", 60, 240))) { "overlap is half open" }

        section("steps: a bar of 16ths is 16 steps; toggle on, read back, toggle off")
        var p = Pattern("p", "Beat")
        check(p.stepCount == 16 && p.stepCount(8) == 8 && p.stepTicks == 240L) { "16 steps of 240" }
        p = p.toggleStep(36, 3)
        check(p.notes.size == 1 && p.notes[0].startTick == 720L && p.notes[0].lengthTicks == 240L && p.notes[0].pitch == 36 && p.notes[0].velocity == 100) { "step 3 is a note at 720: ${p.notes}" }
        check(p.steps(36).let { it[3] && it.count { b -> b } == 1 } && !p.steps(38)[3]) { "the row reads back" }
        p = p.toggleStep(36, 5, velocity = 60, probability = 0.5, ratchet = 3)
        val row = p.stepRow(36)
        check(row[5] == Step(5, true, 60, 0.5, 3) && row[3] == Step(3, true, 100, 1.0, 1) && !row[4].on) { "per-step velocity, probability, ratchet: ${row[5]}" }
        p = p.toggleStep(36, 3)
        check(!p.steps(36)[3] && p.notes.size == 1) { "toggled off" }
        check(p.setStep(36, Step(5, false)).notes.isEmpty()) { "setStep off removes" }
        check(runCatching { p.setStep(36, Step(16, true)) }.isFailure) { "step 16 is outside a bar" }
        val onEighths = Pattern("q", "Q", notes = listOf(n("a", 60, 480), n("b", 60, 500))).steps(60, 8)
        check(onEighths.size == 8 && onEighths[1] && onEighths.count { it } == 1) { "a note anywhere inside the step lights it, once" }

        section("realise: ratchets split the step, probability rolls from the seed, swing delays the off-steps")
        check(p.realise(0).isEmpty() && p.realise(1).size == 3) { "a half chance falls one way for seed 0 and the other for seed 1: ${p.realise(0).size}, ${p.realise(1).size}" }
        check(p.realise(0, rollProbability = false).size == 3) { "a file export ignores the chance" }
        val r = p.withNotes(p.notes.map { it.copy(probability = 1.0) }).realise()
        check(r.size == 3 && r.map { it.startTick } == listOf(1200L, 1280L, 1360L) && r.all { it.lengthTicks == 80L && it.ratchet == 1 && it.probability == 1.0 }) { "ratchet 3 over 240: $r" }
        val always = p.withNotes(listOf(n("a", 60, 0).copy(probability = 1.0), n("b", 60, 240).copy(probability = 0.0)))
        check(always.realise(7).map { it.id } == listOf("a")) { "probability 0 never, 1 always" }
        val coin = Pattern("c", "C", lengthTicks = 3840 * 25, notes = (0 until 400).map { n("n$it", 60, it * 240L).copy(probability = 0.5) })
        val heads = coin.realise(1).size
        check(heads in 160..240) { "about half of 400 at p=0.5: $heads" }
        check(coin.realise(1) == coin.realise(1) && coin.realise(1) != coin.realise(2)) { "deterministic for a seed" }
        val swung = Pattern("s", "S", notes = listOf(n("a", 60, 0), n("b", 60, 240), n("c", 60, 480), n("d", 60, 720)), swing = 0.5).realise()
        check(swung.map { it.startTick } == listOf(0L, 300L, 480L, 780L)) { "swing 0.5 at 16ths is +60 on the off-16ths: ${swung.map { it.startTick }}" }

        section("quantise: nearest line, by strength")
        val q = listOf(n("a", 60, 300), n("b", 60, 400), n("c", 60, 0), n("d", 60, 3700))
        check(NoteEdits.quantise(q, 16).map { it.startTick } == listOf(240L, 480L, 0L, 3600L)) { "snapped to 16ths" }
        check(NoteEdits.quantise(q, 16, 0.5).map { it.startTick } == listOf(270L, 440L, 0L, 3650L)) { "halfway at 0.5" }
        check(NoteEdits.quantise(q, 16, 0.0) == q) { "strength 0 is the identity" }
        check(NoteEdits.quantise(listOf(n("a", 60, 500)), 4).single().startTick == 960L) { "to the quarter" }
        check(NoteEdits.quantise(q, 16).map { it.id } == q.map { it.id }) { "ids survive" }

        section("swing as an edit, humanise from a seed, transpose, legato, duplicate")
        val sw = NoteEdits.applySwing(listOf(n("a", 60, 0), n("b", 60, 240), n("c", 60, 480), n("d", 60, 720)), 16, 1.0)
        check(sw.map { it.startTick } == listOf(0L, 360L, 480L, 840L)) { "a full swing puts the off-16th on the triplet: ${sw.map { it.startTick }}" }
        check(NoteEdits.applySwing(listOf(n("a", 60, 480)), 8, 0.5).single().startTick == 600L) { "8ths swing by a quarter of a step" }
        val src = (0 until 16).map { n("n$it", 60, it * 240L) }
        val h1 = NoteEdits.humanise(src, 30, 20, 42)
        check(h1 == NoteEdits.humanise(src, 30, 20, 42)) { "same seed, same feel" }
        check(h1 != NoteEdits.humanise(src, 30, 20, 43)) { "another seed, another feel" }
        check(h1.zip(src).all { (a, b) -> abs(a.startTick - b.startTick) <= 30 && abs(a.velocity - b.velocity) <= 20 }) { "inside the ranges" }
        check(h1.zip(src).any { (a, b) -> a.startTick != b.startTick } && h1.zip(src).any { (a, b) -> a.velocity != b.velocity }) { "and it does move" }
        check(NoteEdits.humanise(listOf(n("a", 60, 0, velocity = 126)), 50, 10, 1).single().let { it.startTick >= 0 && it.velocity in 1..127 }) { "clamped at the edges" }
        check(NoteEdits.humanise(src, 0, 0, 9) == src) { "nothing asked, nothing moved" }
        val t = NoteEdits.transpose(listOf(n("a", 60, 0), n("b", 120, 0)), 12)
        check(t.map { it.pitch } == listOf(72)) { "transposed, the one past 127 dropped: $t" }
        check(NoteEdits.transpose(listOf(n("a", 5, 0)), -12).isEmpty()) { "below 0 dropped" }
        val leg = NoteEdits.legato(listOf(n("a", 60, 0, 100), n("c", 64, 0, 100), n("b", 62, 480, 100), n("d", 65, 960, 100)))
        check(leg.map { it.lengthTicks } == listOf(480L, 480L, 480L, 100L)) { "each reaches the next start; the chord's two both do; the last keeps its length: ${leg.map { it.lengthTicks }}" }
        check(NoteEdits.legato(listOf(n("a", 60, 0, 100)), 3840).single().lengthTicks == 3840L) { "the last reaches the end when given" }
        val dup = NoteEdits.duplicate(listOf(n("n1", 60, 0), n("n3", 62, 240)), 3840, setOf("n2"))
        check(dup.map { it.startTick } == listOf(3840L, 4080L) && dup.map { it.id } == listOf("n4", "n5")) { "copies after, ids fresh against both: $dup" }
        val pat = Pattern("p", "P", notes = listOf(n("n1", 60, 0), n("n2", 62, 1920)))
        val dd = pat.duplicate()
        check(dd.lengthTicks == 7680L && dd.notes.size == 4 && dd.notes.map { it.id }.toSet().size == 4 && dd.notes.map { it.startTick }.sorted() == listOf(0L, 1920L, 3840L, 5760L)) { "pattern doubled: $dd" }
        check(NoteIds.fresh(setOf("n1", "n2", "n4"), 3) == listOf("n3", "n5", "n6")) { "fresh ids fill the holes" }
        val added = pat.adding(listOf(n("n1", 64, 480)))
        check(added.notes.size == 3 && added.notes.map { it.id }.toSet().size == 3) { "adding renames a colliding id" }

        section("arpeggiator: C E G up over two octaves at 8ths is six notes 480 apart")
        val up = Arpeggiator.arpeggiate(listOf(67, 60, 64), Arpeggiator.Mode.UP, 8, 2, 2880)
        check(up.size == 6) { "six notes, got ${up.size}" }
        check(up.map { it.pitch } == listOf(60, 64, 67, 72, 76, 79)) { "sorted, then the octave: ${up.map { it.pitch }}" }
        check(up.map { it.startTick } == listOf(0L, 480L, 960L, 1440L, 1920L, 2400L) && up.all { it.lengthTicks == 480L }) { "480 apart, each a step long" }
        check(up.map { it.id }.toSet().size == 6) { "distinct ids" }
        val down = Arpeggiator.arpeggiate(listOf(60, 64, 67), Arpeggiator.Mode.DOWN, 8, 2, 2880)
        check(down.map { it.pitch } == listOf(79, 76, 72, 67, 64, 60)) { "down" }
        val updown = Arpeggiator.arpeggiate(listOf(60, 64, 67), Arpeggiator.Mode.UP_DOWN, 8, 2, 4800)
        check(updown.map { it.pitch } == listOf(60, 64, 67, 72, 76, 79, 76, 72, 67, 64)) { "up-down sounds neither end twice: ${updown.map { it.pitch }}" }
        check(Arpeggiator.cycle(listOf(60, 64), Arpeggiator.Mode.UP_DOWN, 1) == listOf(60, 64)) { "two notes up-down is just the two" }
        check(Arpeggiator.arpeggiate(listOf(60, 64, 67), Arpeggiator.Mode.UP, 16, 1, 1920).map { it.pitch } == listOf(60, 64, 67, 60, 64, 67, 60, 64)) { "a longer length cycles" }
        check(Arpeggiator.arpeggiate(listOf(60), Arpeggiator.Mode.UP, 8, 1, 1000).map { it.lengthTicks } == listOf(480L, 480L, 40L)) { "the last note is cut at the length" }
        check(Arpeggiator.arpeggiate(listOf(60, 64, 67), Arpeggiator.Mode.UP, 8, 2, 2880, gate = 0.5).all { it.lengthTicks == 240L }) { "gate halves the notes" }
        val rnd = Arpeggiator.arpeggiate(listOf(60, 64, 67), Arpeggiator.Mode.RANDOM, 16, 1, 3840, seed = 5)
        check(rnd.size == 16 && rnd.all { it.pitch in setOf(60, 64, 67) }) { "random draws from the pool" }
        check(rnd == Arpeggiator.arpeggiate(listOf(60, 64, 67), Arpeggiator.Mode.RANDOM, 16, 1, 3840, seed = 5)) { "the same seed is the same line" }
        check(rnd.map { it.pitch }.toSet().size == 3) { "and it does vary" }
        check(Arpeggiator.cycle(listOf(120, 124), Arpeggiator.Mode.UP, 2) == listOf(120, 124)) { "octaves past 127 are left off" }
        check(Arpeggiator.arpeggiate(emptyList(), Arpeggiator.Mode.UP, 8, 1, 960).isEmpty()) { "no chord, no notes" }

        section("arrangement: placements, repeats, a note cut at its pattern's end")
        val a = Pattern("a", "A", 3840, listOf(n("x", 60, 0, 480), n("y", 62, 3600, 480)))
        val b = Pattern("b", "B", 1920, listOf(n("z", 48, 960, 960)))
        val arr = Arrangement(listOf(Placement("a", 0, 2), Placement("b", 7680), Placement("missing", 100)))
        val flat = arr.flatten(listOf(a, b))
        check(flat.map { it.startTick } == listOf(0L, 3600L, 3840L, 7440L, 8640L)) { "notes at their places: ${flat.map { it.startTick }}" }
        check(flat[1].lengthTicks == 240L && flat[3].lengthTicks == 240L) { "the note hanging past the bar is cut at it" }
        check(flat.map { it.id }.toSet().size == 5) { "ids unique across repeats: ${flat.map { it.id }}" }
        check(arr.lengthTicks(mapOf("a" to a, "b" to b)) == 9600L) { "the song's length is the last placement's end" }
        check(Arrangement().flatten(listOf(a)).isEmpty() && Arrangement().lengthTicks(emptyMap()) == 0L) { "empty" }
        check(runCatching { Placement("a", -1) }.isFailure && runCatching { Placement("a", 0, 0) }.isFailure) { "placement limits" }

        section("the project file: a pattern round trips, a missing field reads as the default")
        val full = Pattern("p7", "Snare roll", 7680, listOf(n("n1", 38, 0).copy(probability = 0.75, ratchet = 4, channel = 9), n("n2", 40, 3600, 480, 127)), swing = 0.3, stepDivision = 32)
        val text = Json.write(SequencerJson.write(full))
        check(SequencerJson.readPattern(Json.parseObject(text)) == full) { "round trip: $text" }
        val old = SequencerJson.readPattern(Json.parseObject("""{"id":"o","name":"Old","notes":[{"id":"n1","pitch":60,"velocity":90,"start":0,"length":240}]}"""))
        check(old.lengthTicks == 3840L && old.swing == 0.0 && old.stepDivision == 16 && old.notes.single().probability == 1.0 && old.notes.single().ratchet == 1) { "defaults: $old" }
        val arrText = Json.write(SequencerJson.write(arr))
        check(SequencerJson.readArrangement(Json.parseObject(arrText)) == arr) { "arrangement round trip: $arrText" }
        check(SequencerJson.write(full.copy(swing = Double.NaN.coerceIn(0.0, 1.0).let { 0.0 })).has("swing")) { "swing written" }
    }
}
