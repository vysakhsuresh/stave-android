import com.layerbit.stave.engine.dsp.Gain
import com.layerbit.stave.engine.dsp.Repair
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

object RepairChecks : Suite {
    override val name = "repair"

    override fun run() {
        val rate = 48_000
        section("a click injected into a tone is found and mended")
        val clean = FloatArray(rate) { i -> (0.5 * sin(2 * PI * 220.0 * i / rate)).toFloat() }
        val damaged = clean.copyOf()
        for (p in listOf(10_000, 20_003, 30_010)) { damaged[p] += 0.6f; damaged[p + 1] -= 0.4f }
        val found = Repair.declick(damaged, sensitivity = 0.5f)
        check(found.size in 3..4) { "found ${found.size} clicks: $found" }
        var worst = 0f
        for (i in 1000 until rate - 1000) worst = maxOf(worst, abs(damaged[i] - clean[i]))
        check(Gain.linearToDb(worst.toDouble()) < -40.0) { "residue after declick ${Gain.linearToDb(worst.toDouble())} dB" }
        section("and a clean tone is left alone")
        val untouched = clean.copyOf()
        val none = Repair.declick(untouched, sensitivity = 0.5f)
        check(none.isEmpty()) { "false positives: $none" }
        for (i in untouched.indices) check(untouched[i] == clean[i]) { "sample $i changed" }

        // On 2 November a 320 kbps song came back from Remove clicks grainy: 7 "clicks" a second,
        // each a real cymbal or consonant attack flattened by up to 0.7 of full scale. The tone
        // above has no attacks, so nothing here had ever asked about music.
        section("music with sharp attacks is left alone")
        val music = band(rate * 2)
        for (s in listOf(0.5f, 0.8f)) {
            val copy = music.copyOf()
            val wrong = Repair.declick(copy, sensitivity = s)
            check(wrong.isEmpty()) { "sensitivity $s mended ${wrong.size} attacks that were meant: ${wrong.take(5)}" }
            for (i in copy.indices) check(copy[i] == music[i]) { "sample $i changed at sensitivity $s" }
        }
        section("and a click in that music is still found")
        val scratched = music.copyOf()
        val at = listOf(7_000, 31_111, 60_500)
        for (p in at) { scratched[p] += 0.5f; scratched[p + 1] -= 0.3f }
        val heard = Repair.declick(scratched, sensitivity = 0.5f)
        for (p in at) check(heard.any { p in it.position - 2..it.position + it.length + 2 }) { "click at $p missed: $heard" }
        check(heard.size <= at.size + 1) { "${heard.size} mends for ${at.size} clicks: $heard" }
        for (p in at) check(abs(scratched[p] - music[p]) < 0.15f) { "click at $p left ${scratched[p] - music[p]}" }

        // A real record, when one is named: stereo 32-bit float, little-endian, interleaved (a
        // project's media/<id>.f32). Nothing from a record is committed, so this runs on a laptop.
        System.getenv("STAVE_RECORD")?.let { path ->
            section("a real record is left alone ($path)")
            val bytes = java.io.File(path).readBytes()
            val fb = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val frames = fb.remaining() / 2
            for (ch in 0..1) {
                val one = FloatArray(frames) { fb.get(it * 2 + ch) }
                val was = one.copyOf()
                val wrong = Repair.declick(one, sensitivity = 0.5f)
                // The old detector made 800 mends in this song; the new one leaves a few single
                // high wiggles a click detector cannot tell from a click, and a cut at the end.
                check(wrong.size <= frames / rate / 30) { "channel $ch: ${wrong.size} mends in ${frames / rate} s of a clean record: ${wrong.take(5)}" }
                var sig = 0.0; var diff = 0.0
                for (k in 0 until frames) { sig += was[k].toDouble() * was[k]; val d = (one[k] - was[k]).toDouble(); diff += d * d }
                val db = 10 * kotlin.math.log10(diff.coerceAtLeast(1e-30) / sig)
                check(db < -60.0) { "channel $ch changed by $db dB" }
                println("      channel $ch: ${wrong.size} mends, change ${"%.1f".format(java.util.Locale.ROOT, db)} dB")
            }
        }

        section("a clipped sine is lifted back towards its peak")
        val full = FloatArray(rate) { i -> (0.9 * sin(2 * PI * 100.0 * i / rate)).toFloat() }
        val clipped = FloatArray(rate) { i -> full[i].coerceIn(-0.6f, 0.6f) }
        val mended = Repair.declip(clipped, thresholdOfFullScale = 0.59f)
        check(mended >= 190) { "mended $mended runs of 200" }
        var before = 0.0; var after = 0.0
        for (i in 2000 until rate - 2000) { before = maxOf(before, abs(full[i].coerceIn(-0.6f, 0.6f) - full[i]).toDouble()); after = maxOf(after, abs(clipped[i] - full[i]).toDouble()) }
        check(after < before * 0.5) { "declip halves the worst error: $after vs $before" }

        section("dc offset")
        val dc = FloatArray(1000) { 0.25f + 0.1f * sin(0.1 * it).toFloat() }
        val removed = Repair.removeDc(dc)
        near(removed.toDouble(), 0.25, 0.002, "offset found")
        var mean = 0.0; for (v in dc) mean += v
        near(mean / dc.size, 0.0, 1e-4, "mean after")

        section("hum found at its frequency and taken out with its harmonics")
        val voice = FloatArray(rate * 2) { i -> (0.3 * sin(2 * PI * 440.0 * i / rate)).toFloat() }
        val hummed = FloatArray(voice.size) { i -> voice[i] + (0.05 * sin(2 * PI * 50.25 * i / rate) + 0.02 * sin(2 * PI * 150.75 * i / rate)).toFloat() }
        val hum = Repair.findHum(hummed, rate)
        check(hum != null) { "hum not found" }
        near(hum!!.first, 50.25, 0.3, "fundamental")
        near(hum.second, Gain.linearToDb(0.05), 1.0, "hum level")
        Repair.dehum(hummed, rate, hum.first, harmonics = 6)
        // Correlate the residue against the hum's sine over the second half.
        var residue = 0.0
        for (i in rate until 2 * rate) residue += hummed[i] * sin(2 * PI * 50.25 * i / rate)
        residue = abs(residue * 2 / rate)
        check(Gain.linearToDb(residue) < Gain.linearToDb(0.05) - 25.0) { "hum residue ${Gain.linearToDb(residue)} dB from ${Gain.linearToDb(0.05)}" }
        var voiceLeft = 0.0
        for (i in rate until 2 * rate) voiceLeft += hummed[i] * sin(2 * PI * 440.0 * i / rate)
        near(voiceLeft * 2 / rate, 0.3, 0.01, "the 440 Hz tone is untouched")
        check(Repair.findHum(voice, rate) == null) { "no hum in a clean tone" }
    }

    /**
     * A band at 48 kHz: a chord with bright partials, a hi-hat every eighth (noise that starts
     * at full level and dies in 30 ms), a snare on the backbeat and a kick.
     * Every attack is abrupt, as on a mastered record, and none of it is a click. The kick starts
     * from zero as a drum sample does: a half-scale jump in one sample cannot survive an encoder's
     * low-pass, and is exactly what a click is.
     */
    private fun band(frames: Int, rate: Int = 48_000): FloatArray {
        var seed = 0x2545F491L
        fun noise(): Float { seed = (seed * 6364136223846793005L + 1442695040888963407L); return ((seed ushr 33).toInt() / 2147483648f) * 2f - 1f }
        val out = FloatArray(frames)
        for (i in 0 until frames) {
            val t = i.toDouble() / rate
            out[i] = (0.12 * sin(2 * PI * 220.0 * t) + 0.08 * sin(2 * PI * 277.2 * t) + 0.08 * sin(2 * PI * 329.6 * t) +
                0.02 * sin(2 * PI * 5_280.0 * t) + 0.015 * sin(2 * PI * 11_090.0 * t)).toFloat()
        }
        val eighth = rate / 4
        var hit = 0
        var start = 1_234
        while (start < frames) {
            val snare = hit % 4 == 2
            val kick = hit % 4 == 0
            // Each hit rings until the next one and dies to nothing first, as a recorded drum does;
            // a hit cut off mid-swing is a click of its own.
            val len = eighth
            for (k in 0 until len) {
                val i = start + k
                if (i >= frames) break
                val tail = minOf(1.0, (len - k) / 480.0)
                val decay = (tail * kotlin.math.exp(-k / (if (snare) 0.06 * rate else 0.01 * rate))).toFloat()
                out[i] += (if (snare) 0.45f else 0.25f) * decay * noise()
                if (kick) out[i] += (0.5 * tail * kotlin.math.exp(-k / (0.08 * rate)) * sin(2 * PI * (55.0 + 60.0 * kotlin.math.exp(-k / 1500.0)) * k / rate)).toFloat()
            }
            hit++
            start += eighth
        }
        return out
    }
}
