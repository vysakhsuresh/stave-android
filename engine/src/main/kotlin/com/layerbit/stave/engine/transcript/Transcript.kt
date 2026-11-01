package com.layerbit.stave.engine.transcript

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** One word Whisper heard, with the stretch of audio it was said in. Times in ms on the timeline. */
data class Word(val text: String, val startMs: Long, val endMs: Long) {
    val durationMs: Long get() = endMs - startMs
    fun shifted(byMs: Long): Word = copy(startMs = startMs + byMs, endMs = endMs + byMs)
}

/** What a transcription produced: words in order and the language heard. */
data class Transcript(val words: List<Word>, val language: String) {
    val isEmpty: Boolean get() = words.isEmpty()
    val text: String get() = words.joinToString(" ") { it.text }

    /**
     * After [from, to) ms were cut from the audio: words inside go, words after move back, a
     * word straddling an edge keeps the part outside. The transcript follows the sound.
     */
    fun afterCut(fromMs: Long, toMs: Long): Transcript {
        if (toMs <= fromMs) return this
        val gap = toMs - fromMs
        val kept = ArrayList<Word>()
        for (w in words) {
            when {
                w.endMs <= fromMs -> kept += w
                w.startMs >= toMs -> kept += w.shifted(-gap)
                w.startMs < fromMs && w.endMs > toMs -> kept += w.copy(endMs = w.endMs - gap)
                w.startMs < fromMs -> kept += w.copy(endMs = fromMs)
                w.endMs > toMs -> kept += w.copy(startMs = fromMs, endMs = w.endMs - gap)
                else -> Unit // wholly inside: gone
            }
        }
        return copy(words = kept)
    }

    /** After [lengthMs] of silence was opened at [atMs]: words from there move on. */
    fun afterInsert(atMs: Long, lengthMs: Long): Transcript =
        copy(words = words.map { w -> if (w.startMs >= atMs) w.shifted(lengthMs) else if (w.endMs > atMs) w.copy(endMs = w.endMs + lengthMs) else w })

    /** The words whose middle falls inside [fromMs, toMs). */
    fun wordsIn(fromMs: Long, toMs: Long): List<Int> = words.indices.filter { val m = (words[it].startMs + words[it].endMs) / 2; m >= fromMs && m < toMs }

    /** Words grouped into lines at pauses longer than [gapMs] or after sentence punctuation. */
    fun lines(gapMs: Long = 700): List<List<Word>> {
        val out = ArrayList<List<Word>>()
        var current = ArrayList<Word>()
        for ((i, w) in words.withIndex()) {
            current += w
            val next = words.getOrNull(i + 1)
            val pause = next != null && next.startMs - w.endMs > gapMs
            val sentenceEnd = w.text.lastOrNull()?.let { it == '.' || it == '?' || it == '!' || it == '।' } == true
            if (next == null || pause || sentenceEnd) { out += current; current = ArrayList() }
        }
        return out
    }
}

/**
 * Whisper's text, read as *bytes*. whisper.cpp hands each word's piece back as UTF-8 that can end
 * in the middle of a character (any Indic script), and NewStringUTF aborts on that, so the
 * native side gives bytes and this joins a word whose bytes end in an incomplete sequence onto
 * the next (Squish, 26 October, on a Hindi file). Whatever is still unreadable becomes U+FFFD.
 */
object WhisperText {
    /** Lines "t0\tt1\ttext" with a last "lang\tcode"; [offsetMs] is where the window sat in the timeline. */
    fun parseBytes(bytes: ByteArray?, offsetMs: Long): Transcript? {
        if (bytes == null) return null
        val words = ArrayList<Word>()
        var language = ""
        var pendingBytes: ByteArray? = null
        var pendingStart = 0L
        var i = 0
        while (i < bytes.size) {
            var j = i
            while (j < bytes.size && bytes[j] != '\n'.code.toByte()) j++
            val line = bytes.copyOfRange(i, j)
            i = j + 1
            val tab1 = line.indexOf('\t'.code.toByte())
            if (tab1 < 0) continue
            val head = String(line, 0, tab1, Charsets.UTF_8)
            if (head == "lang") { language = String(line, tab1 + 1, line.size - tab1 - 1, Charsets.UTF_8).trim(); continue }
            val tab2 = line.indexOf('\t'.code.toByte(), tab1 + 1)
            if (tab2 < 0) continue
            val t0 = head.toLongOrNull() ?: continue
            val t1 = String(line, tab1 + 1, tab2 - tab1 - 1, Charsets.UTF_8).toLongOrNull() ?: continue
            var text = line.copyOfRange(tab2 + 1, line.size)
            var start = t0
            if (pendingBytes != null) { text = pendingBytes + text; start = pendingStart; pendingBytes = null }
            if (endsMidCharacter(text)) { pendingBytes = text; pendingStart = start; continue }
            val decoded = String(text, Charsets.UTF_8).trim()
            if (decoded.isEmpty()) continue
            words += Word(decoded, start + offsetMs, max(t1, t0 + 1) + offsetMs)
        }
        pendingBytes?.let { if (it.isNotEmpty()) words += Word(String(it, Charsets.UTF_8).trim(), pendingStart + offsetMs, pendingStart + offsetMs + 100) }
        return Transcript(words, language)
    }

    private fun ByteArray.indexOf(b: Byte, from: Int = 0): Int { for (k in from until size) if (this[k] == b) return k; return -1 }

    /** Whether the last UTF-8 sequence in [bytes] is cut short. */
    fun endsMidCharacter(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        // Walk back over continuation bytes to the lead byte, then see how many it promised.
        var k = bytes.size - 1
        var continuations = 0
        while (k >= 0 && (bytes[k].toInt() and 0xC0) == 0x80) { continuations++; k-- }
        if (k < 0) return true
        val lead = bytes[k].toInt() and 0xFF
        val needed = when {
            lead < 0x80 -> 0
            lead and 0xE0 == 0xC0 -> 1
            lead and 0xF0 == 0xE0 -> 2
            lead and 0xF8 == 0xF0 -> 3
            else -> 0
        }
        return continuations < needed
    }
}

/**
 * Stretches of audio heard a window at a time. Whisper reads 30 s windows and pads a shorter
 * call to one, so a two-second line costs as much as thirty seconds: neighbouring stretches are
 * heard together up to [MAX_WINDOW_MS] and each word handed back to where it was said.
 */
object WhisperWindows {
    const val MAX_WINDOW_MS = 28_000L
    /** The overlap between consecutive windows of one long stretch, so a word on the seam is heard whole once. */
    const val OVERLAP_MS = 1_000L

    /** [lengthMs] of continuous audio cut into windows (start, end) with an overlap. */
    fun windows(lengthMs: Long, maxMs: Long = MAX_WINDOW_MS, overlapMs: Long = OVERLAP_MS): List<LongRange> {
        if (lengthMs <= 0) return emptyList()
        if (lengthMs <= maxMs) return listOf(0L until lengthMs)
        val out = ArrayList<LongRange>()
        var start = 0L
        while (start < lengthMs) {
            val end = min(lengthMs, start + maxMs)
            out += start until end
            if (end >= lengthMs) break
            start = end - overlapMs
        }
        return out
    }

    /**
     * Words from overlapping windows merged: in an overlap, the later window's words replace the
     * earlier window's words that start inside the overlap, so the seam is heard once.
     */
    fun merge(perWindow: List<Pair<LongRange, List<Word>>>): List<Word> {
        val out = ArrayList<Word>()
        for ((i, pair) in perWindow.withIndex()) {
            val (range, words) = pair
            val overlapStart = if (i == 0) Long.MIN_VALUE else range.first
            if (i > 0) out.removeAll { it.startMs >= overlapStart }
            out += words.filter { i == 0 || it.startMs >= overlapStart || out.none { o -> abs(o.startMs - it.startMs) < 50 } }
        }
        return out.sortedBy { it.startMs }
    }
}

/** The un-words: what "Take out every um and uh" takes out. */
object Fillers {
    val english = setOf("um", "uh", "er", "erm", "ah", "hmm", "mm", "mhm", "umm", "uhh", "ehm")

    /** Indices of filler words, by the word's letters alone (punctuation and case dropped). */
    fun find(words: List<Word>, extra: Set<String> = emptySet()): List<Int> {
        val set = english + extra.map { it.lowercase() }
        return words.indices.filter { normalise(words[it].text) in set }
    }

    fun normalise(text: String): String = text.lowercase().filter { it.isLetter() }
}

/**
 * Words to cuts. A cut is padded a little either side so a consonant is not clipped, and
 * adjacent cuts are merged only when the gap between them is short - **never by their place in
 * the word list**: fillers are often lines apart, and merging two "um"s twenty-seven seconds
 * apart made one 29-second cut inside one undo step (Squish's sweep five).
 */
object WordCuts {
    fun rangesFor(words: List<Word>, indices: Collection<Int>, padMs: Long = 20, mergeGapMs: Long = 150, totalMs: Long = Long.MAX_VALUE): List<LongRange> {
        val sorted = indices.filter { it in words.indices }.sorted()
        if (sorted.isEmpty()) return emptyList()
        val out = ArrayList<LongRange>()
        var start = -1L; var end = -1L
        for (i in sorted) {
            val w = words[i]
            val s = max(0, w.startMs - padMs); val e = min(totalMs, w.endMs + padMs)
            if (start < 0) { start = s; end = e; continue }
            if (s - end <= mergeGapMs) end = max(end, e) else { out += start until end; start = s; end = e }
        }
        out += start until end
        return out
    }

    /** Several ranges applied to a transcript and reported as what to cut from the audio, last first so earlier positions stay valid. */
    fun applyAll(transcript: Transcript, ranges: List<LongRange>): Transcript {
        var t = transcript
        for (r in ranges.sortedByDescending { it.first }) t = t.afterCut(r.first, r.last + 1)
        return t
    }
}

/**
 * Silences: stretches whose level stays under a threshold for long enough, each shrunk by a
 * margin at both ends so the breath before a word and the tail of the last are kept.
 */
object Silences {
    /** RMS level in dBFS per [frameMs] frame of [samples] at [rate]. */
    fun levels(samples: FloatArray, rate: Int, frameMs: Int = 20): FloatArray {
        val frame = max(1, rate * frameMs / 1000)
        val n = (samples.size + frame - 1) / frame
        return FloatArray(n) { i ->
            val from = i * frame; val to = min(samples.size, from + frame)
            var s = 0.0
            for (k in from until to) s += samples[k].toDouble() * samples[k]
            val rms = Math.sqrt(s / max(1, to - from))
            if (rms <= 1e-9) -144f else (20 * Math.log10(rms)).toFloat()
        }
    }

    /** Ranges in ms to remove: under [thresholdDb] for at least [minMs], shrunk by [keepMs] each side. */
    fun find(levelsDb: FloatArray, frameMs: Int, thresholdDb: Float, minMs: Long, keepMs: Long): List<LongRange> {
        val out = ArrayList<LongRange>()
        var i = 0
        while (i < levelsDb.size) {
            if (levelsDb[i] < thresholdDb) {
                var j = i
                while (j < levelsDb.size && levelsDb[j] < thresholdDb) j++
                val startMs = i.toLong() * frameMs; val endMs = j.toLong() * frameMs
                if (endMs - startMs >= minMs) {
                    val s = startMs + keepMs; val e = endMs - keepMs
                    if (e > s) out += s until e
                }
                i = j
            } else i++
        }
        return out
    }

    /** The adaptive threshold: a little above the quietest tenth of the frames, capped so speech is never "silence". */
    fun thresholdFor(levelsDb: FloatArray, aboveFloorDb: Float = 10f, capDb: Float = -30f): Float {
        if (levelsDb.isEmpty()) return capDb
        val sorted = levelsDb.filter { it > -140f }.sorted()
        if (sorted.isEmpty()) return capDb
        val floor = sorted[(sorted.size - 1) / 10]
        return min(capDb, floor + aboveFloorDb)
    }
}

/**
 * Word edges fitted to the pauses in the sound. Whisper's one-word segments are laid end to end,
 * so the last word before a pause runs on through it: on the phone (1 Nov 2026) "dog." ended
 * 340 ms after the speech stopped, and tapping "lazy dog." chose mostly silence. A pause that
 * begins inside a word ends the word there (plus a tail for the decay); a word that begins
 * inside a pause starts with the speech (less a lead for the breath). A word wholly inside a
 * pause is left as heard: the sound gives nothing to move it to.
 */
object WordTiming {
    /** Runs of [frameMs] frames under [thresholdDb] lasting at least [minPauseMs], in ms. */
    fun pauses(levelsDb: FloatArray, frameMs: Int, thresholdDb: Float, minPauseMs: Long = 120): List<LongRange> {
        val out = ArrayList<LongRange>()
        var i = 0
        while (i < levelsDb.size) {
            if (levelsDb[i] < thresholdDb) {
                var j = i
                while (j < levelsDb.size && levelsDb[j] < thresholdDb) j++
                val from = i.toLong() * frameMs; val to = j.toLong() * frameMs
                if (to - from >= minPauseMs) out += from until to
                i = j
            } else i++
        }
        return out
    }

    fun fitToSpeech(words: List<Word>, pauses: List<LongRange>, tailMs: Long = 40, leadMs: Long = 20): List<Word> = words.map { w ->
        var start = w.startMs; var end = w.endMs
        for (p in pauses) {
            val pFrom = p.first; val pTo = p.last + 1
            // The word starts inside the pause and runs on past it: start with the speech.
            if (start >= pFrom && start < pTo && end > pTo) start = max(start, pTo - leadMs)
            // A pause begins after the word starts and before it ends: the word ends there.
            if (pFrom > start && pFrom < end) end = min(end, pFrom + tailMs)
        }
        if (end > start && (start != w.startMs || end != w.endMs)) w.copy(startMs = start, endMs = end) else w
    }
}
