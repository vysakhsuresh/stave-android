package com.layerbit.stave.engine.sequencer

import java.io.ByteArrayOutputStream
import kotlin.math.roundToLong

/*
 * Standard MIDI Files (the 1996 specification), types 0 and 1, read and written by hand: the
 * format is a few hundred bytes of rules and a library would bring its own ideas about ticks.
 * Ticks in a MidiFile are the file's own (ticksPerQuarter); toPatterns rescales them to
 * Ticks.PER_QUARTER and Pattern.toMidiFile the other way, so nothing downstream ever sees a
 * file's resolution.
 */

class MidiException(message: String) : RuntimeException(message)

sealed class MidiEvent {
    /** Absolute ticks from the track's start, in the file's resolution. */
    abstract val tick: Long
    abstract fun at(tick: Long): MidiEvent

    data class NoteOn(override val tick: Long, val channel: Int, val pitch: Int, val velocity: Int) : MidiEvent() { override fun at(tick: Long) = copy(tick = tick) }
    data class NoteOff(override val tick: Long, val channel: Int, val pitch: Int, val velocity: Int = 64) : MidiEvent() { override fun at(tick: Long) = copy(tick = tick) }
    data class PolyPressure(override val tick: Long, val channel: Int, val pitch: Int, val pressure: Int) : MidiEvent() { override fun at(tick: Long) = copy(tick = tick) }
    data class ControlChange(override val tick: Long, val channel: Int, val controller: Int, val value: Int) : MidiEvent() { override fun at(tick: Long) = copy(tick = tick) }
    data class ProgramChange(override val tick: Long, val channel: Int, val program: Int) : MidiEvent() { override fun at(tick: Long) = copy(tick = tick) }
    data class ChannelPressure(override val tick: Long, val channel: Int, val pressure: Int) : MidiEvent() { override fun at(tick: Long) = copy(tick = tick) }
    /** [value] is -8192..8191 with 0 at the centre, as a player thinks of it; the wire's 14-bit unsigned form is the writer's business. */
    data class PitchBend(override val tick: Long, val channel: Int, val value: Int) : MidiEvent() { override fun at(tick: Long) = copy(tick = tick) }

    data class Tempo(override val tick: Long, val microsPerQuarter: Int) : MidiEvent() {
        val bpm: Double get() = 60_000_000.0 / microsPerQuarter
        override fun at(tick: Long) = copy(tick = tick)
        companion object { fun ofBpm(tick: Long, bpm: Double) = Tempo(tick, (60_000_000.0 / bpm).roundToLong().toInt()) }
    }

    /** [denominatorPower]: the denominator is 2^power, as the file stores it (2 for a 4). */
    data class TimeSignature(override val tick: Long, val numerator: Int, val denominatorPower: Int, val clocksPerClick: Int = 24, val thirtySecondsPerQuarter: Int = 8) : MidiEvent() {
        val denominator: Int get() = 1 shl denominatorPower
        override fun at(tick: Long) = copy(tick = tick)
    }

    data class TrackName(override val tick: Long, val name: String) : MidiEvent() { override fun at(tick: Long) = copy(tick = tick) }
    data class EndOfTrack(override val tick: Long) : MidiEvent() { override fun at(tick: Long) = copy(tick = tick) }

    /** A meta event this file keeps but does not interpret (lyrics, markers, key signature...), carried through a round trip. */
    class MetaOther(override val tick: Long, val type: Int, val data: ByteArray) : MidiEvent() {
        override fun at(tick: Long) = MetaOther(tick, type, data)
        override fun equals(other: Any?) = other is MetaOther && other.tick == tick && other.type == type && other.data.contentEquals(data)
        override fun hashCode() = (tick.hashCode() * 31 + type) * 31 + data.contentHashCode()
        override fun toString() = "MetaOther(tick=$tick, type=$type, ${data.size} bytes)"
    }

    /** [data] is the bytes after the F0 (or F7) status, the terminating F7 included, as the file holds them. */
    class SysEx(override val tick: Long, val data: ByteArray, val continuation: Boolean = false) : MidiEvent() {
        override fun at(tick: Long) = SysEx(tick, data, continuation)
        override fun equals(other: Any?) = other is SysEx && other.tick == tick && other.continuation == continuation && other.data.contentEquals(data)
        override fun hashCode() = (tick.hashCode() * 31 + continuation.hashCode()) * 31 + data.contentHashCode()
        override fun toString() = "SysEx(tick=$tick, ${data.size} bytes)"
    }
}

data class MidiTrack(val events: List<MidiEvent>) {
    val name: String? get() = events.firstNotNullOfOrNull { it as? MidiEvent.TrackName }?.name
    val lastTick: Long get() = events.maxOfOrNull { it.tick } ?: 0L
    val hasNotes: Boolean get() = events.any { it is MidiEvent.NoteOn }
}

/** Variable-length quantities: seven bits a byte, high bit set on every byte but the last, at most four bytes (0x0FFFFFFF). */
object Vlq {
    const val MAX = 0x0FFFFFFFL

    fun write(value: Long, out: ByteArrayOutputStream) {
        require(value in 0..MAX) { "VLQ $value out of 0..$MAX" }
        var buffer = value and 0x7F
        var v = value shr 7
        while (v > 0) {
            buffer = (buffer shl 8) or 0x80 or (v and 0x7F)
            v = v shr 7
        }
        while (true) {
            out.write((buffer and 0xFF).toInt())
            if (buffer and 0x80 != 0L) buffer = buffer shr 8 else break
        }
    }

    fun encode(value: Long): ByteArray = ByteArrayOutputStream(4).also { write(value, it) }.toByteArray()

    /** Reads one VLQ from [bytes] at [pos]; returns the value and the position after it. */
    fun read(bytes: ByteArray, pos: Int): Pair<Long, Int> {
        var value = 0L
        var p = pos
        var count = 0
        while (true) {
            if (p >= bytes.size) throw MidiException("variable-length quantity runs off the end at $pos")
            val b = bytes[p++].toInt() and 0xFF
            value = (value shl 7) or (b and 0x7F).toLong()
            count++
            if (b and 0x80 == 0) break
            if (count >= 4) throw MidiException("variable-length quantity longer than four bytes at $pos")
        }
        return Pair(value, p)
    }
}

data class MidiFile(val format: Int, val ticksPerQuarter: Int, val tracks: List<MidiTrack>) {
    init {
        require(format in 0..2) { "SMF format $format" }
        require(ticksPerQuarter in 1..0x7FFF) { "ticks per quarter $ticksPerQuarter" }
    }

    /** The first tempo in the file, or the specification's default of 120 when none is written. */
    val tempoBpm: Double get() = tracks.asSequence().flatMap { it.events.asSequence() }.filterIsInstance<MidiEvent.Tempo>().minByOrNull { it.tick }?.bpm ?: 120.0

    /** Every tempo change in the file, in Ticks.PER_QUARTER ticks, as the player's map. */
    fun tempoMap(): TempoMap {
        val changes = tracks.flatMap { it.events }.filterIsInstance<MidiEvent.Tempo>().sortedBy { it.tick }
            .map { TempoMap.Change(rescale(it.tick, ticksPerQuarter, Ticks.PER_QUARTER.toInt()), it.bpm) }
        return TempoMap(changes.ifEmpty { listOf(TempoMap.Change(0, 120.0)) })
    }

    fun write(): ByteArray {
        val out = ByteArrayOutputStream()
        writeChunk(out, "MThd") { h ->
            u16(h, format); u16(h, tracks.size); u16(h, ticksPerQuarter)
        }
        for (track in tracks) writeChunk(out, "MTrk") { body -> writeTrack(track, body) }
        return out.toByteArray()
    }

    private fun writeTrack(track: MidiTrack, out: ByteArrayOutputStream) {
        // A file is in delta time, so the events must be in tick order; the sort is stable so a
        // builder that put an off before an on at the same tick keeps that order.
        val ordered = track.events.filter { it !is MidiEvent.EndOfTrack }.sortedBy { it.tick }
        val end = (track.events.firstNotNullOfOrNull { it as? MidiEvent.EndOfTrack }?.tick ?: 0L).coerceAtLeast(ordered.lastOrNull()?.tick ?: 0L)
        var last = 0L
        for (e in ordered) {
            Vlq.write(e.tick - last, out)
            last = e.tick
            writeEvent(e, out)
        }
        Vlq.write(end - last, out)
        out.write(0xFF); out.write(0x2F); out.write(0x00)
    }

    private fun writeEvent(e: MidiEvent, out: ByteArrayOutputStream) {
        fun channel(status: Int, ch: Int) = out.write(status or (ch and 0x0F))
        fun data(v: Int) = out.write(v.coerceIn(0, 127))
        fun meta(type: Int, bytes: ByteArray) { out.write(0xFF); out.write(type); Vlq.write(bytes.size.toLong(), out); out.write(bytes) }
        when (e) {
            is MidiEvent.NoteOff -> { channel(0x80, e.channel); data(e.pitch); data(e.velocity) }
            is MidiEvent.NoteOn -> { channel(0x90, e.channel); data(e.pitch); data(e.velocity) }
            is MidiEvent.PolyPressure -> { channel(0xA0, e.channel); data(e.pitch); data(e.pressure) }
            is MidiEvent.ControlChange -> { channel(0xB0, e.channel); data(e.controller); data(e.value) }
            is MidiEvent.ProgramChange -> { channel(0xC0, e.channel); data(e.program) }
            is MidiEvent.ChannelPressure -> { channel(0xD0, e.channel); data(e.pressure) }
            is MidiEvent.PitchBend -> {
                val v = (e.value.coerceIn(-8192, 8191) + 8192)
                channel(0xE0, e.channel); out.write(v and 0x7F); out.write((v shr 7) and 0x7F)
            }
            is MidiEvent.Tempo -> { val t = e.microsPerQuarter; meta(0x51, byteArrayOf((t shr 16).toByte(), (t shr 8).toByte(), t.toByte())) }
            is MidiEvent.TimeSignature -> meta(0x58, byteArrayOf(e.numerator.toByte(), e.denominatorPower.toByte(), e.clocksPerClick.toByte(), e.thirtySecondsPerQuarter.toByte()))
            is MidiEvent.TrackName -> meta(0x03, e.name.toByteArray(Charsets.UTF_8))
            is MidiEvent.MetaOther -> meta(e.type, e.data)
            is MidiEvent.SysEx -> { out.write(if (e.continuation) 0xF7 else 0xF0); Vlq.write(e.data.size.toLong(), out); out.write(e.data) }
            is MidiEvent.EndOfTrack -> meta(0x2F, ByteArray(0))
        }
    }

    /**
     * One pattern per track with notes, in Ticks.PER_QUARTER ticks. Note-ons and offs are paired
     * first in, first out per channel and pitch, so two overlapping notes of one pitch (a legato
     * line, as a piano roll writes it) come back as the two notes that were written and not as
     * one cut short at the second's start. A note still open at the end closes there.
     */
    fun toPatterns(): List<Pattern> {
        val out = ArrayList<Pattern>()
        tracks.forEachIndexed { ti, track ->
            if (!track.hasNotes) return@forEachIndexed
            val open = HashMap<Int, ArrayDeque<MidiEvent.NoteOn>>()
            val notes = ArrayList<Note>()
            var count = 0
            fun close(on: MidiEvent.NoteOn, offTick: Long) {
                val start = rescale(on.tick, ticksPerQuarter, Ticks.PER_QUARTER.toInt())
                val end = rescale(offTick, ticksPerQuarter, Ticks.PER_QUARTER.toInt())
                notes += Note("n${++count}", on.pitch, on.velocity.coerceIn(1, 127), start, (end - start).coerceAtLeast(1), on.channel)
            }
            for (e in track.events.sortedWith(compareBy({ it.tick }, { if (it is MidiEvent.NoteOn) 1 else 0 }))) {
                when (e) {
                    is MidiEvent.NoteOn -> open.getOrPut(e.channel shl 8 or e.pitch) { ArrayDeque() }.addLast(e)
                    is MidiEvent.NoteOff -> open[e.channel shl 8 or e.pitch]?.removeFirstOrNull()?.let { close(it, e.tick) }
                    else -> {}
                }
            }
            val endTick = track.lastTick
            for (queue in open.values) for (on in queue) close(on, maxOf(endTick, on.tick + 1))
            val reach = maxOf(rescale(endTick, ticksPerQuarter, Ticks.PER_QUARTER.toInt()), notes.maxOf { it.endTick })
            val bars = ((reach + Ticks.PER_BAR - 1) / Ticks.PER_BAR).coerceAtLeast(1)
            out += Pattern("t$ti", track.name ?: "Track ${ti + 1}", bars * Ticks.PER_BAR, notes)
        }
        return out
    }

    companion object {
        fun read(bytes: ByteArray): MidiFile {
            val r = Reader(bytes)
            if (r.remaining < 14 || r.chunkId() != "MThd") throw MidiException("not a Standard MIDI File (no MThd)")
            val headerLength = r.u32()
            if (headerLength < 6) throw MidiException("header too short: $headerLength")
            val format = r.u16()
            val declared = r.u16()
            val division = r.u16()
            if (division and 0x8000 != 0) throw MidiException("SMPTE timing is not supported")
            if (division == 0) throw MidiException("zero ticks per quarter")
            r.skip(headerLength - 6)
            val tracks = ArrayList<MidiTrack>()
            while (r.remaining >= 8) {
                val id = r.chunkId()
                val length = r.u32()
                if (length > r.remaining) throw MidiException("chunk $id claims $length bytes with ${r.remaining} left")
                val start = r.pos
                if (id == "MTrk") tracks += readTrack(bytes, start, start + length)
                r.pos = start + length
            }
            if (tracks.isEmpty()) throw MidiException("no tracks ($declared declared)")
            return MidiFile(format.coerceIn(0, 2), division, tracks)
        }

        private fun readTrack(bytes: ByteArray, from: Int, to: Int): MidiTrack {
            val events = ArrayList<MidiEvent>()
            var pos = from
            var tick = 0L
            var running = -1
            fun u8(): Int { if (pos >= to) throw MidiException("track runs off its end at $pos"); return bytes[pos++].toInt() and 0xFF }
            fun take(n: Long): ByteArray { if (n < 0 || pos + n > to) throw MidiException("event data runs off the track at $pos"); return bytes.copyOfRange(pos, pos + n.toInt()).also { pos += n.toInt() } }
            while (pos < to) {
                val (delta, after) = Vlq.read(bytes, pos)
                pos = after
                tick += delta
                var b = u8()
                when {
                    b == 0xFF -> {
                        val type = u8()
                        val (len, p) = Vlq.read(bytes, pos); pos = p
                        val data = take(len)
                        events += when (type) {
                            0x51 -> if (data.size == 3) MidiEvent.Tempo(tick, (data[0].toInt() and 0xFF shl 16) or (data[1].toInt() and 0xFF shl 8) or (data[2].toInt() and 0xFF)) else MidiEvent.MetaOther(tick, type, data)
                            0x58 -> if (data.size >= 2) MidiEvent.TimeSignature(tick, data[0].toInt() and 0xFF, data[1].toInt() and 0xFF, if (data.size > 2) data[2].toInt() and 0xFF else 24, if (data.size > 3) data[3].toInt() and 0xFF else 8) else MidiEvent.MetaOther(tick, type, data)
                            0x03 -> MidiEvent.TrackName(tick, String(data, Charsets.UTF_8))
                            0x2F -> MidiEvent.EndOfTrack(tick)
                            else -> MidiEvent.MetaOther(tick, type, data)
                        }
                        // A meta event leaves running status alone: the specification says only
                        // system messages cancel it, and files from every sequencer rely on that.
                    }
                    b == 0xF0 || b == 0xF7 -> {
                        val (len, p) = Vlq.read(bytes, pos); pos = p
                        events += MidiEvent.SysEx(tick, take(len), continuation = b == 0xF7)
                    }
                    else -> {
                        val status: Int
                        if (b < 0x80) {
                            if (running < 0) throw MidiException("data byte $b with no running status at ${pos - 1}")
                            status = running
                        } else {
                            status = b
                            running = b
                            b = u8()
                        }
                        val kind = status and 0xF0
                        val ch = status and 0x0F
                        events += when (kind) {
                            0x80 -> MidiEvent.NoteOff(tick, ch, b, u8())
                            0x90 -> { val v = u8(); if (v == 0) MidiEvent.NoteOff(tick, ch, b, 0) else MidiEvent.NoteOn(tick, ch, b, v) }
                            0xA0 -> MidiEvent.PolyPressure(tick, ch, b, u8())
                            0xB0 -> MidiEvent.ControlChange(tick, ch, b, u8())
                            0xC0 -> MidiEvent.ProgramChange(tick, ch, b)
                            0xD0 -> MidiEvent.ChannelPressure(tick, ch, b)
                            0xE0 -> { val msb = u8(); MidiEvent.PitchBend(tick, ch, ((msb shl 7) or b) - 8192) }
                            else -> throw MidiException("system message ${Integer.toHexString(status)} inside a track at ${pos - 1}")
                        }
                    }
                }
            }
            return MidiTrack(events)
        }

        /** Type 1: a tempo track, then one track per pattern. */
        fun of(patterns: List<Pattern>, bpm: Double, ticksPerQuarter: Int = Ticks.PER_QUARTER.toInt()): MidiFile {
            val tempo = MidiTrack(listOf(MidiEvent.Tempo.ofBpm(0, bpm), MidiEvent.TimeSignature(0, 4, 2), MidiEvent.EndOfTrack(0)))
            return MidiFile(1, ticksPerQuarter, listOf(tempo) + patterns.map { it.toMidiTrack(ticksPerQuarter, withTempo = null) })
        }

        internal fun rescale(tick: Long, fromPpq: Int, toPpq: Int): Long =
            if (fromPpq == toPpq) tick else Math.round(tick.toDouble() * toPpq / fromPpq)

        private fun writeChunk(out: ByteArrayOutputStream, id: String, body: (ByteArrayOutputStream) -> Unit) {
            val b = ByteArrayOutputStream()
            body(b)
            out.write(id.toByteArray(Charsets.US_ASCII))
            u32(out, b.size().toLong())
            b.writeTo(out)
        }

        private fun u16(out: ByteArrayOutputStream, v: Int) { out.write((v shr 8) and 0xFF); out.write(v and 0xFF) }
        private fun u32(out: ByteArrayOutputStream, v: Long) { out.write(((v shr 24) and 0xFF).toInt()); out.write(((v shr 16) and 0xFF).toInt()); out.write(((v shr 8) and 0xFF).toInt()); out.write((v and 0xFF).toInt()) }
    }

    private class Reader(val bytes: ByteArray) {
        var pos = 0
        val remaining: Int get() = bytes.size - pos
        fun u8(): Int { if (pos >= bytes.size) throw MidiException("unexpected end of file"); return bytes[pos++].toInt() and 0xFF }
        fun u16(): Int = (u8() shl 8) or u8()
        fun u32(): Int { val v = (u8().toLong() shl 24) or (u8().toLong() shl 16) or (u8().toLong() shl 8) or u8().toLong(); if (v > Int.MAX_VALUE) throw MidiException("chunk length $v"); return v.toInt() }
        fun chunkId(): String = String(ByteArray(4) { u8().toByte() }, Charsets.US_ASCII)
        fun skip(n: Int) { if (n < 0 || pos + n > bytes.size) throw MidiException("cannot skip $n at $pos"); pos += n }
    }
}

/**
 * The pattern as one track: name, then the notes, offs before ons at a tick so a pitch that
 * ends where it starts again is heard twice. Ratchets are written as their repeats and a
 * probability is written as certain - a file plays the same way every time, by definition.
 */
fun Pattern.toMidiTrack(ticksPerQuarter: Int = Ticks.PER_QUARTER.toInt(), withTempo: Double? = null): MidiTrack {
    val ppq = Ticks.PER_QUARTER.toInt()
    val events = ArrayList<MidiEvent>()
    if (withTempo != null) { events += MidiEvent.Tempo.ofBpm(0, withTempo); events += MidiEvent.TimeSignature(0, 4, 2) }
    if (name.isNotEmpty()) events += MidiEvent.TrackName(0, name)
    val sounded = copy(swing = 0.0).realise(rollProbability = false)
    val noteEvents = ArrayList<Pair<Int, MidiEvent>>()
    var end = lengthTicks
    for (n in sounded) {
        noteEvents += 1 to MidiEvent.NoteOn(MidiFile.rescale(n.startTick, ppq, ticksPerQuarter), n.channel, n.pitch, n.velocity)
        noteEvents += 0 to MidiEvent.NoteOff(MidiFile.rescale(n.endTick, ppq, ticksPerQuarter), n.channel, n.pitch, 64)
        if (n.endTick > end) end = n.endTick
    }
    events += noteEvents.sortedWith(compareBy({ it.second.tick }, { it.first })).map { it.second }
    events += MidiEvent.EndOfTrack(MidiFile.rescale(end, ppq, ticksPerQuarter))
    return MidiTrack(events)
}

/** Type 0: the pattern alone, with its tempo on the one track. */
fun Pattern.toMidiFile(bpm: Double, ticksPerQuarter: Int = Ticks.PER_QUARTER.toInt()): MidiFile =
    MidiFile(0, ticksPerQuarter, listOf(toMidiTrack(ticksPerQuarter, withTempo = bpm)))

/** Ticks to samples and back at one tempo. A quarter at 120 bpm is half a second: 24 000 samples at 48 kHz. */
object TicksTime {
    fun ticksToSeconds(ticks: Long, bpm: Double, ppq: Long = Ticks.PER_QUARTER): Double = ticks * 60.0 / (bpm * ppq)
    fun secondsToTicks(seconds: Double, bpm: Double, ppq: Long = Ticks.PER_QUARTER): Long = (seconds * bpm * ppq / 60.0).roundToLong()
    fun ticksToSamples(ticks: Long, bpm: Double, ppq: Long = Ticks.PER_QUARTER, sampleRate: Int): Long = (ticksToSeconds(ticks, bpm, ppq) * sampleRate).roundToLong()
    fun samplesToTicks(samples: Long, bpm: Double, ppq: Long = Ticks.PER_QUARTER, sampleRate: Int): Long = secondsToTicks(samples.toDouble() / sampleRate, bpm, ppq)
    fun beatsToSamples(beats: Double, bpm: Double, sampleRate: Int): Long = (beats * 60.0 / bpm * sampleRate).roundToLong()
    fun samplesToBeats(samples: Long, bpm: Double, sampleRate: Int): Double = samples.toDouble() / sampleRate * bpm / 60.0
}

/**
 * Tempo changes along the song, in Ticks.PER_QUARTER ticks. A position in samples is the sum
 * of each stretch at its own tempo, so a ritardando two bars in moves nothing before it.
 */
class TempoMap(changes: List<Change>) {
    data class Change(val tick: Long, val bpm: Double)

    val changes: List<Change> = changes.sortedBy { it.tick }.ifEmpty { listOf(Change(0, 120.0)) }.let { if (it.first().tick > 0) listOf(Change(0, it.first().bpm)) + it else it }

    fun bpmAt(tick: Long): Double = changes.last { it.tick <= tick.coerceAtLeast(0) }.bpm

    fun ticksToSeconds(tick: Long): Double {
        var seconds = 0.0
        for (i in changes.indices) {
            val c = changes[i]
            val next = changes.getOrNull(i + 1)?.tick ?: Long.MAX_VALUE
            if (tick <= c.tick) break
            seconds += TicksTime.ticksToSeconds(minOf(tick, next) - c.tick, c.bpm)
        }
        return seconds
    }

    fun ticksToSamples(tick: Long, sampleRate: Int): Long = (ticksToSeconds(tick) * sampleRate).roundToLong()

    fun secondsToTicks(seconds: Double): Long {
        var left = seconds
        for (i in changes.indices) {
            val c = changes[i]
            val next = changes.getOrNull(i + 1)
            val stretch = if (next == null) Double.MAX_VALUE else TicksTime.ticksToSeconds(next.tick - c.tick, c.bpm)
            if (left <= stretch || next == null) return c.tick + TicksTime.secondsToTicks(left, c.bpm)
            left -= stretch
        }
        return 0
    }

    fun samplesToTicks(samples: Long, sampleRate: Int): Long = secondsToTicks(samples.toDouble() / sampleRate)
}
