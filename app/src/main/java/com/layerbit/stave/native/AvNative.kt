package com.layerbit.stave.native

/**
 * The JNI surface of libstaveav.so - Stave's own build of FFmpeg's audio half
 * (app/src/main/cpp/av, built by tools/native/build.sh, committed under jniLibs). Handles are
 * native pointers as longs and **0 is the failure value**: a pointer is never zero, and it is
 * never compared to anything else - Squish lost every open on arm64 to a "<= 0" test, because
 * the top byte of a heap pointer there is a tag and the long reads negative (AvHandles).
 */
object AvNative {
    val loaded: Boolean by lazy {
        runCatching { System.loadLibrary("staveav") }
            .onFailure { android.util.Log.w("StaveAv", "libstaveav did not load", it) }
            .isSuccess
    }

    @JvmStatic external fun version(): String
    @JvmStatic external fun hasEncoder(name: String): Boolean
    @JvmStatic external fun hasDecoder(name: String): Boolean

    /** JSON bytes describing the file: format, duration, streams, tags, chapters, cover. */
    @JvmStatic external fun probe(fd: Int, extension: String?): ByteArray?
    @JvmStatic external fun coverArt(fd: Int, extension: String?): ByteArray?

    @JvmStatic external fun decOpen(fd: Int, extension: String?, streamIndex: Int): Long
    /** [rate, channels, durationUs, framesEstimate] of the stream. */
    @JvmStatic external fun decInfo(decoder: Long): LongArray
    /** Decodes the whole stream to [path] as interleaved float at [rate] (0 keeps) and [channels] (0 keeps). Blocking. Frames, or negative. */
    @JvmStatic external fun decRun(decoder: Long, path: String, rate: Int, channels: Int): Long
    @JvmStatic external fun decProgress(decoder: Long): Int
    @JvmStatic external fun decOutRate(decoder: Long): Int
    @JvmStatic external fun decOutChannels(decoder: Long): Int
    @JvmStatic external fun decCancel(decoder: Long)
    @JvmStatic external fun decClose(decoder: Long)

    @JvmStatic external fun encOpen(inPath: String, frames: Long, rate: Int, channels: Int, outPath: String, format: String?, codec: String?, bitrate: Int, outRate: Int, outChannels: Int, quality: Float): Long
    @JvmStatic external fun encSetTag(encoder: Long, key: String, value: String)
    @JvmStatic external fun encSetCover(encoder: Long, bytes: ByteArray?, mime: String?)
    @JvmStatic external fun encAddChapter(encoder: Long, startMs: Long, endMs: Long, title: String)
    /** Blocking. 0 on success, negative on failure (the half-written file is removed). */
    @JvmStatic external fun encRun(encoder: Long): Int
    @JvmStatic external fun encProgress(encoder: Long): Int
    @JvmStatic external fun encCancel(encoder: Long)
    @JvmStatic external fun encClose(encoder: Long)
}

/** The one test for a failed native open. */
object AvHandles {
    fun failed(handle: Long): Boolean = handle == 0L
}
