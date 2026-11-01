import com.layerbit.stave.engine.project.Clip
import com.layerbit.stave.engine.project.Comping
import com.layerbit.stave.engine.project.Session
import com.layerbit.stave.engine.project.Source
import com.layerbit.stave.engine.project.Track

/** Comping by swipe: the stretch is heard from one lane, split at its edges, and nothing outside it changes; Hear all undoes it. */
object CompingChecks : Suite {
    override val name = "comping"

    private fun session(): Session {
        val src = Source("s1", "take", "media/s1.f32", 48_000, 1, 480_000)
        val t = Track("t1", "Voice", lanes = 3)
        val lane0 = Clip("a", "s1", "t1", 0, 0, 480_000, lane = 0)
        val lane1 = Clip("b", "s1", "t1", 0, 0, 480_000, lane = 1)
        val lane2short = Clip("c", "s1", "t1", 200_000, 0, 50_000, lane = 2)
        return Session(sources = listOf(src), tracks = listOf(t), clips = listOf(lane0, lane1, lane2short))
    }

    override fun run() {
        section("use lane 1 over the middle: the others are split there and muted inside only")
        val s = Comping.use(session(), "t1", 1, 100_000, 300_000)
        val on0 = s.clipsOn("t1").filter { it.lane == 0 }
        check(on0.size == 3 && on0.map { it.position } == listOf(0L, 100_000L, 300_000L)) { "lane 0 is split at both edges: ${on0.map { it.position }}" }
        check(on0.map { it.muted } == listOf(false, true, false)) { "only its middle piece is muted: ${on0.map { it.muted }}" }
        val on1 = s.clipsOn("t1").filter { it.lane == 1 }
        check(on1.size == 3 && on1.none { it.muted }) { "lane 1 is split too and heard throughout: ${on1.map { it.muted }}" }
        val on2 = s.clipsOn("t1").filter { it.lane == 2 }
        check(on2.size == 1 && on2[0].muted) { "the short clip wholly inside is muted whole, not split: ${on2.map { it.position to it.muted }}" }
        // The pieces still read the right samples: offsets follow the splits.
        check(on0[1].offset == 100_000L && on0[2].offset == 300_000L) { "offsets follow the split: ${on0.map { it.offset }}" }
        check(s.clips.sumOf { it.length } == 480_000L * 2 + 50_000) { "no sample lost or doubled" }

        section("heardAt reads the lane; a second swipe moves it; Hear all brings every lane back")
        check(Comping.heardAt(s, "t1", 200_000) == 1 && Comping.heardAt(s, "t1", 50_000) == null) { "one lane inside, two heard outside: ${Comping.heardAt(s, "t1", 200_000)} / ${Comping.heardAt(s, "t1", 50_000)}" }
        val moved = Comping.use(s, "t1", 2, 200_000, 250_000)
        check(Comping.heardAt(moved, "t1", 225_000) == 2 && Comping.heardAt(moved, "t1", 150_000) == 1) { "the short lane takes its stretch, lane 1 keeps the rest" }
        val all = Comping.hearAll(moved, "t1")
        check(all.clips.none { it.muted }) { "hear all" }
        check(Comping.use(session(), "t1", 1, 300_000, 100_000) == session()) { "a backwards stretch changes nothing" }

        section("a stretch on a lane with no clip there mutes the others and hears silence - which is what the person asked for")
        val quiet = Comping.use(session(), "t1", 2, 0, 50_000)
        check(Comping.heardAt(quiet, "t1", 25_000) == null && quiet.clipsOn("t1").filter { it.lane == 0 }.first().muted) { "silence chosen: ${quiet.clipsOn("t1").map { Triple(it.lane, it.position, it.muted) }}" }
        check(Comping.lanes(session(), "t1") == listOf(0, 1, 2)) { "lanes" }
    }
}
