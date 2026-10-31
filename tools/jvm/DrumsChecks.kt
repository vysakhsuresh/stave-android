import com.layerbit.stave.engine.synth.AdsrSettings
import com.layerbit.stave.engine.synth.DrumKit
import com.layerbit.stave.engine.synth.DrumMachine
import com.layerbit.stave.engine.synth.Pad
import com.layerbit.stave.engine.synth.PadMode
import com.layerbit.stave.engine.synth.Slicer
import kotlin.math.abs

/** The drum sampler and the slicer: slices rejoin, their ends are faded, a choke group stops the other pad. */
object DrumsChecks : Suite {
    override val name = "drums"

    private const val RATE = 48000
    private const val FADE = 96 // 2 ms at 48 kHz

    override fun run() {
        section("slices rejoin to the original length and keep its middle")
        val loop = FloatArray(RATE) { 0.5f + 0.25f * ((it % 100) / 100f) }
        val slices = Slicer.slice(loop, RATE, listOf(12_000L, 24_000L, 36_000L))
        check(slices.size == 4 && slices.sumOf { it.size } == loop.size) { "four slices of ${slices.map { it.size }} rejoin to ${loop.size}" }
        check(slices.all { it.size == 12_000 }) { "at the onsets given" }
        for ((i, s) in slices.withIndex()) for (j in FADE until s.size - FADE) check(s[j] == loop[i * 12_000 + j]) { "slice $i sample $j untouched between the fades" }
        val unsorted = Slicer.slice(loop, RATE, listOf(36_000L, 12_000L, 12_000L, 24_000L, 60_000L, -5L))
        check(unsorted.map { it.size } == listOf(12_000, 12_000, 12_000, 12_000)) { "onsets are sorted, deduplicated and clamped: ${unsorted.map { it.size }}" }
        val even = Slicer.evenSlices(loop, RATE, 7)
        check(even.size == 7 && even.sumOf { it.size } == loop.size && even.map { it.size }.distinct().size <= 2) { "seven even slices: ${even.map { it.size }}" }
        check(Slicer.slice(FloatArray(0), RATE, listOf(5L)).isEmpty() && Slicer.evenSlices(loop, RATE, 1).single().size == loop.size) { "edges" }

        section("a slice's first and last 2 ms are faded, linearly, from and to silence")
        val s = slices[1]
        check(s[0] == 0f) { "starts at silence" }
        near(s[FADE / 2], loop[12_000 + FADE / 2] * 0.5f, 1e-5f, "half way up at 1 ms")
        check(s[FADE] == loop[12_000 + FADE]) { "full at 2 ms" }
        check(abs(s[s.size - 1]) <= loop[24_000 - 1] / FADE + 1e-6f) { "the last sample is one step from silence: ${s[s.size - 1]}" }
        near(s[s.size - FADE / 2], loop[24_000 - FADE / 2] * 0.5f, 1e-5f, "half way down at 1 ms before the end")
        check(s[s.size - FADE - 1] == loop[24_000 - FADE - 1]) { "full before the fade out" }
        val tiny = Slicer.slice(FloatArray(50) { 1f }, RATE, emptyList()).single()
        check(tiny[0] == 0f && tiny[25] == 1f && tiny[49] < 0.05f) { "a slice shorter than two fades still peaks in its middle: ${tiny.toList()}" }

        section("a kit from slices, and the kit's shape")
        val kit = DrumKit.fromSlices(slices, RATE)
        check(kit.pads.size == DrumKit.PADS && kit.pads.take(4).none { it.isEmpty } && kit.pads.drop(4).all { it.isEmpty }) { "four pads filled" }
        check(kit.pads[0].sample!!.contentEquals(slices[0]) && kit.pads[0] == Pad("Slice 1", slices[0].copyOf(), RATE)) { "a pad compares by its frames" }
        check(runCatching { DrumKit("k", emptyList()) }.isFailure) { "a kit is sixteen pads" }
        check(DrumKit.empty().with(3, Pad("Clap")).pads[3].name == "Clap") { "with()" }

        section("a pad plays its sample at its gain and pan; a one-shot plays through a release")
        val click = FloatArray(1000) { 1f }
        val machine = DrumMachine(DrumKit.empty().with(0, Pad("Kick", click, RATE, gainDb = -6.0206, pan = 1.0)), RATE)
        val out = FloatArray(2 * 500)
        machine.trigger(0)
        machine.release(0)
        machine.render(out, 500)
        near(out[2 * 100], 0f, 1e-6f, "hard right: left is silent")
        near(out[2 * 100 + 1], 0.5f, 1e-3f, "-6 dB on the right")
        check(machine.isSounding(0)) { "a one-shot keeps going after release" }
        machine.render(out, 500); machine.render(out, 500)
        check(!machine.isSounding(0) && out.all { it == 0f }) { "and stops at its end" }
        val gated = DrumMachine(DrumKit.empty().with(0, Pad("Pad", click, RATE, mode = PadMode.GATE, envelope = AdsrSettings(0.0, 0.0, 1.0, 1.0))), RATE)
        gated.trigger(0); gated.render(out, 10); gated.release(0); gated.render(out, 200)
        check(!gated.isSounding(0)) { "a gated pad releases" }
        val loopPad = DrumMachine(DrumKit.empty().with(0, Pad("Loop", FloatArray(100) { 1f }, RATE, mode = PadMode.LOOP)), RATE)
        loopPad.trigger(0); loopPad.render(out, 500)
        check(loopPad.isSounding(0) && out[2 * 499] > 0.5f) { "a loop pad goes round past its length" }
        val quiet = DrumMachine(DrumKit.empty().with(0, Pad("Kick", click, RATE)), RATE)
        quiet.trigger(0, 64); quiet.render(out, 10)
        near(quiet.let { out[2 * 5] }.toDouble(), 64.0 / 127 * 0.7071, 0.01, "velocity scales the hit, centre pan at -3 dB")

        section("a choke group stops the other pad within 2 ms; a pad outside the group keeps going")
        val hats = DrumKit.empty()
            .with(0, Pad("Open hat", FloatArray(RATE) { 1f }, RATE, chokeGroup = 1))
            .with(1, Pad("Closed hat", FloatArray(RATE) { 0f }, RATE, chokeGroup = 1))
            .with(2, Pad("Ride", FloatArray(RATE) { 1f }, RATE, chokeGroup = 2))
        val m = DrumMachine(hats, RATE)
        m.trigger(0); m.trigger(2)
        m.render(out, 100)
        near(out[2 * 50], 2 * 0.7071f, 1e-3f, "the open hat and the ride both sound")
        m.trigger(1)
        m.render(out, 200)
        check(out[0] > 1.2f && out[2 * 199] < 0.72f && out[2 * 199] > 0.70f) { "the open hat fades out over the first 2 ms, the ride alone remains: ${out[0]} .. ${out[2 * 199]}" }
        check(!m.isSounding(0) && m.isSounding(2)) { "the open hat is stopped; the ride, in another group, is not" }
        var monotone = true
        for (i in 1 until 96) if (out[2 * i] > out[2 * (i - 1)] + 1e-6f) monotone = false
        check(monotone) { "the choke is a fade, not a cut" }
        m.trigger(0); m.render(out, 10); m.trigger(0); m.render(out, 200)
        check(m.isSounding(0) && out[2 * 199] > 1.4f) { "the same pad hit again restarts and sounds" }
    }
}
