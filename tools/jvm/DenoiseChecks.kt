import com.layerbit.stave.engine.dsp.Gain
import com.layerbit.stave.engine.dsp.NoiseProfile
import com.layerbit.stave.engine.dsp.SpectralDenoiser
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

object DenoiseChecks : Suite {
    override val name = "denoise"

    private class Rng(var s: Long) { fun next(): Float { s = s xor (s shl 13); s = s xor (s ushr 7); s = s xor (s shl 17); return ((s ushr 11).toDouble() / (1L shl 53) * 2 - 1).toFloat() } }

    private fun snrDb(signal: FloatArray, reference: FloatArray, from: Int, to: Int): Double {
        var s = 0.0; var e = 0.0
        for (i in from until to) { s += reference[i].toDouble() * reference[i]; val d = signal[i] - reference[i]; e += d.toDouble() * d }
        return 10 * Math.log10(s / e.coerceAtLeast(1e-20))
    }

    override fun run() {
        val rate = 48_000
        val rng = Rng(42)
        section("white noise under a tone, profile learned from a noise-only stretch")
        val tone = FloatArray(rate * 3) { i -> if (i >= rate) (0.3 * sin(2 * PI * 440.0 * i / rate)).toFloat() else 0f }
        val noise = FloatArray(rate * 3) { 0.02f * rng.next() }
        val noisy = FloatArray(tone.size) { tone[it] + noise[it] }
        val denoiser = SpectralDenoiser(rate)
        val profile = NoiseProfile.learn(denoiser.stft, noisy.copyOfRange(0, rate))
        val out = denoiser.process(noisy, profile, reductionDb = 24.0, strength = 1f)
        check(out.size == noisy.size) { "length kept" }
        val before = snrDb(noisy, tone, rate + 4800, 3 * rate - 4800)
        val after = snrDb(out, tone, rate + 4800, 3 * rate - 4800)
        check(after > before + 8.0) { "SNR improved from $before to $after dB" }
        // The noise-only head is quieter by nearly the full reduction.
        val headBefore = Gain.linearToDb(Gain.rms(noisy, 4800, rate - 4800).toDouble())
        val headAfter = Gain.linearToDb(Gain.rms(out, 4800, rate - 4800).toDouble())
        check(headAfter < headBefore - 12.0) { "noise alone went from $headBefore to $headAfter dBFS" }

        section("strength 0 leaves the sound alone")
        val same = denoiser.process(noisy, profile, strength = 0f)
        var worst = 0f
        for (i in 2000 until same.size - 2000) worst = maxOf(worst, kotlin.math.abs(same[i] - noisy[i]))
        check(worst < 1e-3f) { "strength 0 changed samples by $worst" }

        section("the estimated profile from the whole file lands near the learned one")
        val estimated = NoiseProfile.estimate(denoiser.stft, noisy, rate)
        // The median across bins: the tone's bins and their leakage would swamp a mean.
        val ratios = (20 until profile.bins - 20).filter { profile.power[it] > 0f }.map { (estimated.power[it] / profile.power[it]).toDouble() }.sorted()
        val ratio = ratios[ratios.size / 2]
        check(ratio > 0.3 && ratio < 3.0) { "estimate/learned median ratio $ratio" }
        val floor = denoiser.floorDbfs(profile)
        check(floor < -20.0 && floor > -60.0) { "noise floor reads ${floor} dBFS for 0.02 white noise (${Gain.linearToDb(0.02 / sqrt(3.0))})" }
    }
}
