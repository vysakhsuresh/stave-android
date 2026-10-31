package com.layerbit.stave.voice

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.layerbit.stave.engine.dsp.Resampler
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * The phone's own text-to-speech, as a door: a line typed on the Voice tab becomes a WAV
 * through the system engine (Google's, Samsung's, or whatever is installed - nothing of ours
 * leaves the phone, and the manifest's `<queries>` for TTS_SERVICE is what lets the engine
 * bind on Android 11+), read back as float at the session's rate for a clip at the play-head.
 *
 * Engines write 16-bit PCM at their own rate (22 050 or 24 000 Hz, mono) with a header that is
 * not always the canonical 44 bytes, so the chunks are walked rather than skipped.
 */
class ReadAloud(private val context: Context) {

    class Spoken(val mono: FloatArray, val sampleRate: Int)

    /** Speaks [text] to a file and returns it decoded, or throws with the reason. [language] is a BCP-47 tag or null for the engine's default. */
    suspend fun speak(text: String, language: String?, into: File): Spoken {
        require(text.isNotBlank()) { "Nothing to read." }
        var engine: TextToSpeech? = null
        try {
            val ready = suspendCoroutine<Boolean> { cont -> engine = TextToSpeech(context) { status -> cont.resume(status == TextToSpeech.SUCCESS) } }
            val tts = engine ?: throw IllegalStateException("No speech engine on this phone.")
            if (!ready) throw IllegalStateException("The speech engine did not start.")
            if (language != null) {
                val r = tts.setLanguage(Locale.forLanguageTag(language))
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) throw IllegalStateException("The speech engine has no voice for $language. Install one in the phone's text-to-speech settings.")
            }
            val id = "stave-${System.nanoTime()}"
            val done = suspendCoroutine<String?> { cont ->
                var resumed = false
                fun finish(error: String?) { if (!resumed) { resumed = true; cont.resume(error) } }
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { if (utteranceId == id) finish(null) }
                    @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) { if (utteranceId == id) finish("The speech engine could not read that.") }
                    override fun onError(utteranceId: String?, errorCode: Int) { if (utteranceId == id) finish("The speech engine could not read that (code $errorCode).") }
                })
                val queued = tts.synthesizeToFile(text, Bundle(), into, id)
                if (queued != TextToSpeech.SUCCESS) finish("The speech engine refused the text.")
            }
            if (done != null) throw IllegalStateException(done)
            return decodeWav(into)
        } finally {
            engine?.shutdown()
        }
    }

    /** A spoken file at the session's rate, interleaved for one channel (mono). */
    fun atRate(spoken: Spoken, sampleRate: Int): FloatArray =
        if (spoken.sampleRate == sampleRate) spoken.mono else Resampler(spoken.sampleRate, sampleRate).convert(spoken.mono)

    companion object {
        /** PCM WAV (8, 16, 24 or 32-bit integer, or 32-bit float) to mono float, the chunks walked. */
        fun decodeWav(file: File): Spoken {
            val bytes = file.readBytes()
            require(bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE") { "Not a WAV file." }
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            var pos = 12
            var channels = 1; var rate = 22_050; var bits = 16; var format = 1
            var dataStart = -1; var dataLength = 0
            while (pos + 8 <= bytes.size) {
                val tag = String(bytes, pos, 4, Charsets.US_ASCII)
                val size = buf.getInt(pos + 4)
                val body = pos + 8
                when (tag) {
                    "fmt " -> { format = buf.getShort(body).toInt() and 0xFFFF; channels = buf.getShort(body + 2).toInt(); rate = buf.getInt(body + 4); bits = buf.getShort(body + 14).toInt() }
                    "data" -> { dataStart = body; dataLength = if (size < 0 || body + size > bytes.size) bytes.size - body else size }
                }
                if (dataStart >= 0) break
                pos = body + size + (size and 1)
            }
            require(dataStart >= 0 && channels in 1..8 && rate > 0) { "The WAV has no data." }
            val bytesPer = bits / 8
            val frames = dataLength / (bytesPer * channels)
            val mono = FloatArray(frames)
            for (f in 0 until frames) {
                var sum = 0f
                for (c in 0 until channels) {
                    val at = dataStart + (f * channels + c) * bytesPer
                    sum += when {
                        format == 3 && bits == 32 -> buf.getFloat(at)
                        bits == 16 -> buf.getShort(at) / 32768f
                        bits == 8 -> ((bytes[at].toInt() and 0xFF) - 128) / 128f
                        bits == 24 -> (((bytes[at + 2].toInt() shl 24) or ((bytes[at + 1].toInt() and 0xFF) shl 16) or ((bytes[at].toInt() and 0xFF) shl 8)) shr 8) / 8388608f
                        bits == 32 -> buf.getInt(at) / 2147483648f
                        else -> 0f
                    }
                }
                mono[f] = sum / channels
            }
            return Spoken(mono, rate)
        }
    }
}
