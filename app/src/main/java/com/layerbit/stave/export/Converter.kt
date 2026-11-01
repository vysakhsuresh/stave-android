package com.layerbit.stave.export

import android.content.Context
import android.net.Uri
import com.layerbit.stave.engine.export.ExportSettings
import com.layerbit.stave.engine.project.Session
import com.layerbit.stave.files.MediaImport
import com.layerbit.stave.files.ProjectFiles
import com.layerbit.stave.files.RawAudio
import java.io.File

/**
 * Batch conversion: files from the phone decoded one by one into a scratch project folder,
 * encoded to the chosen format and published to Music/Stave through the same Exporter that
 * the editor uses - so a converted file carries the same tags, loudness option and rate choice
 * an export does. Nothing is kept: the scratch folder goes when the batch ends.
 */
class Converter(private val context: Context, private val media: MediaImport, private val exporter: Exporter) {

    class Outcome(val name: String, val result: Exporter.Result?, val error: String?)

    suspend fun convert(uris: List<Uri>, settings: ExportSettings, onProgress: (index: Int, fraction: Float, label: String) -> Unit): List<Outcome> {
        val scratch = File(context.cacheDir, "convert-${System.currentTimeMillis()}").apply { mkdirs() }
        val files = ProjectFiles(scratch)
        val out = ArrayList<Outcome>()
        try {
            for ((i, uri) in uris.withIndex()) {
                val name = media.displayName(uri)
                try {
                    onProgress(i, 0f, "Decoding $name")
                    // Decoded at its own rate: a conversion keeps the rate unless the settings say otherwise.
                    val probe = media.probe(uri)
                    val rate = probe?.sampleRate?.takeIf { it > 0 } ?: 48_000
                    val source = media.import(uri, files, rate) { p -> onProgress(i, p * 0.5f, "Decoding $name") }
                    val raw = files.sourceFile(source.file)
                    val session = Session(name = source.name, sampleRate = source.sampleRate)
                    val result = exporter.export(session, files, settings.copy(title = settings.title.ifBlank { source.name }), source.name, render = { target ->
                        // The exporter wants interleaved stereo at the session's rate: a mono file is doubled, more than two channels are folded to two.
                        val frames = source.frames.toInt()
                        val inter = RawAudio.read(raw, source.channels, 0, frames)
                        val stereo = when (source.channels) {
                            2 -> inter
                            1 -> FloatArray(frames * 2) { inter[it / 2] }
                            else -> FloatArray(frames * 2) { k -> val f = k / 2; val c = k % 2; var sum = 0f; var n = 0; for (ch in c until source.channels step 2) { sum += inter[f * source.channels + ch]; n++ }; if (n == 0) 0f else sum / n }
                        }
                        RawAudio.write(target, stereo)
                        frames.toLong()
                    }, onProgress = { p, label -> onProgress(i, 0.5f + p * 0.5f, label) })
                    out += Outcome(name, result, null)
                    raw.delete()
                } catch (e: Exception) {
                    out += Outcome(name, null, e.message ?: "Could not convert $name")
                }
            }
        } finally {
            scratch.deleteRecursively()
        }
        return out
    }
}
