package com.layerbit.stave.engine.project

import kotlin.math.max
import kotlin.math.min

/**
 * Every edit as a pure function from a Session to a Session. Nothing here touches samples: a cut
 * is a change to which clips play which parts of which sources, which is why Undo is a pointer
 * swap and a destructive-looking tool costs nothing until it is rendered.
 *
 * Executed in SessionEditsChecks against hand-drawn timelines.
 */
object SessionEdits {

    // ---- clips ---------------------------------------------------------------------------

    /** The clip split at [at] on the timeline, into two that together play what it played. */
    fun split(session: Session, clipId: String, at: Long): Session {
        val c = session.clip(clipId) ?: return session
        if (at <= c.position || at >= c.end) return session
        val firstLength = at - c.position
        val first = c.copy(id = Ids.next("c"), length = firstLength, fadeOut = Fade(), envelope = c.envelope.filter { it.position < firstLength })
        val second = c.copy(
            id = Ids.next("c"), position = at, length = c.length - firstLength,
            offset = c.offset + (firstLength / c.warp.stretch).toLong(), fadeIn = Fade(),
            envelope = c.envelope.filter { it.position >= firstLength }.map { it.copy(position = it.position - firstLength) }
        )
        return session.copy(clips = session.clips.flatMap { if (it.id == clipId) listOf(first, second) else listOf(it) })
    }

    /** Every clip crossing [at] split there. */
    fun splitAll(session: Session, at: Long, trackId: String? = null): Session =
        session.clipsIn(at, at + 1, trackId).filter { it.position < at }.fold(session) { s, c -> split(s, c.id, at) }

    /** The clip's head moved to [newStart], keeping what it plays in place (the source offset moves with it). */
    fun trimStart(session: Session, clipId: String, newStart: Long): Session {
        val c = session.clip(clipId) ?: return session
        val start = newStart.coerceIn(c.position - (c.offset * c.warp.stretch).toLong().coerceAtLeast(0), c.end - 1)
        val delta = start - c.position
        val source = session.source(c.sourceId)
        val newOffset = c.offset + (delta / c.warp.stretch).toLong()
        if (newOffset < 0) return session
        val newLength = c.length - delta
        if (source != null && newOffset + (newLength / c.warp.stretch).toLong() > source.frames) return session
        return replace(session, c.copy(position = start, offset = newOffset, length = newLength, envelope = c.envelope.map { it.copy(position = it.position - delta) }.filter { it.position >= 0 }))
    }

    /** The clip's tail moved to [newEnd]; cannot pass the source's end. */
    fun trimEnd(session: Session, clipId: String, newEnd: Long): Session {
        val c = session.clip(clipId) ?: return session
        val source = session.source(c.sourceId)
        val maxEnd = if (source == null) Long.MAX_VALUE else c.position + ((source.frames - c.offset) * c.warp.stretch).toLong()
        val end = newEnd.coerceIn(c.position + 1, maxEnd)
        val newLength = end - c.position
        return replace(session, c.copy(length = newLength, envelope = c.envelope.filter { it.position < newLength }))
    }

    /** The clip moved to [position] on [trackId] and [lane]; no ripple. */
    fun move(session: Session, clipId: String, position: Long, trackId: String? = null, lane: Int? = null): Session {
        val c = session.clip(clipId) ?: return session
        return replace(session, c.copy(position = position.coerceAtLeast(0), trackId = trackId ?: c.trackId, lane = lane ?: c.lane))
    }

    fun replace(session: Session, clip: Clip): Session = session.copy(clips = session.clips.map { if (it.id == clip.id) clip else it })

    fun update(session: Session, clipId: String, change: (Clip) -> Clip): Session {
        val c = session.clip(clipId) ?: return session
        return replace(session, change(c))
    }

    fun delete(session: Session, clipIds: Collection<String>): Session = session.copy(clips = session.clips.filter { it.id !in clipIds })

    fun add(session: Session, clip: Clip): Session = session.copy(clips = session.clips + clip)

    // ---- ranges ---------------------------------------------------------------------------

    /**
     * [from, to) taken out of [trackId] (or every track when null): clips inside go, clips
     * crossing an edge are trimmed, and when [ripple] everything after moves back by the gap -
     * on the chosen track alone, or on every track and the markers when the whole timeline is
     * cut, so a sound under a picture stays under it (Squish's sweep six: a stretch taken out
     * over a gap moved the sound further than the picture).
     */
    fun deleteRange(session: Session, from: Long, to: Long, trackId: String? = null, ripple: Boolean = true): Session {
        if (to <= from) return session
        var s = splitAll(session, from, trackId)
        s = splitAll(s, to, trackId)
        val gap = to - from
        val kept = s.clips.filter { c ->
            val inTrack = trackId == null || c.trackId == trackId
            !(inTrack && c.position >= from && c.end <= to)
        }.map { c ->
            val inTrack = trackId == null || c.trackId == trackId
            if (ripple && inTrack && c.position >= to) c.copy(position = c.position - gap) else c
        }
        var result = s.copy(clips = kept)
        if (ripple && trackId == null) {
            result = result.copy(
                markers = result.markers.filter { it.position < from || it.position >= to }.map { if (it.position >= to) it.copy(position = it.position - gap) else it },
                regions = result.regions.mapNotNull { r ->
                    val ns = if (r.start >= to) r.start - gap else if (r.start >= from) from else r.start
                    val ne = if (r.end >= to) r.end - gap else if (r.end > from) from else r.end
                    if (ne > ns) r.copy(start = ns, end = ne) else null
                },
                tracks = result.tracks.map { t -> t.copy(automation = t.automation.mapValues { (_, v) -> v.cut(from, to) }) },
                playhead = if (result.playhead >= to) result.playhead - gap else if (result.playhead > from) from else result.playhead,
                selection = null
            )
        } else if (ripple && trackId != null) {
            result = result.copy(tracks = result.tracks.map { t -> if (t.id == trackId) t.copy(automation = t.automation.mapValues { (_, v) -> v.cut(from, to) }) else t })
        }
        return result
    }

    /** [length] samples of silence opened at [at] on [trackId] (or everywhere): clips crossing are split, clips after move. */
    fun insertSilence(session: Session, at: Long, length: Long, trackId: String? = null): Session {
        if (length <= 0) return session
        val s = splitAll(session, at, trackId)
        return s.copy(
            clips = s.clips.map { c -> if ((trackId == null || c.trackId == trackId) && c.position >= at) c.copy(position = c.position + length) else c },
            markers = if (trackId == null) s.markers.map { if (it.position >= at) it.copy(position = it.position + length) else it } else s.markers,
            tracks = s.tracks.map { t -> if (trackId == null || t.id == trackId) t.copy(automation = t.automation.mapValues { (_, v) -> v.shifted(at, length) }) else t }
        )
    }

    /** Everything outside [from, to) removed and the remainder moved to start at 0. */
    fun crop(session: Session, from: Long, to: Long): Session {
        var s = deleteRange(session, to, max(to, session.length), null, ripple = false)
        s = deleteRange(s, 0, from, null, ripple = true)
        return s
    }

    /** A copy of the clips in [from, to) on [trackIds] (every track when empty), positions relative to [from]. */
    fun copyRange(session: Session, from: Long, to: Long, trackIds: Set<String> = emptySet()): Clipboard {
        val s = splitAll(splitAll(session, from), to)
        val clips = s.clips.filter { (trackIds.isEmpty() || it.trackId in trackIds) && it.position >= from && it.end <= to }
            .map { it.copy(position = it.position - from) }
        return Clipboard(clips, to - from, trackIds.isEmpty())
    }

    /**
     * The clipboard laid down at [at]: onto the tracks the clips came from, or onto [trackId]
     * when it holds one track's worth. With [insert], room is opened first (a ripple paste);
     * otherwise what is underneath is replaced.
     */
    fun paste(session: Session, clipboard: Clipboard, at: Long, trackId: String? = null, insert: Boolean = false): Session {
        if (clipboard.clips.isEmpty()) return session
        val sourceTracks = clipboard.clips.map { it.trackId }.toSet()
        var s = if (insert) insertSilence(session, at, clipboard.length, if (clipboard.allTracks) null else trackId)
        else deleteRange(session, at, at + clipboard.length, if (sourceTracks.size == 1 && trackId != null) trackId else null, ripple = false)
        val placed = clipboard.clips.map { c ->
            c.copy(id = Ids.next("c"), position = c.position + at, trackId = if (sourceTracks.size == 1 && trackId != null) trackId else c.trackId)
        }
        s = s.copy(clips = s.clips + placed.filter { p -> s.track(p.trackId) != null })
        return s
    }

    data class Clipboard(val clips: List<Clip>, val length: Long, val allTracks: Boolean)

    // ---- tracks ---------------------------------------------------------------------------

    fun addTrack(session: Session, track: Track, index: Int = session.tracks.size): Session =
        session.copy(tracks = session.tracks.toMutableList().also { it.add(index.coerceIn(0, it.size), track) })

    fun removeTrack(session: Session, trackId: String): Session =
        session.copy(tracks = session.tracks.filter { it.id != trackId }.map { t -> if (t.outputId == trackId) t.copy(outputId = null) else t.copy(sends = t.sends.filter { it.toTrackId != trackId }) },
            clips = session.clips.filter { it.trackId != trackId })

    fun updateTrack(session: Session, trackId: String, change: (Track) -> Track): Session =
        session.copy(tracks = session.tracks.map { if (it.id == trackId) change(it) else it })

    fun moveTrack(session: Session, trackId: String, toIndex: Int): Session {
        val list = session.tracks.toMutableList()
        val i = list.indexOfFirst { it.id == trackId }
        if (i < 0) return session
        val t = list.removeAt(i)
        list.add(toIndex.coerceIn(0, list.size), t)
        return session.copy(tracks = list)
    }

    // ---- markers and regions ---------------------------------------------------------------

    fun addMarker(session: Session, position: Long, name: String, kind: Marker.Kind = Marker.Kind.MARKER): Session =
        session.copy(markers = (session.markers + Marker(Ids.next("m"), position, name, kind)).sortedBy { it.position })

    fun removeMarker(session: Session, id: String): Session = session.copy(markers = session.markers.filter { it.id != id })

    fun addRegion(session: Session, start: Long, end: Long, name: String): Session =
        if (end <= start) session else session.copy(regions = (session.regions + Region(Ids.next("r"), start, end, name)).sortedBy { it.start })

    /** The marker nearest [position] within [within] samples, for snapping. */
    fun nearestMarker(session: Session, position: Long, within: Long): Marker? =
        session.markers.minByOrNull { kotlin.math.abs(it.position - position) }?.takeIf { kotlin.math.abs(it.position - position) <= within }

    /** Where a dragged edge should land: the nearest of the clip edges, markers, beats and the play-head within [within]. */
    fun snap(session: Session, position: Long, within: Long, beats: Boolean = true, excludeClipId: String? = null): Long {
        val candidates = ArrayList<Long>()
        session.clips.filter { it.id != excludeClipId }.forEach { candidates += it.position; candidates += it.end }
        session.markers.forEach { candidates += it.position }
        candidates += session.playhead
        candidates += 0L
        if (beats) candidates += session.beats(position - within, position + within + 1)
        val best = candidates.minByOrNull { kotlin.math.abs(it - position) } ?: return position
        return if (kotlin.math.abs(best - position) <= within) best else position
    }

    /** A new clip from a source, laid at [position] on [trackId], whole. */
    fun clipFor(source: Source, trackId: String, position: Long, sessionRate: Int): Clip {
        // A source at another rate is played at the session's rate by the graph's resampler, so its
        // timeline length is its frames scaled to the session's rate.
        val length = if (source.sampleRate == sessionRate) source.frames else source.frames * sessionRate / source.sampleRate
        return Clip(Ids.next("c"), source.id, trackId, position, 0, length, name = source.name)
    }

    fun minClipLength(sampleRate: Int): Long = max(1L, sampleRate / 1000L)   // a millisecond

    /** Fades fitted to a clip that shrank: a fade longer than the clip is the clip. */
    fun fitFades(clip: Clip): Clip = clip.copy(
        fadeIn = clip.fadeIn.copy(length = min(clip.fadeIn.length, clip.length)),
        fadeOut = clip.fadeOut.copy(length = min(clip.fadeOut.length, clip.length))
    )
}
