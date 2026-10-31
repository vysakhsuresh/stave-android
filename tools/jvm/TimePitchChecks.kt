import com.layerbit.stave.engine.dsp.Gain
import com.layerbit.stave.engine.dsp.PitchDetector
import com.layerbit.stave.engine.dsp.TimePitch
import kotlin.math.PI
import kotlin.math.sin

object TimePitchChecks : Suite {
    override val name = "timepitch"

    private fun tone(hz: Double, seconds: Double, rate: Int) = FloatArray((seconds * rate).toInt()) { i -> (0.5 * sin(2 * PI * hz * i / rate)).toFloat() }

    override fun run() {
        val rate = 48_000
        val detector = PitchDetector(rate)
        section("a 2x stretch doubles the length and keeps the pitch")
        val a = tone(440.0, 1.0, rate)
        val stretched = TimePitch.stretch(a, 2.0, rate)
        check(stretched.size == rate * 2) { "length ${stretched.size}" }
        val hz = PitchDetector.medianHz(detector.track(stretched.copyOfRange(rate / 2, 3 * rate / 2)))
        check(hz != null) { "pitch found" }
        near(hz!!, 440.0, 2.0, "pitch after stretch")
        near(Gain.rms(stretched, rate / 2, 3 * rate / 2).toDouble(), 0.5 / Math.sqrt(2.0), 0.04, "level kept")

        section("a 0.5x stretch halves the length")
        val shorter = TimePitch.stretch(a, 0.5, rate)
        check(shorter.size == rate / 2) { "length ${shorter.size}" }
        near(PitchDetector.medianHz(detector.track(shorter.copyOfRange(rate / 8, 3 * rate / 8)))!!, 440.0, 2.0, "pitch after squeeze")

        section("pitch shift up an octave at the same length")
        val up = TimePitch.pitchShift(a, 12.0, rate)
        check(up.size == a.size) { "length kept ${up.size}" }
        near(PitchDetector.medianHz(detector.track(up.copyOfRange(rate / 4, 3 * rate / 4)))!!, 880.0, 5.0, "an octave up")
        val down = TimePitch.pitchShift(a, -7.0, rate)
        near(PitchDetector.medianHz(detector.track(down.copyOfRange(rate / 4, 3 * rate / 4)))!!, 440.0 / Math.pow(2.0, 7.0 / 12), 2.0, "a fifth down")

        section("speed with pitch following")
        val fast = TimePitch.speed(a, 2.0, rate)
        check(Math.abs(fast.size - rate / 2) <= 2) { "half the length ${fast.size}" }
        near(PitchDetector.medianHz(detector.track(fast.copyOfRange(rate / 8, 3 * rate / 8)))!!, 880.0, 5.0, "tape pitch")

        section("identity")
        check(TimePitch.stretch(a, 1.0, rate).contentEquals(a) && TimePitch.pitchShift(a, 0.0, rate).contentEquals(a)) { "ratio 1 copies" }
    }
}
