package com.layerbit.stave.engine.export

import com.layerbit.stave.engine.dsp.Dither
import com.layerbit.stave.engine.dsp.Gain
import com.layerbit.stave.engine.dsp.Limiter
import com.layerbit.stave.engine.dsp.LoudnessMeter
import com.layerbit.stave.engine.dsp.Resampler

/**
 * What happens to a rendered mix between the graph and the encoder: fold to mono, gain to a
 * loudness target under a true-peak limiter, convert the rate, trim to the true-peak ceiling,
 * dither to the file's bit depth. All of it in blocks, a few passes over files, so an hour of
 * podcast costs the same memory as a jingle. The Exporter held the whole song as floats in up to
 * three copies until 2 November; an hour of stereo is 1.4 GB of floats.
 *
 * The true peak is read on the final signal at the final rate; the earlier whole-song version
 * read it before the rate conversion, which moves inter-sample peaks.
 */
object ExportChain {

    /** The mix, interleaved float frames. */
    interface Source {
        val frames: Long
        val channels: Int
        /** Fills [into] with [count] frames starting at [from]. */
        fun read(from: Long, count: Int, into: FloatArray)
    }

    /** Where the processed signal is staged for the encoder: appended once, then read and rewritten in place. */
    interface Stage {
        fun append(block: FloatArray, frames: Int)
        fun read(from: Long, count: Int, into: FloatArray)
        fun overwrite(from: Long, block: FloatArray, frames: Int)
    }

    class Plan(
        val rate: Int,
        val outRate: Int,
        val outChannels: Int,
        /** Integrated loudness and true-peak ceiling to reach, or null to leave the level alone. */
        val targetLufs: Double? = null,
        val ceilingDbtp: Double = -1.0,
        /** 16 or 24 to dither for an integer file, null for none (lossy formats quantise nothing). */
        val ditherBits: Int? = null
    )

    class Report(val frames: Long, val integratedLufs: Double, val truePeakDbtp: Double, val gainDb: Double)

    const val BLOCK = 8192

    fun run(source: Source, stage: Stage, plan: Plan, block: Int = BLOCK, progress: (Float) -> Unit = {}, alive: () -> Unit = {}): Report {
        val inCh = source.channels
        val outCh = plan.outChannels
        val n = source.frames
        val inBuf = FloatArray(block * inCh)
        val mixBuf = FloatArray(block * outCh)
        val passes = if (plan.targetLufs != null) 3f else 2f

        fun fold(frames: Int) {
            if (outCh == inCh) { inBuf.copyInto(mixBuf, 0, 0, frames * inCh); return }
            // Stereo to mono as the average: a centred voice keeps its level.
            for (f in 0 until frames) { var s = 0f; for (c in 0 until inCh) s += inBuf[f * inCh + c]; mixBuf[f] = s / inCh }
        }

        // Pass 1: the loudness the folded mix has now.
        var gain = 1f
        if (plan.targetLufs != null) {
            val meter = LoudnessMeter(plan.rate, outCh)
            var pos = 0L
            while (pos < n) {
                alive()
                val m = minOf(block.toLong(), n - pos).toInt()
                source.read(pos, m, inBuf); fold(m); meter.process(mixBuf, m)
                pos += m
                progress(pos.toFloat() / n / passes)
            }
            gain = Gain.normaliseLoudness(meter.reading().integratedLufs, plan.targetLufs)
        }

        // Pass 2: gain, the limiter (half a decibel under the ceiling: it holds samples, the target
        // is a true peak), the rate. The limiter's look-ahead is fed silence at the end and its
        // first frames dropped, so the output lines up with the input frame for frame.
        val limiter = if (plan.targetLufs != null) Limiter(plan.rate, outCh, plan.ceilingDbtp - 0.5, 5.0, 60.0) else null
        val latency = limiter?.latency ?: 0
        val resampler = if (plan.outRate != plan.rate) Resampler(plan.rate, plan.outRate).stream(outCh) else null
        val meter = LoudnessMeter(plan.outRate, outCh)
        var staged = 0L
        fun emit(block: FloatArray, frames: Int) {
            if (frames <= 0) return
            meter.process(block, frames)
            stage.append(block, frames)
            staged += frames
        }
        fun toRate(buf: FloatArray, frames: Int) {
            if (resampler == null) emit(buf, frames) else resampler.push(buf, frames).let { emit(it, it.size / outCh) }
        }
        var skip = latency
        var pos = 0L
        val total = n + latency
        while (pos < total) {
            alive()
            val m = minOf(block.toLong(), total - pos).toInt()
            val real = (n - pos).coerceIn(0, m.toLong()).toInt()
            if (real > 0) { source.read(pos, real, inBuf); fold(real) }
            for (i in real * outCh until m * outCh) mixBuf[i] = 0f
            if (gain != 1f) for (i in 0 until real * outCh) mixBuf[i] *= gain
            limiter?.process(mixBuf, m)
            val from = minOf(skip, m)
            skip -= from
            if (from == 0) toRate(mixBuf, m) else if (m > from) toRate(mixBuf.copyOfRange(from * outCh, m * outCh), m - from)
            pos += m
            progress((if (plan.targetLufs != null) 1f else 0f) / passes + pos.toFloat() / total / passes)
        }
        resampler?.finish()?.let { emit(it, it.size / outCh) }

        // Pass 3: the true-peak trim and the dither, on the staged signal in place.
        var reading = meter.reading()
        var trimDb = 0.0
        if (plan.targetLufs != null && reading.truePeakDbtp > plan.ceilingDbtp) trimDb = plan.ceilingDbtp - reading.truePeakDbtp - 0.05
        if (trimDb != 0.0 || plan.ditherBits != null) {
            val trim = Gain.dbToLinear(trimDb).toFloat()
            val dither = plan.ditherBits?.let { Dither(it) }
            val after = LoudnessMeter(plan.outRate, outCh)
            val buf = FloatArray(block * outCh)
            var at = 0L
            while (at < staged) {
                alive()
                val m = minOf(block.toLong(), staged - at).toInt()
                stage.read(at, m, buf)
                if (trim != 1f) for (i in 0 until m * outCh) buf[i] *= trim
                after.process(buf, m)
                if (dither != null) for (i in 0 until m * outCh) buf[i] = dither.apply(buf[i])
                stage.overwrite(at, buf, m)
                at += m
                progress((passes - 1f) / passes + at.toFloat() / staged / passes)
            }
            reading = after.reading()
        }
        return Report(staged, reading.integratedLufs, reading.truePeakDbtp, Gain.linearToDb(gain.toDouble()) + trimDb)
    }
}
