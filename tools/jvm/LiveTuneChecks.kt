import com.layerbit.stave.engine.dsp.LiveTuneRules
import com.layerbit.stave.engine.dsp.PitchCorrect
import kotlin.math.abs

/** The live tuner's rules: the scale mask, the target with hysteresis, the strength in cents, the retune time constant. */
object LiveTuneChecks : Suite {
    override val name = "livetune"

    override fun run() {
        section("masks: chromatic is every bit, C major is seven, the root moves them")
        check(LiveTuneRules.mask(PitchCorrect.Scale.CHROMATIC) == 0xFFF) { "chromatic" }
        val cMajor = LiveTuneRules.mask(PitchCorrect.Scale.major(0))
        check(Integer.bitCount(cMajor) == 7 && (cMajor shr 1) and 1 == 0 && (cMajor shr 4) and 1 == 1) { "C major: ${Integer.toBinaryString(cMajor)}" }
        val dMajor = LiveTuneRules.mask(PitchCorrect.Scale.major(2))
        check((dMajor shr 1) and 1 == 1 && (dMajor shr 0) and 1 == 0) { "D major has C# and not C" }

        section("the target is the nearest allowed note; a sung note between two holds the old one until it is well past the middle")
        check(LiveTuneRules.targetMidi(60.2, 0xFFF, null) == 60 && LiveTuneRules.targetMidi(60.6, 0xFFF, null) == 61) { "chromatic nearest" }
        check(LiveTuneRules.targetMidi(61.0, cMajor, null) in setOf(60, 62)) { "C# in C major goes to a neighbour" }
        check(LiveTuneRules.targetMidi(61.3, cMajor, null) == 62 && LiveTuneRules.targetMidi(60.7, cMajor, null) == 60) { "to the nearer neighbour" }
        // Holding: at 60.55 the nearest is 61, but the voice was on 60 and is within 0.6 of it.
        check(LiveTuneRules.targetMidi(60.55, 0xFFF, 60) == 60) { "held on 60" }
        check(LiveTuneRules.targetMidi(60.7, 0xFFF, 60) == 61) { "let go past the hold" }
        check(LiveTuneRules.targetMidi(60.5, cMajor, 61) != 61) { "a previous target the scale no longer allows is not held" }
        check(LiveTuneRules.targetMidi(66.4, 0, null) == 66) { "an empty mask is chromatic" }

        section("the correction ratio: full strength lands on the target, half strength halves the cents, none leaves it")
        val a = 440.0; val target = LiveTuneRules.midiToHz(69.0)
        near(LiveTuneRules.correctionRatio(a * 1.03, target, 1.0).toFloat(), (1 / 1.03).toFloat(), 1e-5f, "all the way")
        near(LiveTuneRules.correctionRatio(a * 1.03, target, 0.0).toFloat(), 1f, 1e-6f, "none")
        val half = LiveTuneRules.correctionRatio(a * 1.03, target, 0.5)
        val centsFull = 1200 * Math.log(1 / 1.03) / Math.log(2.0); val centsHalf = 1200 * Math.log(half) / Math.log(2.0)
        near(centsHalf.toFloat(), (centsFull / 2).toFloat(), 0.01f, "half the cents")
        check(LiveTuneRules.correctionRatio(0.0, target, 1.0) == 1.0 && LiveTuneRules.correctionRatio(50.0, 2000.0, 1.0) == 2.0) { "no pitch is no change; the ratio is capped at an octave" }

        section("retune: the one-pole reaches 63% in the retune time and is immediate at 0 ms")
        val hop = 256; val rate = 48_000
        val c = LiveTuneRules.smoothing(50.0, hop, rate)
        var r = 1.0; var hops = 0
        while (r < 1.0 + 0.632 * 0.1 && hops < 1000) { r = LiveTuneRules.follow(r, 1.1, c); hops++ }
        val reachedMs = hops * hop * 1000.0 / rate
        check(abs(reachedMs - 50.0) < 6.0) { "63% of the way after $reachedMs ms, not 50" }
        check(LiveTuneRules.smoothing(0.0, hop, rate) == 1.0 && LiveTuneRules.follow(1.0, 1.1, 1.0) == 1.1) { "0 ms is at once" }
        near(LiveTuneRules.follow(1.1, 1.0, 0.5).toFloat(), Math.sqrt(1.1).toFloat(), 1e-6f, "the log domain: half way down is the geometric middle")
        near(LiveTuneRules.hzToMidi(LiveTuneRules.midiToHz(57.0)).toFloat(), 57f, 1e-6f, "hz and midi are inverses")
    }
}
