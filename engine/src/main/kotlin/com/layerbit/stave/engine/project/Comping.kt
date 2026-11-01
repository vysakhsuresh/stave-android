package com.layerbit.stave.engine.project

/**
 * Comping by swipe: a stretch of one lane chosen to be heard, the other lanes of the track
 * silenced there and left as they were elsewhere. Clips are split at the stretch's edges so
 * the mute lands only on the piece inside it; a lane's clip wholly inside the stretch is
 * muted whole, one wholly outside is untouched. The pieces keep their source and offset, so a
 * comp is reversible to the sample: Undo, or Hear all, puts every piece back.
 */
object Comping {

    /** [from, to) of [trackId] heard from [lane] alone. */
    fun use(session: Session, trackId: String, lane: Int, from: Long, to: Long): Session {
        if (to <= from) return session
        var s = session
        // Split every clip on the track that straddles an edge, so the stretch is whole clips.
        for (edge in listOf(from, to)) {
            for (c in s.clipsOn(trackId)) if (c.position < edge && c.end > edge) s = SessionEdits.split(s, c.id, edge)
        }
        return s.copy(clips = s.clips.map { c ->
            if (c.trackId != trackId || c.end <= from || c.position >= to) c
            else c.copy(muted = c.lane != lane)
        })
    }

    /** Every lane heard again over [from, to), or over the whole track when no stretch is given. */
    fun hearAll(session: Session, trackId: String, from: Long = 0, to: Long = Long.MAX_VALUE): Session =
        session.copy(clips = session.clips.map { c -> if (c.trackId == trackId && c.end > from && c.position < to) c.copy(muted = false) else c })

    /** The lanes a track's clips sit on, in order. */
    fun lanes(session: Session, trackId: String): List<Int> = session.clipsOn(trackId).map { it.lane }.distinct().sorted()

    /** Which lane is heard at [position] on [trackId]: the one with an unmuted clip there, or null when none or several. */
    fun heardAt(session: Session, trackId: String, position: Long): Int? {
        val heard = session.clipsOn(trackId).filter { !it.muted && it.contains(position) }.map { it.lane }.distinct()
        return heard.singleOrNull()
    }
}
