package com.layerbit.stave.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.layerbit.stave.engine.dsp.LoudnessTarget
import com.layerbit.stave.engine.export.ExportChain
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import com.layerbit.stave.engine.export.ExportSettings
import com.layerbit.stave.engine.project.Marker
import com.layerbit.stave.engine.project.Session
import com.layerbit.stave.files.ProjectFiles
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
            // Fold, loudness, limiter, rate, true-peak trim and dither, in blocks (ExportChain): the
            // whole song was held here as floats until 2 November, and an hour of podcast is 1.4 GB.
            val rate = session.sampleRate
            val fmt0 = settings.format
            val outRate = settings.outputRate(rate)
            val channels = settings.outputChannels(2)
            val depth = settings.depth
            val target = if (settings.normaliseToTarget) LoudnessTarget.byId(settings.loudnessTargetId) else null
            val staged = File(work, "staged-${System.currentTimeMillis()}.f32")
            val job = coroutineContext
            val report = try {
                RandomAccessFile(raw, "r").use { inFile ->
                    RandomAccessFile(staged, "rw").use { outFile ->
                        outFile.setLength(0)
                        ExportChain.run(
                            source = FileFrames(inFile, 2, frames),
                            stage = FileStage(outFile, channels),
                            plan = ExportChain.Plan(
                                rate = rate, outRate = outRate, outChannels = channels,
                                targetLufs = target?.lufs, ceilingDbtp = target?.truePeakDbtp ?: -1.0,
                                // Lossy encoders quantise nothing, so they get the plain float.
                                ditherBits = if (settings.dither && fmt0.lossless && depth in setOf(16, 24)) depth else null
                            ),
                            progress = { p -> onProgress(0.1f + 0.5f * p, if (target != null) "Measuring and levelling" else "Preparing") },
                            alive = { job.ensureActive() }
                        )
                    }
                }
            } catch (e: Throwable) { staged.delete(); throw e }
            raw.delete()
            staged.renameTo(raw)
            val reading = report
            val outFrames = report.frames
            val outFile = File(files.exports, settings.fileName(fallbackName))
            onProgress(0.6f, "Encoding")
            val fmt = settings.format
            val h = AvNative.encOpen(raw.path, outFrames, outRate, channels, outFile.path, fmt.muxer, settings.encoderName(),
                if (fmt.lossless) 0 else settings.bitrateKbps * 1000, outRate, channels, -1f)
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
            Result(outFile, uri, outFile.length(), reading.integratedLufs, reading.truePeakDbtp, outFrames * 1000L / outRate)
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

/** A raw interleaved float file read in blocks, little-endian as the graph writes it. */
private class FileFrames(private val file: RandomAccessFile, override val channels: Int, override val frames: Long) : ExportChain.Source {
    private var bytes = ByteArray(0)
    override fun read(from: Long, count: Int, into: FloatArray) {
        val n = count * channels * 4
        if (bytes.size < n) bytes = ByteArray(n)
        file.seek(from * channels * 4)
        file.readFully(bytes, 0, n)
        ByteBuffer.wrap(bytes, 0, n).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(into, 0, count * channels)
    }
}

/** The staged signal: appended once, then read back and rewritten in place by the trim and dither pass. */
private class FileStage(private val file: RandomAccessFile, private val channels: Int) : ExportChain.Stage {
    private var bytes = ByteArray(0)
    private var end = 0L
    private fun put(at: Long, block: FloatArray, frames: Int) {
        val n = frames * channels * 4
        if (bytes.size < n) bytes = ByteArray(n)
        ByteBuffer.wrap(bytes, 0, n).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().put(block, 0, frames * channels)
        file.seek(at * channels * 4)
        file.write(bytes, 0, n)
    }
    override fun append(block: FloatArray, frames: Int) { put(end, block, frames); end += frames }
    override fun overwrite(from: Long, block: FloatArray, frames: Int) = put(from, block, frames)
    override fun read(from: Long, count: Int, into: FloatArray) {
        val n = count * channels * 4
        if (bytes.size < n) bytes = ByteArray(n)
        file.seek(from * channels * 4)
        file.readFully(bytes, 0, n)
        ByteBuffer.wrap(bytes, 0, n).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(into, 0, count * channels)
    }
}
