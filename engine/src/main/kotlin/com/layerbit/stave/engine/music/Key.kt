package com.layerbit.stave.engine.music

import kotlin.math.sqrt

/** A key, and how well the piece's pitch-class profile fitted it (a correlation, 0..1). */
data class KeyEstimate(val root: Int, val isMinor: Boolean, val confidence: Float) {
    /** "C major", "A minor", "B♭ major". */
    fun name(): String = PitchClasses.name(root) + if (isMinor) " minor" else " major"
}

/**
 * The key, by Krumhansl and Schmuckler: the summed chroma correlated against the
 * listener-rated profile of every major and minor key, the best correlation winning.
 *
 * The profiles are Krumhansl's 1990 probe-tone ratings, which weight the tonic, the
 * fifth and the third above the rest - so a piece that leans on its tonic chord reads
 * right even when its scale is shared with the relative key. Where the tonic is not
 * leaned on, the relative pair is a genuine ambiguity, and [relativeOf] is the one tap
 * that resolves it the other way.
 */
object Key {
    val MAJOR_PROFILE = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
    val MINOR_PROFILE = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)

    fun detect(chroma: List<FloatArray>): KeyEstimate = detect(Chroma.sum(chroma))

    /** The key of one pitch-class profile. Nothing in it reads as C major at no confidence. */
    fun detect(profile: FloatArray): KeyEstimate {
        require(profile.size == 12) { "a profile is twelve pitch classes" }
        var best = KeyEstimate(0, false, 0f)
        var bestR = Double.NEGATIVE_INFINITY
        for (root in 0 until 12) {
            for (minor in listOf(false, true)) {
                val r = pearson(profile, if (minor) MINOR_PROFILE else MAJOR_PROFILE, root)
                if (r > bestR) { bestR = r; best = KeyEstimate(root, minor, r.coerceIn(0.0, 1.0).toFloat()) }
            }
        }
        return if (bestR.isNaN()) KeyEstimate(0, false, 0f) else best
    }

    /** The relative key: the minor a third below a major, the major a third above a minor. Confidence carried over. */
    fun relativeOf(key: KeyEstimate): KeyEstimate =
        if (key.isMinor) KeyEstimate((key.root + 3).mod(12), false, key.confidence)
        else KeyEstimate((key.root + 9).mod(12), true, key.confidence)

    /** Pearson's r between [profile] and [template] rotated so its first entry sits on [root]; NaN when either is flat. */
    private fun pearson(profile: FloatArray, template: DoubleArray, root: Int): Double {
        var meanP = 0.0
        var meanT = 0.0
        for (i in 0 until 12) { meanP += profile[i]; meanT += template[i] }
        meanP /= 12; meanT /= 12
        var cov = 0.0
        var varP = 0.0
        var varT = 0.0
        for (i in 0 until 12) {
            val p = profile[i] - meanP
            val t = template[(i - root).mod(12)] - meanT
            cov += p * t; varP += p * p; varT += t * t
        }
        return cov / sqrt(varP * varT)
    }
}
