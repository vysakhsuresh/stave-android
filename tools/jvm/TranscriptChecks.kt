import com.layerbit.stave.engine.transcript.Fillers
import com.layerbit.stave.engine.transcript.Silences
import com.layerbit.stave.engine.transcript.Transcript
import com.layerbit.stave.engine.transcript.WhisperText
import com.layerbit.stave.engine.transcript.WhisperWindows
import com.layerbit.stave.engine.transcript.Word
import com.layerbit.stave.engine.transcript.WordCuts

object TranscriptChecks : Suite {
    override val name = "transcript"

    override fun run() {
        section("whisper lines parse, including a character cut across two pieces")
        val malayalam = "മലയാളം".toByteArray(Charsets.UTF_8)
        val cut = malayalam.size / 2 + 1
        val lines = ArrayList<Byte>()
        fun add(bytes: ByteArray) { lines.addAll(bytes.toList()) }
        add("0\t400\tHello\n".toByteArray()); add("400\t900\t".toByteArray()); add(malayalam.copyOfRange(0, cut)); add("\n".toByteArray())
        add("900\t1300\t".toByteArray()); add(malayalam.copyOfRange(cut, malayalam.size)); add("\n".toByteArray())
        add("1300\t1800\tworld.\n".toByteArray()); add("lang\tml\n".toByteArray())
        val t = WhisperText.parseBytes(lines.toByteArray(), offsetMs = 10_000)!!
        check(t.language == "ml") { "language ${t.language}" }
        check(t.words.map { it.text } == listOf("Hello", "മലയാളം", "world.")) { "words ${t.words.map { it.text }}" }
        check(t.words[1].startMs == 10_400L && t.words[1].endMs == 11_300L) { "the joined word spans both pieces: ${t.words[1]}" }
        check(t.words[0].startMs == 10_000L) { "offset applied" }
        check(WhisperText.endsMidCharacter(malayalam.copyOfRange(0, cut)) && !WhisperText.endsMidCharacter(malayalam) && !WhisperText.endsMidCharacter("abc".toByteArray())) { "mid-character test" }
        check(WhisperText.parseBytes(null, 0) == null) { "null in, null out" }

        section("windows and their merge")
        check(WhisperWindows.windows(20_000) == listOf(0L until 20_000L)) { "short stays one window" }
        val w = WhisperWindows.windows(60_000)
        check(w.size == 3 && w[0] == (0L until 28_000L) && w[1].first == 27_000L && w.last().last == 59_999L) { "three overlapping windows: $w" }
        check(WhisperWindows.windows(0).isEmpty()) { "nothing" }
        val merged = WhisperWindows.merge(listOf(
            (0L until 28_000L) to listOf(Word("a", 1000, 1500), Word("seam", 27_200, 27_600)),
            (27_000L until 55_000L) to listOf(Word("seam", 27_210, 27_610), Word("b", 30_000, 30_400))
        ))
        check(merged.map { it.text } == listOf("a", "seam", "b")) { "seam heard once: ${merged.map { it.text }}" }

        section("fillers found by letters alone")
        val words = listOf(Word("So", 0, 300), Word("um,", 400, 600), Word("I", 700, 800), Word("think", 900, 1200), Word("Uh", 30_000, 30_200), Word("yes", 30_300, 30_600))
        check(Fillers.find(words) == listOf(1, 4)) { Fillers.find(words).toString() }
        check(Fillers.find(words, setOf("so")) == listOf(0, 1, 4)) { "an extra filler" }

        section("word cuts pad and merge by gap, never by list position")
        val ranges = WordCuts.rangesFor(words, listOf(1, 4), padMs = 20, mergeGapMs = 150)
        check(ranges == listOf(380L until 620L, 29_980L until 30_220L)) { "two cuts thirty seconds apart stay two: $ranges" }
        val adjacent = WordCuts.rangesFor(words, listOf(2, 3), padMs = 20, mergeGapMs = 150)
        check(adjacent == listOf(680L until 1220L)) { "neighbours merge: $adjacent" }
        check(WordCuts.rangesFor(words, listOf(0), padMs = 50).first().first == 0L) { "clamped at zero" }
        check(WordCuts.rangesFor(words, emptyList()).isEmpty()) { "nothing selected" }

        section("the transcript follows a cut and an insert")
        val t2 = Transcript(words, "en")
        val after = t2.afterCut(380, 620)
        check(after.words.map { it.text } == listOf("So", "I", "think", "Uh", "yes")) { "the um is gone: ${after.words.map { it.text }}" }
        check(after.words[1].startMs == 460L && after.words[3].startMs == 29_760L) { "later words moved back by 240: ${after.words}" }
        val straddle = t2.afterCut(1000, 1100)
        check(straddle.words[3].startMs == 900L && straddle.words[3].endMs == 1100L) { "a word straddling a cut keeps what is outside: ${straddle.words[3]}" }
        val inserted = t2.afterInsert(650, 1000)
        check(inserted.words[2].startMs == 1700L && inserted.words[1].endMs == 600L) { "words after an insert move on" }
        val all = WordCuts.applyAll(t2, ranges)
        check(all.words.map { it.text } == listOf("So", "I", "think", "yes")) { "both fillers gone: ${all.words.map { it.text }}" }
        check(t2.wordsIn(350, 650) == listOf(1)) { "words in a range" }
        check(t2.lines(gapMs = 700).size == 2) { "a long pause starts a new line: ${t2.lines().size}" }

        section("silences from levels")
        val rate = 48_000
        val samples = FloatArray(rate * 4) { i -> val t = i.toDouble() / rate; if (t < 1.0 || t > 3.0) (0.3 * Math.sin(2 * Math.PI * 200 * t)).toFloat() else 0.0005f * (if (i % 2 == 0) 1 else -1) }
        val levels = Silences.levels(samples, rate, 20)
        check(levels.size == 200) { "20 ms frames over 4 s: ${levels.size}" }
        val threshold = Silences.thresholdFor(levels)
        check(threshold <= -30f) { "threshold capped: $threshold" }
        val found = Silences.find(levels, 20, threshold, minMs = 500, keepMs = 100)
        check(found.size == 1 && found[0].first == 1100L && found[0].last == 2899L) { "one silence, shrunk by the margin: $found" }
        check(Silences.find(levels, 20, threshold, minMs = 5000, keepMs = 100).isEmpty()) { "too short to count" }
    }
}
