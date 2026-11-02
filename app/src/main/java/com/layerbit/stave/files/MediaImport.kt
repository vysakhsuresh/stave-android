package com.layerbit.stave.files

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.layerbit.stave.engine.json.Json
import com.layerbit.stave.engine.json.JsonObject
import com.layerbit.stave.engine.project.Ids
import com.layerbit.stave.engine.project.Source
import com.layerbit.stave.native.AvHandles
import com.layerbit.stave.native.AvNative
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * The one door a sound enters by: a Uri from the picker or a Share, probed and decoded by
 * libstaveav into a raw float file under the project's media/, then described as a Source. The
 * original is never changed and never kept open: a Uri's grant does not survive a restart and
 * is not the app's to hold.
 */
class MediaImport(private val context: Context) {

    data class Probe(val format: String, val durationMs: Long, val sampleRate: Int, val channels: Int, val codec: String, val bitrate: Long, val tags: Map<String, String>, val chapters: List<Chapter>, val hasCover: Boolean, val streamIndex: Int)
    data class Chapter(val startMs: Long, val endMs: Long, val title: String)

    class ImportFailed(message: String) : Exception(message)

    fun displayName(uri: Uri): String {
        if (uri.scheme == "file") return uri.lastPathSegment ?: "sound"
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment ?: "sound"
    }

    private fun extensionOf(name: String): String? = name.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it.length <= 5 }?.lowercase()

    /** What the file holds, or null when nothing in it is audio FFmpeg reads. */
    fun probe(uri: Uri): Probe? {
        if (!AvNative.loaded) return null
        val name = displayName(uri)
        return context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            val bytes = AvNative.probe(pfd.fd, extensionOf(name)) ?: return null
            val o = Json.parseObject(bytes.toString(Charsets.UTF_8))
            val streams = o.arr("streams")?.objects() ?: emptyList()
            val audio = streams.firstOrNull { it.string("type") == "audio" } ?: return null
            val tags = o.obj("tags")?.let { t -> t.keys.associateWith { t.string(it) ?: "" } } ?: emptyMap()
            Probe(
                format = o.string("format") ?: "", durationMs = o.long("durationMs"), sampleRate = audio.int("rate"), channels = audio.int("channels"),
                codec = audio.string("codec") ?: "", bitrate = audio.long("bitrate").takeIf { it > 0 } ?: o.long("bitrate"), tags = tags,
                chapters = o.arr("chapters")?.objects()?.map { Chapter(it.long("startMs"), it.long("endMs"), it.string("title") ?: "") } ?: emptyList(),
                hasCover = o.bool("hasCover"), streamIndex = audio.int("index")
            )
        }
    }

    fun coverArt(uri: Uri): ByteArray? = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { AvNative.coverArt(it.fd, extensionOf(displayName(uri))) }
    }.getOrNull()

    /**
     * Decodes [uri] into [files]' media at [sampleRate] (0 keeps the file's own) and returns the
     * Source. [onProgress] gets 0..1. Cancelling the coroutine cancels the decode and removes
     * the half-written file.
     */
    suspend fun import(uri: Uri, files: ProjectFiles, sampleRate: Int, onProgress: (Float) -> Unit = {}): Source = withContext(Dispatchers.IO) {
        if (!AvNative.loaded) throw ImportFailed("The decoder library is missing from this build.")
        val name = displayName(uri)
        val id = Ids.next("s")
        val out = files.newSourceFile(id)
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: throw ImportFailed("$name could not be read. The app that holds it may have withdrawn access; pick it again.")
        pfd.use {
            val h = AvNative.decOpen(it.fd, extensionOf(name), -1)
            if (AvHandles.failed(h)) throw ImportFailed("$name is not a sound this phone can decode.")
            try {
                val info = AvNative.decInfo(h)
                val watcher = kotlinx.coroutines.CoroutineScope(coroutineContext).launch(Dispatchers.Default) {
                    while (true) { onProgress(AvNative.decProgress(h) / 1000f * if (sampleRate > 0 && sampleRate != info[0].toInt()) 0.7f else 1f); kotlinx.coroutines.delay(120) }
                }
                // Decoded at the file's own rate, then brought to the project's by the app's own
                // converter (Resampler: -112 dB against an ideal, measured), not by FFmpeg's
                // default resampler, which nothing here had measured.
                val native = File(out.parentFile, "${id}.native.f32")
                val decoded = try {
                    AvNative.decRun(h, native.path, 0, 0)
                } finally { watcher.cancel() }
                coroutineContext.ensureActive()
                if (decoded <= 0) { native.delete(); throw ImportFailed(if (decoded == -2L) "Cancelled." else "$name could not be decoded all the way through.") }
                val fileRate = AvNative.decOutRate(h).takeIf { r -> r > 0 } ?: info[0].toInt()
                val channels = AvNative.decOutChannels(h).takeIf { c -> c > 0 } ?: info[1].toInt()
                val rate = if (sampleRate > 0) sampleRate else fileRate
                val frames = try {
                    if (rate == fileRate) { if (!native.renameTo(out)) { native.copyTo(out, overwrite = true) }; decoded }
                    else convertRate(native, out, channels, fileRate, rate, decoded) { p -> onProgress(0.7f + 0.3f * p) }
                } finally { native.delete() }
                coroutineContext.ensureActive()
                Source(id = id, name = com.layerbit.stave.engine.project.Names.fromFile(name), file = "media/${out.name}", sampleRate = rate, channels = channels, frames = frames, kind = Source.Kind.AUDIO, origin = name)
            } catch (e: Throwable) {
                AvNative.decCancel(h); out.delete(); throw e
            } finally { AvNative.decClose(h) }
        }
    }

    /** [from] (interleaved float at [inRate]) streamed through the Resampler into [to] at [outRate]; returns frames written. */
    private suspend fun convertRate(from: File, to: File, channels: Int, inRate: Int, outRate: Int, frames: Long, onProgress: (Float) -> Unit): Long {
        val stream = com.layerbit.stave.engine.dsp.Resampler(inRate, outRate).Stream(channels)
        val block = 65_536
        var written = 0L
        to.outputStream().buffered(1 shl 16).use { out ->
            fun put(x: FloatArray) {
                if (x.isEmpty()) return
                val bb = java.nio.ByteBuffer.allocate(x.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                bb.asFloatBuffer().put(x); out.write(bb.array()); written += x.size / channels
            }
            var at = 0L
            while (at < frames) {
                coroutineContext.ensureActive()
                val chunk = RawAudio.read(from, channels, at, minOf(block.toLong(), frames - at).toInt())
                if (chunk.isEmpty()) break
                put(stream.push(chunk))
                at += chunk.size / channels
                onProgress(at.toFloat() / frames)
            }
            put(stream.finish())
        }
        return written
    }

    /** A take's WAV copied into media/ as a Source (the header skipped). */
    fun takeToSource(files: ProjectFiles, wav: File, sampleRate: Int, channels: Int, name: String): Source {
        val id = Ids.next("s")
        val out = files.newSourceFile(id)
        wav.inputStream().use { inp -> inp.skip(RawAudio.wavDataOffset().toLong()); out.outputStream().use { o -> inp.copyTo(o) } }
        return Source(id, name, "media/${out.name}", sampleRate, channels, RawAudio.frames(out, channels), Source.Kind.TAKE, origin = wav.name)
    }
}
