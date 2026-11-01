import com.layerbit.stave.engine.transcript.Lyrics
import com.layerbit.stave.engine.transcript.Word

/** Typed lyrics against heard words: lines take the time of their words, misheard words still pair, extra or missing words are skipped, a repeated word does not drag a line backwards. */
object LyricsChecks : Suite {
    override val name = "lyrics"

    private fun heard(vararg words: String, startMs: Long = 1000, stepMs: Long = 500): List<Word> =
        words.mapIndexed { i, w -> Word(w, startMs + i * stepMs, startMs + i * stepMs + 400) }

    override fun run() {
        section("tokens: lower case, punctuation off, any script")
        check(Lyrics.tokens("Hello, World!  it's") == listOf("hello", "world", "its")) { Lyrics.tokens("Hello, World!  it's").toString() }
        check(Lyrics.tokens("നമസ്കാരം लोग 123") == listOf("നമസ്കാരം", "लोग", "123")) { Lyrics.tokens("നമസ്കാരം लोग 123").toString() }
        check(Lyrics.tokens("...") .isEmpty()) { "only punctuation" }

        section("similarity: the same word 1, a near-miss 0.6, unalike 0")
        check(Lyrics.similarity("colour", "color") == 0.6) { "colour/color ${Lyrics.similarity("colour", "color")}" }
        check(Lyrics.similarity("wondering", "wonder") == 0.6) { "wondering/wonder share three letters" }
        check(Lyrics.similarity("gonna", "going") == 0.0) { "gonna/going share two letters and are four edits apart: unalike" }
        check(Lyrics.similarity("cat", "car") == 0.6) { "one edit on a short word" }
        check(Lyrics.similarity("love", "hate") == 0.0 && Lyrics.similarity("a", "") == 0.0) { "unalike" }

        section("two lines over the words heard, each line timed by its first and last matched word")
        val words = heard("twinkle", "twinkle", "little", "star", "how", "i", "wonder", "what", "you", "are")
        val lines = Lyrics.align("Twinkle, twinkle, little star\nHow I wonder what you are", words)
        check(lines.size == 2 && lines.all { it.isPlaced }) { "both lines placed: $lines" }
        check(lines[0].startMs == 1000L && lines[0].endMs == 2900L) { "line 1 spans its four words: ${lines[0]}" }
        check(lines[1].startMs == 3000L && lines[1].endMs == 5900L) { "line 2 spans its six: ${lines[1]}" }

        section("Whisper mishears a word and drops another: the lines are still placed")
        val misheard = heard("twinkle", "twinkle", "litle", "star", "how", "wonder", "what", "you", "are")
        val l2 = Lyrics.align("Twinkle twinkle little star\nHow I wonder what you are", misheard)
        check(l2.all { it.isPlaced } && l2[0].endMs == 2900L && l2[1].startMs == 3000L) { "placed despite a misheard and a missing word: $l2" }

        section("a line nobody sang is left without a time; the lines around it keep theirs")
        val l3 = Lyrics.align("Twinkle twinkle little star\nThis line is not in the song at all\nHow I wonder what you are", words)
        check(l3.size == 3 && l3[0].isPlaced && !l3[1].isPlaced && l3[2].isPlaced) { "the middle line unplaced: $l3" }
        check(l3[2].startMs == 3000L) { "the third line still starts on 'how': ${l3[2]}" }

        section("a repeated word later in the song does not pull an early line forward, and lines never run backwards")
        val chorus = heard("star", "star", "star", "shine", "on", "little", "star", "forever")
        val l4 = Lyrics.align("Star star star\nShine on little star\nForever", chorus)
        check(l4[0].startMs == 1000L && l4[0].endMs == 2400L) { "the first three stars: ${l4[0]}" }
        check(l4[1].startMs!! >= l4[0].endMs!! && l4[2].startMs!! >= l4[1].endMs!!) { "in order: $l4" }
        check(l4[2].startMs == 4500L) { "forever is the last word: ${l4[2]}" }

        section("edges: no lyrics, no words")
        check(Lyrics.align("", words).isEmpty()) { "no lyrics" }
        val none = Lyrics.align("Some words", emptyList())
        check(none.size == 1 && !none[0].isPlaced) { "no words heard: the line is kept, unplaced" }
    }
}
