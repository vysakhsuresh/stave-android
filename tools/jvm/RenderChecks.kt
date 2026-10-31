import com.layerbit.stave.engine.sequencer.DrumInstrument
import com.layerbit.stave.engine.sequencer.Instrument
import com.layerbit.stave.engine.sequencer.Note
import com.layerbit.stave.engine.sequencer.PatternRenderer
import com.layerbit.stave.engine.sequencer.SynthInstrument
import com.layerbit.stave.engine.sequencer.Ticks
import com.layerbit.stave.engine.synth.DrumKit
import com.layerbit.stave.engine.synth.DrumMachine
import com.layerbit.stave.engine.synth.Pad
import com.layerbit.stave.engine.synth.PolySynth
import kotlin.math.abs

/** The pattern renderer: notes land on their sample inside a block, the length is the pattern's plus the tail, the instruments answer. */
object RenderChecks : Suite {
    override val name = "render"

    private const val RATE = 48_000

    /** Writes a single 1.0 at the frame of every note-on and -1.0 at every note-off; otherwise silent. */
    private class Stamp : Instrument {
        var pending = ArrayList<Float>()
        var held = 0
        override fun noteOn(pitch: Int, velocity: Int) { pending += velocity / 127f; held++ }
        override fun noteOff(pitch: Int) { pending += -1f; held-- }
        override fun allNotesOff() { held = 0 }
        override fun render(out: FloatArray, frames: Int) {
            out.fill(0f, 0, frames * 2)
            // Whatever was triggered since the last render lands on this block's first frame: that is the frame the renderer asked for.
            if (pending.isNotEmpty() && frames > 0) { out[0] = pending.sum(); out[1] = pending.size.toFloat(); pending.clear() }
        }
        override val isSounding: Boolean get() = held > 0
    }

    override fun run() {
        section("a note at tick 960 at 120 BPM lands on sample 24000, inside a block, and its off a quarter later")
        val notes = listOf(Note("a", 60, 127, Ticks.PER_QUARTER, Ticks.PER_QUARTER))
        val stamp = Stamp()
        val out = PatternRenderer.render(notes, Ticks.PER_BAR, 120.0, RATE, stamp, maxTailMs = 0)
        val frames = out.size / 2
        check(frames == RATE * 2) { "a bar at 120 is two seconds: $frames frames" }
        fun onsAt(frame: Int) = out[2 * frame]
        near(onsAt(24_000), 1f, 1e-6f, "the on is on sample 24000")
        near(onsAt(48_000), -1f, 1e-6f, "the off is on sample 48000")
        check((0 until frames).count { out[2 * it] != 0f } == 2) { "and nothing else is stamped" }
        check(24_000 % PatternRenderer.BLOCK != 0) { "the fixture's on is not on a block edge (${24_000 % PatternRenderer.BLOCK})" }

        section("two notes on one tick start on one sample; a note's off comes before the next note's on at the same tick")
        val chord = listOf(Note("a", 60, 100, 0, 480), Note("b", 64, 100, 0, 480), Note("c", 60, 100, 480, 480))
        val s2 = Stamp()
        val o2 = PatternRenderer.render(chord, Ticks.PER_QUARTER, 120.0, RATE, s2, maxTailMs = 0)
        near(o2[0], 2 * 100 / 127f, 1e-5f, "both ons on sample 0")
        near(o2[1], 2f, 1e-6f, "two events there")
        // At 12000: two offs (-2) and one on (+100/127), in that order inside one render call.
        near(o2[2 * 12_000], -2f + 100 / 127f, 1e-5f, "offs and the on share sample 12000")
        val events = PatternRenderer.events(chord, 120.0, RATE)
        val at12k = events.filter { it.frame == 12_000L }
        check(at12k.size == 3 && !at12k[0].on && !at12k[1].on && at12k[2].on) { "offs before ons at a shared sample: ${at12k.map { it.on }}" }

        section("the tail: rendering runs on while the instrument sounds, up to the limit, and stops when it goes quiet")
        val ringing = object : Instrument {
            var left = 0
            override fun noteOn(pitch: Int, velocity: Int) { left = RATE / 2 }
            override fun noteOff(pitch: Int) {}
            override fun allNotesOff() { left = 0 }
            override fun render(out: FloatArray, frames: Int) { out.fill(0f, 0, frames * 2); for (i in 0 until frames) if (left > 0) { out[2 * i] = 0.5f; out[2 * i + 1] = 0.5f; left-- } }
            override val isSounding: Boolean get() = left > 0
        }
        val one = listOf(Note("a", 60, 127, 0, 480))
        val withTail = PatternRenderer.render(one, Ticks.PER_QUARTER, 120.0, RATE, ringing, maxTailMs = 4000)
        val tailFrames = withTail.size / 2 - 24_000
        check(tailFrames in 0..PatternRenderer.BLOCK) { "the instrument rang half a second from sample 0 and the render ended ${tailFrames} frames past the quarter: within a block of quiet" }
        val capped = PatternRenderer.render(one, Ticks.PER_QUARTER, 120.0, RATE, ringing.also { it.left = 0 }, maxTailMs = 100)
        check(capped.size / 2 in 24_000 - 1..24_000 + RATE / 10 + PatternRenderer.BLOCK) { "the tail is capped at 100 ms: ${capped.size / 2} frames" }

        section("gain scales the result; an empty note list is the bare length")
        val quiet = PatternRenderer.render(one, Ticks.PER_QUARTER, 120.0, RATE, ringing.also { it.left = 0 }, maxTailMs = 0, gain = 0.5f)
        near(quiet[200], 0.25f, 1e-6f, "half the level")
        check(PatternRenderer.render(emptyList(), Ticks.PER_BAR, 120.0, RATE, Stamp(), maxTailMs = 0).size == RATE * 2 * 2) { "a bar of nothing is a bar long" }
        check(runCatching { PatternRenderer.render(one, Ticks.PER_BAR, 0.0, RATE, Stamp()) }.isFailure) { "zero BPM is refused" }

        section("the drum machine as an instrument: pitch 36 is pad 0, and it reports sounding")
        val click = FloatArray(2000) { 1f }
        val drums = DrumInstrument(DrumMachine(DrumKit.empty().with(0, Pad("Kick", click, RATE)).with(1, Pad("Snare", click, RATE)), RATE))
        val beat = listOf(Note("k", 36, 127, 0, 120), Note("s", 37, 127, Ticks.PER_QUARTER, 120), Note("x", 60, 127, 0, 120))
        val rendered = PatternRenderer.render(beat, Ticks.PER_QUARTER * 2, 120.0, RATE, drums, maxTailMs = 1000)
        check(rendered.size / 2 >= RATE) { "two quarters at 120 is a second: ${rendered.size / 2}" }
        check(abs(rendered[2 * 100]) > 0.3f && abs(rendered[2 * (24_000 + 100)]) > 0.3f) { "the kick on 0 and the snare on 24000 sound" }
        check(abs(rendered[2 * 12_000]) < 1e-6f) { "pitch 60 is no pad and is silent between the hits" }

        section("a synth as an instrument plays and then goes quiet")
        val synth = SynthInstrument(PolySynth(sampleRate = RATE))
        val line = listOf(Note("a", 60, 100, 0, 480), Note("b", 67, 100, 480, 480))
        val tune = PatternRenderer.render(line, Ticks.PER_QUARTER * 2, 120.0, RATE, synth, maxTailMs = 2000)
        check(tune.size / 2 >= 24_000 && tune.copyOfRange(2000, 4000).any { abs(it) > 0.01f }) { "it sounds" }
        check(tune.copyOfRange(tune.size - 200, tune.size).all { abs(it) < 0.05f }) { "and has gone quiet by the end" }
        check(!synth.isSounding) { "nothing left ringing" }
    }
}
