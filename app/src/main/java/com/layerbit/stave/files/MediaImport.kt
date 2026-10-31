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
                val watcher = kotlinx.coroutines.CoroutineScope(coroutineContext).let { scope ->
                    kotlinx.coroutines.launch(Dispatchers.Default) {
                        while (true) { onProgress(AvNative.decProgress(h) / 1000f); kotlinx.coroutines.delay(120) }
                    }
                }
                val frames = try {
                    AvNative.decRun(h, out.path, sampleRate, 0)
                } finally { watcher.cancel() }
                coroutineContext.ensureActive()
                if (frames <= 0) { out.delete(); throw ImportFailed(if (frames == -2L) "Cancelled." else "$name could not be decoded all the way through.") }
                val rate = AvNative.decOutRate(h).takeIf { r -> r > 0 } ?: info[0].toInt()
                val channels = AvNative.decOutChannels(h).takeIf { c -> c > 0 } ?: info[1].toInt()
                Source(id = id, name = name.substringBeforeLast('.').ifBlank { name }, file = "media/${out.name}", sampleRate = rate, channels = channels, frames = frames, kind = Source.Kind.AUDIO, origin = name)
            } catch (e: Throwable) {
                AvNative.decCancel(h); out.delete(); throw e
            } finally { AvNative.decClose(h) }
        }
    }

    /** A take's WAV copied into media/ as a Source (the header skipped). */
    fun takeToSource(files: ProjectFiles, wav: File, sampleRate: Int, channels: Int, name: String): Source {
        val id = Ids.next("s")
        val out = files.newSourceFile(id)
        wav.inputStream().use { inp -> inp.skip(RawAudio.wavDataOffset().toLong()); out.outputStream().use { o -> inp.copyTo(o) } }
        return Source(id, name, "media/${out.name}", sampleRate, channels, RawAudio.frames(out, channels), Source.Kind.TAKE, origin = wav.name)
    }
}
