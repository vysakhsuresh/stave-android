package com.layerbit.stave.engine.sequencer

import kotlin.math.roundToLong
import kotlin.random.Random

/*
 * The sequencer's model: notes on a tick grid, patterns of them, and the pure edits a step
 * sequencer, a piano roll and an arpeggiator make. Immutable values like the project model, so
 * an edit is a new list and History keeps the old one.
 *
 * Time is in ticks at Ticks.PER_QUARTER throughout. The audio side converts to samples at the
 * last moment (TicksTime), so a tempo change moves no note; a beat is a Double of quarters for
 * the screen (Ticks.toBeats), never a float of a position.
 */
object Ticks {
    /**
     * 960 = 2^6 x 3 x 5: a quarter divides evenly into every subdivision a sequencer offers,
     * straight, triplet and quintuplet, down to 64ths. 480 (the common SMF choice) does not
     * reach 64th-note quintuplets, and 96 (older hardware) loses 32nd triplets.
     */
    const val PER_QUARTER = 960L
    /** A 4/4 bar; the arrangement carries its own time signature when it is not. */
    const val PER_BAR = PER_QUARTER * 4

    /** Ticks in one step of a [division], counted as notes per whole note: 4 quarters, 8 eighths, 16 sixteenths, 12 eighth triplets. */
    fun perStep(division: Int): Long {
        require(division > 0) { "division must be positive, not $division" }
        return PER_BAR / division
    }

    fun toBeats(ticks: Long): Double = ticks / PER_QUARTER.toDouble()
    fun fromBeats(beats: Double): Long = (beats * PER_QUARTER).roundToLong()
}

/**
 * One note. [probability] and [ratchet] are the step sequencer's per-step extras and are
 * resolved into plain notes when a pattern is played (Pattern.realise), never stored resolved,
 * so the step keeps saying what it was set to.
 */
data class Note(
    val id: String,
    val pitch: Int,
    val velocity: Int,
    val startTick: Long,
    val lengthTicks: Long,
    val channel: Int = 0,
    val probability: Double = 1.0,
    val ratchet: Int = 1
) {
    init {
        require(pitch in 0..127) { "pitch $pitch out of 0..127" }
        require(velocity in 1..127) { "velocity $velocity out of 1..127" }
        require(startTick >= 0) { "start $startTick before the pattern" }
        require(lengthTicks > 0) { "a note must last at least a tick" }
        require(channel in 0..15) { "channel $channel out of 0..15" }
        require(probability in 0.0..1.0) { "probability $probability out of 0..1" }
        require(ratchet in 1..MAX_RATCHET) { "ratchet $ratchet out of 1..$MAX_RATCHET" }
    }

    val endTick: Long get() = startTick + lengthTicks
    val startBeat: Double get() = Ticks.toBeats(startTick)
    val lengthBeats: Double get() = Ticks.toBeats(lengthTicks)

    fun overlaps(other: Note): Boolean = startTick < other.endTick && endTick > other.startTick

    /** The same note with no id, for comparing what is heard rather than which object it is (a MIDI file carries no ids). */
    fun anonymous(): Note = copy(id = "")

    companion object {
        const val MAX_RATCHET = 4
    }
}

/** One step of a step-sequencer row: on or off, and the extras a note carries. */
data class Step(val index: Int, val on: Boolean, val velocity: Int = 100, val probability: Double = 1.0, val ratchet: Int = 1)

/** Ids for notes made by the sequencer: the smallest "n<k>" nothing in the pattern uses. */
object NoteIds {
    fun fresh(taken: Set<String>, count: Int = 1): List<String> {
        val out = ArrayList<String>(count)
        var k = 1
        while (out.size < count) {
            val id = "n$k"
            if (id !in taken) out += id
            k++
        }
        return out
    }
}

data class Pattern(
    val id: String,
    val name: String,
    val lengthTicks: Long = Ticks.PER_BAR,
    val notes: List<Note> = emptyList(),
    /** Swing applied when the pattern is played, not to the stored notes, so it can be turned down again. */
    val swing: Double = 0.0,
    /** The step sequencer's grid, as notes per whole note. */
    val stepDivision: Int = 16
) {
    init {
        require(lengthTicks > 0) { "a pattern must last at least a tick" }
        require(swing in 0.0..1.0) { "swing $swing out of 0..1" }
        require(stepDivision > 0) { "division must be positive" }
    }

    val lengthBeats: Double get() = Ticks.toBeats(lengthTicks)
    val stepTicks: Long get() = Ticks.perStep(stepDivision)
    val stepCount: Int get() = (lengthTicks / stepTicks).toInt()

    fun stepCount(division: Int): Int = (lengthTicks / Ticks.perStep(division)).toInt()

    private fun ids(): Set<String> = notes.mapTo(HashSet()) { it.id }

    /** The notes of [row] (a pitch) that start inside step [step] of [division]. */
    fun notesInStep(row: Int, step: Int, division: Int = stepDivision): List<Note> {
        val grid = Ticks.perStep(division)
        val from = step * grid
        return notes.filter { it.pitch == row && it.startTick >= from && it.startTick < from + grid }
    }

    /** A row of the step sequencer: true where a note of [row] starts in that step. */
    fun steps(row: Int, division: Int = stepDivision): BooleanArray {
        val grid = Ticks.perStep(division)
        val out = BooleanArray(stepCount(division))
        for (n in notes) {
            if (n.pitch != row) continue
            val s = (n.startTick / grid).toInt()
            if (s in out.indices) out[s] = true
        }
        return out
    }

    /** The row with each step's velocity, probability and ratchet, read off the first note in the step. */
    fun stepRow(row: Int, division: Int = stepDivision): List<Step> {
        val grid = Ticks.perStep(division)
        val first = HashMap<Int, Note>()
        for (n in notes.sortedBy { it.startTick }) {
            if (n.pitch != row) continue
            first.putIfAbsent((n.startTick / grid).toInt(), n)
        }
        return List(stepCount(division)) { i ->
            val n = first[i]
            if (n == null) Step(i, false) else Step(i, true, n.velocity, n.probability, n.ratchet)
        }
    }

    /** Turns a step on (a note a step long, on the grid) or off (every note of the row that starts in the step goes). */
    fun toggleStep(row: Int, step: Int, division: Int = stepDivision, velocity: Int = 100, probability: Double = 1.0, ratchet: Int = 1): Pattern {
        val inStep = notesInStep(row, step, division)
        if (inStep.isNotEmpty()) return copy(notes = notes - inStep.toSet())
        return setStep(row, Step(step, true, velocity, probability, ratchet), division)
    }

    /** Writes a step: off removes, on replaces whatever starts in the step with one note carrying the step's values. */
    fun setStep(row: Int, step: Step, division: Int = stepDivision): Pattern {
        require(step.index in 0 until stepCount(division)) { "step ${step.index} outside the pattern" }
        val grid = Ticks.perStep(division)
        val kept = notes - notesInStep(row, step.index, division).toSet()
        if (!step.on) return copy(notes = kept)
        val note = Note(NoteIds.fresh(ids()).first(), row, step.velocity, step.index * grid, grid, 0, step.probability, step.ratchet)
        return copy(notes = kept + note)
    }

    fun withNotes(notes: List<Note>): Pattern = copy(notes = notes)

    /** Adds notes, giving each a fresh id so a pasted or arpeggiated set never collides with what is there. */
    fun adding(added: List<Note>): Pattern {
        val fresh = NoteIds.fresh(ids(), added.size)
        return copy(notes = notes + added.mapIndexed { i, n -> n.copy(id = fresh[i]) })
    }

    /** The pattern twice as long with its notes repeated in the second half. */
    fun duplicate(): Pattern = copy(lengthTicks = lengthTicks * 2, notes = notes + NoteEdits.duplicate(notes, lengthTicks, ids()))

    /**
     * What the player hears: the swing, then every ratchet split into its repeats, then each
     * note's probability rolled with [seed]. Deterministic for a seed, so a loop can play the
     * same roll each time round when that is wanted, and a different one when the seed is the
     * bar number. A file export passes [rollProbability] false: a file plays every note, by
     * definition, so the chance is written as certain.
     */
    fun realise(seed: Long = 0L, rollProbability: Boolean = true): List<Note> {
        val swung = if (swing > 0.0) NoteEdits.applySwing(notes, stepDivision, swing) else notes
        val random = Random(seed)
        val out = ArrayList<Note>()
        for (n in swung.sortedWith(compareBy({ it.startTick }, { it.pitch }))) {
            if (rollProbability && n.probability < 1.0 && random.nextDouble() >= n.probability) continue
            if (n.ratchet == 1) { out += n.copy(probability = 1.0); continue }
            // Ratchets divide the note's own length, so a half-step note ratcheted plays its
            // repeats inside its half step rather than spilling into the next one.
            val piece = (n.lengthTicks / n.ratchet).coerceAtLeast(1)
            for (r in 0 until n.ratchet) out += n.copy(id = "${n.id}.$r", startTick = n.startTick + piece * r, lengthTicks = piece, probability = 1.0, ratchet = 1)
        }
        return out
    }
}

/** The pure edits over a note list. Each returns a new list and leaves the ids alone, so a selection survives the edit. */
object NoteEdits {

    /** Each start moved toward the nearest line of [division] by [strength] of the way; 1 snaps, 0.5 goes halfway. */
    fun quantise(notes: List<Note>, division: Int, strength: Double = 1.0): List<Note> {
        val grid = Ticks.perStep(division)
        val s = strength.coerceIn(0.0, 1.0)
        return notes.map { n ->
            val target = Math.round(n.startTick.toDouble() / grid) * grid
            val moved = (n.startTick + (target - n.startTick) * s).roundToLong().coerceAtLeast(0)
            if (moved == n.startTick) n else n.copy(startTick = moved)
        }
    }

    /** Every second step of [division] delayed by [amount] of half a step: at 16ths a full swing lands the off-16ths on the 8th triplet after. */
    fun applySwing(notes: List<Note>, division: Int, amount: Double): List<Note> {
        val grid = Ticks.perStep(division)
        val delay = (amount.coerceIn(0.0, 1.0) * grid / 2).roundToLong()
        if (delay == 0L) return notes
        return notes.map { n -> if ((n.startTick / grid) % 2 == 1L) n.copy(startTick = n.startTick + delay) else n }
    }

    /**
     * Starts nudged within ±[timingTicks] and velocities within ±[velocityRange], from [seed]
     * alone, so Undo and a project reload meet the same performance. A note pushed before the
     * pattern's start stays at 0; a velocity leaves 1..127 by clamping, never by wrapping.
     */
    fun humanise(notes: List<Note>, timingTicks: Long, velocityRange: Int, seed: Long): List<Note> {
        val random = Random(seed)
        return notes.map { n ->
            val dt = if (timingTicks > 0) random.nextLong(-timingTicks, timingTicks + 1) else 0L
            val dv = if (velocityRange > 0) random.nextInt(-velocityRange, velocityRange + 1) else 0
            n.copy(startTick = (n.startTick + dt).coerceAtLeast(0), velocity = (n.velocity + dv).coerceIn(1, 127))
        }
    }

    /** Pitches moved by [semitones]; a note that would leave 0..127 is dropped rather than clamped, which would bend the chord. */
    fun transpose(notes: List<Note>, semitones: Int): List<Note> =
        notes.mapNotNull { n -> val p = n.pitch + semitones; if (p in 0..127) n.copy(pitch = p) else null }

    /**
     * Each note held to the start of the next later note (any pitch, so a chord's notes all reach
     * the next chord); the last reaches [endTick] when given, else keeps its length.
     */
    fun legato(notes: List<Note>, endTick: Long? = null): List<Note> {
        val starts = notes.map { it.startTick }.distinct().sorted()
        return notes.map { n ->
            val next = starts.firstOrNull { it > n.startTick } ?: endTick
            if (next == null || next <= n.startTick) n else n.copy(lengthTicks = next - n.startTick)
        }
    }

    /** Copies of [notes] at +[offsetTicks] with ids fresh against [taken] and the originals. */
    fun duplicate(notes: List<Note>, offsetTicks: Long, taken: Set<String> = emptySet()): List<Note> {
        val fresh = NoteIds.fresh(taken + notes.map { it.id }, notes.size)
        return notes.mapIndexed { i, n -> n.copy(id = fresh[i], startTick = n.startTick + offsetTicks) }
    }
}

object Arpeggiator {
    enum class Mode { UP, DOWN, UP_DOWN, RANDOM }

    /** The cycle of pitches an arpeggio walks: the chord sorted, through [octaves], in [mode]'s order. RANDOM is a cycle only in name - see [arpeggiate]. */
    fun cycle(chordPitches: List<Int>, mode: Mode, octaves: Int): List<Int> {
        val pool = ArrayList<Int>()
        for (o in 0 until octaves.coerceAtLeast(1)) for (p in chordPitches.distinct().sorted()) { val q = p + 12 * o; if (q <= 127) pool += q }
        return when (mode) {
            Mode.UP, Mode.RANDOM -> pool
            Mode.DOWN -> pool.reversed()
            // Up then down without sounding either end twice, which is what "up-down" means on
            // every hardware arpeggiator: C E G c e g e c G E, then round again.
            Mode.UP_DOWN -> if (pool.size <= 2) pool else pool + pool.subList(1, pool.size - 1).reversed()
        }
    }

    /**
     * Notes every step of [rateDivision] for [lengthTicks], each [gate] of a step long. RANDOM
     * draws each step from the pool with [seed], so the same seed is the same line.
     */
    fun arpeggiate(
        chordPitches: List<Int>, mode: Mode, rateDivision: Int, octaves: Int, lengthTicks: Long,
        velocity: Int = 100, gate: Double = 1.0, channel: Int = 0, seed: Long = 0L
    ): List<Note> {
        val pool = cycle(chordPitches, mode, octaves)
        if (pool.isEmpty() || lengthTicks <= 0) return emptyList()
        val grid = Ticks.perStep(rateDivision)
        val length = (grid * gate.coerceIn(0.05, 1.0)).roundToLong().coerceAtLeast(1)
        val random = Random(seed)
        val out = ArrayList<Note>()
        var tick = 0L
        var i = 0
        while (tick < lengthTicks) {
            val pitch = if (mode == Mode.RANDOM) pool[random.nextInt(pool.size)] else pool[i % pool.size]
            out += Note("arp${i + 1}", pitch, velocity, tick, minOf(length, lengthTicks - tick), channel)
            tick += grid
            i++
        }
        return out
    }
}

/** A pattern placed on the arrangement's timeline, played [repeats] times end to end. */
data class Placement(val patternId: String, val startTick: Long, val repeats: Int = 1) {
    init {
        require(startTick >= 0) { "a placement starts at or after 0" }
        require(repeats >= 1) { "a placement plays at least once" }
    }
}

/** The song: placements over a bank of patterns, flattened to one note list for the player or a MIDI export. */
data class Arrangement(val placements: List<Placement> = emptyList()) {

    fun lengthTicks(patterns: Map<String, Pattern>): Long =
        placements.maxOfOrNull { p -> val pat = patterns[p.patternId] ?: return@maxOfOrNull 0L; p.startTick + pat.lengthTicks * p.repeats } ?: 0L

    /**
     * Every note at its place in the song. A note that hangs past its pattern's end is cut
     * there, as a clip cuts its content, so a repeat never sounds a note under its own first
     * beat. Ids are prefixed by placement and repeat so the same pattern placed twice stays two
     * sets of notes to whoever selects them.
     */
    fun flatten(patterns: Map<String, Pattern>, seed: Long = 0L): List<Note> {
        val out = ArrayList<Note>()
        placements.forEachIndexed { pi, p ->
            val pattern = patterns[p.patternId] ?: return@forEachIndexed
            val notes = pattern.realise(seed)
            for (r in 0 until p.repeats) {
                val base = p.startTick + pattern.lengthTicks * r
                for (n in notes) {
                    if (n.startTick >= pattern.lengthTicks) continue
                    val length = minOf(n.lengthTicks, pattern.lengthTicks - n.startTick)
                    out += n.copy(id = "$pi.$r.${n.id}", startTick = base + n.startTick, lengthTicks = length)
                }
            }
        }
        return out.sortedWith(compareBy({ it.startTick }, { it.pitch }))
    }

    fun flatten(patterns: List<Pattern>, seed: Long = 0L): List<Note> = flatten(patterns.associateBy { it.id }, seed)
}
