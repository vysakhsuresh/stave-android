package com.layerbit.stave.voice

import com.layerbit.stave.engine.dsp.Resampler
import com.layerbit.stave.engine.transcript.Transcript
import com.layerbit.stave.engine.transcript.WhisperText
import com.layerbit.stave.engine.transcript.WhisperWindows
import com.layerbit.stave.engine.transcript.Word
import com.layerbit.stave.models.ModelStore
import com.layerbit.stave.native.WhisperNative
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * Whisper over a stretch of planar audio: folded to mono, resampled to 16 kHz, cut into 28 s
 * windows with a second of overlap, each run through whisper.cpp, the words put back on the
 * timeline and the seams merged. One model open at a time; the handle is kept between calls
 * because loading is the slow part.
 */
class WhisperRunner(private val models: ModelStore) {
    private var handle = 0L
    private var openFile: File? = null

    val available: Boolean get() = WhisperNative.loaded && models.bestWhisper() != null

    /** The languages offered: a code and its own name, plus "auto". Whisper's list is long; these are the ones a phone in India meets. */
    val languages = listOf(
        "auto" to "Detect", "en" to "English", "hi" to "हिन्दी", "ml" to "മലയാളം", "ta" to "தமிழ்", "te" to "తెలుగు", "kn" to "ಕನ್ನಡ", "bn" to "বাংলা", "mr" to "मराठी", "gu" to "ગુજરાતી", "pa" to "ਪੰਜਾਬੀ", "ur" to "اردو",
        "ar" to "العربية", "es" to "Español", "fr" to "Français", "de" to "Deutsch", "pt" to "Português", "it" to "Italiano", "ru" to "Русский", "ja" to "日本語", "ko" to "한국어", "zh" to "中文", "id" to "Bahasa Indonesia", "tr" to "Türkçe"
    )

    private fun ensureOpen(): Boolean {
        if (!WhisperNative.loaded) return false
        val (_, file) = models.bestWhisper() ?: return false
        if (handle != 0L && openFile == file) return true
        if (handle != 0L) { WhisperNative.close(handle); handle = 0L }
        handle = WhisperNative.open(file.path)
        openFile = file
        return handle != 0L
    }

    fun close() { if (handle != 0L) { WhisperNative.close(handle); handle = 0L; openFile = null } }

    /**
     * [channels] at [sampleRate], starting at [offsetMs] on the timeline. [language] a code or
     * "auto". [onProgress] 0..1. Returns the transcript or null when the library or model is missing.
     */
    suspend fun transcribe(channels: List<FloatArray>, sampleRate: Int, offsetMs: Long, language: String, onProgress: (Float) -> Unit): Transcript? = withContext(Dispatchers.Default) {
        if (!ensureOpen()) return@withContext null
        val n = channels.first().size
        val mono = FloatArray(n)
        for (a in channels) for (i in 0 until n) mono[i] += a[i] / channels.size
        val pcm = if (sampleRate == 16_000) mono else Resampler(sampleRate, 16_000).convert(mono)
        val lengthMs = pcm.size * 1000L / 16_000
        val windows = WhisperWindows.windows(lengthMs)
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
        val results = ArrayList<Pair<LongRange, List<Word>>>()
        var heard = language
        for ((i, w) in windows.withIndex()) {
            coroutineContext.ensureActive()
            val from = (w.first * 16).toInt(); val to = ((w.last + 1) * 16).toInt().coerceAtMost(pcm.size)
            val slice = pcm.copyOfRange(from, to)
            val bytes = WhisperNative.transcribeBytes(handle, slice, heard, threads, null)
            val t = WhisperText.parseBytes(bytes, offsetMs + w.first)
            if (t != null) {
                results += (w.first + offsetMs until w.last + 1 + offsetMs) to t.words
                // Once the language is heard, every later window is told it, so a quiet window does not switch.
                if (heard == "auto" && t.language.isNotBlank()) heard = t.language
            }
            onProgress((i + 1).toFloat() / windows.size)
        }
        Transcript(WhisperWindows.merge(results), if (heard == "auto") "" else heard)
    }
}
