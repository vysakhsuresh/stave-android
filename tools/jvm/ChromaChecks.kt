import com.layerbit.stave.engine.music.Chords
import com.layerbit.stave.engine.music.Chroma
import com.layerbit.stave.engine.music.Key
import kotlin.math.abs

object ChromaChecks : Suite {
    override val name = "chroma"

    override fun run() {
        val rate = ChordsChecks.RATE

        section("an A major triad puts over 80% of its energy on A, C♯ and E")
        for (r in listOf(rate, 8000)) {
            val frames = Chroma.frames(ChordsChecks.tones(listOf(69, 73, 76), 1.0, r), r)
            check(frames.size == 10) { "a second at $r Hz gave ${frames.size} frames" }
            for (i in 2..7) {
                val f = frames[i]
                near(f.sum(), 1f, 1e-3f, "frame $i sums to one")
                val onChord = f[9] + f[1] + f[4]
                check(onChord > 0.8f) { "frame $i at $r Hz has only $onChord on the chord: ${f.toList()}" }
            }
        }

        section("a lone C peaks at C")
        run {
            val frames = Chroma.frames(ChordsChecks.tones(listOf(60), 0.5, rate), rate)
            val f = frames[2]
            val peak = (0 until 12).maxByOrNull { f[it] }!!
            check(peak == 0) { "C peaked at pitch class $peak: ${f.toList()}" }
            check(f[0] > 0.9f) { "C has only ${f[0]} on C" }
        }

        section("silence is all zeros, and the frame count follows the hop")
        run {
            val frames = Chroma.frames(FloatArray(rate), rate)
            check(frames.size == 10 && frames.all { f -> f.all { it == 0f } }) { "silence gave ${frames.size} frames, nonzero: ${frames.count { f -> f.any { it != 0f } }}" }
            check(Chroma.frames(FloatArray((rate * 0.95).toInt()), rate).size == 10) { "0.95 s is ten frames at 100 ms" }
            check(Chroma.frames(FloatArray(rate), rate, hopMs = 50).size == 20) { "a second is twenty frames at 50 ms" }
            check(Chroma.frames(FloatArray(0), rate).isEmpty()) { "no samples, no frames" }
            // A loud tone then silence: the silent frames are zero, not the tone's shape carried over.
            val half = FloatArray(rate)
            ChordsChecks.tones(listOf(60), 0.5, rate).copyInto(half)
            val f = Chroma.frames(half, rate)
            check(f[9].all { it == 0f }) { "a frame well after the tone stopped still had ${f[9].toList()}" }
        }

        section("pitch classes between 55 Hz and 4 kHz, and the frame that resolves them")
        run {
            check(Chroma.pitchClassOf(440.0) == 9 && Chroma.pitchClassOf(261.63) == 0 && Chroma.pitchClassOf(55.0) == 9 && Chroma.pitchClassOf(466.16) == 10) { "pitch classes" }
            check(Chroma.pitchClassOf(50.0) == -1 && Chroma.pitchClassOf(5000.0) == -1 && Chroma.pitchClassOf(0.0) == -1) { "out of range" }
            check(Chroma.frameSize(44_100, 4410) == 16384) { "frame at 44.1 kHz is ${Chroma.frameSize(44_100, 4410)}" }
            check(Chroma.frameSize(8000, 800) == 4096) { "frame at 8 kHz is ${Chroma.frameSize(8000, 800)}" }
            // A hop longer than the frame the rate asks for: the frame grows to hold it.
            check(Chroma.frameSize(8000, 8000) == 8192) { "a long hop widens the frame" }
            val bins = FloatArray(12) { 2f }
            Chroma.normalise(bins)
            check(abs(bins.sum() - 1f) < 1e-6f && bins.all { abs(it - 1f / 12) < 1e-6f }) { "normalise" }
            val none = FloatArray(12) { 1e-12f }
            Chroma.normalise(none)
            check(none.all { it == 0f }) { "a frame with nothing in it is zeros, not a scaled-up whisper" }
        }

        section("a minute at 44.1 kHz through chroma, chords and key in good time")
        run {
            val parts = (0 until 15).flatMap { ChordsChecks.C_AM_F_G7 }
            val signal = ChordsChecks.progression(parts, rate)
            val started = System.nanoTime()
            val frames = Chroma.frames(signal, rate)
            val chromaMs = (System.nanoTime() - started) / 1_000_000
            val spans = Chords.detect(frames)
            val key = Key.detect(frames)
            val totalMs = (System.nanoTime() - started) / 1_000_000
            println("      60 s: ${frames.size} frames in $chromaMs ms, ${spans.size} spans and ${key.name()} in $totalMs ms")
            check(frames.size == 600) { "a minute is 600 frames, not ${frames.size}" }
            check(totalMs < 6000) { "a minute took $totalMs ms" }
            check(spans.size == 60) { "sixty chords in a minute, found ${spans.size}" }
        }
    }
}
