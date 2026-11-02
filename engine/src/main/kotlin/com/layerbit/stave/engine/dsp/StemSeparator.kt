package com.layerbit.stave.engine.dsp

import kotlin.math.hypot

/**
 * Stems from a stereo mix, a window of spectrogram frames at a time, written out as they are
 * made: the shape Open-Unmix (tools/models/umx_to_tflite.py) was trained on, with the model
 * itself handed in as [model] so the arithmetic around it is checked on the JVM with an oracle.
 *
 * The STFT is torch.stft's: a periodic Hann of [SIZE], hop [HOP], frames centred on f * HOP
 * (the signal zero-padded by SIZE / 2 at both ends), unnormalised. The first runner analysed
 * frames starting at f * HOP and resynthesised frames starting 3 hops earlier, so every mask
 * landed 70 ms from its frame; StemSeparatorChecks holds the alignment with an oracle model.
 *
 * The model sees [WINDOW] frames a call (the converted models' fixed shape; the last call is
 * zero-padded) and each frame's estimate is taken from a call where it has [CONTEXT] frames on
 * both sides (except at the song's ends). Each stem gets its Wiener share of the mixture's
 * complex spectrum (its estimate squared over the sum of all estimates squared), so the stems
 * sum to the mix; the inverse is overlap-added through the same window and divided by the
 * summed squared window. Memory is a window, whatever the song's length.
 */
class StemSeparator(
    val stemCount: Int,
    /** Fills [output] ([frames] x 2 x BINS magnitudes) for stem [stem] from [input] of the same shape, frames from [firstFrame]. */
    private val model: (stem: Int, firstFrame: Int, input: FloatArray, output: FloatArray) -> Unit
) {
    companion object {
        const val SIZE = 4096
        const val HOP = 1024
        const val BINS = SIZE / 2 + 1
        const val WINDOW = 256
        const val CONTEXT = 21

        /** Frames for [samples] samples: centred frames up to and including the one on the last sample. */
        fun framesFor(samples: Int): Int = if (samples <= 0) 0 else samples / HOP + 1
    }

    private val fft = Fft.of(SIZE)
    private val window = Windows.hann(SIZE)

    /**
     * Separates [left] and [right] (the same length, at the model's rate). [sink] receives each
     * stem's next [count] samples of left and right, in order, from sample 0 to the end; the
     * arrays are reused, so the sink copies what it keeps.
     */
    fun run(left: FloatArray, right: FloatArray, sink: (stem: Int, l: FloatArray, r: FloatArray, count: Int) -> Unit, onProgress: (Float) -> Unit = {}) {
        val n = left.size
        require(right.size == n)
        val frames = framesFor(n)
        if (frames == 0) return
        val step = WINDOW - 2 * CONTEXT
        // Spectra of the current call's frames: [frame][bin] per channel, real and imaginary.
        val re = Array(2) { Array(WINDOW) { FloatArray(BINS) } }
        val im = Array(2) { Array(WINDOW) { FloatArray(BINS) } }
        val input = FloatArray(WINDOW * 2 * BINS)
        val estimates = Array(stemCount) { FloatArray(WINDOW * 2 * BINS) }
        // Overlap-add: per stem and channel, SIZE samples starting at the next frame's first sample.
        val acc = Array(stemCount) { Array(2) { FloatArray(SIZE) } }
        val norm = FloatArray(SIZE)
        val outL = FloatArray(HOP); val outR = FloatArray(HOP)
        val fr = FloatArray(SIZE); val fi = FloatArray(SIZE)
        var nextFrame = 0          // the next frame to synthesise
        var callStart = 0
        while (nextFrame < frames) {
            // This call covers [callStart, callStart + WINDOW); it settles frames up to keepEnd.
            val t = minOf(WINDOW, frames - callStart)
            val keepEnd = if (callStart + WINDOW >= frames) frames else callStart + WINDOW - CONTEXT
            for (j in 0 until WINDOW) {
                val f = callStart + j
                for (c in 0 until 2) {
                    if (j < t) analyse(if (c == 0) left else right, f, re[c][j], im[c][j]) else { re[c][j].fill(0f); im[c][j].fill(0f) }
                    val base = (j * 2 + c) * BINS
                    for (k in 0 until BINS) input[base + k] = if (j < t) hypot(re[c][j][k], im[c][j][k]) else 0f
                }
            }
            for (s in 0 until stemCount) model(s, callStart, input, estimates[s])
            while (nextFrame < keepEnd) {
                val j = nextFrame - callStart
                for (c in 0 until 2) for (s in 0 until stemCount) {
                    val base = (j * 2 + c) * BINS
                    for (k in 0 until BINS) {
                        var total = 1e-12f
                        for (o in 0 until stemCount) { val e = estimates[o][base + k]; total += e * e }
                        val e = estimates[s][base + k]
                        val share = e * e / total
                        fr[k] = re[c][j][k] * share; fi[k] = im[c][j][k] * share
                    }
                    for (k in 1 until SIZE / 2) { fr[SIZE - k] = fr[k]; fi[SIZE - k] = -fi[k] }
                    fft.inverse(fr, fi)
                    val a = acc[s][c]
                    for (i in 0 until SIZE) a[i] += fr[i] * window[i]
                }
                for (i in 0 until SIZE) norm[i] += window[i] * window[i]
                // The first HOP samples of this frame are now final (no later frame reaches them).
                val first = nextFrame * HOP - SIZE / 2
                emit(acc, norm, first, n, outL, outR, sink)
                nextFrame++
            }
            onProgress(nextFrame.toFloat() / frames)
            callStart += step
        }
        // The tail: what the last frames still hold.
        var first = nextFrame * HOP - SIZE / 2
        while (first < n) { emit(acc, norm, first, n, outL, outR, sink); first += HOP }
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

    /** Frame [f] of [x]: centred on f * HOP, zero outside the signal, windowed, transformed. */
    private fun analyse(x: FloatArray, f: Int, re: FloatArray, im: FloatArray) {
        val r = FloatArray(SIZE); val i = FloatArray(SIZE)
        val start = f * HOP - SIZE / 2
        for (k in 0 until SIZE) { val p = start + k; r[k] = if (p in x.indices) x[p] * window[k] else 0f }
        fft.forward(r, i)
        r.copyInto(re, 0, 0, BINS); i.copyInto(im, 0, 0, BINS)
    }
}
