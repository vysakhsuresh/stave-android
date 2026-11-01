import com.layerbit.stave.engine.dsp.FadeShape
import com.layerbit.stave.engine.project.Clip
import com.layerbit.stave.engine.project.EnvelopePoint
import com.layerbit.stave.engine.project.Fade
import com.layerbit.stave.engine.project.History
import com.layerbit.stave.engine.project.Insert
import com.layerbit.stave.engine.project.Marker
import com.layerbit.stave.engine.project.ProjectJson
import com.layerbit.stave.engine.project.Region
import com.layerbit.stave.engine.project.Send
import com.layerbit.stave.engine.project.Session
import com.layerbit.stave.engine.project.SessionEdits
import com.layerbit.stave.engine.project.Source
import com.layerbit.stave.engine.project.TempoEntry
import com.layerbit.stave.engine.project.Track
import com.layerbit.stave.engine.project.ValueTrack
import com.layerbit.stave.engine.project.Warp

object ProjectChecks : Suite {
    override val name = "project"

    private fun full(): Session {
        val src = Source("s1", "Take 1", "media/s1.f32", 48_000, 2, 480_000, Source.Kind.TAKE, null, "take1.wav")
        val src2 = Source("s2", "Song", "media/s2.f32", 44_100, 2, 4_410_000, Source.Kind.AUDIO, null, "song.mp3")
        val bus = Track("b1", "Reverb bus", Track.Kind.BUS)
        val t1 = Track("t1", "Voice", volumeDb = -3.5, pan = -0.25, muted = false, solo = true, armed = true,
            inserts = listOf(Insert("i1", "eq", true, mapOf("mix" to 1.0), listOf(mapOf("kind" to 6.0, "hz" to 1000.0, "gain" to 3.0, "q" to 1.0, "enabled" to 1.0)))),
            sends = listOf(Send("b1", -9.0, true)), outputId = null,
            automation = mapOf("volume" to ValueTrack(listOf(ValueTrack.Point(0, -6.0), ValueTrack.Point(48_000, 0.0)))), colour = 3, lanes = 2, heightDp = 120)
        val t2 = Track("t2", "Music", outputId = "b1")
        val c1 = Clip("c1", "s1", "t1", 10_000, 500, 200_000, "Intro", -2.0, Fade(4800, FadeShape.S_CURVE), Fade(2400, FadeShape.LINEAR), false, true, Warp(1.5, -2.0, false), listOf(EnvelopePoint(0, 0.0), EnvelopePoint(100_000, -12.0)), lane = 1, colour = 7)
        val c2 = Clip("c2", "s2", "t2", 0, 0, 4_800_000)
        val pattern = com.layerbit.stave.engine.sequencer.Pattern("p1", "Beat A", notes = listOf(com.layerbit.stave.engine.sequencer.Note("n1", 36, 110, 0, 240), com.layerbit.stave.engine.sequencer.Note("n2", 42, 80, 480, 240, probability = 0.5, ratchet = 2)), swing = 0.3)
        return Session("My podcast", "voice", 48_000, listOf(src, src2), listOf(t1, t2, bus), listOf(c1, c2),
            listOf(Marker("m1", 96_000, "Chapter 1", Marker.Kind.CHAPTER)), listOf(Region("r1", 0, 48_000, "Intro")),
            listOf(TempoEntry(0, 118.5), TempoEntry(960_000, 140.0, 3)), 0L until 96_000L, 12_345, 1000L until 2000L, "t1", -1.0,
            listOf(Insert("mi", "limiter", true, mapOf("ceiling" to -1.0))),
            patterns = listOf(pattern, pattern.copy(id = "p2", name = "Beat B", notes = emptyList())),
            arrangement = com.layerbit.stave.engine.sequencer.Arrangement(listOf(com.layerbit.stave.engine.sequencer.Placement("p1", 0, 2), com.layerbit.stave.engine.sequencer.Placement("p2", 7680))),
            instruments = mapOf("p1" to "drums:starter", "p2" to "kit:k1"),
            kits = listOf(com.layerbit.stave.engine.synth.PadKit.fromSlices("k1", "Sliced loop", listOf("s1", "s2")).with(2, com.layerbit.stave.engine.synth.PadSpec("s2", "Clap", -3.0, 0.25, -2.0, 1, false))))
    }

    override fun run() {
        section("a session with every field set round-trips through the file")
        val s = full()
        val text = ProjectJson.write(s)
        val back = ProjectJson.read(text)
        check(back == s) { "round trip differs:\n$text" }
        check(ProjectJson.write(back) == text) { "writing again is byte-identical" }
        check(text.contains("\"durationMs\"") && text.contains("\"trackCount\": 2")) { "the summary fields the grid reads are there" }

        section("non-finite numbers are dropped and read as defaults")
        val broken = s.copy(masterVolumeDb = Double.NaN, clips = s.clips.map { it.copy(gainDb = Double.POSITIVE_INFINITY) })
        val fixed = ProjectJson.read(ProjectJson.write(broken))
        check(fixed.masterVolumeDb == 0.0 && fixed.clips.all { it.gainDb == 0.0 }) { "NaN became the default" }

        section("an older or emptier file opens")
        val sparse = ProjectJson.read("{\"name\":\"x\"}")
        check(sparse.name == "x" && sparse.sampleRate == 48_000 && sparse.tempo.size == 1 && sparse.tracks.isEmpty()) { "defaults" }

        section("session queries")
        check(s.length == 4_800_000L && s.durationMs == 100_000L) { "length ${s.length}" }
        check(s.clipAt("t1", 20_000, lane = 1)?.id == "c1" && s.clipAt("t1", 20_000, lane = 0) == null) { "clip at, by lane" }
        check(s.clipsIn(0, 5000).map { it.id } == listOf("c2")) { "clips in a range" }
        check(s.heard(s.track("t1")!!) && !s.heard(s.track("t2")!!) && s.heard(s.track("b1")!!)) { "solo rules" }
        check(s.tempoAt(0).bpm == 118.5 && s.tempoAt(960_001).bpm == 140.0) { "tempo at" }
        val beats = s.beats(0, 48_000 * 2L)
        near(beats[1].toDouble(), 48_000 * 60.0 / 118.5, 1.0, "second beat")
        check(beats.first() == 0L && beats.size == 4) { "four beats in two seconds at 118.5: ${beats.size}" }

        section("automation value track")
        val v = ValueTrack(listOf(ValueTrack.Point(100, 0.0), ValueTrack.Point(300, 10.0)))
        near(v.valueAt(0, 5.0), 0.0, 1e-9, "holds before"); near(v.valueAt(200, 5.0), 5.0, 1e-9, "midway"); near(v.valueAt(999, 5.0), 10.0, 1e-9, "holds after")
        near(ValueTrack().valueAt(0, 7.0), 7.0, 1e-9, "empty is the default")
        check(v.cut(150, 250).points.map { it.position } == listOf(100L, 200L)) { "cut moves the later point back by the gap" }
        check(v.shifted(200, 50).points.map { it.position } == listOf(100L, 350L)) { "shift" }

        section("history: steps, gestures, late results")
        val h = History(Session(name = "a"))
        h.record(h.current.copy(name = "b"), "rename")
        h.record(h.current.copy(playhead = 1), "drag", gestureId = "g1")
        h.record(h.current.copy(playhead = 2), "drag", gestureId = "g1")
        h.record(h.current.copy(playhead = 3), "drag", gestureId = "g1")
        check(h.depth == 2 && h.current.playhead == 3L) { "a gesture is one step: depth ${h.depth}" }
        h.recordLate("render") { it.copy(kind = "rendered") }
        check(h.depth == 2 && h.current.kind == "rendered") { "late result went under the gesture" }
        check(h.undo() && h.current.playhead == 0L && h.current.kind == "rendered" && h.current.name == "b") { "undo the drag keeps the render: ${h.current}" }
        check(h.undo() && h.current.name == "a") { "undo the rename" }
        check(!h.undo()) { "nothing more" }
        check(h.redo() && h.current.name == "b" && h.redoLabel == "drag") { "redo" }
        h.record(h.current.copy(name = "c"), "rename again")
        check(!h.canRedo) { "a new step clears the future" }
        h.amendAll { it.copy(sampleRate = 96_000) }
        check(h.current.sampleRate == 96_000) { "amendAll reaches the present" }
        check(h.undo() && h.current.sampleRate == 96_000) { "and the past" }
        val same = History(Session())
        same.record(same.current, "no change")
        check(same.depth == 0) { "recording an equal session is not a step" }
        val capped = History(Session(), limit = 5)
        for (i in 1..10) capped.record(capped.current.copy(playhead = i.toLong()), "p$i")
        check(capped.depth == 5) { "capped at 5: ${capped.depth}" }

        section("the undo history survives a save and a reopen, and only onto its own session")
        val live = History(Session(name = "a"))
        live.record(Session(name = "b"), "Rename")
        live.record(Session(name = "c"), "Rename again")
        live.undo()
        val historyText = com.layerbit.stave.engine.project.HistoryJson.write(live)
        val saved = com.layerbit.stave.engine.project.HistoryJson.read(historyText)!!
        val reopened = History(Session(name = "b"))
        check(reopened.restore(saved.current, saved.past, saved.future)) { "restores onto the session it was saved with" }
        check(reopened.undoLabel == "Rename" && reopened.redoLabel == "Rename again") { "labels: ${reopened.undoLabel} / ${reopened.redoLabel}" }
        check(reopened.undo() && reopened.current.name == "a") { "undo after reopening goes back: ${reopened.current.name}" }
        check(reopened.redo() && reopened.redo() && reopened.current.name == "c") { "redo runs forward again: ${reopened.current.name}" }
        val other = History(Session(name = "z"))
        check(!other.restore(saved.current, saved.past, saved.future) && !other.canUndo) { "a history saved for another session is refused" }
        check(com.layerbit.stave.engine.project.HistoryJson.read("not json") == null) { "a broken file reads as nothing" }
    }
}
