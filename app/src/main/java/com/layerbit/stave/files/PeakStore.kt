package com.layerbit.stave.files

import com.layerbit.stave.engine.wave.Peaks
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Peak pyramids on disk, one per source channel, built once from the .f32 and read in a few
 * milliseconds after. The file is the pyramid's floats, little-endian, so it memory-maps.
 */
class PeakStore(private val files: ProjectFiles) {
    private val cache = HashMap<String, List<Peaks>>()

    fun file(sourceId: String) = File(files.peaks, "$sourceId.peaks")

    /** The pyramid per channel for a source, built if missing. [sourceFile] is the .f32. */
    @Synchronized fun forSource(sourceId: String, sourceFile: File, channels: Int, sampleRate: Int): List<Peaks> {
        cache[sourceId]?.let { return it }
        val f = file(sourceId)
        val loaded = if (f.isFile) runCatching { read(f) }.getOrNull() else null
        val peaks = loaded ?: build(sourceFile, channels, sampleRate).also { write(f, it) }
        cache[sourceId] = peaks
        return peaks
    }

    @Synchronized fun forget(sourceId: String) { cache.remove(sourceId); file(sourceId).delete() }

    /** Builds from the raw float file in one pass, a channel's builder each. */
    fun build(sourceFile: File, channels: Int, sampleRate: Int): List<Peaks> {
        val builders = List(channels) { Peaks.Builder(sampleRate) }
        RandomAccessFile(sourceFile, "r").use { raf ->
            val ch = raf.channel
            val frameBytes = 4 * channels
            val chunkFrames = 65_536
            val buf = ByteBuffer.allocateDirect(chunkFrames * frameBytes).order(ByteOrder.LITTLE_ENDIAN)
            val perChannel = Array(channels) { FloatArray(chunkFrames) }
            var position = 0L
            val size = ch.size()
            while (position < size) {
                buf.clear()
                val limit = minOf(buf.capacity().toLong(), size - position).toInt() / frameBytes * frameBytes
                buf.limit(limit)
                var read = 0
                while (read < limit) { val n = ch.read(buf, position + read); if (n <= 0) break; read += n }
                buf.flip()
                val frames = read / frameBytes
                val fb = buf.asFloatBuffer()
                for (f in 0 until frames) for (c in 0 until channels) perChannel[c][f] = fb.get(f * channels + c)
                for (c in 0 until channels) builders[c].add(perChannel[c], 0, frames)
                position += read
                if (read == 0) break
            }
        }
        return builders.map { it.build() }
    }

    private fun write(f: File, peaks: List<Peaks>) {
        val tmp = File(f.path + ".tmp")
        DataOutputStream(tmp.outputStream().buffered()).use { out ->
            out.writeInt(MAGIC); out.writeInt(peaks.size)
            for (p in peaks) { val floats = p.toFloats(); out.writeInt(floats.size); val bb = ByteBuffer.allocate(floats.size * 4).order(ByteOrder.LITTLE_ENDIAN); bb.asFloatBuffer().put(floats); out.write(bb.array()) }
        }
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    private fun read(f: File): List<Peaks> = DataInputStream(f.inputStream().buffered()).use { inp ->
        require(inp.readInt() == MAGIC) { "not a peaks file" }
        val channels = inp.readInt()
        List(channels) {
            val n = inp.readInt()
            val bytes = ByteArray(n * 4); inp.readFully(bytes)
            val floats = FloatArray(n); ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floats)
            Peaks.fromFloats(floats)
        }
    }

    companion object { private const val MAGIC = 0x53504B31 } // 'SPK1'
}

/** Reading and writing the raw float files the graph plays: interleaved 32-bit float, little-endian. */
object RawAudio {
    fun frames(file: File, channels: Int): Long = if (channels <= 0) 0 else file.length() / (4L * channels)

    /** Reads [frames] frames from [fromFrame], interleaved. Short at the end of the file. */
    fun read(file: File, channels: Int, fromFrame: Long, frames: Int): FloatArray {
        RandomAccessFile(file, "r").use { raf ->
            val ch = raf.channel
            val total = frames(file, channels)
            val count = minOf(frames.toLong(), (total - fromFrame).coerceAtLeast(0)).toInt()
            if (count <= 0) return FloatArray(0)
            val buf = ByteBuffer.allocate(count * channels * 4).order(ByteOrder.LITTLE_ENDIAN)
            var position = fromFrame * channels * 4
            while (buf.hasRemaining()) { val n = ch.read(buf, position); if (n <= 0) break; position += n }
            buf.flip()
            val out = FloatArray(count * channels)
            buf.asFloatBuffer().get(out, 0, buf.remaining() / 4)
            return out
        }
    }

    /** One channel of [fromFrame, fromFrame + frames). */
    fun readChannel(file: File, channels: Int, channel: Int, fromFrame: Long, frames: Int): FloatArray {
        val inter = read(file, channels, fromFrame, frames)
        val n = inter.size / channels
        return FloatArray(n) { inter[it * channels + channel.coerceIn(0, channels - 1)] }
    }

    fun write(file: File, interleaved: FloatArray, append: Boolean = false) {
        val bb = ByteBuffer.allocate(interleaved.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        bb.asFloatBuffer().put(interleaved)
        if (append) file.appendBytes(bb.array()) else file.writeBytes(bb.array())
    }

    /** Writes a 32-bit float WAV header plus the data, for a take or an export the system can play directly. */
    fun writeWav(file: File, interleaved: FloatArray, sampleRate: Int, channels: Int) {
        val dataBytes = interleaved.size * 4
        val bb = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray()).putInt(16).putShort(3).putShort(channels.toShort()).putInt(sampleRate).putInt(sampleRate * channels * 4).putShort((channels * 4).toShort()).putShort(32)
        bb.put("data".toByteArray()).putInt(dataBytes)
        bb.asFloatBuffer().put(interleaved)
        file.writeBytes(bb.array())
    }

    /**
     * A take's WAV (float, written by the recorder) read as raw float frames: the header is 44
     * bytes and the data follows, so the .wav *is* a .f32 after an offset - which is why the
     * graph can map a take by skipping 44 bytes (TakeFiles.toSource copies it to media/).
     */
    fun wavDataOffset(): Int = 44

    /** Fixes a take cut short by a crash: the header's sizes are set from the file's length. */
    fun repairWavHeader(file: File) {
        RandomAccessFile(file, "rw").use { raf ->
            if (raf.length() < 44) return
            val data = (raf.length() - 44).toInt()
            raf.seek(4); raf.write(le(36 + data))
            raf.seek(40); raf.write(le(data))
        }
    }

    private fun le(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(), ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte())
}
