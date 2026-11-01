package com.layerbit.stave.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.layerbit.stave.engine.dsp.Gain
import com.layerbit.stave.engine.dsp.Limiter
import com.layerbit.stave.engine.dsp.LoudnessMeter
import com.layerbit.stave.engine.dsp.LoudnessTarget
import com.layerbit.stave.engine.dsp.Resampler
import com.layerbit.stave.engine.export.ExportSettings
import com.layerbit.stave.engine.project.Marker
import com.layerbit.stave.engine.project.Session
import com.layerbit.stave.files.ProjectFiles
import com.layerbit.stave.files.RawAudio
import com.layerbit.stave.native.AvHandles
import com.layerbit.stave.native.AvNative
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * An export, start to finish: the graph renders the session to a raw stereo float file, the
 * engine measures and normalises it to the target if asked, libstaveav encodes it with tags,
 * art and chapters into the project's exports/, and the file is published to Music through
 * MediaStore so the gallery and every player see it. The private copy stays until the project is
 * deleted, so the Library can share it again.
 */
class Exporter(private val context: Context) {

    class Result(val file: File, val mediaUri: Uri?, val bytes: Long, val integratedLufs: Double, val truePeakDbtp: Double, val durationMs: Long)
    class ExportFailed(message: String) : Exception(message)

    /** [render] turns the session into an interleaved stereo float file at the session's rate (the Transport's renderOffline). */
    suspend fun export(
        session: Session, files: ProjectFiles, settings: ExportSettings, fallbackName: String,
        render: suspend (File) -> Long, onProgress: (Float, String) -> Unit
    ): Result = withContext(Dispatchers.IO) {
        if (!AvNative.loaded) throw ExportFailed("The encoder library is missing from this build.")
        val work = File(context.cacheDir, "work").apply { mkdirs() }
        val raw = File(work, "mix-${System.currentTimeMillis()}.f32")
        try {
            onProgress(0.05f, "Mixing")
            val frames = render(raw)
            coroutineContext.ensureActive()
            if (frames <= 0) throw ExportFailed("Nothing to export: the session is empty.")
            var rate = session.sampleRate
            var channels = 2
            // Loudness: measured off the mix, the gain applied in place, a true-peak limiter after.
            var samples = RawAudio.read(raw, channels, 0, frames.toInt())
            val outChannels = settings.outputChannels(channels)
            if (outChannels == 1 && channels == 2) {
                val mono = FloatArray(frames.toInt()) { (samples[it * 2] + samples[it * 2 + 1]) * 0.5f }
                samples = mono; channels = 1
            }
            if (settings.normaliseToTarget) {
                onProgress(0.3f, "Measuring loudness")
                val target = LoudnessTarget.byId(settings.loudnessTargetId)
                val measured = LoudnessMeter.integrated(samples, rate, channels)
                val g = Gain.normaliseLoudness(measured, target.lufs)
                Gain.apply(samples, g)
                // Half a decibel under the true-peak ceiling, then read and trimmed (OfflineTools
                // .normaliseLoudness says why): the limiter holds samples, the target is a true peak.
                val lim = Limiter(rate, channels, target.truePeakDbtp - 0.5, 5.0, 60.0)
                lim.process(samples)
                val tp = LoudnessMeter(rate, channels).also { it.process(samples) }.reading().truePeakDbtp
                if (tp > target.truePeakDbtp) Gain.apply(samples, Gain.dbToLinear(target.truePeakDbtp - tp - 0.05).toFloat())
            }
            val outRate = settings.outputRate(rate)
            if (outRate != rate) {
                onProgress(0.45f, "Converting the rate")
                samples = Resampler(rate, outRate).convertInterleaved(samples, channels)
                rate = outRate
            }
            onProgress(0.55f, "Measuring")
            val meter = LoudnessMeter(rate, channels).also { it.process(samples) }
            val reading = meter.reading()
            // Dither: a float mix going to 16 or 24 integer bits gets TPDF noise at one LSB before the
            // encoder quantises it, so a fade's tail dies into noise rather than into a stepped buzz.
            // Lossy encoders quantise nothing, so they get the plain float.
            val depth = settings.depth
            if (settings.dither && settings.format.lossless && depth in setOf(16, 24)) {
                val dither = com.layerbit.stave.engine.dsp.Dither(depth)
                for (i in samples.indices) samples[i] = dither.apply(samples[i])
            }
            RawAudio.write(raw, samples)
            val outFile = File(files.exports, settings.fileName(fallbackName))
            onProgress(0.6f, "Encoding")
            val fmt = settings.format
            val h = AvNative.encOpen(raw.path, (samples.size / channels).toLong(), rate, channels, outFile.path, fmt.muxer, settings.encoderName(),
                if (fmt.lossless) 0 else settings.bitrateKbps * 1000, rate, channels, -1f)
            if (AvHandles.failed(h)) throw ExportFailed("The encoder for ${fmt.label} could not be opened.")
            try {
                if (settings.title.isNotBlank()) AvNative.encSetTag(h, "title", settings.title)
                if (settings.artist.isNotBlank()) AvNative.encSetTag(h, "artist", settings.artist)
                if (settings.album.isNotBlank()) AvNative.encSetTag(h, "album", settings.album)
                if (settings.comment.isNotBlank()) AvNative.encSetTag(h, "comment", settings.comment)
                if (settings.trackNumber > 0) AvNative.encSetTag(h, "track", settings.trackNumber.toString())
                if (settings.year.isNotBlank()) AvNative.encSetTag(h, "date", settings.year)
                if (settings.genre.isNotBlank()) AvNative.encSetTag(h, "genre", settings.genre)
                AvNative.encSetTag(h, "encoder", "Stave")
                if (settings.writeChapters && fmt.chapters) {
                    val chapters = session.markers.filter { it.kind == Marker.Kind.CHAPTER }.sortedBy { it.position }
                    val endMs = frames * 1000 / session.sampleRate
                    chapters.forEachIndexed { i, m ->
                        val start = m.position * 1000 / session.sampleRate
                        val end = chapters.getOrNull(i + 1)?.let { it.position * 1000 / session.sampleRate } ?: endMs
                        if (end > start) AvNative.encAddChapter(h, start, end, m.name.ifBlank { "Chapter ${i + 1}" })
                    }
                }
                val art = File(files.dir, "cover.jpg").takeIf { it.isFile && fmt.cover }
                if (art != null) AvNative.encSetCover(h, art.readBytes(), "image/jpeg")
                val watcher = kotlinx.coroutines.CoroutineScope(coroutineContext).launch(Dispatchers.Default) { while (true) { onProgress(0.6f + 0.35f * AvNative.encProgress(h) / 1000f, "Encoding"); kotlinx.coroutines.delay(150) } }
                val rc = try { AvNative.encRun(h) } finally { watcher.cancel() }
                coroutineContext.ensureActive()
                if (rc != 0) throw ExportFailed("Encoding failed (${fmt.label}, code $rc).")
            } finally { AvNative.encClose(h) }
            onProgress(0.97f, "Saving to Music")
            val uri = publish(outFile, fmt.mime)
            Result(outFile, uri, outFile.length(), reading.integratedLufs, reading.truePeakDbtp, samples.size / channels * 1000L / rate)
        } finally { raw.delete() }
    }

    /** The file copied into Music/Stave through MediaStore (no storage permission needed for the app's own entries). */
    fun publish(file: File, mime: String): Uri? = runCatching {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Audio.Media.MIME_TYPE, mime)
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/Stave")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: return null
        resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
        uri
    }.getOrNull()
}
