import com.layerbit.stave.engine.project.Clip
import com.layerbit.stave.engine.project.EnvelopePoint
import com.layerbit.stave.engine.project.Fade
import com.layerbit.stave.engine.project.Marker
import com.layerbit.stave.engine.project.Session
import com.layerbit.stave.engine.project.SessionEdits
import com.layerbit.stave.engine.project.Source
import com.layerbit.stave.engine.project.Track
import com.layerbit.stave.engine.project.ValueTrack
import com.layerbit.stave.engine.project.Warp

object SessionEditsChecks : Suite {
    override val name = "edits"

    private fun session(): Session {
        val src = Source("s", "src", "media/s.f32", 48_000, 1, 1_000_000)
        val a = Track("a", "A"); val b = Track("b", "B")
        return Session(
            sampleRate = 48_000, sources = listOf(src), tracks = listOf(a, b),
            clips = listOf(
                Clip("c1", "s", "a", 0, 0, 100_000, fadeOut = Fade(5000), envelope = listOf(EnvelopePoint(10_000, -3.0), EnvelopePoint(80_000, -6.0))),
                Clip("c2", "s", "a", 150_000, 200_000, 100_000),
                Clip("c3", "s", "b", 50_000, 0, 300_000)
            ),
            markers = listOf(Marker("m1", 120_000, "m"), Marker("m2", 400_000, "n")),
            playhead = 300_000
        ).let { s -> s.copy(tracks = s.tracks.map { t -> if (t.id == "b") t.copy(automation = mapOf("volume" to ValueTrack(listOf(ValueTrack.Point(60_000, 0.0), ValueTrack.Point(140_000, -6.0), ValueTrack.Point(300_000, 3.0))))) else t }) }
    }

    override fun run() {
        val s = session()
        section("split keeps what plays where")
        val split = SessionEdits.split(s, "c1", 40_000)
        val parts = split.clipsOn("a").filter { it.position < 100_000 }
        check(parts.size == 2) { "two parts" }
        val (p1, p2) = parts
        check(p1.position == 0L && p1.length == 40_000L && p1.offset == 0L && p1.fadeOut.length == 0L) { "first part $p1" }
        check(p2.position == 40_000L && p2.length == 60_000L && p2.offset == 40_000L && p2.fadeIn.length == 0L && p2.fadeOut.length == 5000L) { "second part $p2" }
        check(p1.envelope.map { it.position } == listOf(10_000L) && p2.envelope.map { it.position } == listOf(40_000L)) { "envelope divided and rebased" }
        check(SessionEdits.split(s, "c1", 0) == s && SessionEdits.split(s, "c1", 100_000) == s) { "a split at an edge is nothing" }

        section("split with a stretch moves the offset in source frames")
        val stretched = SessionEdits.replace(s, s.clip("c1")!!.copy(warp = Warp(2.0)))
        val sp = SessionEdits.split(stretched, "c1", 50_000).clipsOn("a").first { it.position == 50_000L }
        check(sp.offset == 25_000L) { "half the timeline distance in source frames: ${sp.offset}" }

        section("trims")
        val headed = SessionEdits.trimStart(s, "c2", 160_000)
        val c2 = headed.clip("c2")!!
        check(c2.position == 160_000L && c2.offset == 210_000L && c2.length == 90_000L) { "head trim moves start and offset: $c2" }
        val unhead = SessionEdits.trimStart(headed, "c2", 100_000)
        check(unhead.clip("c2")!!.position == 100_000L && unhead.clip("c2")!!.offset == 150_000L) { "head trimmed back out, offset follows" }
        check(SessionEdits.trimStart(s, "c1", -10).clip("c1")!!.position == 0L) { "cannot trim before the source's start" }
        val tailed = SessionEdits.trimEnd(s, "c2", 220_000)
        check(tailed.clip("c2")!!.length == 70_000L) { "tail trim" }
        val overlong = SessionEdits.trimEnd(s, "c2", 5_000_000)
        check(overlong.clip("c2")!!.end == 150_000L + (1_000_000L - 200_000L)) { "tail cannot pass the source's end: ${overlong.clip("c2")!!.end}" }
        check(SessionEdits.trimEnd(s, "c1", 0).clip("c1")!!.length == 1L) { "a clip is at least a sample" }

        section("delete a range on every track with ripple")
        val cut = SessionEdits.deleteRange(s, 120_000, 170_000)
        // c1 untouched; c2 (150k-250k) loses its first 20k and moves to 120k; c3 (50k-350k) loses 50k in its middle.
        check(cut.clip("c1") != null) { "c1 kept" }
        val c2c = cut.clipsOn("a").first { it.position >= 100_000 }
        check(c2c.position == 120_000L && c2c.length == 80_000L && c2c.offset == 220_000L) { "c2 trimmed and rippled: $c2c" }
        val c3s = cut.clipsOn("b")
        check(c3s.size == 2 && c3s[0].position == 50_000L && c3s[0].length == 70_000L && c3s[1].position == 120_000L && c3s[1].offset == 120_000L && c3s[1].length == 180_000L) { "c3 split round the gap: $c3s" }
        check(cut.markers.map { it.position } == listOf(350_000L)) { "the marker inside went, the one after moved: ${cut.markers}" }
        check(cut.playhead == 250_000L) { "play-head rippled: ${cut.playhead}" }
        val auto = cut.track("b")!!.automationOf("volume").points.map { it.position }
        check(auto == listOf(60_000L, 250_000L)) { "automation point inside went, later one moved: $auto" }
        check(cut.length == s.length - 50_000) { "the session is shorter by the gap" }

        section("delete a range on one track only, no ripple elsewhere")
        val one = SessionEdits.deleteRange(s, 0, 60_000, trackId = "a")
        check(one.clip("c3")!!.position == 50_000L && one.markers == s.markers) { "track b and markers untouched" }
        val aClips = one.clipsOn("a")
        check(aClips[0].position == 0L && aClips[0].offset == 60_000L && aClips[0].length == 40_000L) { "c1's remainder rippled to the start: ${aClips[0]}" }
        check(aClips[1].position == 90_000L && aClips[1].offset == 200_000L) { "c2 rippled back on track a, its offset untouched: ${aClips[1]}" }
        val noRipple = SessionEdits.deleteRange(s, 0, 60_000, trackId = "a", ripple = false)
        check(noRipple.clipsOn("a").map { it.position } == listOf(60_000L, 150_000L)) { "no ripple leaves a gap: ${noRipple.clipsOn("a").map { it.position }}" }

        section("insert silence")
        val gap = SessionEdits.insertSilence(s, 60_000, 10_000)
        check(gap.clip("c1") == null || gap.clipsOn("a").first().length == 60_000L) { "c1 split at the insertion" }
        check(gap.clipsOn("a")[1].position == 70_000L && gap.clipsOn("b")[1].position == 70_000L && gap.markers.first().position == 130_000L) { "everything after moved" }

        section("copy and paste")
        val board = SessionEdits.copyRange(s, 20_000, 60_000)
        check(board.length == 40_000L && board.clips.size == 2) { "two clips in the range: ${board.clips}" }
        check(board.clips.first { it.trackId == "a" }.position == 0L && board.clips.first { it.trackId == "b" }.position == 30_000L) { "relative positions" }
        val pasted = SessionEdits.paste(s, board, 500_000)
        check(pasted.clipsIn(500_000, 540_000).size == 2 && pasted.clips.size == s.clips.size + 2) { "pasted onto both tracks" }
        val inserted = SessionEdits.paste(s, board, 10_000, insert = true)
        check(inserted.length == s.length + 40_000) { "insert paste opens room" }
        val replaced = SessionEdits.paste(s, board, 0, insert = false)
        check(replaced.length == s.length && replaced.clipsOn("a").first().id != "c1") { "replace paste overwrites what was there" }

        section("crop to a range")
        val cropped = SessionEdits.crop(s, 100_000, 200_000)
        check(cropped.length == 100_000L && cropped.clipsOn("a").first().position == 50_000L && cropped.clipsOn("b").first().position == 0L) { "cropped and rebased: ${cropped.clips}" }

        section("snapping and markers")
        val near = SessionEdits.snap(s, 149_000, 2000, beats = false)
        check(near == 150_000L) { "snaps to a clip edge: $near" }
        check(SessionEdits.snap(s, 130_000, 2000, beats = false) == 130_000L) { "nothing within reach stays put" }
        check(SessionEdits.snap(s, 24_100, 500, beats = true) == 24_000L) { "snaps to the beat at 120 bpm (24 000 samples)" }
        check(SessionEdits.nearestMarker(s, 121_000, 2000)?.id == "m1" && SessionEdits.nearestMarker(s, 200_000, 2000) == null) { "nearest marker" }
        val withMarker = SessionEdits.addMarker(s, 10, "first")
        check(withMarker.markers.first().name == "first") { "markers stay sorted" }

        section("tracks")
        val removed = SessionEdits.removeTrack(s, "a")
        check(removed.tracks.size == 1 && removed.clips.all { it.trackId == "b" }) { "track and its clips gone" }
        val moved = SessionEdits.moveTrack(s, "b", 0)
        check(moved.tracks.first().id == "b") { "reordered" }
        val clip = SessionEdits.clipFor(Source("x", "x", "f", 44_100, 2, 44_100), "a", 0, 48_000)
        check(clip.length == 48_000L) { "a 44.1k second is a 48k second on the timeline: ${clip.length}" }
    }
}
