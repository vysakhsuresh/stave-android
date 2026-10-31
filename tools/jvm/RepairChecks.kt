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
}
