package com.layerbit.stave.engine.sequencer

import com.layerbit.stave.engine.json.JsonArray
import com.layerbit.stave.engine.json.JsonObject

/**
 * The sequencer's part of the project file, by name and by hand like ProjectJson. Every float
 * (swing, probability) goes through putFinite; a missing field reads as the model's default, so
 * a pattern written before a field existed opens as it was.
 */
object SequencerJson {

    fun write(note: Note): JsonObject = JsonObject()
        .put("id", note.id).put("pitch", note.pitch).put("velocity", note.velocity)
        .put("start", note.startTick).put("length", note.lengthTicks).put("channel", note.channel)
        .putFinite("probability", note.probability).put("ratchet", note.ratchet)

    fun readNote(o: JsonObject): Note = Note(
        id = o.string("id") ?: "",
        pitch = o.int("pitch", 60).coerceIn(0, 127),
        velocity = o.int("velocity", 100).coerceIn(1, 127),
        startTick = o.long("start").coerceAtLeast(0),
        lengthTicks = o.long("length", Ticks.PER_QUARTER).coerceAtLeast(1),
        channel = o.int("channel").coerceIn(0, 15),
        probability = o.double("probability", 1.0).coerceIn(0.0, 1.0),
        ratchet = o.int("ratchet", 1).coerceIn(1, Note.MAX_RATCHET)
    )

    fun write(pattern: Pattern): JsonObject = JsonObject()
        .put("id", pattern.id).put("name", pattern.name).put("length", pattern.lengthTicks)
        .putFinite("swing", pattern.swing).put("stepDivision", pattern.stepDivision)
        .put("notes", JsonArray.ofObjects(pattern.notes.map { write(it) }))

    fun readPattern(o: JsonObject): Pattern = Pattern(
        id = o.string("id") ?: "",
        name = o.string("name") ?: "",
        lengthTicks = o.long("length", Ticks.PER_BAR).coerceAtLeast(1),
        notes = o.arr("notes")?.objects()?.map { readNote(it) } ?: emptyList(),
        swing = o.double("swing", 0.0).coerceIn(0.0, 1.0),
        stepDivision = o.int("stepDivision", 16).coerceAtLeast(1)
    )

    fun write(placement: Placement): JsonObject = JsonObject()
        .put("pattern", placement.patternId).put("start", placement.startTick).put("repeats", placement.repeats)

    fun readPlacement(o: JsonObject): Placement =
        Placement(o.string("pattern") ?: "", o.long("start").coerceAtLeast(0), o.int("repeats", 1).coerceAtLeast(1))

    fun write(arrangement: Arrangement): JsonObject =
        JsonObject().put("placements", JsonArray.ofObjects(arrangement.placements.map { write(it) }))

    fun readArrangement(o: JsonObject): Arrangement =
        Arrangement(o.arr("placements")?.objects()?.map { readPlacement(it) } ?: emptyList())
}
