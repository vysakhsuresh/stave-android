package com.layerbit.stave.engine.dsp

import kotlin.math.ln
import kotlin.math.pow

/** One band of a parametric equaliser, as the sheet shows it. */
data class EqBand(
    val kind: FilterKind,
    val hz: Double,
    val gainDb: Double = 0.0,
    val q: Double = BiquadCoefficients.SQRT_HALF,
    val enabled: Boolean = true
) {
    fun coefficients(sampleRate: Int): BiquadCoefficients =
        if (!enabled) BiquadCoefficients.IDENTITY else BiquadCoefficients.of(kind, hz, sampleRate, q, gainDb)
}

/**
 * A parametric equaliser: any number of bands in series, each a biquad, with the curve the
 * sheet draws computed from the same coefficients the audio runs through - so what is drawn is
 * what is heard, to the precision of a double.
 */
class ParametricEq(bands: List<EqBand>, val sampleRate: Int, val channels: Int) {
    var bands: List<EqBand> = bands
        set(value) {
            field = value
            rebuild()
        }

    private var cascades: Array<BiquadCascade> = Array(channels) { BiquadCascade(bands.map { it.coefficients(sampleRate) }) }

    private fun rebuild() {
        // New coefficients, kept state: a band dragged while playing must not click.
        val coefficients = bands.map { it.coefficients(sampleRate) }
        cascades = Array(channels) { c ->
            val old = cascades.getOrNull(c)
            if (old != null && old.sections.size == coefficients.size) {
                old.sections.forEachIndexed { i, s -> s.coefficients = coefficients[i] }
                old
            } else BiquadCascade(coefficients)
        }
    }

    /** Processes interleaved [buffer] in place. */
    fun processInterleaved(buffer: FloatArray, frames: Int = buffer.size / channels) {
        if (bands.none { it.enabled }) return
        for (c in 0 until channels) {
            val cascade = cascades[c]
            var i = c
            for (f in 0 until frames) {
                buffer[i] = cascade.process(buffer[i])
                i += channels
            }
        }
    }

    /** Processes one channel's buffer in place. */
    fun processChannel(channel: Int, buffer: FloatArray, from: Int = 0, to: Int = buffer.size) {
        cascades[channel].process(buffer, from, to)
    }

    fun reset() = cascades.forEach { it.reset() }

    /** The response in dB at [hz]. */
    fun responseDb(hz: Double): Double = bands.filter { it.enabled }.sumOf { it.coefficients(sampleRate).magnitudeDb(hz, sampleRate) }

    /** [points] samples of the response, spaced evenly in log frequency from [fromHz] to [toHz], for drawing. */
    fun curve(points: Int, fromHz: Double = 20.0, toHz: Double = 20_000.0): DoubleArray {
        val out = DoubleArray(points)
        val ratio = ln(toHz / fromHz)
        for (i in 0 until points) {
            val hz = fromHz * Math.E.pow(ratio * i / (points - 1).coerceAtLeast(1))
            out[i] = responseDb(hz)
        }
        return out
    }

    companion object {
        /** The ISO centre frequencies a 10-band graphic equaliser stands on. */
        val ISO_10 = doubleArrayOf(31.25, 62.5, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0)

        /** The 31 third-octave centres of a 31-band graphic equaliser. */
        val ISO_31: DoubleArray = DoubleArray(31) { i -> 20.0 * 2.0.pow(i / 3.0) }

        /** A graphic equaliser as parametric bands: one peaking filter per centre, an octave wide for 10, a third for 31. */
        fun graphic(centres: DoubleArray, gainsDb: DoubleArray): List<EqBand> {
            require(centres.size == gainsDb.size)
            val octaves = if (centres.size <= 10) 1.0 else 1.0 / 3.0
            return centres.indices.map { i ->
                // Q from bandwidth in octaves at the band's centre, the textbook relation.
                val q = 1.0 / (2.0 * Math.sinh(ln(2.0) / 2.0 * octaves))
                EqBand(FilterKind.PEAKING, centres[i], gainsDb[i], q)
            }
        }

        /** A tilt: a low shelf down and a high shelf up by [db] (negative tilts the other way), pivoting at 1 kHz. */
        fun tilt(db: Double): List<EqBand> = listOf(
            EqBand(FilterKind.LOW_SHELF, 1000.0, -db / 2, 0.5),
            EqBand(FilterKind.HIGH_SHELF, 1000.0, db / 2, 0.5)
        )

        /** What a voice usually wants: rumble out, a little body, a little air. */
        val VOICE_PRESET = listOf(
            EqBand(FilterKind.HIGH_PASS, 80.0, q = BiquadCoefficients.SQRT_HALF),
            EqBand(FilterKind.PEAKING, 250.0, -2.0, 1.0),
            EqBand(FilterKind.PEAKING, 3000.0, 2.0, 1.2),
            EqBand(FilterKind.HIGH_SHELF, 10_000.0, 1.5, 0.7)
        )
    }
}
