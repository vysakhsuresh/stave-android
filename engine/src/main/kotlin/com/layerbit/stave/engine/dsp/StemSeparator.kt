package com.layerbit.stave.engine.dsp

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.math.hypot

/**
 * Stems from a stereo mix: the shape Open-Unmix (tools/models/umx_to_tflite.py) was trained on,
 * with the models handed in as [StemModel]s so the arithmetic around them is checked on the JVM
 * with an oracle.
 *
 * The STFT is torch.stft's: a periodic Hann of [SIZE], hop [HOP], frames centred on f * HOP
 * (the signal zero-padded by SIZE / 2 at both ends), unnormalised. The first runner analysed
 * frames starting at f * HOP and resynthesised frames starting 3 hops earlier, so every mask
 * landed 70 ms from its frame; StemSeparatorChecks holds the alignment with an oracle model.
 *
 * One model is open at a time. Each runs over the whole song, [WINDOW] frames a call (the
 * converted models' fixed shape; the last call is zero-padded), each frame's estimate taken from
 * a call where it has [CONTEXT] frames on both sides, and its estimates go to a spool file as
 * 16-bit floats. Then one pass reads the four spools together: each stem gets its Wiener share of
 * the mixture's complex spectrum (its estimate squared over the sum of all estimates squared), so
 * the stems sum to the mix, and the inverse is overlap-added through the same window and divided
 * by the summed squared window. Four models open together held 3.2 GB of native memory on the
 * phone; a spool is about 120 MB of disk per stem for six minutes.
 */
class StemSeparator(val stemCount: Int, private val spoolDir: File) {

    /** One stem's model, opened for its pass and closed after it. */
    interface StemModel : AutoCloseable {
        /** Fills [output] (WINDOW x 2 x BINS magnitudes) from [input] of the same shape, frames from [firstFrame]. */
        fun infer(firstFrame: Int, input: FloatArray, output: FloatArray)
    }

    companion object {
        const val SIZE = 4096
        const val HOP = 1024
        const val BINS = SIZE / 2 + 1
        /** The converted models' fixed length (umx_to_tflite.py T): LiteRT's XNNPACK memory grows with it. */
        const val WINDOW = 128
        const val CONTEXT = 21

        /** Frames for [samples] samples: centred frames up to and including the one on the last sample. */
        fun framesFor(samples: Int): Int = if (samples <= 0) 0 else samples / HOP + 1
    }

    private val fft = Fft.of(SIZE)
    private val window = Windows.hann(SIZE)
    private val fr = FloatArray(SIZE)
    private val fi = FloatArray(SIZE)

    /**
     * Separates [left] and [right] (the same length, at the model's rate), opening each stem's
     * model with [open] in turn. [sink] receives each stem's next [count] samples of left and
     * right, in order, from sample 0 to the end; the arrays are reused, so the sink copies what it
     * keeps. [onProgress] runs 0..1 over the model passes and the resynthesis.
     */
    fun run(
        left: FloatArray, right: FloatArray, open: (stem: Int) -> StemModel,
        sink: (stem: Int, l: FloatArray, r: FloatArray, count: Int) -> Unit, onProgress: (Float) -> Unit = {}
    ) {
        val n = left.size
        require(right.size == n)
        val frames = framesFor(n)
        if (frames == 0) return
        spoolDir.mkdirs()
        val spools = List(stemCount) { File(spoolDir, "stem-estimate-$it-${System.nanoTime()}.f16") }
        try {
            val passes = stemCount + 1
            for (s in 0 until stemCount) {
                open(s).use { model -> estimate(left, right, frames, model, spools[s]) { p -> onProgress((s + p) / passes) } }
            }
            resynthesise(left, right, frames, spools, sink) { p -> onProgress((stemCount + p) / passes) }
            onProgress(1f)
        } finally { spools.forEach { it.delete() } }
    }

    /** One model over the song: every frame's 2 x BINS estimate, in frame order, as halves. */
    private fun estimate(left: FloatArray, right: FloatArray, frames: Int, model: StemModel, spool: File, onProgress: (Float) -> Unit) {
        val step = WINDOW - 2 * CONTEXT
        val input = FloatArray(WINDOW * 2 * BINS)
        val output = FloatArray(WINDOW * 2 * BINS)
        val re = FloatArray(BINS); val im = FloatArray(BINS)
        DataOutputStream(BufferedOutputStream(FileOutputStream(spool), 1 shl 16)).use { out ->
            var next = 0
            var callStart = 0
            while (next < frames) {
                val t = minOf(WINDOW, frames - callStart)
                val keepEnd = if (callStart + WINDOW >= frames) frames else callStart + WINDOW - CONTEXT
                input.fill(0f)
                for (j in 0 until t) for (c in 0 until 2) {
                    analyse(if (c == 0) left else right, callStart + j, re, im)
                    val base = (j * 2 + c) * BINS
                    for (k in 0 until BINS) input[base + k] = hypot(re[k], im[k])
                }
                model.infer(callStart, input, output)
                while (next < keepEnd) {
                    val base = (next - callStart) * 2 * BINS
                    for (k in 0 until 2 * BINS) out.writeShort(Half.of(output[base + k]).toInt())
                    next++
                }
                onProgress(next.toFloat() / frames)
                callStart += step
            }
        }
    }

    /** The four spools read together: each frame's Wiener shares applied, inverted and overlap-added. */
    private fun resynthesise(
        left: FloatArray, right: FloatArray, frames: Int, spools: List<File>,
        sink: (Int, FloatArray, FloatArray, Int) -> Unit, onProgress: (Float) -> Unit
    ) {
        val n = left.size
        val ins = spools.map { DataInputStream(BufferedInputStream(FileInputStream(it), 1 shl 16)) }
        try {
            val est = Array(stemCount) { FloatArray(2 * BINS) }
            val re = Array(2) { FloatArray(BINS) }; val im = Array(2) { FloatArray(BINS) }
            // Overlap-add: per stem and channel, SIZE samples starting at the next frame's first sample.
            val acc = Array(stemCount) { Array(2) { FloatArray(SIZE) } }
            val norm = FloatArray(SIZE)
            val outL = FloatArray(HOP); val outR = FloatArray(HOP)
            for (f in 0 until frames) {
                for (s in 0 until stemCount) for (k in 0 until 2 * BINS) est[s][k] = Half.toFloat(ins[s].readShort())
                analyse(left, f, re[0], im[0]); analyse(right, f, re[1], im[1])
                for (c in 0 until 2) for (s in 0 until stemCount) {
                    val base = c * BINS
                    for (k in 0 until BINS) {
                        var total = 0f
                        for (o in 0 until stemCount) { val e = est[o][base + k]; total += e * e }
                        val e = est[s][base + k]
                        // Where every model says nothing (their last layer is a ReLU), the bin is
                        // shared out evenly rather than dropped from all four: on the phone such
                        // bins left the stems summing to the mix only to -25 dB.
                        val share = if (total > 1e-30f) e * e / total else 1f / stemCount
                        fr[k] = re[c][k] * share; fi[k] = im[c][k] * share
                    }
                    for (k in 1 until SIZE / 2) { fr[SIZE - k] = fr[k]; fi[SIZE - k] = -fi[k] }
                    fft.inverse(fr, fi)
                    val a = acc[s][c]
                    for (i in 0 until SIZE) a[i] += fr[i] * window[i]
                }
                for (i in 0 until SIZE) norm[i] += window[i] * window[i]
                // The first HOP samples of this frame are now final (no later frame reaches them).
                emit(acc, norm, f * HOP - SIZE / 2, n, outL, outR, sink)
                if (f % 64 == 0) onProgress(f.toFloat() / frames)
            }
            var first = frames * HOP - SIZE / 2
            while (first < n) { emit(acc, norm, first, n, outL, outR, sink); first += HOP }
        } finally { ins.forEach { runCatching { it.close() } } }
    }

    /** Hands the first HOP samples of [acc] (absolute start [first]) to [sink], clipped to [0, n), and slides everything on. */
    private fun emit(acc: Array<Array<FloatArray>>, norm: FloatArray, first: Int, n: Int, outL: FloatArray, outR: FloatArray, sink: (Int, FloatArray, FloatArray, Int) -> Unit) {
        val from = maxOf(0, -first); val to = minOf(HOP, n - first)
        for (s in acc.indices) {
            if (to > from) {
                for (i in from until to) {
                    val d = norm[i]
                    outL[i - from] = if (d > 1e-6f) acc[s][0][i] / d else 0f
                    outR[i - from] = if (d > 1e-6f) acc[s][1][i] / d else 0f
                }
                sink(s, outL, outR, to - from)
            }
            for (c in 0 until 2) { val a = acc[s][c]; a.copyInto(a, 0, HOP, SIZE); a.fill(0f, SIZE - HOP, SIZE) }
        }
        norm.copyInto(norm, 0, HOP, SIZE); norm.fill(0f, SIZE - HOP, SIZE)
    }

    /** Frame [f] of [x]: centred on f * HOP, zero outside the signal, windowed, transformed; the first BINS kept. */
    private fun analyse(x: FloatArray, f: Int, re: FloatArray, im: FloatArray) {
        val start = f * HOP - SIZE / 2
        for (k in 0 until SIZE) { val p = start + k; fr[k] = if (p in x.indices) x[p] * window[k] else 0f; fi[k] = 0f }
        fft.forward(fr, fi)
        fr.copyInto(re, 0, 0, BINS); fi.copyInto(im, 0, 0, BINS)
    }
}

/** IEEE 754 half precision, for spooling magnitudes: 11 bits of mantissa is a mask's precision many times over. */
object Half {
    fun of(f: Float): Short {
        val bits = java.lang.Float.floatToIntBits(f)
        val sign = (bits ushr 16) and 0x8000
        val v = (bits and 0x7fffffff)
        if (v >= 0x477ff000) return (sign or 0x7c00).toShort()            // too large (or NaN): infinity
        if (v < 0x38800000) {                                               // subnormal or zero
            if (v < 0x33000000) return sign.toShort()
            val e = v ushr 23; val m = (v and 0x7fffff) or 0x800000
            val shift = 126 - e
            val half = (m + (1 shl (shift - 1))) ushr shift
            return (sign or (half and 0x3ff) or ((half ushr 10) shl 10)).toShort()
        }
        val rounded = v + 0x00000fff + ((v ushr 13) and 1)                  // round to nearest even
        return (sign or ((rounded - 0x38000000) ushr 13)).toShort()
    }

    fun toFloat(h: Short): Float {
        val bits = h.toInt() and 0xffff
        val sign = (bits and 0x8000) shl 16
        val e = (bits ushr 10) and 0x1f
        val m = bits and 0x3ff
        val out = when {
            e == 0 -> if (m == 0) sign else { var mm = m; var ee = -14; while (mm and 0x400 == 0) { mm = mm shl 1; ee-- }; sign or ((ee + 127) shl 23) or ((mm and 0x3ff) shl 13) }
            e == 31 -> sign or 0x7f800000 or (m shl 13)
            else -> sign or ((e - 15 + 127) shl 23) or (m shl 13)
        }
        return java.lang.Float.intBitsToFloat(out)
    }
}
