package com.layerbit.stave.engine.export

/**
 * What a file can be written as: the container, the encoder inside it, what it carries. One table
 * the Export sheet reads, the size estimate stands on, and the encoder door is given its names
 * from. The names are FFmpeg's (muxer and encoder), so a row here is exactly one call.
 */
data class AudioFormat(
    val id: String,
    val label: String,
    val extension: String,
    val mime: String,
    /** FFmpeg muxer name. */
    val muxer: String,
    /** FFmpeg encoder name; null means the muxer's default. */
    val encoder: String?,
    val lossless: Boolean,
    /** Bitrates offered, kbps; empty for lossless. */
    val bitrates: List<Int> = emptyList(),
    val defaultBitrate: Int = 0,
    /** Bit depths offered for PCM. */
    val depths: List<Int> = emptyList(),
    val chapters: Boolean = false,
    val cover: Boolean = false,
    /** Sample rates the encoder takes; empty means any. */
    val rates: List<Int> = emptyList(),
    val note: String = ""
) {
    /** Bytes for [seconds] at [bitrateKbps] (lossy) or [rate], [channels], [depth] (PCM), with a container allowance. */
    fun estimateBytes(seconds: Double, bitrateKbps: Int, rate: Int, channels: Int, depth: Int): Long {
        val payload = when {
            !lossless -> bitrateKbps * 1000.0 / 8 * seconds
            id == "flac" || id == "alac" || id == "wavpack" -> rate * channels * (depth / 8.0) * seconds * 0.62   // a typical lossless ratio for music
            else -> rate * channels * (depth / 8.0) * seconds
        }
        return (payload + 4096).toLong()
    }

    companion object {
        val MP3 = AudioFormat("mp3", "MP3", "mp3", "audio/mpeg", "mp3", "libmp3lame", false, listOf(96, 128, 160, 192, 256, 320), 192, chapters = true, cover = true, rates = listOf(8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000), note = "Plays everywhere. 192 kbps is transparent for speech, 320 for music.")
        val AAC = AudioFormat("m4a", "AAC (M4A)", "m4a", "audio/mp4", "ipod", "aac", false, listOf(64, 96, 128, 160, 192, 256), 160, chapters = true, cover = true, note = "Apple's and most podcast hosts' favourite; smaller than MP3 at the same quality.")
        val OPUS = AudioFormat("opus", "Opus", "opus", "audio/ogg", "opus", "libopus", false, listOf(24, 32, 48, 64, 96, 128, 160), 64, chapters = true, cover = false, rates = listOf(48000), note = "The best lossy codec; 64 kbps speech sounds like 192 kbps MP3. Not every old player opens it.")
        val OGG = AudioFormat("ogg", "Ogg Vorbis", "ogg", "audio/ogg", "ogg", "libvorbis", false, listOf(96, 128, 160, 192, 256), 160, chapters = false, cover = false, note = "Free and open; games and Linux players like it.")
        val FLAC = AudioFormat("flac", "FLAC", "flac", "audio/flac", "flac", "flac", true, depths = listOf(16, 24), chapters = false, cover = true, note = "Lossless and about 40% smaller than WAV. Keeps every bit of a master.")
        val ALAC = AudioFormat("alac", "Apple Lossless", "m4a", "audio/mp4", "ipod", "alac", true, depths = listOf(16, 24), chapters = true, cover = true, note = "Lossless, for Apple devices.")
        val WAV = AudioFormat("wav", "WAV", "wav", "audio/wav", "wav", "pcm_s16le", true, depths = listOf(16, 24, 32), chapters = false, cover = false, note = "Uncompressed. 24-bit for a master, 32-bit float to keep headroom for another studio.")
        val AIFF = AudioFormat("aiff", "AIFF", "aiff", "audio/aiff", "aiff", "pcm_s16be", true, depths = listOf(16, 24, 32), chapters = false, cover = false, note = "The WAV of the Mac world.")
        val WAVPACK = AudioFormat("wavpack", "WavPack", "wv", "audio/x-wavpack", "wv", "wavpack", true, depths = listOf(16, 24, 32), chapters = false, cover = false, note = "Lossless, and the one that also keeps 32-bit float.")
        val all = listOf(MP3, AAC, OPUS, OGG, FLAC, ALAC, WAV, AIFF, WAVPACK)
        fun byId(id: String?): AudioFormat = all.firstOrNull { it.id == id } ?: MP3

        /** The PCM encoder name for a depth and endianness, since WAV and AIFF choose by depth. */
        fun pcmEncoder(depth: Int, bigEndian: Boolean): String = when (depth) {
            24 -> if (bigEndian) "pcm_s24be" else "pcm_s24le"
            32 -> if (bigEndian) "pcm_f32be" else "pcm_f32le"
            else -> if (bigEndian) "pcm_s16be" else "pcm_s16le"
        }

        /** A rate the format accepts nearest [wanted]. */
        fun rateFor(format: AudioFormat, wanted: Int): Int = if (format.rates.isEmpty()) wanted else format.rates.minByOrNull { kotlin.math.abs(it - wanted) } ?: wanted
    }
}

/** What a person asked for on the Export sheet. */
data class ExportSettings(
    val formatId: String = "mp3",
    val bitrateKbps: Int = 192,
    val depth: Int = 16,
    /** 0 keeps the session's rate. */
    val sampleRate: Int = 0,
    /** 0 keeps the session's channel count; 1 folds to mono. */
    val channels: Int = 0,
    val normaliseToTarget: Boolean = false,
    val loudnessTargetId: String = "podcast",
    val dither: Boolean = true,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val comment: String = "",
    val writeChapters: Boolean = true,
    /** Album metadata: the track's number, the year, the genre; blank or 0 is left out of the file. */
    val trackNumber: Int = 0,
    val year: String = "",
    val genre: String = ""
) {
    val format: AudioFormat get() = AudioFormat.byId(formatId)

    /** The encoder name the door is given, resolving PCM by depth. */
    fun encoderName(): String? = when (format.id) {
        "wav" -> AudioFormat.pcmEncoder(depth, bigEndian = false)
        "aiff" -> AudioFormat.pcmEncoder(depth, bigEndian = true)
        else -> format.encoder
    }

    fun outputRate(sessionRate: Int): Int = AudioFormat.rateFor(format, if (sampleRate > 0) sampleRate else sessionRate)
    fun outputChannels(sessionChannels: Int): Int = if (channels > 0) channels else sessionChannels

    /** A file name from the title, safe for a file system: only what a name cannot hold is taken out (a whole Malayalam name stays). */
    fun fileName(fallback: String): String {
        val base = (title.ifBlank { fallback }).trim().replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").take(80).ifBlank { "stave" }
        return "$base.${format.extension}"
    }
}


/**
 * Publish presets: where the file is going decides the format, the bitrate and the loudness
 * target, in one tap. The numbers are the platforms' own published recommendations (Apple
 * Podcasts and Spotify take MP3 or AAC, YouTube normalises to -14, broadcast wants -23 or -24
 * with a true-peak ceiling); a preset only moves the settings it names, the tags stay.
 */
data class ExportPreset(val id: String, val label: String, val note: String, val apply: (ExportSettings) -> ExportSettings)

object ExportPresets {
    val all: List<ExportPreset> = listOf(
        ExportPreset("podcast_mp3", "Podcast MP3", "128 kbps MP3 at -16 LUFS: what Apple Podcasts and Spotify ask for a spoken show.") { it.copy(formatId = "mp3", bitrateKbps = 128, normaliseToTarget = true, loudnessTargetId = "podcast", writeChapters = true) },
        ExportPreset("podcast_aac", "Podcast AAC", "96 kbps AAC at -16 LUFS: smaller than the MP3, every podcast app plays it.") { it.copy(formatId = "m4a", bitrateKbps = 96, normaliseToTarget = true, loudnessTargetId = "podcast", writeChapters = true) },
        ExportPreset("streaming", "Streaming", "320 kbps MP3 at -14 LUFS, the level Spotify and YouTube play music at.") { it.copy(formatId = "mp3", bitrateKbps = 320, normaliseToTarget = true, loudnessTargetId = "streaming") },
        ExportPreset("master", "Master", "24-bit FLAC, the loudness left alone: the file to keep and to send to a distributor.") { it.copy(formatId = "flac", depth = 24, normaliseToTarget = false) },
        ExportPreset("broadcast", "Broadcast", "48 kHz 24-bit WAV at -23 LUFS with a -1 dBTP ceiling (EBU R128).") { it.copy(formatId = "wav", depth = 24, sampleRate = 48_000, normaliseToTarget = true, loudnessTargetId = "ebu") },
        ExportPreset("voice_note", "Voice note", "Mono Opus at 32 kbps: a message, a tenth the size of an MP3.") { it.copy(formatId = "opus", bitrateKbps = 32, channels = 1, normaliseToTarget = true, loudnessTargetId = "podcast") }
    )
    fun byId(id: String): ExportPreset? = all.firstOrNull { it.id == id }
}
