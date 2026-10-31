package com.layerbit.stave.engine.synth

import com.layerbit.stave.engine.dsp.Biquad
import com.layerbit.stave.engine.dsp.BiquadCoefficients
import com.layerbit.stave.engine.dsp.FilterKind
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * The starter kit, synthesised: sixteen drum sounds made from sine sweeps, filtered noise and
 * short envelopes, so the Beats tab has something to play the moment it opens with no sample
 * pack downloaded and nothing to license. Each sound is a pure function of the sample rate and
 * a seed, so the kit a project was made with is the kit it reopens with.
 *
 * Every sound ends on a fade (its envelope reaches nothing within its length), starts at zero
 * and has no DC, because a pad hit a hundred times a minute turns any click or offset into a
 * rhythm of its own. The levels are set so a hit at full velocity peaks near -3 dBFS and the
 * kick a little above the rest, as on a drum machine's unmixed outputs.
 */
object KitSynth {

    /** The pads in order: the 16-pad layout every drum machine shares, kick bottom left. */
    enum class Sound(val label: String, val chokeGroup: Int = 0) {
        KICK("Kick"), SNARE("Snare"), CLAP("Clap"), RIM("Rim"),
        HAT_CLOSED("Closed hat", 1), HAT_OPEN("Open hat", 1), SHAKER("Shaker"), CRASH("Crash"),
        TOM_LOW("Low tom"), TOM_MID("Mid tom"), TOM_HIGH("High tom"), COWBELL("Cowbell"),
        CLAVE("Clave"), CONGA("Conga"), PERC("Perc"), SUB("Sub")
    }

    /** The whole kit at [sampleRate]. */
    fun kit(sampleRate: Int, name: String = "Starter"): DrumKit =
        DrumKit(name, Sound.entries.map { s -> Pad(s.label, render(s, sampleRate), sampleRate, chokeGroup = s.chokeGroup, mode = PadMode.ONE_SHOT) })

    /** One sound, mono, at [sampleRate]. */
    fun render(sound: Sound, sampleRate: Int): FloatArray = when (sound) {
        Sound.KICK -> kick(sampleRate, startHz = 160.0, endHz = 48.0, lengthMs = 420.0, sweepMs = 35.0, level = 0.95)
        Sound.SUB -> kick(sampleRate, startHz = 90.0, endHz = 40.0, lengthMs = 700.0, sweepMs = 60.0, level = 0.9, click = false)
        Sound.SNARE -> snare(sampleRate)
        Sound.CLAP -> clap(sampleRate)
        Sound.RIM -> rim(sampleRate)
        Sound.HAT_CLOSED -> hat(sampleRate, lengthMs = 70.0, highHz = 7000.0, level = 0.5)
        Sound.HAT_OPEN -> hat(sampleRate, lengthMs = 380.0, highHz = 6500.0, level = 0.45)
        Sound.SHAKER -> noiseBurst(sampleRate, lengthMs = 110.0, attackMs = 12.0, bandHz = 5500.0, q = 1.2, level = 0.4, seed = 11)
        Sound.CRASH -> crash(sampleRate)
        Sound.TOM_LOW -> tom(sampleRate, hz = 95.0, lengthMs = 480.0)
        Sound.TOM_MID -> tom(sampleRate, hz = 150.0, lengthMs = 400.0)
        Sound.TOM_HIGH -> tom(sampleRate, hz = 220.0, lengthMs = 330.0)
        Sound.COWBELL -> cowbell(sampleRate)
        Sound.CLAVE -> decayedSine(sampleRate, hz = 2500.0, lengthMs = 60.0, decayMs = 12.0, level = 0.6)
        Sound.CONGA -> conga(sampleRate)
        Sound.PERC -> perc(sampleRate)
    }

    // ---- The recipes -----------------------------------------------------------------

    /** A sine whose pitch falls from [startHz] to [endHz] over [sweepMs] and whose level decays over the length; a little noise click on top. */
    private fun kick(rate: Int, startHz: Double, endHz: Double, lengthMs: Double, sweepMs: Double, level: Double, click: Boolean = true): FloatArray {
        val n = frames(rate, lengthMs)
        val out = FloatArray(n)
        var phase = 0.0
        val sweepTau = sweepMs / 1000.0 / 3.0
        val ampTau = lengthMs / 1000.0 / 5.5
        for (i in 0 until n) {
            val t = i.toDouble() / rate
            val hz = endHz + (startHz - endHz) * exp(-t / sweepTau)
            phase += hz / rate
            // Soft saturation gives the body a little second harmonic, which is what makes a kick read on a small speaker.
            val body = kotlin.math.tanh(1.6 * sin(2 * PI * phase)) * exp(-t / ampTau)
            out[i] = (body * level).toFloat()
        }
        if (click) {
            val rng = Random(3)
            val clickTau = 0.004
            for (i in 0 until min(n, frames(rate, 20.0))) {
                val t = i.toDouble() / rate
                out[i] += ((rng.nextDouble() * 2 - 1) * 0.3 * exp(-t / clickTau)).toFloat()
            }
        }
        return finish(out, rate)
    }

    /** Two detuned sines for the drum and a band of noise for the wires, each on its own decay. */
    private fun snare(rate: Int): FloatArray {
        val n = frames(rate, 260.0)
        val out = FloatArray(n)
        val rng = Random(7)
        val band = Biquad(BiquadCoefficients.of(FilterKind.BAND_PASS, 3200.0, rate, q = 0.7))
        val high = Biquad(BiquadCoefficients.of(FilterKind.HIGH_PASS, 900.0, rate))
        for (i in 0 until n) {
            val t = i.toDouble() / rate
            val drum = (sin(2 * PI * 185.0 * t) + 0.6 * sin(2 * PI * 330.0 * t)) * exp(-t / 0.045)
            val wires = high.process(band.process(((rng.nextDouble() * 2 - 1)).toFloat())) * exp(-t / 0.075)
            out[i] = (drum * 0.45 + wires * 0.9).toFloat()
        }
        return finish(out, rate)
    }

    /** Four noise bursts a few milliseconds apart, as a room of hands is, then the tail. */
    private fun clap(rate: Int): FloatArray {
        val n = frames(rate, 300.0)
        val out = FloatArray(n)
        val rng = Random(5)
        val band = Biquad(BiquadCoefficients.of(FilterKind.BAND_PASS, 1800.0, rate, q = 0.8))
        val starts = doubleArrayOf(0.0, 0.011, 0.021, 0.032)
        for (i in 0 until n) {
            val t = i.toDouble() / rate
            var env = 0.0
            for ((k, s) in starts.withIndex()) if (t >= s) env += exp(-(t - s) / (if (k == starts.lastIndex) 0.09 else 0.008))
            out[i] = (band.process(((rng.nextDouble() * 2 - 1)).toFloat()) * env * 0.55).toFloat()
        }
        return finish(out, rate)
    }

    private fun rim(rate: Int): FloatArray {
        val n = frames(rate, 90.0)
        val out = FloatArray(n)
        val rng = Random(9)
        val band = Biquad(BiquadCoefficients.of(FilterKind.BAND_PASS, 2400.0, rate, q = 2.5))
        for (i in 0 until n) {
            val t = i.toDouble() / rate
            val ring = (sin(2 * PI * 1700.0 * t) + 0.7 * sin(2 * PI * 2600.0 * t)) * exp(-t / 0.012)
            val stick = band.process(((rng.nextDouble() * 2 - 1)).toFloat()) * exp(-t / 0.006)
            out[i] = (ring * 0.35 + stick * 0.8).toFloat()
        }
        return finish(out, rate)
    }

    /** Six square-ish partials in the 808's ratios, high-passed, under a fast decay: metal. */
    private fun hat(rate: Int, lengthMs: Double, highHz: Double, level: Double): FloatArray {
        val n = frames(rate, lengthMs)
        val out = FloatArray(n)
        val ratios = doubleArrayOf(2.0, 3.0, 4.16, 5.43, 6.79, 8.21)
        val high = Biquad(BiquadCoefficients.of(FilterKind.HIGH_PASS, highHz, rate))
        val tau = lengthMs / 1000.0 / 5.0
        for (i in 0 until n) {
            val t = i.toDouble() / rate
            var s = 0.0
            for (r in ratios) s += if (sin(2 * PI * 205.0 * r * t) >= 0) 1.0 else -1.0
            out[i] = (high.process((s / ratios.size).toFloat()) * exp(-t / tau) * level * 2.2).toFloat()
        }
        return finish(out, rate)
    }

    private fun crash(rate: Int): FloatArray {
        val n = frames(rate, 1400.0)
        val out = FloatArray(n)
        val rng = Random(13)
        val high = Biquad(BiquadCoefficients.of(FilterKind.HIGH_PASS, 3000.0, rate))
        val shimmer = Biquad(BiquadCoefficients.of(FilterKind.PEAKING, 7500.0, rate, q = 1.0, gainDb = 6.0))
        for (i in 0 until n) {
            val t = i.toDouble() / rate
            val env = exp(-t / 0.35) * (1 - exp(-t / 0.004))
            out[i] = (shimmer.process(high.process(((rng.nextDouble() * 2 - 1)).toFloat())) * env * 0.5).toFloat()
        }
        return finish(out, rate)
    }

    private fun tom(rate: Int, hz: Double, lengthMs: Double): FloatArray {
        val n = frames(rate, lengthMs)
        val out = FloatArray(n)
        var phase = 0.0
        val tau = lengthMs / 1000.0 / 5.0
        for (i in 0 until n) {
            val t = i.toDouble() / rate
            val f = hz * (1 + 0.35 * exp(-t / 0.02))
            phase += f / rate
            out[i] = ((sin(2 * PI * phase) + 0.25 * sin(2 * PI * phase * 1.5)) * exp(-t / tau) * 0.7).toFloat()
        }
        return finish(out, rate)
    }

    private fun cowbell(rate: Int): FloatArray {
        val n = frames(rate, 320.0)
        val out = FloatArray(n)
        val band = Biquad(BiquadCoefficients.of(FilterKind.BAND_PASS, 2640.0, rate, q = 3.0))
        for (i in 0 until n) {
            val t = i.toDouble() / rate
            // The 808's two square waves a fifth and a bit apart.
            val a = if (sin(2 * PI * 587.0 * t) >= 0) 1.0 else -1.0
            val b = if (sin(2 * PI * 845.0 * t) >= 0) 1.0 else -1.0
            out[i] = (band.process(((a + b) * 0.5).toFloat()) * exp(-t / 0.08) * 1.2).toFloat()
        }
        return finish(out, rate)
    }

    private fun conga(rate: Int): FloatArray {
        val n = frames(rate, 280.0)
        val out = FloatArray(n)
        var phase = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / rate
            phase += 310.0 * (1 + 0.15 * exp(-t / 0.01)) / rate
            out[i] = ((sin(2 * PI * phase) + 0.3 * sin(2 * PI * phase * 2.3)) * exp(-t / 0.055) * 0.65).toFloat()
        }
        return finish(out, rate)
    }

    private fun perc(rate: Int): FloatArray =
        noiseBurst(rate, lengthMs = 160.0, attackMs = 1.0, bandHz = 1200.0, q = 4.0, level = 0.7, seed = 17)

    private fun decayedSine(rate: Int, hz: Double, lengthMs: Double, decayMs: Double, level: Double): FloatArray {
        val n = frames(rate, lengthMs)
        val out = FloatArray(n)
        for (i in 0 until n) { val t = i.toDouble() / rate; out[i] = (sin(2 * PI * hz * t) * exp(-t / (decayMs / 1000.0)) * level).toFloat() }
        return finish(out, rate)
    }

    private fun noiseBurst(rate: Int, lengthMs: Double, attackMs: Double, bandHz: Double, q: Double, level: Double, seed: Int): FloatArray {
        val n = frames(rate, lengthMs)
        val out = FloatArray(n)
        val rng = Random(seed)
        val band = Biquad(BiquadCoefficients.of(FilterKind.BAND_PASS, bandHz, rate, q = q))
        val tau = lengthMs / 1000.0 / 5.0
        val attack = attackMs / 1000.0
        for (i in 0 until n) {
            val t = i.toDouble() / rate
            val env = exp(-t / tau) * (1 - exp(-t / max(attack, 1e-4)))
            out[i] = (band.process(((rng.nextDouble() * 2 - 1)).toFloat()) * env * level * 2.5).toFloat()
        }
        return finish(out, rate)
    }

    // ---- Shared ----------------------------------------------------------------------

    private fun frames(rate: Int, ms: Double): Int = max(1, (ms / 1000.0 * rate).toInt())

    /**
     * Every sound through the same door: the mean taken off (a one-sided sweep leaves one), the
     * last [FADE_MS] faded to nothing so the loop point of a long sample does not click, the
     * first sample held at zero by a short fade in, and the peak brought to [PEAK].
     */
    private fun finish(samples: FloatArray, rate: Int): FloatArray {
        val n = samples.size
        var mean = 0.0
        for (v in samples) mean += v
        mean /= n
        for (i in 0 until n) samples[i] = (samples[i] - mean).toFloat()
        val fadeIn = min(n / 4, frames(rate, 0.5))
        for (i in 0 until fadeIn) samples[i] *= i.toFloat() / fadeIn
        val fadeOut = min(n / 2, frames(rate, FADE_MS))
        for (i in 0 until fadeOut) { val k = n - 1 - i; samples[k] *= i.toFloat() / fadeOut }
        var peak = 0f
        for (v in samples) peak = max(peak, kotlin.math.abs(v))
        if (peak > 0f) { val g = (PEAK / peak).toFloat(); for (i in 0 until n) samples[i] *= g }
        return samples
    }

    const val FADE_MS = 8.0
    /** -3 dBFS: room for sixteen pads to sum without the master limiter working on every bar. */
    val PEAK = 10.0.pow(-3.0 / 20.0)
}
