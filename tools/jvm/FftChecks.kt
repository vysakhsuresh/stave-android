import com.layerbit.stave.engine.dsp.Fft
import com.layerbit.stave.engine.dsp.Stft
import com.layerbit.stave.engine.dsp.Windows
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

object FftChecks : Suite {
    override val name = "fft"

    override fun run() {
        section("agrees with a direct DFT")
        val n = 64
        val fft = Fft(n)
        val re = FloatArray(n) { i -> (sin(2 * PI * 3 * i / n) + 0.5 * cos(2 * PI * 11 * i / n) + 0.1 * i / n).toFloat() }
        val im = FloatArray(n)
        val expectedRe = DoubleArray(n); val expectedIm = DoubleArray(n)
        for (k in 0 until n) for (t in 0 until n) {
            val a = -2 * PI * k * t / n
            expectedRe[k] += re[t] * cos(a); expectedIm[k] += re[t] * sin(a)
        }
        fft.forward(re, im)
        for (k in 0 until n) {
            near(re[k].toDouble(), expectedRe[k], 1e-3, "re[$k]")
            near(im[k].toDouble(), expectedIm[k], 1e-3, "im[$k]")
        }

        section("inverse undoes forward")
        val original = FloatArray(1024) { i -> (sin(0.01 * i * i) * 0.7).toFloat() }
        val r = original.copyOf(); val m = FloatArray(1024)
        val f = Fft.of(1024)
        f.forward(r, m); f.inverse(r, m)
        for (i in original.indices) near(r[i], original[i], 1e-4f, "round trip sample $i")

        section("a tone lands in its bin at its amplitude")
        val size = 4096
        val window = Windows.hann(size)
        // Exactly on a bin centre (40 bins = 468.75 Hz), so no scalloping loss muddies the amplitude.
        val toneHz = 40 * 48_000.0 / size
        val tone = FloatArray(size) { i -> (0.5 * sin(2 * PI * toneHz * i / 48_000)).toFloat() * window[i] }
        val mags = FloatArray(size / 2 + 1)
        Fft.of(size).magnitudes(tone, FloatArray(size), FloatArray(size), mags)
        var best = 0; for (k in mags.indices) if (mags[k] > mags[best]) best = k
        check(best == 40) { "peak bin $best" }
        // Amplitude: magnitude * 2 / (N * coherent gain)
        near(mags[best] * 2.0 / (size * Windows.coherentGain(window)), 0.5, 0.01, "tone amplitude read back")

        section("stft reconstructs exactly when frames are left alone")
        val signal = FloatArray(10_000) { i -> (sin(0.02 * i) * 0.6 + sin(0.3 * i) * 0.2).toFloat() }
        for ((size2, hop) in listOf(1024 to 512, 1024 to 256, 2048 to 512)) {
            val stft = Stft(size2, hop, if (hop * 2 == size2) Windows.sqrtHann(size2) else Windows.hann(size2))
            val out = stft.process(signal) { _, _, _ -> }
            check(out.size == signal.size) { "length kept for $size2/$hop" }
            var err = 0.0
            for (i in signal.indices) err = maxOf(err, kotlin.math.abs(out[i] - signal[i]).toDouble())
            check(err < 1e-4) { "reconstruction error $err for $size2/$hop" }
        }

        section("stft frames count and bin frequencies")
        val s = Stft(2048, 512)
        check(s.frameCount(2048) == 4) { "frames for one window" }
        near(s.binHz(100, 48_000), 100 * 48_000.0 / 2048, 1e-9, "bin hz")
        check(s.binOf(1000.0, 48_000) == (1000.0 * 2048 / 48_000).toInt()) { "bin of" }

        section("windows")
        check(Windows.hann(8)[0] == 0f && Windows.hann(8)[4] == 1f) { "periodic hann" }
        near(Windows.besselI0(0.0), 1.0, 1e-12, "I0(0)")
        near(Windows.besselI0(1.0), 1.2660658777520084, 1e-9, "I0(1)")
        check(Fft.ceilPow2(1000) == 1024 && Fft.ceilPow2(1024) == 1024) { "ceilPow2" }
        check(hypot(3f, 4f) == 5f) { "hypot sanity" }
    }
}
