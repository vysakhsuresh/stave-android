import com.layerbit.stave.engine.music.Chroma
import com.layerbit.stave.engine.music.Key
import com.layerbit.stave.engine.music.KeyEstimate

object KeyChecks : Suite {
    override val name = "key"

    override fun run() {
        val rate = ChordsChecks.RATE

        section("C → Am → F → G7 is C major, or its relative A minor")
        run {
            val k = Key.detect(Chroma.frames(ChordsChecks.progression(ChordsChecks.C_AM_F_G7), rate))
            check(k.name() == "C major" || k.name() == "A minor") { "read ${k.name()} at ${k.confidence}" }
            check(k.confidence > 0.5f) { "confidence only ${k.confidence}" }
            println("      C-Am-F-G7: ${k.name()} (${k.confidence})")
        }

        section("Dm → Gm → A7 is D minor")
        run {
            val parts = listOf(listOf(62, 65, 69) to 1.0, listOf(55, 58, 62) to 1.0, listOf(57, 61, 64, 67) to 1.0)
            val k = Key.detect(Chroma.frames(ChordsChecks.progression(parts), rate))
            check(k.name() == "D minor") { "read ${k.name()} at ${k.confidence}" }
            check(k.confidence > 0.5f) { "confidence only ${k.confidence}" }
            println("      Dm-Gm-A7: ${k.name()} (${k.confidence})")
        }

        section("a scale reads its key")
        run {
            val eFlat = listOf(63, 65, 67, 68, 70, 72, 74, 75).map { listOf(it) to 0.25 }
            check(Key.detect(Chroma.frames(ChordsChecks.progression(eFlat), rate)).name() == "E♭ major") { "E♭ major scale read as ${Key.detect(Chroma.frames(ChordsChecks.progression(eFlat), rate)).name()}" }
            // A minor: the harmonic scale leans on the G♯ no C major has.
            val aMinor = listOf(57, 59, 60, 62, 64, 65, 68, 69).map { listOf(it) to 0.25 }
            check(Key.detect(Chroma.frames(ChordsChecks.progression(aMinor), rate)).name() == "A minor") { "A harmonic minor read as ${Key.detect(Chroma.frames(ChordsChecks.progression(aMinor), rate)).name()}" }
        }

        section("each key's own profile reads as itself")
        for (root in 0 until 12) for (minor in listOf(false, true)) {
            val template = if (minor) Key.MINOR_PROFILE else Key.MAJOR_PROFILE
            val profile = FloatArray(12) { i -> template[(i - root).mod(12)].toFloat() }
            val k = Key.detect(profile)
            check(k.root == root && k.isMinor == minor) { "the profile of ${KeyEstimate(root, minor, 1f).name()} read as ${k.name()}" }
            near(k.confidence, 1f, 1e-4f, "${k.name()} on its own profile")
        }

        section("relative keys and names")
        run {
            val c = KeyEstimate(0, false, 0.9f)
            check(Key.relativeOf(c) == KeyEstimate(9, true, 0.9f)) { "the relative of C major is ${Key.relativeOf(c).name()}" }
            check(Key.relativeOf(Key.relativeOf(c)) == c) { "the relative of the relative" }
            check(Key.relativeOf(KeyEstimate(2, true, 0.5f)).name() == "F major") { "the relative of D minor" }
            check(Key.relativeOf(KeyEstimate(10, false, 0.5f)).name() == "G minor") { "the relative of B♭ major" }
            check(KeyEstimate(10, false, 1f).name() == "B♭ major" && KeyEstimate(6, true, 1f).name() == "F♯ minor" && KeyEstimate(9, true, 1f).name() == "A minor") { "names" }
        }

        section("nothing reads as nothing")
        run {
            val k = Key.detect(FloatArray(12))
            check(k.confidence == 0f) { "silence read as ${k.name()} at ${k.confidence}" }
            check(Key.detect(emptyList()).confidence == 0f) { "no frames" }
            val flat = Key.detect(FloatArray(12) { 0.5f })
            check(flat.confidence == 0f) { "a flat profile read as ${flat.name()} at ${flat.confidence}" }
        }
    }
}
