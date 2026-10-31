import com.layerbit.stave.engine.dsp.Notes
import com.layerbit.stave.engine.dsp.PitchDetector
import kotlin.math.PI
import kotlin.math.sin

object PitchChecks : Suite {
    override val name = "pitch"

    override fun run() {
        val rate = 48_000
        val d = PitchDetector(rate)
        section("yin finds tones across the range")
        for (hz in listOf(82.41, 110.0, 220.0, 440.0, 659.25, 1046.5)) {
            val frame = FloatArray(2048) { i -> (0.4 * sin(2 * PI * hz * i / rate) + 0.1 * sin(2 * PI * 2 * hz * i / rate)).toFloat() }
            val e = d.detect(frame)
            check(e != null) { "no pitch at $hz" }
            near(e!!.hz, hz, hz * 0.004, "frequency at $hz (within 0.4% ~ 7 cents)")
            check(e.clarity > 0.8f) { "clarity ${e.clarity} at $hz" }
        }
        section("silence and noise give no pitch")
        check(d.detect(FloatArray(2048)) == null) { "silence" }
        var s = 12345L
        val noise = FloatArray(2048) { s = s * 6364136223846793005L + 1442695040888963407L; ((s ushr 40).toInt() / 8_388_608f) - 1f }
        val n = d.detect(noise)
        check(n == null || n.clarity < 0.5f) { "noise read as pitch ${n?.hz} clarity ${n?.clarity}" }

        section("note names")
        check(Notes.name(69) == "A4" && Notes.name(60) == "C4" && Notes.name(61) == "C♯4" && Notes.name(59) == "B3") { "names" }
        near(Notes.midiToHz(69.0), 440.0, 1e-9, "A4")
        near(Notes.hzToMidi(261.6256), 60.0, 1e-3, "C4")
        near(Notes.centsOff(442.0), 7.85, 0.05, "442 is 7.85 cents sharp of A")
        check(Notes.nearest(466.0) == 70) { "nearest to 466 is A#4" }
        near(Notes.midiToHz(69.0, a4 = 432.0), 432.0, 1e-9, "a4 reference")

        section("track and median")
        val vibrato = FloatArray(rate) { i -> sin(2 * PI * (440.0 + 5 * sin(2 * PI * 6 * i / rate)) * i / rate).toFloat() * 0.5f }
        val track = d.track(vibrato)
        check(track.size > 50) { "frames" }
        near(PitchDetector.medianHz(track)!!, 440.0, 8.0, "median through vibrato")
        near(PitchDetector.smoothLog(440.0, 880.0, 0.5), 622.25, 0.1, "log smoothing is the geometric middle")
    }
}
