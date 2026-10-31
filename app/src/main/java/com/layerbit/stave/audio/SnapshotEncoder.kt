package com.layerbit.stave.audio

import com.layerbit.stave.engine.dsp.FadeShape
import com.layerbit.stave.engine.dsp.Gain
import com.layerbit.stave.engine.project.Insert
import com.layerbit.stave.engine.project.Session
import com.layerbit.stave.engine.project.Track
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A Session as the bytes the graph reads (app/src/main/cpp/graph/Snapshot.cpp documents the
 * layout; the two must move together). Built on the UI thread on every change the graph needs to
 * hear; a fader under the finger goes through EngineNative.setTrackVolume instead and the
 * snapshot catches up on release.
 */
object SnapshotEncoder {
    private const val MAGIC = 0x4E535453   // 'STSN' little-endian
    private const val VERSION = 1

    /** Insert kinds the graph knows: the rest are rendered offline and arrive as a new source. */
    private val insertKinds = mapOf("eq" to 1, "compressor" to 2, "limiter" to 3, "gate" to 4)

    fun encode(session: Session, projectDir: File, generation: Int): ByteArray {
        val buf = ByteBuffer.allocate(estimate(session)).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(MAGIC).putInt(VERSION).putInt(session.sampleRate).putInt(generation)
        val sourceIndex = session.sources.withIndex().associate { it.value.id to it.index }
        buf.putInt(session.sources.size)
        for (s in session.sources) {
            buf.putInt(s.sampleRate).putInt(s.channels).putLong(s.frames)
            val path = File(projectDir, s.file).path.toByteArray(Charsets.UTF_8)
            buf.putShort(path.size.toShort()).put(path)
        }
        val trackIndex = session.tracks.withIndex().associate { it.value.id to it.index }
        buf.putInt(session.tracks.size)
        for (t in session.tracks) {
            buf.put(when (t.kind) { Track.Kind.AUDIO -> 0; Track.Kind.MIDI -> 1; Track.Kind.BUS -> 2; Track.Kind.MASTER -> 3 }.toByte())
            buf.putFloat(Gain.dbToLinear(t.volumeDb)).putFloat(t.pan.toFloat())
            buf.put(flag(t.muted)).put(flag(t.solo)).put(flag(t.armed))
            buf.putInt(t.outputId?.let { trackIndex[it] } ?: -1)
            inserts(buf, t.inserts)
            buf.putShort(t.sends.size.toShort())
            for (s in t.sends) { buf.putInt(trackIndex[s.toTrackId] ?: -1).putFloat(Gain.dbToLinear(s.gainDb)).put(flag(s.preFader)) }
        }
        val clips = session.clips.filter { it.sourceId in sourceIndex && it.trackId in trackIndex }
        buf.putInt(clips.size)
        for (c in clips) {
            buf.putInt(trackIndex[c.trackId]!!).putInt(sourceIndex[c.sourceId]!!)
            buf.putLong(c.position).putLong(c.offset).putLong(c.length).putFloat(Gain.dbToLinear(c.gainDb))
            buf.putLong(c.fadeIn.length).put(shape(c.fadeIn.shape)).putLong(c.fadeOut.length).put(shape(c.fadeOut.shape))
            buf.put(flag(c.muted)).put(flag(c.reversed)).putDouble(c.warp.stretch)
            buf.putShort(c.envelope.size.toShort())
            for (p in c.envelope) { buf.putLong(p.position).putFloat(p.gainDb.toFloat()) }
        }
        buf.putFloat(Gain.dbToLinear(session.masterVolumeDb))
        inserts(buf, session.masterInserts)
        return buf.array().copyOf(buf.position())
    }

    private fun inserts(buf: ByteBuffer, list: List<Insert>) {
        val known = list.filter { it.kind in insertKinds }
        buf.putShort(known.size.toShort())
        for (i in known) {
            buf.put(insertKinds[i.kind]!!.toByte()).put(flag(i.enabled))
            val params = paramOrder[i.kind] ?: emptyList()
            buf.putShort(params.size.toShort())
            for ((name, default) in params) buf.putFloat(i.param(name, default).toFloat())
            buf.putShort(i.bands.size.toShort())
            for (b in i.bands) {
                buf.put((b["kind"] ?: 5.0).toInt().toByte()).putFloat((b["hz"] ?: 1000.0).toFloat()).putFloat((b["gain"] ?: 0.0).toFloat()).putFloat((b["q"] ?: 0.707).toFloat()).put(flag((b["enabled"] ?: 1.0) != 0.0))
            }
        }
    }

    /** The parameters each kind carries, in the order the graph reads them (Engine.cpp configureInserts). */
    val paramOrder: Map<String, List<Pair<String, Double>>> = mapOf(
        "eq" to emptyList(),
        "compressor" to listOf("threshold" to -18.0, "ratio" to 3.0, "knee" to 6.0, "attack" to 10.0, "release" to 120.0, "makeup" to 0.0),
        "limiter" to listOf("ceiling" to -1.0, "lookahead" to 5.0, "release" to 60.0),
        "gate" to listOf("threshold" to -45.0, "range" to 80.0, "attack" to 2.0, "release" to 150.0, "hold" to 50.0)
    )

    private fun flag(b: Boolean): Byte = if (b) 1 else 0
    private fun shape(s: FadeShape): Byte = s.ordinal.toByte()

    /** An upper bound on the encoded size, so the buffer is allocated once. */
    private fun estimate(s: Session): Int {
        fun insertsBytes(list: List<Insert>) = 2 + list.sumOf { 4 + 2 + 4 * 8 + 2 + it.bands.size * 14 }
        return 64 +
            s.sources.sumOf { 18 + it.file.toByteArray(Charsets.UTF_8).size + 1024 } +   // the project dir's own path is under a kilobyte
            s.tracks.sumOf { 32 + insertsBytes(it.inserts) + 2 + it.sends.size * 9 } +
            s.clips.sumOf { 80 + it.envelope.size * 12 } +
            insertsBytes(s.masterInserts)
    }
}
