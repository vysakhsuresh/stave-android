package com.layerbit.stave.engine.dsp

/**
 * A short-time Fourier transform and its inverse, with overlap-add that gives the input back
 * when every frame is left alone.
 *
 * The frame is handed to the caller as (re, im) to change in place; what comes back is
 * overlap-added through the synthesis window and divided by the summed squared window, so any
 * window and any hop that covers the signal reconstructs exactly (checked in StftChecks to
 * -100 dB). The latency is taken off the front and the tail is drained, so the output is the
 * input's length, sample for sample in step - a clip keeps its length and its sync.
 */
class Stft(val size: Int, val hop: Int, window: FloatArray = Windows.sqrtHann(size)) {
    init {
        require(hop in 1..size) { "hop must be within the frame" }
        require(window.size == size) { "window must match the frame" }
    }

    val bins: Int = size / 2 + 1
    private val fft = Fft.of(size)
    private val window = window.copyOf()
    private val re = FloatArray(size)
    private val im = FloatArray(size)

    /** The frequency of bin [k] at [sampleRate]. */
    fun binHz(k: Int, sampleRate: Int): Double = k.toDouble() * sampleRate / size

    /** The bin nearest [hz]. */
    fun binOf(hz: Double, sampleRate: Int): Int = (hz * size / sampleRate).toInt().coerceIn(0, bins - 1)

    /**
     * Runs [signal] through [onFrame] a frame at a time and returns the resynthesis, the same
     * length as the input. [onFrame] receives the complex spectrum (size entries; only the first
     * [bins] are independent for a real signal - change those and mirror with [mirror], or
     * change all consistently) and the frame's index; it edits re/im in place.
     */
    fun process(signal: FloatArray, onFrame: (re: FloatArray, im: FloatArray, frame: Int) -> Unit): FloatArray {
        val n = signal.size
        if (n == 0) return FloatArray(0)
        // Padded so the first and last samples are covered by a full set of overlapping frames.
        val pad = size - hop
        val padded = FloatArray(n + 2 * pad)
        signal.copyInto(padded, pad)
        val out = FloatArray(padded.size)
        val norm = FloatArray(padded.size)
        var frame = 0
        var start = 0
        while (start + size <= padded.size) {
            for (i in 0 until size) { re[i] = padded[start + i] * window[i]; im[i] = 0f }
            fft.forward(re, im)
            onFrame(re, im, frame)
            fft.inverse(re, im)
            for (i in 0 until size) {
                val w = window[i]
                out[start + i] += re[i] * w
                norm[start + i] += w * w
            }
            frame++
            start += hop
        }
        val result = FloatArray(n)
        for (i in 0 until n) {
            val d = norm[pad + i]
            result[i] = if (d > 1e-6f) out[pad + i] / d else 0f
        }
        return result
    }

    /**
     * Only analysis: [onFrame] is given each frame's spectrum and may not change it. Cheaper
     * than [process] when nothing is to be synthesised (a spectrogram, a profile, a chroma).
     */
    fun analyze(signal: FloatArray, onFrame: (re: FloatArray, im: FloatArray, frame: Int) -> Unit) {
        val n = signal.size
        if (n == 0) return
        var frame = 0
        var start = 0
        while (start < n) {
            for (i in 0 until size) {
                val j = start + i
                re[i] = if (j < n) signal[j] * window[i] else 0f
                im[i] = 0f
            }
            fft.forward(re, im)
            onFrame(re, im, frame)
            frame++
            start += hop
        }
    }

    /** How many frames [analyze] visits for [samples] samples. */
    fun frameCount(samples: Int): Int = if (samples <= 0) 0 else (samples + hop - 1) / hop

    /** Rewrites bins above the Nyquist as the conjugate mirror of those below, so the inverse is real. */
    fun mirror(re: FloatArray, im: FloatArray) {
        for (k in 1 until size / 2) {
            re[size - k] = re[k]
            im[size - k] = -im[k]
        }
        im[0] = 0f
        im[size / 2] = 0f
    }
}
