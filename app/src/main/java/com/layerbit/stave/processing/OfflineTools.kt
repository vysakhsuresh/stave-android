package com.layerbit.stave.processing

import com.layerbit.stave.engine.dsp.Compressor
import com.layerbit.stave.engine.dsp.CompressorSettings
import com.layerbit.stave.engine.dsp.EqBand
import com.layerbit.stave.engine.dsp.Fades
import com.layerbit.stave.engine.dsp.FadeShape
import com.layerbit.stave.engine.dsp.Gain
import com.layerbit.stave.engine.dsp.Gate
import com.layerbit.stave.engine.dsp.Limiter
import com.layerbit.stave.engine.dsp.LoudnessMeter
import com.layerbit.stave.engine.dsp.NoiseProfile
import com.layerbit.stave.engine.dsp.ParametricEq
import com.layerbit.stave.engine.dsp.Repair
import com.layerbit.stave.engine.dsp.Resampler
import com.layerbit.stave.engine.dsp.SpectralDenoiser
import com.layerbit.stave.engine.dsp.TimePitch
import com.layerbit.stave.engine.dsp.DeEsser
import com.layerbit.stave.engine.project.Clip
import com.layerbit.stave.engine.project.Ids
import com.layerbit.stave.engine.project.Session
import com.layerbit.stave.engine.project.SessionEdits
import com.layerbit.stave.engine.project.Source
import com.layerbit.stave.files.ProjectFiles
import com.layerbit.stave.files.RawAudio
import com.layerbit.stave.native.EngineNative
import com.layerbit.stave.native.StretchNative
import java.io.File

/**
 * The offline tools: each takes the samples a selection covers, changes them, and writes the
 * result as a *new* source the clip then points at, so the original stays on disk until the
 * project is deleted and Undo is a pointer swap. The arithmetic is the engine's; this file is
 * the plumbing between a Session, the raw files and the engine.
 */
object OfflineTools {

    /** Samples of a selection: the planar channels, the rate, and where they came from. */
    class Grab(val channels: List<FloatArray>, val sampleRate: Int, val clip: Clip, val source: Source)

    /** A transform over planar channels at a rate; may change the length (a stretch) but not the channel count. */
    fun interface Transform { fun apply(channels: List<FloatArray>, sampleRate: Int): List<FloatArray> }

    /**
     * Applies [transform] to [range] on [trackId] of [session]: every clip overlapping the range
     * is split at the range's edges, the parts inside are rendered to new sources and repointed.
     * Returns the new session, or the same one when nothing was in range.
     */
    fun applyToRange(session: Session, files: ProjectFiles, range: LongRange, trackId: String?, label: String, transform: Transform): Session {
        var s = SessionEdits.splitAll(session, range.first, trackId)
        s = SessionEdits.splitAll(s, range.last + 1, trackId)
        val inside = s.clips.filter { (trackId == null || it.trackId == trackId) && it.position >= range.first && it.end <= range.last + 1 && !it.muted }
        if (inside.isEmpty()) return session
        var out = s
        for (clip in inside) {
            val source = out.source(clip.sourceId) ?: continue
            val grab = read(files, clip, source, out.sampleRate) ?: continue
            val processed = transform.apply(grab.channels, grab.sampleRate)
            val rendered = writeSource(files, processed, grab.sampleRate, source, label)
            out = out.copy(sources = out.sources + rendered)
            val newLength = rendered.frames * out.sampleRate / rendered.sampleRate
            val delta = newLength - clip.length
            out = SessionEdits.replace(out, clip.copy(sourceId = rendered.id, offset = 0, length = newLength, warp = clip.warp.copy(stretch = 1.0, semitones = 0.0), reversed = false, sourceRateRatio = rendered.sampleRate.toDouble() / out.sampleRate))
            // A length change ripples what follows on the track, so a stretch does not overlap its neighbour.
            if (delta != 0L) out = out.copy(clips = out.clips.map { c -> if (c.id != clip.id && c.trackId == clip.trackId && c.position >= clip.end) c.copy(position = c.position + delta) else c })
        }
        return out
    }

    /** The clip's source frames as planar channels at the source's rate, the warp applied (a stretched clip's samples are what is heard). */
    fun read(files: ProjectFiles, clip: Clip, source: Source, sessionRate: Int): Grab? {
        val file = files.sourceFile(source.file)
        if (!file.isFile) return null
        val srcFrames = clip.sourceLength.coerceAtMost(source.frames - clip.offset).toInt()
        if (srcFrames <= 0) return null
        val inter = RawAudio.read(file, source.channels, clip.offset, srcFrames)
        val frames = inter.size / source.channels
        var channels = List(source.channels) { c -> FloatArray(frames) { f -> inter[f * source.channels + c] } }
        if (clip.reversed) channels = channels.map { it.reversedArray() }
        if (!clip.warp.isIdentity) channels = stretch(channels, source.sampleRate, clip.warp.stretch, clip.warp.semitones, clip.warp.preserveFormant)
        // The clip's own gain is part of what is heard; it stays on the clip, so the samples are left at unity here.
        return Grab(channels, source.sampleRate, clip, source)
    }

    /** Planar channels written interleaved as a new RENDER source beside [parent]. */
    fun writeSource(files: ProjectFiles, channels: List<FloatArray>, sampleRate: Int, parent: Source, label: String): Source {
        val id = Ids.next("s")
        val out = files.newSourceFile(id)
        val n = channels.first().size
        val ch = channels.size
        val inter = FloatArray(n * ch)
        for (c in 0 until ch) { val a = channels[c]; for (f in 0 until n) inter[f * ch + c] = a[f] }
        RawAudio.write(out, inter)
        return Source(id, "${parent.name} · $label", "media/${out.name}", sampleRate, ch, n.toLong(), Source.Kind.RENDER, parentId = parent.id)
    }

    /** Rubber Band when the library is here, the engine's phase vocoder when it is not (the JVM, or a build without it). */
    fun stretch(channels: List<FloatArray>, sampleRate: Int, ratio: Double, semitones: Double, formant: Boolean): List<FloatArray> {
        if (ratio == 1.0 && semitones == 0.0) return channels
        if (EngineNative.loaded) {
            val n = channels.first().size; val ch = channels.size
            val inter = FloatArray(n * ch); for (c in 0 until ch) for (f in 0 until n) inter[f * ch + c] = channels[c][f]
            val out = runCatching { StretchNative.stretch(inter, ch, sampleRate, ratio, semitones, formant) }.getOrNull()
            if (out != null && out.isNotEmpty()) { val m = out.size / ch; return List(ch) { c -> FloatArray(m) { f -> out[f * ch + c] } } }
        }
        return channels.map { TimePitch.pitchShift(TimePitch.stretch(it, ratio, sampleRate), semitones, sampleRate) }
    }

    // ---- the tools, as transforms -----------------------------------------------------------

    fun gain(db: Double) = Transform { chs, _ -> chs.map { a -> a.copyOf().also { Gain.apply(it, Gain.dbToLinear(db).toFloat()) } } }

    fun normalisePeak(targetDbfs: Double) = Transform { chs, _ ->
        val peak = chs.maxOf { Gain.peak(it) }
        val g = Gain.normalisePeak(peak, targetDbfs.toFloat())
        chs.map { a -> a.copyOf().also { Gain.apply(it, g) } }
    }

    fun normaliseLoudness(targetLufs: Double, ceilingDbtp: Double) = Transform { chs, rate ->
        val inter = interleave(chs)
        // As the mix will play it: a mono source sounds on both channels (integratedAsPlayed).
        val measured = LoudnessMeter.integratedAsPlayed(inter, rate, chs.size)
        val g = Gain.normaliseLoudness(measured, targetLufs)
        Gain.apply(inter, g)
        val lim = Limiter(rate, chs.size, ceilingDbtp, 5.0, 60.0)
        lim.process(inter)
        deinterleave(shiftBack(inter, lim.latency, chs.size), chs.size)
    }

    fun fadeIn(length: Int, shape: FadeShape) = Transform { chs, _ -> chs.map { a -> a.copyOf().also { Fades.fadeIn(it, length, shape) } } }
    fun fadeOut(length: Int, shape: FadeShape) = Transform { chs, _ -> chs.map { a -> a.copyOf().also { Fades.fadeOut(it, length, shape) } } }
    fun reverse() = Transform { chs, _ -> chs.map { it.reversedArray() } }
    fun silence() = Transform { chs, _ -> chs.map { FloatArray(it.size) } }
    fun invert() = Transform { chs, _ -> chs.map { a -> FloatArray(a.size) { -a[it] } } }
    fun dcRemove() = Transform { chs, _ -> chs.map { a -> a.copyOf().also { Repair.removeDc(it) } } }

    fun declick(sensitivity: Float) = Transform { chs, _ -> chs.map { a -> a.copyOf().also { Repair.declick(it, sensitivity) } } }
    fun declip(threshold: Float) = Transform { chs, _ ->
        val out = chs.map { a -> a.copyOf().also { Repair.declip(it, threshold) } }
        // The mend can pass full scale; bring the whole back under -0.5 dBFS so nothing clips again.
        val peak = out.maxOf { Gain.peak(it) }
        if (peak > 0.94f) { val g = 0.94f / peak; out.forEach { Gain.apply(it, g) } }
        out
    }
    fun dehum(fundamentalHz: Double, harmonics: Int) = Transform { chs, rate -> chs.map { a -> a.copyOf().also { Repair.dehum(it, rate, fundamentalHz, harmonics) } } }

    /** Denoise with a profile learned from [noiseOnly] (planar, same rate) or estimated from the sound itself when null. */
    fun denoise(reductionDb: Double, strength: Float, noiseOnly: List<FloatArray>?) = Transform { chs, rate ->
        val d = SpectralDenoiser(rate)
        chs.mapIndexed { i, a ->
            val profile = noiseOnly?.getOrNull(i)?.let { NoiseProfile.learn(d.stft, it) } ?: NoiseProfile.estimate(d.stft, a, rate)
            d.process(a, profile, reductionDb, strength)
        }
    }

    fun eq(bands: List<EqBand>) = Transform { chs, rate ->
        val eq = ParametricEq(bands, rate, chs.size)
        chs.mapIndexed { c, a -> a.copyOf().also { eq.processChannel(c, it) } }
    }

    fun compress(settings: CompressorSettings) = Transform { chs, rate ->
        val inter = interleave(chs); Compressor(rate, chs.size, settings).process(inter); deinterleave(inter, chs.size)
    }

    /** One per-sample effect built per channel ([build] gets the rate and the channel index, so a chorus can offset its LFO on the right). */
    fun perSample(build: (rate: Int, channel: Int) -> (Float) -> Float) = Transform { chs, rate -> chs.mapIndexed { c, a -> val fx = build(rate, c); FloatArray(a.size) { fx(a[it]) } } }

    fun delay(timeMs: Double, feedback: Float, mix: Float, dampHz: Double) = perSample { r, _ -> val d = com.layerbit.stave.engine.dsp.Delay(r, timeMs, feedback, mix, dampHz); { x -> d.process(x) } }
    fun chorus(rateHz: Double, depthMs: Double, mix: Float) = perSample { r, c -> val ch = com.layerbit.stave.engine.dsp.Chorus(r, rateHz, depthMs, 18.0, mix, if (c == 1) Math.PI / 2 else 0.0); { x -> ch.process(x) } }
    fun flanger(rateHz: Double, depthMs: Double, feedback: Float, mix: Float) = perSample { r, _ -> val f = com.layerbit.stave.engine.dsp.Flanger(r, rateHz, depthMs, feedback, mix); { x -> f.process(x) } }
    fun phaser(rateHz: Double, depth: Float, feedback: Float, mix: Float) = perSample { r, _ -> val p = com.layerbit.stave.engine.dsp.Phaser(r, rateHz, depth, feedback, mix); { x -> p.process(x) } }
    fun tremolo(rateHz: Double, depth: Float) = perSample { r, _ -> val t = com.layerbit.stave.engine.dsp.Tremolo(r, rateHz, depth); { x -> t.process(x) } }
    fun reverb(decay: Float, damping: Float, mix: Float, preDelayMs: Double) = perSample { r, _ -> val v = com.layerbit.stave.engine.dsp.Reverb(r, decay, damping, mix, preDelayMs); { x -> v.process(x) } }
    fun saturate(drive: Float, mix: Float) = perSample { _, _ -> val sat = com.layerbit.stave.engine.dsp.Saturator(drive, mix); { x -> sat.process(x) } }
    fun crush(bits: Int, hold: Int) = perSample { _, _ -> val cr = com.layerbit.stave.engine.dsp.Crusher(bits, hold); { x -> cr.process(x) } }

    /** Stereo housekeeping: the channels swapped; one side on both; the balance moved by [db] toward the right (negative, the left). */
    fun swapChannels() = Transform { chs, _ -> if (chs.size < 2) chs else listOf(chs[1].copyOf(), chs[0].copyOf()) + chs.drop(2) }
    fun oneSide(right: Boolean) = Transform { chs, _ -> if (chs.size < 2) chs else { val side = chs[if (right) 1 else 0].copyOf(); listOf(side, side.copyOf()) + chs.drop(2) } }
    fun balance(db: Double) = Transform { chs, _ ->
        if (chs.size < 2) chs else {
            val l = chs[0].copyOf(); val r = chs[1].copyOf()
            if (db > 0) Gain.apply(l, Gain.dbToLinear(-db).toFloat()) else Gain.apply(r, Gain.dbToLinear(db).toFloat())
            listOf(l, r) + chs.drop(2)
        }
    }

    fun multiband(lowHz: Double, highHz: Double, bands: List<CompressorSettings>) = Transform { chs, rate ->
        val inter = interleave(chs); com.layerbit.stave.engine.dsp.MultibandCompressor(rate, chs.size, lowHz, highHz, bands).process(inter); deinterleave(inter, chs.size)
    }

    fun transients(attack: Double, sustain: Double) = Transform { chs, rate ->
        val inter = interleave(chs); com.layerbit.stave.engine.dsp.TransientShaper(rate, chs.size, attack, sustain).process(inter); deinterleave(inter, chs.size)
    }

    /** Mid/side width on a stereo pair; a mono source is left alone, there being no side to scale. */
    fun width(amount: Double) = Transform { chs, _ ->
        if (chs.size < 2) chs else { val l = chs[0].copyOf(); val r = chs[1].copyOf(); com.layerbit.stave.engine.dsp.Stereo.width(l, r, amount); listOf(l, r) + chs.drop(2) }
    }

    fun limit(ceilingDb: Double, releaseMs: Double) = Transform { chs, rate ->
        val inter = interleave(chs); val lim = Limiter(rate, chs.size, ceilingDb, 5.0, releaseMs); lim.process(inter); deinterleave(shiftBack(inter, lim.latency, chs.size), chs.size)
    }

    fun gate(thresholdDb: Double, rangeDb: Double, releaseMs: Double) = Transform { chs, rate ->
        val inter = interleave(chs); Gate(rate, chs.size, thresholdDb, rangeDb, releaseMs = releaseMs).process(inter); deinterleave(inter, chs.size)
    }

    fun deess(hz: Double, thresholdDb: Double) = Transform { chs, rate ->
        val inter = interleave(chs); DeEsser(rate, chs.size, hz, thresholdDb).process(inter); deinterleave(inter, chs.size)
    }

    fun timePitch(ratio: Double, semitones: Double, formant: Boolean) = Transform { chs, rate -> stretch(chs, rate, ratio, semitones, formant) }
    fun speed(factor: Double) = Transform { chs, rate -> chs.map { TimePitch.speed(it, factor, rate) } }
    fun resample(toRate: Int) = Transform { chs, rate -> chs.map { Resampler(rate, toRate).convert(it) } }

    fun toMono() = Transform { chs, _ ->
        if (chs.size == 1) chs else { val n = chs.first().size; val m = FloatArray(n); for (a in chs) for (i in 0 until n) m[i] += a[i] / chs.size; chs.map { m.copyOf() } }
    }

    // ---- helpers -----------------------------------------------------------------------------

    fun interleave(chs: List<FloatArray>): FloatArray {
        val n = chs.first().size; val ch = chs.size
        return FloatArray(n * ch).also { out -> for (c in 0 until ch) { val a = chs[c]; for (f in 0 until n) out[f * ch + c] = a[f] } }
    }

    fun deinterleave(inter: FloatArray, ch: Int): List<FloatArray> { val n = inter.size / ch; return List(ch) { c -> FloatArray(n) { f -> inter[f * ch + c] } } }

    /** A look-ahead processor delays its output; moved back so the result lines up with the input. */
    private fun shiftBack(inter: FloatArray, latencyFrames: Int, ch: Int): FloatArray {
        if (latencyFrames <= 0) return inter
        val out = FloatArray(inter.size)
        val shift = latencyFrames * ch
        System.arraycopy(inter, shift, out, 0, inter.size - shift)
        return out
    }

    /** The planar samples of a whole source at its rate, for analysis (loudness, hum, pitch). */
    fun readSource(files: ProjectFiles, source: Source, maxFrames: Int = Int.MAX_VALUE): List<FloatArray> {
        val file = files.sourceFile(source.file)
        val frames = minOf(source.frames, maxFrames.toLong()).toInt()
        val inter = RawAudio.read(file, source.channels, 0, frames)
        return deinterleave(inter, source.channels)
    }
}
