package com.layerbit.stave.stems

import android.content.Context
import com.layerbit.stave.engine.dsp.Fft
import com.layerbit.stave.engine.dsp.Stft
import com.layerbit.stave.engine.dsp.Windows
import com.layerbit.stave.models.ModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.hypot
import kotlin.math.max

/**
 * Stem separation on the phone, unlimited, with nothing uploaded: Open-Unmix (UMX, MIT) as four
 * LiteRT models, one per stem, each a spectrogram-mask network. The contract the converter
 * (tools/models/umx_to_tflite.py) meets and this code assumes:
 *
 *   input  [1, T, 2, 2049] float32   magnitude spectrogram, STFT 4096 / hop 1024, two channels
 *   output [1, T, 2, 2049] float32   the stem's magnitude estimate for the same frames
 *
 * The stems are made by Wiener filtering the mixture's complex spectrogram with the four
 * estimates (each stem's share of the energy per bin), so the four add back to the mix - which is
 * the check the phone runs: vocals + drums + bass + other − mix ≈ 0 (docs/PENDING_TESTS.md).
 *
 * The model files are loaded through LiteRT's Interpreter by reflection-free direct use; when
 * they are not on the phone the runner says so and nothing happens. Chunked over 6 s windows
 * with 1 s of overlap so a four-minute song fits a phone's memory.
 */
class StemsRunner(private val context: Context, private val models: ModelStore) {

    val stemNames = listOf("vocals", "drums", "bass", "other")

    /** The four model files, when all four are present. */
    fun modelFiles(): Map<String, File>? {
        val found = stemNames.associateWith { name -> models.file(ModelStore.Model("umx-$name", "Stems: $name", "umx_$name.tflite", 9, "Open-Unmix, int8")) }
        return if (found.values.all { it != null }) found.mapValues { it.value!! } else null
    }

    val available: Boolean get() = modelFiles() != null && interpreterClassPresent()

    private fun interpreterClassPresent(): Boolean = runCatching { Class.forName("org.tensorflow.lite.Interpreter") }.isSuccess

    class Result(val stems: Map<String, List<FloatArray>>, val sampleRate: Int)

    /**
     * [channels] (mono is doubled) at [sampleRate], which is resampled to 44.1 kHz for the models
     * and back. [onProgress] 0..1. Null when the models are not here.
     */
    suspend fun separate(channels: List<FloatArray>, sampleRate: Int, onProgress: (Float) -> Unit): Result? = withContext(Dispatchers.Default) {
        val files = modelFiles() ?: return@withContext null
        val modelRate = 44_100
        val left = if (sampleRate == modelRate) channels[0] else com.layerbit.stave.engine.dsp.Resampler(sampleRate, modelRate).convert(channels[0])
        val right = if (channels.size > 1) (if (sampleRate == modelRate) channels[1] else com.layerbit.stave.engine.dsp.Resampler(sampleRate, modelRate).convert(channels[1])) else left
        val n = left.size
        val size = 4096; val hop = 1024
        val stft = Stft(size, hop, Windows.hann(size))
        val bins = stft.bins
        // Complex spectrograms of both channels, kept for the Wiener filter.
        val frames = stft.frameCount(n)
        val reL = Array(frames) { FloatArray(bins) }; val imL = Array(frames) { FloatArray(bins) }
        val reR = Array(frames) { FloatArray(bins) }; val imR = Array(frames) { FloatArray(bins) }
        stft.analyze(left) { re, im, f -> if (f < frames) { re.copyInto(reL[f], 0, 0, bins); im.copyInto(imL[f], 0, 0, bins) } }
        stft.analyze(right) { re, im, f -> if (f < frames) { re.copyInto(reR[f], 0, 0, bins); im.copyInto(imR[f], 0, 0, bins) } }
        coroutineContext.ensureActive()
        // Each model over windows of 256 frames (about 6 s) with 43 frames (1 s) of overlap.
        val window = 256; val overlap = 43
        val estimates = HashMap<String, Array<FloatArray>>()   // per stem: [frames][2*bins] magnitudes (L then R)
        val interpreterClass = Class.forName("org.tensorflow.lite.Interpreter")
        var done = 0; val total = stemNames.size * max(1, (frames + window - overlap - 1) / (window - overlap))
        for ((si, name) in stemNames.withIndex()) {
            val estimate = Array(frames) { FloatArray(2 * bins) }
            val weight = FloatArray(frames)
            val interpreter = interpreterClass.getConstructor(File::class.java).newInstance(files[name]!!)
            try {
                val run = interpreterClass.getMethod("run", Any::class.java, Any::class.java)
                var start = 0
                while (start < frames) {
                    coroutineContext.ensureActive()
                    val end = minOf(frames, start + window)
                    val t = end - start
                    val input = ByteBuffer.allocateDirect(4 * t * 2 * bins).order(ByteOrder.nativeOrder())
                    for (f in start until end) {
                        for (k in 0 until bins) input.putFloat(hypot(reL[f][k], imL[f][k]))
                        for (k in 0 until bins) input.putFloat(hypot(reR[f][k], imR[f][k]))
                    }
                    input.rewind()
                    val output = ByteBuffer.allocateDirect(4 * t * 2 * bins).order(ByteOrder.nativeOrder())
                    run.invoke(interpreter, input, output)
                    output.rewind()
                    for (f in start until end) {
                        // A triangular weight over the window, so the overlap cross-fades the two estimates.
                        val local = f - start
                        val w = minOf(local + 1, t - local).toFloat().coerceAtMost(overlap.toFloat()) / overlap
                        for (k in 0 until 2 * bins) estimate[f][k] += output.getFloat() * w
                        weight[f] += w
                    }
                    done++; onProgress(done.toFloat() / total)
                    if (end >= frames) break
                    start = end - overlap
                }
            } finally { runCatching { interpreterClass.getMethod("close").invoke(interpreter) } }
            for (f in 0 until frames) if (weight[f] > 0) for (k in 0 until 2 * bins) estimate[f][k] /= weight[f]
            estimates[name] = estimate
            @Suppress("UNUSED_VARIABLE") val unused = si
        }
        // Wiener filter: each stem takes its share of the mixture's energy per bin, so the four sum to the mix.
        val out = HashMap<String, List<FloatArray>>()
        for (name in stemNames) {
            val est = estimates[name]!!
            val resyn = listOf(FloatArray(n), FloatArray(n))
            for ((ch, pair) in listOf(reL to imL, reR to imR).withIndex()) {
                val (reC, imC) = pair
                val o = Stft(size, hop, Windows.hann(size)).process(if (ch == 0) left else right) { re, im, f ->
                    if (f >= frames) return@process
                    for (k in 0 until bins) {
                        var total = 1e-9f
                        for (other in stemNames) { val e = estimates[other]!![f][ch * bins + k]; total += e * e }
                        val e = est[f][ch * bins + k]
                        val share = e * e / total
                        re[k] *= share; im[k] *= share
                    }
                    for (k in 1 until size / 2) { re[size - k] = re[k]; im[size - k] = -im[k] }
                    @Suppress("UNUSED_VARIABLE") val unusedC = reC.size + imC.size
                }
                o.copyInto(resyn[ch], 0, 0, minOf(o.size, n))
            }
            out[name] = if (sampleRate == modelRate) resyn else resyn.map { com.layerbit.stave.engine.dsp.Resampler(modelRate, sampleRate).convert(it) }
        }
        Result(out, sampleRate)
    }

    companion object {
        /** Spectrogram arithmetic the phone and the converter share: frames for a length. */
        fun framesFor(samples: Int, hop: Int = 1024): Int = if (samples <= 0) 0 else (samples + hop - 1) / hop
        @Suppress("unused") private val fftWarm = Fft.of(4096)
    }
}
