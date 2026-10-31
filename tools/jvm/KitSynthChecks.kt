import com.layerbit.stave.engine.synth.DrumKit
import com.layerbit.stave.engine.synth.KitSynth
import kotlin.math.abs

/** The synthesised starter kit: every sound is tidy at both ends, at the level, without DC, and sounds like itself. */
object KitSynthChecks : Suite {
    override val name = "kitsynth"

    private const val RATE = 48_000

    override fun run() {
        section("sixteen sounds, each starting at silence, peaking at -3 dBFS, ending faded, carrying no DC")
        val sounds = KitSynth.Sound.entries.associateWith { KitSynth.render(it, RATE) }
        check(sounds.size == DrumKit.PADS) { "${sounds.size} sounds for ${DrumKit.PADS} pads" }
        for ((s, a) in sounds) {
            check(a.size >= RATE / 20) { "$s is ${a.size} frames: shorter than 50 ms" }
            check(a[0] == 0f) { "$s starts at ${a[0]}" }
            near(a.maxOf { abs(it) }, KitSynth.PEAK.toFloat(), 1e-3f, "$s peaks at -3 dBFS")
            // The last 8 ms are faded to nothing: the final sample is exactly silent (this is the
            // check that bit when the fade was taken out - every sound ended a few thousandths
            // off), and the last millisecond sits well under where the fade starts.
            check(a[a.size - 1] == 0f) { "$s ends at ${a[a.size - 1]}, not silence" }
            val fadeStart = a.copyOfRange(a.size - RATE * 8 / 1000 - 10, a.size - RATE * 8 / 1000 + 10).maxOf { abs(it) }
            val tail = a.copyOfRange(a.size - RATE / 1000, a.size).maxOf { abs(it) }
            check(tail <= fadeStart * 0.5f + 1e-4f) { "$s ends at $tail against $fadeStart where its fade starts" }
            var mean = 0.0; for (v in a) mean += v
            check(abs(mean / a.size) < 1e-3) { "$s carries DC ${mean / a.size}" }
            check(a.none { it.isNaN() || it.isInfinite() }) { "$s has a non-finite sample" }
        }

        section("the kick is low and the hat is high: the spectral centroid orders them")
        fun centroid(a: FloatArray): Double {
            // Zero crossings a second: a cheap centroid, enough to order a kick, a tom and a hat.
            var crossings = 0
            for (i in 1 until a.size) if ((a[i - 1] < 0f) != (a[i] < 0f)) crossings++
            return crossings / 2.0 / (a.size.toDouble() / RATE)
        }
        val kick = centroid(sounds.getValue(KitSynth.Sound.KICK)); val tom = centroid(sounds.getValue(KitSynth.Sound.TOM_LOW)); val hat = centroid(sounds.getValue(KitSynth.Sound.HAT_CLOSED)); val sub = centroid(sounds.getValue(KitSynth.Sound.SUB))
        check(sub < kick && kick < tom && tom < hat) { "sub $sub, kick $kick, tom $tom, hat $hat" }
        check(kick < 200 && hat > 3000) { "kick $kick Hz-ish, hat $hat" }
        check(sounds.getValue(KitSynth.Sound.HAT_OPEN).size > 3 * sounds.getValue(KitSynth.Sound.HAT_CLOSED).size) { "the open hat rings longer than the closed" }

        section("no two sounds are alike, and the same sound is the same twice")
        val list = sounds.values.toList()
        for (i in list.indices) for (j in i + 1 until list.size) {
            val a = list[i]; val b = list[j]
            val n = minOf(a.size, b.size)
            var dot = 0.0; var ea = 0.0; var eb = 0.0
            for (k in 0 until n) { dot += a[k] * b[k]; ea += a[k] * a[k]; eb += b[k] * b[k] }
            val corr = dot / Math.sqrt(ea * eb)
            check(a.size != b.size || corr < 0.9) { "sounds $i and $j correlate at $corr" }
        }
        check(KitSynth.render(KitSynth.Sound.SNARE, RATE).contentEquals(sounds.getValue(KitSynth.Sound.SNARE))) { "rendering again is the same sound" }

        section("the kit: sixteen pads in the layout, hats in one choke group, at 44.1 kHz too")
        val kit = KitSynth.kit(RATE)
        check(kit.pads.size == DrumKit.PADS && kit.pads.none { it.isEmpty }) { "every pad filled" }
        check(kit.pads[0].name == "Kick" && kit.pads[4].name == "Closed hat" && kit.pads[5].name == "Open hat") { "the layout" }
        check(kit.pads[4].chokeGroup == 1 && kit.pads[5].chokeGroup == 1 && kit.pads[0].chokeGroup == 0) { "the hats choke each other and nothing else does" }
        val other = KitSynth.kit(44_100)
        check(other.pads[0].sampleRate == 44_100 && abs(other.pads[0].sample!!.size / 44_100.0 - kit.pads[0].sample!!.size / 48_000.0) < 0.001) { "the same length in seconds at another rate" }
    }
}
