package com.layerbit.stave.engine.wave

import kotlin.math.roundToLong

/**
 * The strip's view of time: which samples are on screen, where a moment falls in pixels and
 * back, with the play-head a fixed line and the film moving under it.
 *
 * PLAYHEAD_FRACTION is 0.5 and the drag *subtracts* its travel so a swipe left takes time
 * forward, as in every mobile editor. Squish set this number four times before the comment
 * on it stopped it moving; the lesson is carried here whole: do not reintroduce a walking
 * play-head near the start, because with a line that walks and then holds no one drag sign is
 * right for both halves. The first sample sits at the line with half a screen empty to its
 * left. Executed in TimeWindowChecks.
 */
data class TimeWindow(
    /** Samples per pixel: the zoom. */
    val samplesPerPixel: Double,
    /** The sample under the play-head line. */
    val playhead: Long,
    val widthPx: Int,
    val totalSamples: Long
) {
    val firstSample: Long get() = playhead - (widthPx * PLAYHEAD_FRACTION * samplesPerPixel).roundToLong()
    val lastSample: Long get() = firstSample + (widthPx * samplesPerPixel).roundToLong()

    fun pixelOf(sample: Long): Float = ((sample - firstSample) / samplesPerPixel).toFloat()
    fun sampleAt(px: Float): Long = (firstSample + px * samplesPerPixel).roundToLong()

    /** A drag by [dx] pixels: the film follows the finger, so time under the line goes the other way. */
    fun dragged(dx: Float): TimeWindow = copy(playhead = (playhead - (dx * samplesPerPixel).roundToLong()).coerceIn(0, totalSamples))

    /** A pinch by [factor] about [focusPx]: the sample under the fingers stays under them. */
    fun zoomed(factor: Float, focusPx: Float): TimeWindow {
        val anchor = sampleAt(focusPx)
        val spp = (samplesPerPixel / factor).coerceIn(MIN_SPP, maxSpp())
        val newFirst = anchor - (focusPx * spp).roundToLong()
        val newPlayhead = newFirst + (widthPx * PLAYHEAD_FRACTION * spp).roundToLong()
        return copy(samplesPerPixel = spp, playhead = newPlayhead.coerceIn(0, totalSamples))
    }

    /** The whole file across the width, with a small margin. */
    fun fitted(): TimeWindow {
        val spp = (totalSamples.coerceAtLeast(1) / (widthPx * 0.9)).coerceAtLeast(MIN_SPP)
        return copy(samplesPerPixel = spp, playhead = totalSamples / 2)
    }

    private fun maxSpp(): Double = (totalSamples.coerceAtLeast(widthPx.toLong()) * 4.0 / widthPx).coerceAtLeast(MIN_SPP)

    /** Whether the drawing is finer than a peak bucket, at which point the samples themselves are drawn. */
    val drawsSamples: Boolean get() = samplesPerPixel < Peaks.BASE / 4.0

    companion object {
        const val PLAYHEAD_FRACTION = 0.5
        /** Sixteen pixels a sample is as far in as anyone needs to see a click. */
        const val MIN_SPP = 1.0 / 16

        fun initial(totalSamples: Long, widthPx: Int): TimeWindow =
            TimeWindow(1.0, 0, widthPx.coerceAtLeast(1), totalSamples).fitted().copy(playhead = 0)
    }
}

/**
 * Which ruler ticks to draw at a zoom: a major tick every nice interval of time with at least
 * [minPixels] between labels, minor ticks between. Executed in TimeWindowChecks.
 */
object Ruler {
    private val niceMs = longArrayOf(1, 2, 5, 10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10_000, 15_000, 30_000, 60_000, 120_000, 300_000, 600_000, 1_800_000, 3_600_000)

    /** The interval in ms between labelled ticks. */
    fun majorMs(samplesPerPixel: Double, sampleRate: Int, minPixels: Int = 90): Long {
        val msPerPixel = samplesPerPixel * 1000.0 / sampleRate
        val want = msPerPixel * minPixels
        return niceMs.firstOrNull { it >= want } ?: niceMs.last()
    }

    /** Minor ticks per major: fives for decimal intervals, fours for 1/4-minute ones, threes for 15 s. */
    fun minorPerMajor(majorMs: Long): Int = when {
        majorMs % 60_000L == 0L && majorMs >= 120_000L -> 4
        majorMs == 15_000L || majorMs == 30_000L -> 3
        majorMs % 5 == 0L -> 5
        else -> 2
    }
}
