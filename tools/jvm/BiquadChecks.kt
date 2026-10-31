import com.layerbit.stave.engine.dsp.Biquad
import com.layerbit.stave.engine.dsp.BiquadCoefficients
import com.layerbit.stave.engine.dsp.EqBand
import com.layerbit.stave.engine.dsp.FilterKind
import com.layerbit.stave.engine.dsp.ParametricEq
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

object BiquadChecks : Suite {
    override val name = "biquad"

    private fun toneLevelDb(coefficients: BiquadCoefficients, hz: Double, rate: Int): Double {
        val n = rate
        val b = Biquad(coefficients)
        val out = FloatArray(n) { i -> b.process(sin(2 * PI * hz * i / rate).toFloat()) }
        // The level over the second half, past the transient.
        var s = 0.0
        for (i in n / 2 until n) s += out[i].toDouble() * out[i]
        return 20 * kotlin.math.log10(sqrt(s / (n / 2)) * sqrt(2.0))
    }

    override fun run() {
        val rate = 48_000
        section("the computed response matches what the filter does to a tone")
        for (kind in FilterKind.entries) {
            val c = BiquadCoefficients.of(kind, 1000.0, rate, 1.0, 6.0)
            for (hz in listOf(100.0, 1000.0, 5000.0)) {
                // A notch at its own centre is -infinity in theory and -150 in floats; both read as "gone".
                val predicted = c.magnitudeDb(hz, rate).coerceAtLeast(-80.0)
                val measured = toneLevelDb(c, hz, rate).coerceAtLeast(-80.0)
                near(measured, predicted, 0.15, "$kind at $hz Hz")
            }
        }

        section("cookbook facts")
        val lp = BiquadCoefficients.of(FilterKind.LOW_PASS, 1000.0, rate)
        near(lp.magnitudeDb(1000.0, rate), -3.01, 0.05, "Butterworth low-pass is -3 dB at its corner")
        near(lp.magnitudeDb(10.0, rate), 0.0, 0.01, "low-pass passes the lows")
        check(lp.magnitudeDb(10_000.0, rate) < -38.0) { "low-pass is 40 dB down a decade up" }
        val peak = BiquadCoefficients.of(FilterKind.PEAKING, 2000.0, rate, 1.0, 6.0)
        near(peak.magnitudeDb(2000.0, rate), 6.0, 0.01, "peaking gain at centre")
        near(peak.magnitudeDb(20.0, rate), 0.0, 0.01, "peaking leaves the far lows")
        val shelf = BiquadCoefficients.of(FilterKind.HIGH_SHELF, 4000.0, rate, 0.7, -4.0)
        near(shelf.magnitudeDb(20_000.0, rate), -4.0, 0.1, "high shelf reaches its gain")
        near(shelf.magnitudeDb(100.0, rate), 0.0, 0.05, "high shelf leaves the lows")
        val notch = BiquadCoefficients.of(FilterKind.NOTCH, 50.0, rate, 35.0)
        check(notch.magnitudeDb(50.0, rate) < -60.0) { "notch is deep at its centre" }
        check(notch.magnitudeDb(100.0, rate) > -0.1) { "notch leaves the octave above: ${notch.magnitudeDb(100.0, rate)}" }

        section("octave-wide peaking")
        val oct = BiquadCoefficients.peakingByOctaves(1000.0, rate, 1.0, 6.0)
        near(oct.magnitudeDb(1000.0, rate), 6.0, 0.01, "centre")
        // At the octave edges (707 and 1414 Hz) the gain is half, in dB.
        near(oct.magnitudeDb(707.1, rate), 3.0, 0.3, "lower edge")
        near(oct.magnitudeDb(1414.2, rate), 3.0, 0.3, "upper edge")

        section("parametric eq")
        val eq = ParametricEq(ParametricEq.VOICE_PRESET, rate, 2)
        check(eq.responseDb(40.0) < -6.0) { "voice preset cuts rumble" }
        near(eq.responseDb(3000.0), 2.0, 0.6, "voice preset lifts presence")
        val curve = eq.curve(64)
        check(curve.size == 64) { "curve points" }
        // An interleaved stereo frame through a flat eq is untouched.
        val flat = ParametricEq(listOf(EqBand(FilterKind.PEAKING, 1000.0, 0.0)), rate, 2)
        val buffer = FloatArray(400) { (it % 7) / 7f - 0.5f }
        val before = buffer.copyOf()
        flat.processInterleaved(buffer)
        for (i in buffer.indices) near(buffer[i], before[i], 1e-5f, "flat eq sample $i")
        // A disabled band is identity.
        check(EqBand(FilterKind.PEAKING, 1000.0, 12.0, enabled = false).coefficients(rate) === BiquadCoefficients.IDENTITY) { "disabled band" }

        section("graphic eq bands")
        val g = ParametricEq.graphic(ParametricEq.ISO_10, DoubleArray(10) { if (it == 5) 6.0 else 0.0 })
        val geq = ParametricEq(g, rate, 1)
        near(geq.responseDb(1000.0), 6.0, 0.2, "the 1 kHz slider alone")
        check(geq.responseDb(250.0) < 0.8) { "two octaves away the slider has let go: ${geq.responseDb(250.0)}" }
        check(ParametricEq.ISO_31.size == 31 && ParametricEq.ISO_31[0] == 20.0) { "31 bands from 20 Hz" }
    }
}
