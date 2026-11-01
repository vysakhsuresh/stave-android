package com.layerbit.stave.engine.project

import com.layerbit.stave.engine.dsp.FadeShape
import com.layerbit.stave.engine.sequencer.Arrangement
import com.layerbit.stave.engine.sequencer.Pattern
import kotlin.math.max
import kotlin.math.min

/*
 * The project model: what a session *is*, as immutable values. Every edit makes a new Session
 * (SessionEdits), the History keeps the old ones, and ProjectJson writes the current one. Ids
 * are short strings that survive saves; positions are in samples at the session's rate, as
 * Longs, so an hour at 192 kHz still fits and nothing is ever a float of a time.
 */

/** A decoded sound the project can play: raw 32-bit float, interleaved, in the project's media folder. */
data class Source(
    val id: String,
    /** What the person called it, or the file's name. */
    val name: String,
    /** Relative path inside the project folder of the .f32 file. */
    val file: String,
    val sampleRate: Int,
    val channels: Int,
    val frames: Long,
    val kind: Kind = Kind.AUDIO,
    /** The source this one was rendered from, for a repair or a stem; null for an import or a take. */
    val parentId: String? = null,
    /** The display name of the file it was imported from, kept so a relink can find it. */
    val origin: String? = null
) {
    enum class Kind { AUDIO, TAKE, RENDER, STEM, FREEZE }
    val durationMs: Long get() = if (sampleRate <= 0) 0 else frames * 1000 / sampleRate
}

data class Fade(val length: Long = 0, val shape: FadeShape = FadeShape.EQUAL_POWER)

/** Time and pitch changed on a clip, non-destructively. */
data class Warp(val stretch: Double = 1.0, val semitones: Double = 0.0, val preserveFormant: Boolean = true) {
    val isIdentity: Boolean get() = stretch == 1.0 && semitones == 0.0
}

/** A point of a clip's gain envelope: position within the clip (in clip samples, after warp), gain in dB. */
data class EnvelopePoint(val position: Long, val gainDb: Double)

data class Clip(
    val id: String,
    val sourceId: String,
    val trackId: String,
    /** Where the clip starts on the timeline. */
    val position: Long,
    /** The first source frame played. */
    val offset: Long,
    /** How many *timeline* samples the clip lasts (source frames x stretch). */
    val length: Long,
    val name: String = "",
    val gainDb: Double = 0.0,
    val fadeIn: Fade = Fade(),
    val fadeOut: Fade = Fade(),
    val muted: Boolean = false,
    val reversed: Boolean = false,
    val warp: Warp = Warp(),
    val envelope: List<EnvelopePoint> = emptyList(),
    /** Which take lane the clip sits in on its track; 0 is the main lane. */
    val lane: Int = 0,
    val colour: Int = 0,
    /** The source's rate over the session's: a 44.1k file in a 48k session is 0.91875 source frames a timeline sample. */
    val sourceRateRatio: Double = 1.0
) {
    val end: Long get() = position + length
    val range: LongRange get() = position until end
    /** Timeline samples to the source frames they cover: through the rate ratio and the stretch. */
    fun sourceFrames(timelineSamples: Long): Long = (timelineSamples * sourceRateRatio / warp.stretch).toLong()
    /** Source frames to the timeline samples they take. */
    fun timelineSamples(sourceFrames: Long): Long = (sourceFrames * warp.stretch / sourceRateRatio).toLong()
    /** Source frames the clip covers, after the stretch and the rate ratio are undone. */
    val sourceLength: Long get() = sourceFrames(length)
    fun contains(timelineSample: Long): Boolean = timelineSample >= position && timelineSample < end
    fun overlaps(from: Long, to: Long): Boolean = position < to && end > from
}

/** One processor in a track's insert chain. Parameters by name, so a new processor needs no new field. */
data class Insert(
    val id: String,
    val kind: String,
    val enabled: Boolean = true,
    val params: Map<String, Double> = emptyMap(),
    /** For an EQ: its bands, each a map (kind, hz, gain, q, enabled). */
    val bands: List<Map<String, Double>> = emptyList()
) {
    fun param(name: String, default: Double): Double = params[name] ?: default
}

data class Send(val toTrackId: String, val gainDb: Double = -6.0, val preFader: Boolean = false)

/** A line of automation: (position, value) points, linear between them, held outside. */
data class ValueTrack(val points: List<Point> = emptyList()) {
    data class Point(val position: Long, val value: Double)

    val sorted: List<Point> get() = points.sortedBy { it.position }

    fun valueAt(position: Long, default: Double): Double {
        val p = sorted
        if (p.isEmpty()) return default
        if (position <= p.first().position) return p.first().value
        if (position >= p.last().position) return p.last().value
        var lo = 0; var hi = p.size - 1
        while (hi - lo > 1) { val mid = (lo + hi) / 2; if (p[mid].position <= position) lo = mid else hi = mid }
        val a = p[lo]; val b = p[hi]
        val t = (position - a.position).toDouble() / (b.position - a.position)
        return a.value + (b.value - a.value) * t
    }

    fun with(point: Point): ValueTrack = ValueTrack(points.filter { it.position != point.position } + point)
    fun without(position: Long): ValueTrack = ValueTrack(points.filter { it.position != position })

    /** Points inside [from, to) moved by [delta]; the rest kept. For a ripple. */
    fun shifted(from: Long, delta: Long): ValueTrack = ValueTrack(points.map { if (it.position >= from) it.copy(position = it.position + delta) else it })

    /** Points inside [from, to) removed, those after moved back by the gap. */
    fun cut(from: Long, to: Long): ValueTrack = ValueTrack(points.filter { it.position < from || it.position >= to }.map { if (it.position >= to) it.copy(position = it.position - (to - from)) else it })

    val isEmpty: Boolean get() = points.isEmpty()
}

data class Track(
    val id: String,
    val name: String,
    val kind: Kind = Kind.AUDIO,
    val volumeDb: Double = 0.0,
    val pan: Double = 0.0,
    val muted: Boolean = false,
    val solo: Boolean = false,
    val armed: Boolean = false,
    val inserts: List<Insert> = emptyList(),
    val sends: List<Send> = emptyList(),
    /** The bus this track feeds; null is the master. */
    val outputId: String? = null,
    /** Automation by parameter name: "volume", "pan", "insert:<id>:<param>", "send:<trackId>". */
    val automation: Map<String, ValueTrack> = emptyMap(),
    val colour: Int = 0,
    /** How many take lanes are shown; the clips say which they are in. */
    val lanes: Int = 1,
    val heightDp: Int = 88
) {
    enum class Kind { AUDIO, MIDI, BUS, MASTER }
    fun automationOf(param: String): ValueTrack = automation[param] ?: ValueTrack()
}

data class Marker(val id: String, val position: Long, val name: String, val kind: Kind = Kind.MARKER) {
    enum class Kind { MARKER, CHAPTER, CUE }
}

data class Region(val id: String, val start: Long, val end: Long, val name: String) {
    val length: Long get() = end - start
}

/** The tempo map: one entry per change. Beat positions are derived, never stored. */
data class TempoEntry(val position: Long, val bpm: Double, val beatsPerBar: Int = 4)

data class Session(
    val name: String = "",
    val kind: String = "edit",
    val sampleRate: Int = 48_000,
    val sources: List<Source> = emptyList(),
    val tracks: List<Track> = emptyList(),
    val clips: List<Clip> = emptyList(),
    val markers: List<Marker> = emptyList(),
    val regions: List<Region> = emptyList(),
    val tempo: List<TempoEntry> = listOf(TempoEntry(0, 120.0)),
    val loop: LongRange? = null,
    val playhead: Long = 0,
    val selection: LongRange? = null,
    val selectedTrackId: String? = null,
    val masterVolumeDb: Double = 0.0,
    val masterInserts: List<Insert> = emptyList(),
    /** The Beats tab's patterns, their song order, and the instrument each pattern plays on (by [Pattern.id]; see the app's Instruments for the ids). */
    val patterns: List<Pattern> = emptyList(),
    val arrangement: Arrangement = Arrangement(),
    val instruments: Map<String, String> = emptyMap(),
    /** Kits the person made from slices and samples; a pattern plays one through the instrument id `kit:<id>`. */
    val kits: List<com.layerbit.stave.engine.synth.PadKit> = emptyList(),
    val version: Int = VERSION
) {
    val length: Long get() = clips.maxOfOrNull { it.end } ?: 0L
    fun pattern(id: String): Pattern? = patterns.firstOrNull { it.id == id }
    fun kit(id: String): com.layerbit.stave.engine.synth.PadKit? = kits.firstOrNull { it.id == id }
    val durationMs: Long get() = if (sampleRate <= 0) 0 else length * 1000 / sampleRate
    fun source(id: String): Source? = sources.firstOrNull { it.id == id }
    fun track(id: String): Track? = tracks.firstOrNull { it.id == id }
    fun clip(id: String): Clip? = clips.firstOrNull { it.id == id }
    fun clipsOn(trackId: String): List<Clip> = clips.filter { it.trackId == trackId }.sortedBy { it.position }
    fun clipAt(trackId: String, position: Long, lane: Int = 0): Clip? = clips.firstOrNull { it.trackId == trackId && it.lane == lane && it.contains(position) }
    /** Clips that overlap [from, to) on any track, or on [trackId]. */
    fun clipsIn(from: Long, to: Long, trackId: String? = null): List<Clip> = clips.filter { (trackId == null || it.trackId == trackId) && it.overlaps(from, to) }
    val audioTracks: List<Track> get() = tracks.filter { it.kind == Track.Kind.AUDIO || it.kind == Track.Kind.MIDI }
    val buses: List<Track> get() = tracks.filter { it.kind == Track.Kind.BUS }
    val anySolo: Boolean get() = tracks.any { it.solo }

    /** Whether a track is heard: not muted, and solo'd when anything is. */
    fun heard(track: Track): Boolean = !track.muted && (!anySolo || track.solo || track.kind == Track.Kind.BUS)

    /** The tempo in force at [position]. */
    fun tempoAt(position: Long): TempoEntry = tempo.filter { it.position <= position }.maxByOrNull { it.position } ?: tempo.first()

    /** Beat positions (samples) from [from] to [to], for the grid. */
    fun beats(from: Long, to: Long): List<Long> {
        val out = ArrayList<Long>()
        val entries = tempo.sortedBy { it.position }
        for ((i, e) in entries.withIndex()) {
            val segmentEnd = entries.getOrNull(i + 1)?.position ?: Long.MAX_VALUE
            val beat = sampleRate * 60.0 / e.bpm
            var k = max(0L, ((from - e.position) / beat).toLong() - 1)
            while (true) {
                val p = e.position + (k * beat).toLong()
                if (p >= min(to, segmentEnd)) break
                if (p >= from) out += p
                k++
            }
        }
        return out
    }

    companion object {
        const val VERSION = 1
    }
}

/** Short ids that are unique within a project and safe in a file name. */
object Ids {
    private var counter = 0L
    private val alphabet = "abcdefghijkmnpqrstuvwxyz23456789"

    fun next(prefix: String = ""): String {
        val n = System.nanoTime() xor (counter++ shl 20)
        val sb = StringBuilder(prefix)
        var v = n
        for (i in 0 until 8) { sb.append(alphabet[(v and 31).toInt()]); v = v ushr 5 }
        return sb.toString()
    }
}
