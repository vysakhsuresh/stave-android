package com.layerbit.stave.stems

import android.content.Context
import com.layerbit.stave.engine.dsp.StemSeparator
import com.layerbit.stave.models.ModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

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
 * The model files are loaded through LiteRT's Interpreter, reached by name; when they are not on
 * the phone the runner says so and nothing happens. [StemSeparator] does the rest a window at a
 * time, so any length of song fits a phone's memory.
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

    /** Each stem as a file of interleaved stereo floats at [sampleRate], [frames] long. */
    class Result(val files: Map<String, File>, val sampleRate: Int, val frames: Long) {
        /** One stem read back whole, planar: for a selection, never a whole song. */
        fun read(name: String): List<FloatArray>? = files[name]?.let { com.layerbit.stave.files.RawAudio.readPlanar(it, 2, frames.toInt()) }
    }

    /**
     * [channels] (mono is doubled) at [sampleRate], brought to 44.1 kHz for the models and back,
     * separated by [StemSeparator] (its checks hold the framing, the Wiener sum and the
     * alignment) and written as it is made into [dir], a stem a file. [onProgress] 0..1. Null
     * when the models are not here.
     *
     * The first runner held the whole song's complex spectrogram and four estimates (about a
     * gigabyte for four minutes), fed the fixed-shape models a short last window they refuse, and
     * applied each mask three hops from its own frame.
     */
    suspend fun separate(channels: List<FloatArray>, sampleRate: Int, dir: File, onProgress: (Float) -> Unit): Result? = withContext(Dispatchers.Default) {
        val files = modelFiles() ?: return@withContext null
        val modelRate = 44_100
        val left = if (sampleRate == modelRate) channels[0] else com.layerbit.stave.engine.dsp.Resampler(sampleRate, modelRate).convert(channels[0])
        val right = if (channels.size > 1) (if (sampleRate == modelRate) channels[1] else com.layerbit.stave.engine.dsp.Resampler(sampleRate, modelRate).convert(channels[1])) else left
        val interpreterClass = Class.forName("org.tensorflow.lite.Interpreter")
        val run = interpreterClass.getMethod("run", Any::class.java, Any::class.java)
        val close = interpreterClass.getMethod("close")
        val floats = StemSeparator.WINDOW * 2 * StemSeparator.BINS
        val input = ByteBuffer.allocateDirect(4 * floats).order(ByteOrder.nativeOrder())
        val output = ByteBuffer.allocateDirect(4 * floats).order(ByteOrder.nativeOrder())
        dir.mkdirs()
        val outFiles = stemNames.associateWith { File(dir, "stem-$it-${System.nanoTime()}.f32") }
        val streams = outFiles.mapValues { (_, f) -> java.io.BufferedOutputStream(java.io.FileOutputStream(f), 1 shl 16) }
        val back = stemNames.associateWith { if (sampleRate == modelRate) null else com.layerbit.stave.engine.dsp.Resampler(modelRate, sampleRate).Stream(2) }
        val written = HashMap<String, Long>()
        fun write(name: String, inter: FloatArray) {
            if (inter.isEmpty()) return
            val bb = ByteBuffer.allocate(inter.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            bb.asFloatBuffer().put(inter); streams[name]!!.write(bb.array())
            written[name] = (written[name] ?: 0L) + inter.size / 2
        }
        val job = coroutineContext
        // One interpreter at a time: four open together held 3.2 GB of native memory (StemSeparator).
        val open = { stem: Int ->
            // XNNPACK stays on: without it LiteRT's own kernels gave NaN for three stems on the
            // phone. It packs the weights once per unrolled LSTM step, so its memory grows with
            // the model's window (StemSeparator.WINDOW); the models are converted at 128 frames.
            val interpreter = interpreterClass.getConstructor(File::class.java).newInstance(files[stemNames[stem]]!!)
            object : StemSeparator.StemModel {
                override fun infer(firstFrame: Int, input2: FloatArray, output2: FloatArray) {
                    job.ensureActive()
                    input.clear(); input.asFloatBuffer().put(input2); input.rewind()
                    output.clear()
                    run.invoke(interpreter, input, output)
                    output.rewind(); output.asFloatBuffer().get(output2)
                }
                override fun close() { runCatching { close.invoke(interpreter) } }
            }
        }
        try {
            StemSeparator(stemNames.size, File(dir, "spool")).run(left, right, open, { stem, l, r, count ->
                val name = stemNames[stem]
                val inter = FloatArray(count * 2) { i -> if (i % 2 == 0) l[i / 2] else r[i / 2] }
                write(name, back[name]?.push(inter) ?: inter)
            }, onProgress)
            for (name in stemNames) back[name]?.let { write(name, it.finish()) }
        } catch (e: Throwable) {
            streams.values.forEach { runCatching { it.close() } }
            outFiles.values.forEach { it.delete() }; throw e
        } finally {
            streams.values.forEach { runCatching { it.close() } }
        }
        Result(outFiles, sampleRate, written.values.minOrNull() ?: 0L)
    }

    companion object {
        /** Spectrogram arithmetic the phone and the converter share: frames for a length. */
        fun framesFor(samples: Int): Int = StemSeparator.framesFor(samples)
    }
}
