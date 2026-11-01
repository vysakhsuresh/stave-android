import com.layerbit.stave.engine.wave.Peaks
import com.layerbit.stave.engine.wave.Ruler
import com.layerbit.stave.engine.wave.TimeWindow
import kotlin.math.abs

object PeaksChecks : Suite {
    override val name = "peaks"

    override fun run() {
        val rate = 48_000
        section("a transient survives every zoom")
        val n = rate * 10
        val samples = FloatArray(n) { i -> 0.1f * ((i % 97) / 97f - 0.5f) }
        samples[5 * rate + 17] = 0.95f
        samples[5 * rate + 18] = -0.9f
        val peaks = Peaks.of(samples, rate)
        check(peaks.totalSamples == n.toLong()) { "total" }
        check(peaks.levelCount > 8) { "levels ${peaks.levelCount}" }
        near(peaks.peak, 0.95f, 1e-6f, "file peak")
        for (columns in listOf(10, 100, 1000, 3000)) {
            val cols = peaks.columns(0, n.toLong(), columns)
            check(cols.size == columns) { "column count" }
            val hit = cols.indices.first { abs(it * n.toDouble() / columns - (5 * rate + 17)) <= n.toDouble() / columns }
            check(cols.maxOf { it.max } >= 0.95f - 1e-6f) { "transient max kept at $columns columns" }
            check(cols.minOf { it.min } <= -0.9f + 1e-6f) { "transient min kept at $columns columns" }
            check(cols[hit].max >= 0.9f || cols[(hit + 1).coerceAtMost(columns - 1)].max >= 0.9f) { "and in the right column at $columns" }
        }
        section("outside the audio is silent, rms is sensible")
        val outside = peaks.columns(-rate.toLong(), 0, 10)
        check(outside.all { it.max == 0f && it.min == 0f && it.rms == 0f }) { "before the start" }
        val whole = peaks.columns(0, n.toLong(), 1)[0]
        check(whole.rms > 0.02f && whole.rms < 0.04f) { "rms of a ~0.1 sawtooth ${whole.rms}" }

        section("round trip through floats")
        val back = Peaks.fromFloats(peaks.toFloats())
        check(back.totalSamples == peaks.totalSamples && back.levelCount == peaks.levelCount && back.sampleRate == rate) { "header" }
        val a = peaks.columns(0, n.toLong(), 50); val b = back.columns(0, n.toLong(), 50)
        for (i in a.indices) check(a[i].max == b[i].max && a[i].min == b[i].min && a[i].rms == b[i].rms) { "column $i" }

        section("incremental builder equals one-shot")
        val builder = Peaks.Builder(rate)
        var at = 0
        while (at < n) { val to = minOf(at + 1000, n); builder.add(samples, at, to); at = to }
        val built = builder.build()
        val c1 = built.columns(0, n.toLong(), 77); val c2 = peaks.columns(0, n.toLong(), 77)
        for (i in c1.indices) check(c1[i].max == c2[i].max && c1[i].min == c2[i].min) { "incremental column $i" }

        section("time window: fixed play-head, film under the finger")
        val w = TimeWindow(100.0, 100_000, 1000, n.toLong())
        check(w.firstSample == 100_000L - 50_000L) { "first sample ${w.firstSample}" }
        near(w.pixelOf(100_000).toDouble(), 500.0, 1e-6, "play-head is at the middle")
        check(w.sampleAt(500f) == 100_000L) { "sample at the line" }
        // A swipe left (dx negative) takes time forward.
        check(w.dragged(-100f).playhead == 110_000L) { "swipe left goes forward: ${w.dragged(-100f).playhead}" }
        check(w.dragged(100f).playhead == 90_000L) { "swipe right goes back" }
        check(w.dragged(-1e9f).playhead == n.toLong() && w.dragged(1e9f).playhead == 0L) { "clamped to the file" }
        val z = w.zoomed(2f, 250f)
        near(z.samplesPerPixel, 50.0, 1e-9, "pinch out halves samples per pixel")
        check(z.sampleAt(250f) == w.sampleAt(250f)) { "the sample under the fingers stays: ${z.sampleAt(250f)} vs ${w.sampleAt(250f)}" }
        val fit = w.fitted()
        check(fit.firstSample <= 0 && fit.lastSample >= n) { "fitted shows the whole file" }
        check(TimeWindow.initial(n.toLong(), 1000).playhead == 0L) { "starts at 0" }
        check(TimeWindow(1.0, 0, 1000, n.toLong()).drawsSamples && !w.drawsSamples) { "draws samples when fine" }

        section("ruler")
        check(Ruler.majorMs(100.0, rate) == 200L) { "at 100 spp a label every ${Ruler.majorMs(100.0, rate)} ms" }
        check(Ruler.majorMs(20_000.0, rate) >= 30_000L) { "zoomed out, long intervals" }
        check(Ruler.minorPerMajor(1000) == 5 && Ruler.minorPerMajor(15_000) == 3 && Ruler.minorPerMajor(120_000) == 4) { "minor ticks" }

        section("columnsInto writes exactly what columns returns, at every zoom")
        val rnd = java.util.Random(7)
        val sig = FloatArray(300_000) { (rnd.nextGaussian() * 0.3).toFloat() }
        val pk = com.layerbit.stave.engine.wave.Peaks.of(sig, 48_000)
        for ((from, to, cols) in listOf(Triple(0L, 300_000L, 1000), Triple(1234L, 5678L, 300), Triple(-5000L, 400_000L, 777), Triple(100L, 101L, 50))) {
            val a = pk.columns(from, to, cols); val b = FloatArray(cols * 3); pk.columnsInto(from, to, cols, b)
            for (i in 0 until cols) check(a[i].min == b[i * 3] && a[i].max == b[i * 3 + 1] && a[i].rms == b[i * 3 + 2]) { "column $i of $from..$to/$cols differs" }
        }
    }
}
