package com.layerbit.stave.engine.project

import com.layerbit.stave.engine.dsp.FadeShape
import com.layerbit.stave.engine.json.Json
import com.layerbit.stave.engine.json.JsonArray
import com.layerbit.stave.engine.json.JsonObject
import com.layerbit.stave.engine.sequencer.Arrangement
import com.layerbit.stave.engine.sequencer.SequencerJson

/**
 * The project file. Hand-written both ways; every float through putFinite; every field read
 * with a default so a file from an older build opens. The version is written and read but not
 * yet branched on: the first time a field changes meaning, the branch goes here with a note.
 *
 * Executed in ProjectJsonChecks: a session with every field set round-trips equal.
 */
object ProjectJson {

    fun encode(s: Session): JsonObject = JsonObject()
        .put("version", s.version)
        .put("name", s.name)
        .put("kind", s.kind)
        .put("sampleRate", s.sampleRate)
        .put("durationMs", s.durationMs)
        .put("trackCount", s.audioTracks.size)
        .put("sources", JsonArray.ofObjects(s.sources.map(::source)))
        .put("tracks", JsonArray.ofObjects(s.tracks.map(::track)))
        .put("clips", JsonArray.ofObjects(s.clips.map(::clip)))
        .put("markers", JsonArray.ofObjects(s.markers.map { JsonObject().put("id", it.id).put("position", it.position).put("name", it.name).put("kind", it.kind.name) }))
        .put("regions", JsonArray.ofObjects(s.regions.map { JsonObject().put("id", it.id).put("start", it.start).put("end", it.end).put("name", it.name) }))
        .put("tempo", JsonArray.ofObjects(s.tempo.map { JsonObject().put("position", it.position).putFinite("bpm", it.bpm).put("beatsPerBar", it.beatsPerBar) }))
        .put("loop", s.loop?.let { JsonObject().put("start", it.first).put("end", it.last + 1) })
        .put("playhead", s.playhead)
        .put("selection", s.selection?.let { JsonObject().put("start", it.first).put("end", it.last + 1) })
        .put("selectedTrack", s.selectedTrackId)
        .putFinite("masterVolumeDb", s.masterVolumeDb)
        .put("masterInserts", JsonArray.ofObjects(s.masterInserts.map(::insert)))
        .put("patterns", JsonArray.ofObjects(s.patterns.map { SequencerJson.write(it) }))
        .put("arrangement", SequencerJson.write(s.arrangement))
        .put("instruments", JsonObject().also { o -> for ((id, instrument) in s.instruments.toSortedMap()) o.put(id, instrument) })
        .put("kits", JsonArray.ofObjects(s.kits.map { SequencerJson.write(it) }))

    fun decode(o: JsonObject): Session = Session(
        name = o.string("name") ?: "",
        kind = o.string("kind") ?: "edit",
        sampleRate = o.int("sampleRate", 48_000),
        sources = o.arr("sources")?.objects()?.map(::sourceOf) ?: emptyList(),
        tracks = o.arr("tracks")?.objects()?.map(::trackOf) ?: emptyList(),
        clips = o.arr("clips")?.objects()?.map(::clipOf) ?: emptyList(),
        markers = o.arr("markers")?.objects()?.map { Marker(it.string("id") ?: Ids.next("m"), it.long("position"), it.string("name") ?: "", enumOr(it.string("kind"), Marker.Kind.MARKER)) } ?: emptyList(),
        regions = o.arr("regions")?.objects()?.map { Region(it.string("id") ?: Ids.next("r"), it.long("start"), it.long("end"), it.string("name") ?: "") } ?: emptyList(),
        tempo = o.arr("tempo")?.objects()?.map { TempoEntry(it.long("position"), it.double("bpm", 120.0), it.int("beatsPerBar", 4)) }?.takeIf { it.isNotEmpty() } ?: listOf(TempoEntry(0, 120.0)),
        loop = o.obj("loop")?.let { it.long("start") until it.long("end") },
        playhead = o.long("playhead"),
        selection = o.obj("selection")?.let { it.long("start") until it.long("end") },
        selectedTrackId = o.string("selectedTrack"),
        masterVolumeDb = o.double("masterVolumeDb", 0.0),
        masterInserts = o.arr("masterInserts")?.objects()?.map(::insertOf) ?: emptyList(),
        patterns = o.arr("patterns")?.objects()?.map { SequencerJson.readPattern(it) }?.filter { it.id.isNotEmpty() } ?: emptyList(),
        arrangement = o.obj("arrangement")?.let { SequencerJson.readArrangement(it) } ?: Arrangement(),
        instruments = o.obj("instruments")?.let { io -> io.names().mapNotNull { k -> io.string(k)?.let { k to it } }.toMap() } ?: emptyMap(),
        kits = o.arr("kits")?.objects()?.map { SequencerJson.readKit(it) }?.filter { it.id.isNotEmpty() } ?: emptyList(),
        version = o.int("version", 1)
    )

    fun write(s: Session): String = Json.write(encode(s), pretty = true)
    fun read(text: String): Session = decode(Json.parseObject(text))

    private fun source(s: Source) = JsonObject().put("id", s.id).put("name", s.name).put("file", s.file).put("sampleRate", s.sampleRate)
        .put("channels", s.channels).put("frames", s.frames).put("kind", s.kind.name).put("parent", s.parentId).put("origin", s.origin)

    private fun sourceOf(o: JsonObject) = Source(o.string("id") ?: Ids.next("s"), o.string("name") ?: "", o.string("file") ?: "", o.int("sampleRate", 48_000),
        o.int("channels", 2), o.long("frames"), enumOr(o.string("kind"), Source.Kind.AUDIO), o.string("parent"), o.string("origin"))

    private fun fade(f: Fade) = JsonObject().put("length", f.length).put("shape", f.shape.name)
    private fun fadeOf(o: JsonObject?) = if (o == null) Fade() else Fade(o.long("length"), enumOr(o.string("shape"), FadeShape.EQUAL_POWER))

    private fun clip(c: Clip) = JsonObject().put("id", c.id).put("source", c.sourceId).put("track", c.trackId).put("position", c.position)
        .put("offset", c.offset).put("length", c.length).put("name", c.name).putFinite("gainDb", c.gainDb)
        .put("fadeIn", fade(c.fadeIn)).put("fadeOut", fade(c.fadeOut)).put("muted", c.muted).put("reversed", c.reversed)
        .put("warp", JsonObject().putFinite("stretch", c.warp.stretch).putFinite("semitones", c.warp.semitones).put("formant", c.warp.preserveFormant))
        .put("envelope", JsonArray().also { a -> c.envelope.forEach { a.add(JsonArray().add(it.position).addFinite(it.gainDb)) } })
        .put("lane", c.lane).put("colour", c.colour).putFinite("rateRatio", c.sourceRateRatio)

    private fun clipOf(o: JsonObject): Clip {
        val warp = o.obj("warp")
        return Clip(
            id = o.string("id") ?: Ids.next("c"), sourceId = o.string("source") ?: "", trackId = o.string("track") ?: "",
            position = o.long("position"), offset = o.long("offset"), length = o.long("length"), name = o.string("name") ?: "",
            gainDb = o.double("gainDb", 0.0), fadeIn = fadeOf(o.obj("fadeIn")), fadeOut = fadeOf(o.obj("fadeOut")),
            muted = o.bool("muted"), reversed = o.bool("reversed"),
            warp = if (warp == null) Warp() else Warp(warp.double("stretch", 1.0), warp.double("semitones", 0.0), warp.bool("formant", true)),
            envelope = o.arr("envelope")?.let { a -> (0 until a.size).mapNotNull { i -> a.arr(i)?.let { p -> EnvelopePoint(p.long(0), p.double(1)) } } } ?: emptyList(),
            lane = o.int("lane"), colour = o.int("colour"), sourceRateRatio = o.double("rateRatio", 1.0)
        )
    }

    private fun insert(i: Insert) = JsonObject().put("id", i.id).put("kind", i.kind).put("enabled", i.enabled)
        .put("params", JsonObject().also { p -> i.params.forEach { (k, v) -> p.putFinite(k, v) } })
        .put("bands", JsonArray.ofObjects(i.bands.map { b -> JsonObject().also { o -> b.forEach { (k, v) -> o.putFinite(k, v) } } }))

    private fun insertOf(o: JsonObject): Insert {
        val params = o.obj("params")?.let { p -> p.keys.associateWith { p.double(it) } } ?: emptyMap()
        val bands = o.arr("bands")?.objects()?.map { b -> b.keys.associateWith { b.double(it) } } ?: emptyList()
        return Insert(o.string("id") ?: Ids.next("i"), o.string("kind") ?: "", o.bool("enabled", true), params, bands)
    }

    private fun valueTrack(v: ValueTrack) = JsonArray().also { a -> v.sorted.forEach { a.add(JsonArray().add(it.position).addFinite(it.value)) } }
    private fun valueTrackOf(a: JsonArray?): ValueTrack = if (a == null) ValueTrack() else ValueTrack((0 until a.size).mapNotNull { i -> a.arr(i)?.let { p -> ValueTrack.Point(p.long(0), p.double(1)) } })

    private fun track(t: Track) = JsonObject().put("id", t.id).put("name", t.name).put("kind", t.kind.name).putFinite("volumeDb", t.volumeDb)
        .putFinite("pan", t.pan).put("muted", t.muted).put("solo", t.solo).put("armed", t.armed)
        .put("inserts", JsonArray.ofObjects(t.inserts.map(::insert)))
        .put("sends", JsonArray.ofObjects(t.sends.map { JsonObject().put("to", it.toTrackId).putFinite("gainDb", it.gainDb).put("pre", it.preFader) }))
        .put("output", t.outputId)
        .put("automation", JsonObject().also { a -> t.automation.forEach { (k, v) -> if (!v.isEmpty) a.put(k, valueTrack(v)) } })
        .put("colour", t.colour).put("lanes", t.lanes).put("heightDp", t.heightDp)

    private fun trackOf(o: JsonObject): Track {
        val auto = o.obj("automation")
        return Track(
            id = o.string("id") ?: Ids.next("t"), name = o.string("name") ?: "", kind = enumOr(o.string("kind"), Track.Kind.AUDIO),
            volumeDb = o.double("volumeDb", 0.0), pan = o.double("pan", 0.0), muted = o.bool("muted"), solo = o.bool("solo"), armed = o.bool("armed"),
            inserts = o.arr("inserts")?.objects()?.map(::insertOf) ?: emptyList(),
            sends = o.arr("sends")?.objects()?.map { Send(it.string("to") ?: "", it.double("gainDb", -6.0), it.bool("pre")) } ?: emptyList(),
            outputId = o.string("output"),
            automation = auto?.keys?.associateWith { valueTrackOf(auto.arr(it)) } ?: emptyMap(),
            colour = o.int("colour"), lanes = o.int("lanes", 1), heightDp = o.int("heightDp", 88)
        )
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T = enumValues<T>().firstOrNull { it.name == name } ?: default
}
