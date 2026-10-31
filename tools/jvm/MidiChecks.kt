import com.layerbit.stave.engine.sequencer.MidiEvent
import com.layerbit.stave.engine.sequencer.MidiException
import com.layerbit.stave.engine.sequencer.MidiFile
import com.layerbit.stave.engine.sequencer.MidiTrack
import com.layerbit.stave.engine.sequencer.Note
import com.layerbit.stave.engine.sequencer.Pattern
import com.layerbit.stave.engine.sequencer.TempoMap
import com.layerbit.stave.engine.sequencer.Ticks
import com.layerbit.stave.engine.sequencer.TicksTime
import com.layerbit.stave.engine.sequencer.Vlq
import com.layerbit.stave.engine.sequencer.toMidiFile

/** Standard MIDI Files in and out, the pairing of ons and offs into notes, and ticks against the clock. */
object MidiChecks : Suite {
    override val name = "midi"

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun hex(b: ByteArray) = b.joinToString(" ") { String.format("%02X", it.toInt() and 0xFF) }

    override fun run() {
        section("variable-length quantities: the specification's own examples")
        val cases = listOf(0L to bytes(0x00), 127L to bytes(0x7F), 128L to bytes(0x81, 0x00), 16383L to bytes(0xFF, 0x7F), 16384L to bytes(0x81, 0x80, 0x00), 0x0FFFFFFFL to bytes(0xFF, 0xFF, 0xFF, 0x7F), 0x3FFFL to bytes(0xFF, 0x7F), 0x100000L to bytes(0xC0, 0x80, 0x00))
        for ((value, expected) in cases) {
            val written = Vlq.encode(value)
            check(written.contentEquals(expected)) { "$value writes ${hex(written)}, expected ${hex(expected)}" }
            val (read, after) = Vlq.read(written, 0)
            check(read == value && after == written.size) { "$value reads back as $read" }
        }
        check(runCatching { Vlq.encode(0x10000000L) }.isFailure) { "past four bytes is refused" }
        check(runCatching { Vlq.read(bytes(0xFF, 0xFF, 0xFF, 0xFF, 0x7F), 0) }.exceptionOrNull() is MidiException) { "five continuation bytes is a bad file" }
        check(runCatching { Vlq.read(bytes(0x81), 0) }.exceptionOrNull() is MidiException) { "a VLQ cut off" }

        section("ticks and samples: a quarter at 120 bpm is 24 000 samples at 48 kHz")
        check(TicksTime.ticksToSamples(960, 120.0, sampleRate = 48000) == 24_000L) { "quarter -> samples" }
        check(TicksTime.samplesToTicks(24_000, 120.0, sampleRate = 48000) == 960L) { "samples -> quarter" }
        check(TicksTime.ticksToSamples(480, 120.0, sampleRate = 48000) == 12_000L && TicksTime.ticksToSamples(3840, 60.0, sampleRate = 44100) == 176_400L) { "an eighth, and a bar at 60 bpm at 44.1k" }
        check(TicksTime.ticksToSamples(960, 120.0, 480, 48000) == 48_000L) { "the file's own resolution counts" }
        near(TicksTime.ticksToSeconds(2880, 90.0), 2.0, 1e-12, "three beats at 90")
        check(TicksTime.beatsToSamples(1.0, 120.0, 48000) == 24_000L) { "beats" }; near(TicksTime.samplesToBeats(12_000, 120.0, 48000), 0.5, 1e-12, "and back")
        val map = TempoMap(listOf(TempoMap.Change(0, 120.0), TempoMap.Change(960, 60.0)))
        check(map.ticksToSamples(960, 48000) == 24_000L && map.ticksToSamples(1920, 48000) == 72_000L) { "a tempo change at the second beat: ${map.ticksToSamples(1920, 48000)}" }
        check(map.samplesToTicks(72_000, 48000) == 1920L && map.samplesToTicks(12_000, 48000) == 480L) { "and back through the change" }
        check(map.bpmAt(959) == 120.0 && map.bpmAt(960) == 60.0) { "which tempo rules a tick" }
        check(TempoMap(listOf(TempoMap.Change(1920, 100.0))).bpmAt(0) == 100.0) { "a map that starts late holds its first tempo back to 0" }

        section("a pattern written at 480 ppq and read back is the same notes")
        val pattern = Pattern("p", "Lead", 7680, listOf(
            Note("a", 60, 100, 0, 480), Note("b", 64, 90, 480, 480), Note("c", 67, 80, 960, 1920, channel = 3),
            Note("d", 60, 127, 960, 240), Note("e", 72, 1, 7200, 480), Note("f", 60, 100, 480, 480)))
        val file = pattern.toMidiFile(128.0, 480)
        val written = file.write()
        check(written.copyOfRange(0, 14).contentEquals(bytes(0x4D, 0x54, 0x68, 0x64, 0, 0, 0, 6, 0, 0, 0, 1, 0x01, 0xE0))) { "MThd, type 0, one track, 480: ${hex(written.copyOfRange(0, 14))}" }
        check(written.copyOfRange(14, 18).contentEquals(bytes(0x4D, 0x54, 0x72, 0x6B))) { "then MTrk" }
        val back = MidiFile.read(written)
        check(back.format == 0 && back.ticksPerQuarter == 480 && back.tracks.size == 1) { "header" }
        near(back.tempoBpm, 128.0, 0.01, "tempo")
        val patterns = back.toPatterns()
        check(patterns.size == 1 && patterns[0].name == "Lead") { "one pattern, named" }
        val sort = compareBy<Note>({ it.startTick }, { it.pitch }, { it.lengthTicks })
        check(patterns[0].notes.map { it.anonymous() }.sortedWith(sort) == pattern.notes.map { it.anonymous() }.sortedWith(sort)) { "note for note: ${patterns[0].notes}" }
        check(patterns[0].lengthTicks == 7680L) { "two bars kept through the end of track: ${patterns[0].lengthTicks}" }
        check(MidiFile.read(back.write()) == back) { "a file written again is byte for byte the same events" }
        val onOff = back.tracks[0].events.filter { it.tick == 480L && (it is MidiEvent.NoteOn || it is MidiEvent.NoteOff) }
        check(onOff.first() is MidiEvent.NoteOff && (onOff.first() as MidiEvent.NoteOff).pitch == 60) { "at a tick the off comes before the on, so C4 sounds twice: $onOff" }

        section("ratchets and probability in a file: repeats written, chance written as certain")
        val stepped = Pattern("s", "", notes = listOf(Note("r", 36, 100, 0, 240, ratchet = 3, probability = 0.2)))
        val fromFile = MidiFile.read(stepped.toMidiFile(120.0).write()).toPatterns().single()
        check(fromFile.name == "Track 1" && fromFile.notes.map { it.startTick } == listOf(0L, 80L, 160L) && fromFile.notes.all { it.lengthTicks == 80L && it.probability == 1.0 }) { "$fromFile" }

        section("a hand-built type 1 file with running status and a tempo change")
        val tempoTrack = bytes(
            0x00, 0xFF, 0x51, 0x03, 0x07, 0xA1, 0x20,          // 500 000 us: 120 bpm
            0x83, 0x60, 0xFF, 0x51, 0x03, 0x0F, 0x42, 0x40,    // 480 ticks later: 1 000 000 us, 60 bpm
            0x00, 0xFF, 0x58, 0x04, 0x03, 0x02, 0x18, 0x08,    // 3/4
            0x00, 0xFF, 0x2F, 0x00)
        val noteTrack = bytes(
            0x00, 0xFF, 0x03, 0x04, 0x42, 0x61, 0x73, 0x73,    // "Bass"
            0x00, 0xC0, 0x21,                                  // program 33
            0x00, 0x90, 0x3C, 0x64,                            // on C4 100
            0x83, 0x60, 0x3C, 0x00,                            // running status: on with velocity 0 is off, 480 later
            0x00, 0x40, 0x64,                                  // running status: on E4
            0x00, 0xB0, 0x07, 0x7F,                            // volume 127
            0x00, 0xE0, 0x00, 0x60,                            // bend +4096
            0x83, 0x60, 0x80, 0x40, 0x40,                      // explicit off E4
            0x00, 0xF0, 0x03, 0x7E, 0x09, 0xF7,                // a sysex
            0x00, 0xFF, 0x2F, 0x00)
        fun chunk(id: String, body: ByteArray) = id.toByteArray() + bytes((body.size shr 24) and 0xFF, (body.size shr 16) and 0xFF, (body.size shr 8) and 0xFF, body.size and 0xFF) + body
        val handBuilt = chunk("MThd", bytes(0, 1, 0, 2, 0x01, 0xE0)) + chunk("MTrk", tempoTrack) + chunk("MTrk", noteTrack)
        val parsed = MidiFile.read(handBuilt)
        check(parsed.format == 1 && parsed.ticksPerQuarter == 480 && parsed.tracks.size == 2) { "header: $parsed" }
        val tempos = parsed.tracks[0].events.filterIsInstance<MidiEvent.Tempo>()
        check(tempos.map { it.tick } == listOf(0L, 480L) && tempos.map { it.microsPerQuarter } == listOf(500_000, 1_000_000)) { "tempos: $tempos" }
        near(tempos[0].bpm, 120.0, 1e-9, "120"); near(tempos[1].bpm, 60.0, 1e-9, "60")
        val sig = parsed.tracks[0].events.filterIsInstance<MidiEvent.TimeSignature>().single()
        check(sig.numerator == 3 && sig.denominator == 4 && sig.denominatorPower == 2) { "3/4: $sig" }
        val ev = parsed.tracks[1].events
        check(ev[0] == MidiEvent.TrackName(0, "Bass") && parsed.tracks[1].name == "Bass") { "name: ${ev[0]}" }
        check(ev[1] == MidiEvent.ProgramChange(0, 0, 33)) { "program: ${ev[1]}" }
        check(ev[2] == MidiEvent.NoteOn(0, 0, 60, 100)) { "on: ${ev[2]}" }
        check(ev[3] == MidiEvent.NoteOff(480, 0, 60, 0)) { "running-status off at 480: ${ev[3]}" }
        check(ev[4] == MidiEvent.NoteOn(480, 0, 64, 100)) { "running-status on: ${ev[4]}" }
        check(ev[5] == MidiEvent.ControlChange(480, 0, 7, 127)) { "cc: ${ev[5]}" }
        check(ev[6] == MidiEvent.PitchBend(480, 0, 4096)) { "bend 0x60<<7 - 8192 = 4096: ${ev[6]}" }
        check(ev[7] == MidiEvent.NoteOff(960, 0, 64, 64)) { "explicit off: ${ev[7]}" }
        check(ev[8] == MidiEvent.SysEx(960, bytes(0x7E, 0x09, 0xF7))) { "sysex kept: ${ev[8]}" }
        check(ev[9] == MidiEvent.EndOfTrack(960) && ev.size == 10) { "end: $ev" }
        val fromHand = parsed.toPatterns()
        check(fromHand.size == 1 && fromHand[0].name == "Bass") { "the tempo track makes no pattern" }
        check(fromHand[0].notes.map { it.anonymous() } == listOf(Note("", 60, 100, 0, 960), Note("", 64, 100, 960, 960))) { "480-ppq ticks doubled to 960: ${fromHand[0].notes}" }
        check(fromHand[0].lengthTicks == 3840L) { "rounded up to a bar" }
        val fileMap = parsed.tempoMap()
        check(fileMap.changes == listOf(TempoMap.Change(0, 120.0), TempoMap.Change(960, 60.0))) { "the file's tempo map in 960 ticks: ${fileMap.changes}" }
        check(fileMap.ticksToSamples(1920, 48000) == 72_000L) { "half a second at 120 then a second at 60" }
        val rewritten = MidiFile.read(parsed.write())
        check(rewritten == parsed) { "the hand-built file survives a write" }
        check(parsed.write().size > handBuilt.size) { "written without running status it is longer, and that is allowed" }

        section("type 1 from patterns: a tempo track, then one track each")
        val two = MidiFile.of(listOf(pattern, stepped.copy(name = "Kick")), 100.0)
        check(two.format == 1 && two.tracks.size == 3 && two.tracks[0].events.any { it is MidiEvent.Tempo }) { "shape" }
        val twoBack = MidiFile.read(two.write())
        near(twoBack.tempoBpm, 100.0, 0.01, "tempo")
        check(twoBack.toPatterns().map { it.name } == listOf("Lead", "Kick")) { "two patterns back" }

        section("pairing: overlapping notes of one pitch close first in, first out; an unclosed note ends with the track")
        val overlap = MidiFile(0, 960, listOf(MidiTrack(listOf(
            MidiEvent.NoteOn(0, 0, 60, 100), MidiEvent.NoteOn(240, 0, 60, 90), MidiEvent.NoteOff(480, 0, 60), MidiEvent.NoteOff(720, 0, 60),
            MidiEvent.NoteOn(1000, 0, 62, 70), MidiEvent.EndOfTrack(1500)))))
        val paired = overlap.toPatterns().single().notes.map { it.anonymous() }
        check(paired[0] == Note("", 60, 100, 0, 480) && paired[1] == Note("", 60, 90, 240, 480)) { "two legato notes, not one cut short: $paired" }
        check(paired[2] == Note("", 62, 70, 1000, 500)) { "the open note closes at the end of track: ${paired[2]}" }
        val stray = MidiFile(0, 960, listOf(MidiTrack(listOf(MidiEvent.NoteOff(0, 0, 60), MidiEvent.NoteOn(0, 0, 61, 1), MidiEvent.NoteOff(0, 0, 61)))))
        check(stray.toPatterns().single().notes.single().anonymous() == Note("", 61, 1, 0, 1)) { "an off with no on is ignored; on and off at one tick is a one-tick note" }

        section("what is refused")
        check(runCatching { MidiFile.read(bytes(1, 2, 3)) }.exceptionOrNull() is MidiException) { "garbage" }
        check(runCatching { MidiFile.read(chunk("MThd", bytes(0, 0, 0, 1, 0xE7, 0x28)) + chunk("MTrk", bytes(0, 0xFF, 0x2F, 0))) }.exceptionOrNull()?.message?.contains("SMPTE") == true) { "SMPTE division" }
        check(runCatching { MidiFile.read(chunk("MThd", bytes(0, 0, 0, 1, 0x01, 0xE0)) + chunk("MTrk", bytes(0, 0x3C, 0x64))) }.exceptionOrNull() is MidiException) { "a data byte with no status to run" }
        check(runCatching { MidiFile.read(chunk("MThd", bytes(0, 0, 0, 1, 0x01, 0xE0)) + chunk("MTrk", bytes(0, 0x90, 0x3C))) }.exceptionOrNull() is MidiException) { "an event cut off" }
        check(runCatching { MidiFile.read(chunk("MThd", bytes(0, 0, 0, 1, 0x01, 0xE0))) }.exceptionOrNull() is MidiException) { "no track" }
        check(runCatching { MidiFile.read(chunk("MThd", bytes(0, 0, 0, 1, 0x01, 0xE0)) + chunk("MTrk", bytes(0, 0xF8))) }.exceptionOrNull() is MidiException) { "a realtime byte inside a track" }
        val unknownChunk = MidiFile.read(chunk("MThd", bytes(0, 0, 0, 1, 0x01, 0xE0)) + chunk("XFIH", bytes(1, 2, 3)) + chunk("MTrk", bytes(0, 0x90, 0x3C, 0x64, 0x60, 0x80, 0x3C, 0x40, 0, 0xFF, 0x2F, 0)))
        check(unknownChunk.tracks.size == 1 && unknownChunk.tracks[0].hasNotes) { "an unknown chunk is skipped, as the specification asks" }
        val longer = MidiFile.read(chunk("MThd", bytes(0, 0, 0, 1, 0x01, 0xE0, 0xAA, 0xBB)) + chunk("MTrk", bytes(0, 0xFF, 0x2F, 0)))
        check(longer.tracks.size == 1) { "a header longer than six bytes is skipped past" }
        check(runCatching { MidiFile(3, 960, emptyList()) }.isFailure && runCatching { MidiFile(0, 0, emptyList()) }.isFailure) { "format and resolution limits" }
    }
}
