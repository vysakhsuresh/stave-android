import com.layerbit.stave.engine.text.Timecode
import java.util.Locale

object TimecodeChecks : Suite {
    override val name = "timecode"

    override fun run() {
        section("display forms")
        check(Timecode.display(0, locale = Locale.ROOT) == "0:00") { Timecode.display(0, locale = Locale.ROOT) }
        check(Timecode.display(61_500, 1, Locale.ROOT) == "1:01.5") { Timecode.display(61_500, 1, Locale.ROOT) }
        check(Timecode.display(3_661_007, 3, Locale.ROOT) == "1:01:01.007") { Timecode.display(3_661_007, 3, Locale.ROOT) }
        check(Timecode.display(-1500, 0, Locale.ROOT) == "−0:01") { Timecode.display(-1500, 0, Locale.ROOT) }
        section("file forms are ascii whatever the phone speaks")
        val arabic = Locale.forLanguageTag("ar-EG")
        val screen = Timecode.display(61_500, 1, arabic)
        check(screen.any { it > '؀' }) { "the screen form uses the locale's digits: $screen" }
        check(Timecode.srt(3_661_007) == "01:01:01,007" && Timecode.vtt(3_661_007) == "01:01:01.007") { "srt/vtt" }
        check(Timecode.srt(3_661_007).all { it in '0'..'9' || it == ':' || it == ',' }) { "ascii" }
        section("parsing what files write")
        check(Timecode.parse("01:01:01,007") == 3_661_007L) { "srt" }
        check(Timecode.parse("1:30.5") == 90_500L) { "m:ss.s" }
        check(Timecode.parse("12.25") == 12_250L) { "seconds" }
        check(Timecode.parse("a:b") == null && Timecode.parse("1:2:3:4") == null) { "garbage" }
        check(Timecode.ofSamples(48_000, 48_000) == 1000L && Timecode.ofSamples(1, 0) == 0L) { "samples to ms" }
        check(Timecode.samples(1234567, Locale.ROOT) == "1,234,567") { "grouped" }
    }
}
