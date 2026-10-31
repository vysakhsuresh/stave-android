import com.layerbit.stave.engine.dsp.PitchCorrect
import com.layerbit.stave.engine.dsp.PitchDetector
import kotlin.math.PI
import kotlin.math.sin

object PitchCorrectChecks : Suite {
    override val name = "pitchcorrect"

    override fun run() {
        val rate = 48_000
        val detector = PitchDetector(rate)
        section("scales choose the nearest allowed note")
        val cMajor = PitchCorrect.Scale.major(0)
        check(cMajor.nearest(61.4) == 62 && cMajor.nearest(60.6) == 60) { "C# is not in C major: ${cMajor.nearest(61.4)} ${cMajor.nearest(60.6)}" }
        check(PitchCorrect.Scale.CHROMATIC.nearest(61.4) == 61) { "chromatic takes the semitone" }
        check(PitchCorrect.Scale.minor(9).contains(0) && !PitchCorrect.Scale.minor(9).contains(1)) { "A minor has C, not C#" }
        near(PitchCorrect.centsToScale(440.0 * Math.pow(2.0, 0.3 / 12), PitchCorrect.Scale.CHROMATIC), -30.0, 0.5, "30 cents sharp reads -30 to the note")

        section("a flat A is pulled to 440 at full strength, and halfway at half")
        val flat = 440.0 * Math.pow(2.0, -0.4 / 12)   // 40 cents flat
        val tone = FloatArray(rate) { i -> (0.4 * sin(2 * PI * flat * i / rate)).toFloat() }
        val fixed = PitchCorrect.correct(tone, rate, PitchCorrect.Settings(strength = 1f, retuneMs = 0.0))
        check(fixed.size == tone.size) { "length kept" }
        val hz = PitchDetector.medianHz(detector.track(fixed.copyOfRange(rate / 4, 3 * rate / 4)))!!
        near(hz, 440.0, 2.5, "corrected to A4 within 10 cents")
        val half = PitchCorrect.correct(tone, rate, PitchCorrect.Settings(strength = 0.5f, retuneMs = 0.0))
        val hzHalf = PitchDetector.medianHz(detector.track(half.copyOfRange(rate / 4, 3 * rate / 4)))!!
        near(hzHalf, 440.0 * Math.pow(2.0, -0.2 / 12), 2.5, "half strength goes halfway")
        val level = com.layerbit.stave.engine.dsp.Gain.rms(fixed, rate / 4, 3 * rate / 4)
        near(level.toDouble(), 0.4 / Math.sqrt(2.0), 0.06, "the level survives resynthesis")

        section("an in-tune note is left alone, within tolerance")
        val inTune = FloatArray(rate) { i -> (0.4 * sin(2 * PI * 440.0 * i / rate)).toFloat() }
        val same = PitchCorrect.correct(inTune, rate, PitchCorrect.Settings(toleranceCents = 15.0))
        var worst = 0f
        for (i in 2000 until rate - 2000) worst = maxOf(worst, Math.abs(same[i] - inTune[i]))
        check(worst < 1e-4f) { "an in-tune note changed by $worst" }

        section("silence and noise pass through untouched")
        val silence = PitchCorrect.correct(FloatArray(rate), rate, PitchCorrect.Settings())
        check(silence.all { it == 0f }) { "silence" }
    }
}
