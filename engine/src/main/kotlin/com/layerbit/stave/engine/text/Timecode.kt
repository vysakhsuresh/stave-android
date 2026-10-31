package com.layerbit.stave.engine.text

import java.util.Locale

/**
 * Time as the ruler, the clip chips and the clock show it. Two forms: the one a person reads,
 * built with the phone's locale digits, and the one a file holds (SRT, chapters, cue sheets),
 * built with Locale.ROOT so an Arabic or Bengali phone writes ASCII digits a parser can read.
 *
 * Squish learned both halves of this the hard way: `"%02d".format(n)` emits the locale's own
 * digits, and a localised string must never be cut up with an ASCII literal afterwards (the
 * ".000" the callers used to strip matched nothing once the digits were Arabic, so every ruler
 * label grew to nine characters). The fraction is left out of the format string instead.
 */
object Timecode {

    /** For the screen: m:ss, h:mm:ss, with an optional fraction at [decimals] places. */
    fun display(ms: Long, decimals: Int = 0, locale: Locale = Locale.getDefault(Locale.Category.FORMAT)): String {
        val negative = ms < 0
        val abs = if (negative) -ms else ms
        val totalSeconds = abs / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        val base = if (h > 0) String.format(locale, "%d:%02d:%02d", h, m, s) else String.format(locale, "%d:%02d", m, s)
        val fraction = when (decimals.coerceIn(0, 3)) {
            0 -> ""
            1 -> String.format(locale, ".%01d", (abs % 1000) / 100)
            2 -> String.format(locale, ".%02d", (abs % 1000) / 10)
            else -> String.format(locale, ".%03d", abs % 1000)
        }
        return (if (negative) "−" else "") + base + fraction
    }

    /** For a file: hh:mm:ss,mmm as SRT wants it, always ASCII. */
    fun srt(ms: Long): String {
        val t = ms.coerceAtLeast(0)
        return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", t / 3_600_000, (t / 60_000) % 60, (t / 1000) % 60, t % 1000)
    }

    /** For a file: hh:mm:ss.mmm (WebVTT, chapters), always ASCII. */
    fun vtt(ms: Long): String = srt(ms).replace(',', '.')

    /** A sample position as a time at [sampleRate]. */
    fun ofSamples(samples: Long, sampleRate: Int): Long = if (sampleRate <= 0) 0 else samples * 1000 / sampleRate

    /** Parses "h:mm:ss.mmm", "mm:ss,mmm", "ss.s" - whatever a subtitle or cue file wrote - in ASCII. */
    fun parse(text: String): Long? {
        val cleaned = text.trim().replace(',', '.')
        val parts = cleaned.split(':')
        if (parts.isEmpty() || parts.size > 3) return null
        var total = 0.0
        for (p in parts) {
            val v = p.toDoubleOrNull() ?: return null
            total = total * 60 + v
        }
        return Math.round(total * 1000)
    }

    /** Samples as "1,234,567" style groups; for the status line, the phone's locale. */
    fun samples(n: Long, locale: Locale = Locale.getDefault(Locale.Category.FORMAT)): String = String.format(locale, "%,d", n)
}
