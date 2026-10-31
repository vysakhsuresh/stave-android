package com.layerbit.stave.native

/**
 * whisper.cpp's JNI entry points (app/src/main/cpp/whisper/stavewhisper.cpp). Loaded on first
 * use, not at start: most sessions never transcribe, and a phone whose build lacks the library
 * (it is fetched into jniLibs from the whisper workflow) simply has no Whisper - [loaded] says so.
 */
object WhisperNative {
    val loaded: Boolean by lazy { runCatching { System.loadLibrary("stavewhisper") }.isSuccess }

    @JvmStatic external fun open(modelPath: String): Long
    @JvmStatic external fun close(handle: Long)
    /** One line per word "t0ms\tt1ms\ttext", then "lang\t<code>", as bytes (a character cut across pieces must not reach NewStringUTF). */
    @JvmStatic external fun transcribeBytes(handle: Long, pcm16k: FloatArray, language: String, threads: Int, prompt: String?): ByteArray?
    @JvmStatic external fun version(): String
}
