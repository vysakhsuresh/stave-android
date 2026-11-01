package com.layerbit.stave.engine.transcript

import kotlin.math.max

/**
 * Typed lyrics laid against the words Whisper heard, so a karaoke screen shows the lines as
 * written (Whisper mishears a chorus; the person knows the words) with the times the sound
 * gives them.
 *
 * The two word sequences are aligned by edit distance over normalised tokens (lower-cased,
 * punctuation off, letters and digits kept in any script), with a match worth more than a
 * near-miss and a near-miss (the same first three code points, or one edit apart on a short
 * word) worth more than nothing: Whisper writes "gonna" for "going to" and "colour" for
 * "color", and a karaoke line is still that line. A typed line takes the time of the first
 * and last heard words it matched; a line that matched nothing is left without a time, and
 * the screen shows it between its neighbours.
 */
object Lyrics {

    /** A typed line with the stretch of sound it was matched to, or no stretch when none of its words were heard. */
    data class Line(val text: String, val startMs: Long?, val endMs: Long?) {
        val isPlaced: Boolean get() = startMs != null && endMs != null
    }

    fun align(text: String, heard: List<Word>): List<Line> {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return emptyList()
        // Every typed token, remembering its line.
        val typed = ArrayList<Pair<Int, String>>()
        for ((li, line) in lines.withIndex()) for (tok in tokens(line)) typed += li to tok
        val heardTokens = heard.map { tokens(it.text).joinToString("") }
        val pairs = alignTokens(typed.map { it.second }, heardTokens)
        val starts = LongArray(lines.size) { Long.MAX_VALUE }
        val ends = LongArray(lines.size) { Long.MIN_VALUE }
        for ((ti, hi) in pairs) {
            val li = typed[ti].first
            val w = heard[hi]
            if (w.startMs < starts[li]) starts[li] = w.startMs
            if (w.endMs > ends[li]) ends[li] = w.endMs
        }
        // The alignment is monotonic in both sequences, so a later line's words come after an
        // earlier line's and the lines are already in order (LyricsChecks holds it); a clamp
        // against running backwards was here once, and the negative test showed it never ran.
        return lines.indices.map { i -> if (starts[i] == Long.MAX_VALUE) Line(lines[i], null, null) else Line(lines[i], starts[i], max(ends[i], starts[i] + 1)) }
    }

    /**
     * Words lower-cased with punctuation taken off; empty tokens dropped. Letters and digits of
     * any script count, and so do combining marks: a Malayalam or Devanagari word is letters
     * *and* its vowel signs and viramas, which `isLetterOrDigit` says no to, and dropping them
     * turned നമസ്കാരം into five bare consonants on the first run of the suite.
     */
    fun tokens(text: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val type = Character.getType(cp)
            val mark = type == Character.NON_SPACING_MARK.toInt() || type == Character.COMBINING_SPACING_MARK.toInt() || type == Character.ENCLOSING_MARK.toInt()
            if (Character.isLetterOrDigit(cp) || (mark && sb.isNotEmpty())) sb.appendCodePoint(Character.toLowerCase(cp))
            else if (Character.isWhitespace(cp) && sb.isNotEmpty()) { out += sb.toString(); sb.setLength(0) }
            i += Character.charCount(cp)
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out
    }

    /** How alike two tokens are: 1 the same, 0.6 a near-miss, 0 nothing. */
    fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val stemA = prefix(a, 3); val stemB = prefix(b, 3)
        if (stemA.isNotEmpty() && stemA == stemB && (a.codePointCount(0, a.length) >= 3 && b.codePointCount(0, b.length) >= 3)) return 0.6
        if (a.length <= 6 && b.length <= 6 && editDistance(a, b) == 1) return 0.6
        return 0.0
    }

    /** Levenshtein distance over chars; only ever asked of short tokens. */
    fun editDistance(a: String, b: String): Int {
        val prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev.indices.forEach { prev[it] = cur[it] }
        }
        return prev[b.length]
    }

    private fun prefix(s: String, codePoints: Int): String {
        var i = 0; var n = 0
        while (i < s.length && n < codePoints) { i += Character.charCount(s.codePointAt(i)); n++ }
        return s.substring(0, i)
    }

    /**
     * Pairs (typed index, heard index) of the best alignment: a Needleman-Wunsch over the two
     * sequences, a pair scoring its similarity, a skip costing [SKIP]. Quadratic, which for a
     * song's few hundred words is nothing.
     */
    fun alignTokens(typed: List<String>, heard: List<String>): List<Pair<Int, Int>> {
        val n = typed.size; val m = heard.size
        if (n == 0 || m == 0) return emptyList()
        val score = Array(n + 1) { DoubleArray(m + 1) }
        for (i in 1..n) score[i][0] = score[i - 1][0] - SKIP
        for (j in 1..m) score[0][j] = score[0][j - 1] - SKIP
        for (i in 1..n) for (j in 1..m) {
            val pair = score[i - 1][j - 1] + similarity(typed[i - 1], heard[j - 1]) - PAIR_FLOOR
            val skipTyped = score[i - 1][j] - SKIP
            val skipHeard = score[i][j - 1] - SKIP
            score[i][j] = maxOf(pair, skipTyped, skipHeard)
        }
        val out = ArrayList<Pair<Int, Int>>()
        var i = n; var j = m
        while (i > 0 && j > 0) {
            val here = score[i][j]
            val sim = similarity(typed[i - 1], heard[j - 1])
            when {
                here == score[i - 1][j - 1] + sim - PAIR_FLOOR -> { if (sim > 0.0) out += (i - 1) to (j - 1); i--; j-- }
                here == score[i - 1][j] - SKIP -> i--
                else -> j--
            }
        }
        out.reverse()
        return out
    }

    /** What skipping a word costs; under the gain of a match and over a near-miss's margin, so two near-misses beat a skip pair. */
    const val SKIP = 0.4
    /** Pairing two unalike words scores below zero, so the alignment skips them rather than pairs them. */
    const val PAIR_FLOOR = 0.3
}
