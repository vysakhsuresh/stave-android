package com.layerbit.stave.engine.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.ln

/** The second-order sections every equaliser, meter and repair tool is built from. */
enum class FilterKind { LOW_PASS, HIGH_PASS, BAND_PASS, NOTCH, ALL_PASS, PEAKING, LOW_SHELF, HIGH_SHELF }

/** Normalised coefficients (a0 = 1) of one biquad: y = b0 x + b1 x1 + b2 x2 - a1 y1 - a2 y2. */
class BiquadCoefficients(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {

    /** The magnitude response at [hz] for [sampleRate], as a linear gain. */
    fun magnitude(hz: Double, sampleRate: Int): Double {
        val w = 2.0 * PI * hz / sampleRate
        val cw = cos(w); val sw = sin(w)
        val c2w = cos(2 * w); val s2w = sin(2 * w)
        val nr = b0 + b1 * cw + b2 * c2w
        val ni = -(b1 * sw + b2 * s2w)
        val dr = 1.0 + a1 * cw + a2 * c2w
        val di = -(a1 * sw + a2 * s2w)
        return sqrt((nr * nr + ni * ni) / (dr * dr + di * di))
    }

    fun magnitudeDb(hz: Double, sampleRate: Int): Double = Gain.linearToDb(magnitude(hz, sampleRate))

    companion object {
        val IDENTITY = BiquadCoefficients(1.0, 0.0, 0.0, 0.0, 0.0)

        /**
         * Robert Bristow-Johnson's cookbook, which every audio equaliser quietly is. [gainDb]
         * matters only to the peaking and shelving kinds; [q] to all but the shelves' slope,
         * where it stands in for the shelf's steepness.
         */
        fun of(kind: FilterKind, hz: Double, sampleRate: Int, q: Double = SQRT_HALF, gainDb: Double = 0.0): BiquadCoefficients {
            val f = hz.coerceIn(1.0, sampleRate * 0.499)
            val qq = q.coerceAtLeast(0.025)
            val w0 = 2.0 * PI * f / sampleRate
            val cw = cos(w0)
            val sw = sin(w0)
            val alpha = sw / (2.0 * qq)
            val a = 10.0.pow(gainDb / 40.0)
            var b0: Double; var b1: Double; var b2: Double; var a0: Double; var a1: Double; var a2: Double
            when (kind) {
                FilterKind.LOW_PASS -> { b0 = (1 - cw) / 2; b1 = 1 - cw; b2 = (1 - cw) / 2; a0 = 1 + alpha; a1 = -2 * cw; a2 = 1 - alpha }
                FilterKind.HIGH_PASS -> { b0 = (1 + cw) / 2; b1 = -(1 + cw); b2 = (1 + cw) / 2; a0 = 1 + alpha; a1 = -2 * cw; a2 = 1 - alpha }
                FilterKind.BAND_PASS -> { b0 = alpha; b1 = 0.0; b2 = -alpha; a0 = 1 + alpha; a1 = -2 * cw; a2 = 1 - alpha }
                FilterKind.NOTCH -> { b0 = 1.0; b1 = -2 * cw; b2 = 1.0; a0 = 1 + alpha; a1 = -2 * cw; a2 = 1 - alpha }
                FilterKind.ALL_PASS -> { b0 = 1 - alpha; b1 = -2 * cw; b2 = 1 + alpha; a0 = 1 + alpha; a1 = -2 * cw; a2 = 1 - alpha }
                FilterKind.PEAKING -> return matchedPeaking(f, sampleRate, qq, gainDb)
                FilterKind.LOW_SHELF -> {
                    val s = 2 * sqrt(a) * alpha
                    b0 = a * ((a + 1) - (a - 1) * cw + s); b1 = 2 * a * ((a - 1) - (a + 1) * cw); b2 = a * ((a + 1) - (a - 1) * cw - s)
                    a0 = (a + 1) + (a - 1) * cw + s; a1 = -2 * ((a - 1) + (a + 1) * cw); a2 = (a + 1) + (a - 1) * cw - s
                }
                FilterKind.HIGH_SHELF -> {
                    val s = 2 * sqrt(a) * alpha
                    b0 = a * ((a + 1) + (a - 1) * cw + s); b1 = -2 * a * ((a - 1) + (a + 1) * cw); b2 = a * ((a + 1) + (a - 1) * cw - s)
                    a0 = (a + 1) - (a - 1) * cw + s; a1 = 2 * ((a - 1) - (a + 1) * cw); a2 = (a + 1) - (a - 1) * cw - s
                }
            }
            return BiquadCoefficients(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
        }

        /**
         * A peaking band matched to its analog prototype (M. Vicanek, "Matched Second Order
         * Digital Filters", 2016): the poles are the analog poles mapped exactly (z = e^sT),
         * and the zeros are chosen so the magnitude agrees with the analog band at DC, at the
         * centre and at Nyquist. The cookbook's bilinear transform squeezed a 16 kHz band 5.5 dB
         * off its curve near 19.4 kHz (BiquadChecks measures it).
         */
        private fun matchedPeaking(f0: Double, sampleRate: Int, q: Double, gainDb: Double): BiquadCoefficients {
            if (gainDb == 0.0) return IDENTITY
            val w0 = 2.0 * PI * f0 / sampleRate
            val g = 10.0.pow(gainDb / 20.0)          // the linear gain at the centre
            val zeta = 1.0 / (2.0 * q * sqrt(g))     // the denominator's damping: s^2 + s/(A Q) + 1, A = sqrt(g)
            val a1: Double; val a2: Double
            val decay = kotlin.math.exp(-zeta * w0)
            if (zeta <= 1.0) a1 = -2.0 * decay * cos(sqrt(1.0 - zeta * zeta) * w0)
            else a1 = -2.0 * decay * kotlin.math.cosh(sqrt(zeta * zeta - 1.0) * w0)
            a2 = decay * decay
            val sinHalf = sin(w0 / 2.0)
            val phi1 = sinHalf * sinHalf
            val phi0 = 1.0 - phi1
            val phi2 = 4.0 * phi0 * phi1
            val aa0 = (1.0 + a1 + a2) * (1.0 + a1 + a2)
            val aa1 = (1.0 - a1 + a2) * (1.0 - a1 + a2)
            val aa2 = -4.0 * a2
            val r1 = (aa0 * phi0 + aa1 * phi1 + aa2 * phi2) * g * g
            val r2 = (-aa0 + aa1 + 4.0 * (phi0 - phi1) * aa2) * g * g
            val bb0 = aa0
            val bb2 = (r1 - r2 * phi1 - bb0) / (4.0 * phi1 * phi1)
            val bb1 = r2 + bb0 + 4.0 * (phi1 - phi0) * bb2
            val w = 0.5 * (sqrt(bb0) + sqrt(bb1.coerceAtLeast(0.0)))
            val b0 = 0.5 * (w + sqrt((w * w + bb2).coerceAtLeast(0.0)))
            val b1 = 0.5 * (sqrt(bb0) - sqrt(bb1.coerceAtLeast(0.0)))
            val b2 = -bb2 / (4.0 * b0)
            return BiquadCoefficients(b0, b1, b2, a1, a2)
        }

        /** A peaking band whose width is given in octaves, as a graphic equaliser states it. */
        fun peakingByOctaves(hz: Double, sampleRate: Int, octaves: Double, gainDb: Double): BiquadCoefficients {
            // The analog bandwidth: the matched band keeps it, so the bilinear pre-warp (w0 / sin w0)
            // that once paid back the cookbook's squeeze would now widen every high band.
            val q = 1.0 / (2.0 * sinh(ln(2.0) / 2.0 * octaves))
            return of(FilterKind.PEAKING, hz, sampleRate, q, gainDb)
        }

        /** Butterworth Q: the flattest pass band a single section can have. */
        const val SQRT_HALF = 0.7071067811865476
    }
}

/**
 * One biquad with its state, in transposed direct form II - the form that stays well behaved
 * when its coefficients are changed while it runs, which a fader does all the time.
 */
class Biquad(var coefficients: BiquadCoefficients = BiquadCoefficients.IDENTITY) {
    private var s1 = 0.0
    private var s2 = 0.0

    fun process(x: Float): Float {
        val c = coefficients
        val y = c.b0 * x + s1
        s1 = c.b1 * x - c.a1 * y + s2
        s2 = c.b2 * x - c.a2 * y
        return y.toFloat()
    }

    fun process(buffer: FloatArray, from: Int = 0, to: Int = buffer.size) {
        val c = coefficients
        var l1 = s1; var l2 = s2
        for (i in from until to) {
            val x = buffer[i]
            val y = c.b0 * x + l1
            l1 = c.b1 * x - c.a1 * y + l2
            l2 = c.b2 * x - c.a2 * y
            buffer[i] = y.toFloat()
        }
        s1 = l1; s2 = l2
    }

    fun reset() { s1 = 0.0; s2 = 0.0 }
}

/** Several sections in series, one state each; the response is their product. */
class BiquadCascade(coefficients: List<BiquadCoefficients>) {
    val sections: List<Biquad> = coefficients.map { Biquad(it) }

    fun process(x: Float): Float {
        var y = x
        for (s in sections) y = s.process(y)
        return y
    }

    fun process(buffer: FloatArray, from: Int = 0, to: Int = buffer.size) {
        for (s in sections) s.process(buffer, from, to)
    }

    fun reset() = sections.forEach { it.reset() }

    fun magnitudeDb(hz: Double, sampleRate: Int): Double = sections.sumOf { it.coefficients.magnitudeDb(hz, sampleRate) }
}
