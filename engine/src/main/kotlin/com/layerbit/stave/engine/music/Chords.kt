package com.layerbit.stave.engine.music

import kotlin.math.ln
import kotlin.math.sqrt

/**
 * How a black key is written. A chart with no key in view uses [CHART]: flats where charts use
 * flats, F♯ where they use the sharp. Inside a key the key's own signature decides: in E♭ minor
 * the chord on the sixth black key is G♭, and the chart spelling "F♯" read as a wrong note.
 */
enum class Spelling { CHART, FLATS, SHARPS }

object PitchClasses {
    private val chart = arrayOf("C", "D♭", "D", "E♭", "E", "F", "F♯", "G", "A♭", "A", "B♭", "B")
    private val flats = arrayOf("C", "D♭", "D", "E♭", "E", "F", "G♭", "G", "A♭", "A", "B♭", "B")
    private val sharps = arrayOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")
    fun name(pitchClass: Int, spelling: Spelling = Spelling.CHART): String = when (spelling) {
        Spelling.CHART -> chart
        Spelling.FLATS -> flats
        Spelling.SHARPS -> sharps
    }[pitchClass.mod(12)]
}

/** A chord's shape above its root, and how its name ends. */
enum class Quality(val suffix: String, val intervals: IntArray) {
    MAJOR("", intArrayOf(0, 4, 7)),
    MINOR("m", intArrayOf(0, 3, 7)),
    DOMINANT7("7", intArrayOf(0, 4, 7, 10)),
    MAJOR7("maj7", intArrayOf(0, 4, 7, 11)),
    MINOR7("m7", intArrayOf(0, 3, 7, 10)),
    DIMINISHED("dim", intArrayOf(0, 3, 6)),
    AUGMENTED("aug", intArrayOf(0, 4, 8)),
    SUS2("sus2", intArrayOf(0, 2, 7)),
    SUS4("sus4", intArrayOf(0, 5, 7)),
    /** No chord: silence, noise, a drum break. Its root is meaningless and read as 0. */
    NONE("N.C.", intArrayOf());

    val isSeventh: Boolean get() = intervals.size == 4
}

data class Chord(val root: Int, val quality: Quality) {
    /** "A", "Am", "G7", "Cmaj7", "Bdim", or "N.C." for no chord. */
    fun name(spelling: Spelling = Spelling.CHART): String = if (quality == Quality.NONE) quality.suffix else PitchClasses.name(root, spelling) + quality.suffix

    /** The pitch classes sounding, as a set. */
    fun pitchClasses(): Set<Int> = quality.intervals.map { (root + it).mod(12) }.toSet()

    /**
     * An open-position guitar shape, low E to high E, a fret per string and -1 for a
     * string not played; null when no open shape is in common use (every shape here is
     * one a first-year player is taught, so a barre is not offered as if it were one).
     */
    fun guitarShape(): IntArray? = GuitarShapes.of(this)

    companion object {
        val NONE = Chord(0, Quality.NONE)
    }
}

/** A run of frames on one chord; [endFrame] is exclusive. */
data class ChordSpan(val startFrame: Int, val endFrame: Int, val root: Int, val quality: Quality, val confidence: Float) {
    val chord: Chord get() = Chord(root, quality)
    val frames: Int get() = endFrame - startFrame
}

/**
 * Chords from chroma: a template per chord, correlated frame by frame, then smoothed
 * by Viterbi so the answer does not flicker between a chord and its near relations
 * on every frame where a melody note tips the balance.
 *
 * The templates are binary - one on each chord tone - and compared by cosine, which
 * on a sum-normalised chroma is the same as a correlation. Harmonics are not modelled:
 * a real C note puts energy on G and E too, which still reads as C major, and a
 * harmonic-weighted template would mostly teach the detector to hear sevenths that
 * are not there.
 */
object Chords {
    /**
     * Every state the Viterbi runs over: 108 chords in a fixed order, then no chord last.
     * Some chords are one set of notes under two names - C, E and G♯ is Caug, Eaug and
     * G♯aug alike, and C, D, G is Csus2 or Gsus4 - and twelve pitch classes cannot tell
     * them apart. The order here settles the tie: the lowest root, and sus2 over sus4.
     */
    val states: List<Chord> = buildList {
        for (q in Quality.entries) if (q != Quality.NONE) for (root in 0 until 12) add(Chord(root, q))
        add(Chord.NONE)
    }

    private val noChord = states.lastIndex

    /** Unit-norm templates in [states] order. No chord is flat: what noise and silence look like. */
    val templates: Array<FloatArray> = Array(states.size) { s ->
        val chord = states[s]
        val t = FloatArray(12)
        if (chord.quality == Quality.NONE) t.fill(1f) else for (pc in chord.pitchClasses()) t[pc] = 1f
        var norm = 0f
        for (v in t) norm += v * v
        norm = sqrt(norm)
        for (i in t.indices) t[i] /= norm
        t
    }

    /**
     * How well [chroma] matches each state, 0..1, in [states] order. A frame with no
     * energy matches no chord fully and every chord not at all, so silence is N.C.
     */
    fun correlate(chroma: FloatArray): FloatArray {
        val out = FloatArray(states.size)
        var norm = 0f
        for (v in chroma) norm += v * v
        if (norm <= 0f) { out[noChord] = 1f; return out }
        norm = sqrt(norm)
        for (s in states.indices) {
            val t = templates[s]
            var dot = 0f
            for (i in 0 until 12) dot += chroma[i] * t[i]
            out[s] = (dot / norm).coerceIn(0f, 1f)
        }
        return out
    }

    /**
     * The chord spans of a chroma sequence.
     *
     * [selfTransition] is the probability of staying on the chord from one frame to the
     * next, the rest shared evenly among the changes; [sharpness] scales the correlation
     * into a log-likelihood. Together they set how much evidence a change needs: at the
     * defaults a switch costs about seven units and a frame where the new chord fits
     * a third better than the old pays back three, so a chord that lasts three frames
     * is followed and a single frame of noise is ridden through.
     */
    fun detect(chroma: List<FloatArray>, selfTransition: Double = 0.9, sharpness: Double = 10.0): List<ChordSpan> {
        val n = chroma.size
        if (n == 0) return emptyList()
        val s = states.size
        val stay = ln(selfTransition)
        val switch = ln((1.0 - selfTransition) / (s - 1))
        val fit = Array(n) { correlate(chroma[it]) }

        val score = DoubleArray(s) { fit[0][it] * sharpness }
        val back = Array(n) { IntArray(s) }
        val next = DoubleArray(s)
        for (t in 1 until n) {
            // The best predecessor is either the same state or the best of all the
            // others; two maxima cover every transition without an s-by-s loop.
            var best = 0
            var second = -1
            for (i in 1 until s) {
                if (score[i] > score[best]) { second = best; best = i }
                else if (second < 0 || score[i] > score[second]) second = i
            }
            for (j in 0 until s) {
                val other = if (best == j) second else best
                val viaStay = score[j] + stay
                val viaSwitch = score[other] + switch
                if (viaStay >= viaSwitch) { next[j] = viaStay; back[t][j] = j } else { next[j] = viaSwitch; back[t][j] = other }
                next[j] += fit[t][j] * sharpness
            }
            next.copyInto(score)
        }
        var state = 0
        for (i in 1 until s) if (score[i] > score[state]) state = i
        val path = IntArray(n)
        for (t in n - 1 downTo 0) {
            path[t] = state
            if (t > 0) state = back[t][state]
        }
        return spans(path, fit)
    }

    /** Runs of one state become spans; the confidence is the mean fit over the run. */
    private fun spans(path: IntArray, fit: Array<FloatArray>): List<ChordSpan> {
        val out = ArrayList<ChordSpan>()
        var start = 0
        while (start < path.size) {
            var end = start + 1
            while (end < path.size && path[end] == path[start]) end++
            var sum = 0.0
            for (t in start until end) sum += fit[t][path[start]]
            val chord = states[path[start]]
            out.add(ChordSpan(start, end, chord.root, chord.quality, (sum / (end - start)).toFloat()))
            start = end
        }
        return out
    }
}

/**
 * The chords as a chart lists them. The tracker answers "no chord" wherever a frame fits nothing,
 * and on a real song that was a third of the list: a breath, a drum fill, a held vocal between
 * two strums of the same chord. A gap shorter than [longestGapFrames] is not a chord change: the
 * chord before it carries on, and runs of one chord either side of it become one. A longer
 * silence (an intro, a break) stays "no chord", and so does one at the very start or end.
 */
object ChordChart {
    fun of(spans: List<ChordSpan>, longestGapFrames: Int): List<ChordSpan> {
        val out = ArrayList<ChordSpan>()
        for ((i, span) in spans.withIndex()) {
            val brief = span.quality == Quality.NONE && span.frames < longestGapFrames && i > 0 && i < spans.size - 1
            val last = out.lastOrNull()
            when {
                brief && last != null -> out[out.size - 1] = last.copy(endFrame = span.endFrame)
                last != null && last.quality != Quality.NONE && last.root == span.root && last.quality == span.quality -> {
                    val frames = last.frames + span.frames
                    out[out.size - 1] = last.copy(endFrame = span.endFrame, confidence = (last.confidence * last.frames + span.confidence * span.frames) / frames)
                }
                else -> out.add(span)
            }
        }
        return out
    }
}

/**
 * The open shapes, as a chart writes them from the low E string: a digit is a fret, x a
 * string left out. Every one is checked against its chord's pitch classes by the suite,
 * so a typo here cannot ship; several leave the fifth out (C7, as every chart has it),
 * which is why the suite asks for a subset with the root and the third in it, not equality.
 */
object GuitarShapes {
    /** Standard tuning, low to high, as pitch classes. */
    val OPEN_STRINGS = intArrayOf(4, 9, 2, 7, 11, 4)

    private val shapes: Map<Chord, IntArray> = buildMap {
        fun put(root: Int, quality: Quality, chart: String) = put(Chord(root, quality), parse(chart))
        // Major
        put(0, Quality.MAJOR, "x32010"); put(2, Quality.MAJOR, "xx0232"); put(4, Quality.MAJOR, "022100")
        put(5, Quality.MAJOR, "133211"); put(7, Quality.MAJOR, "320003"); put(9, Quality.MAJOR, "x02220")
        // Minor
        put(9, Quality.MINOR, "x02210"); put(2, Quality.MINOR, "xx0231"); put(4, Quality.MINOR, "022000")
        // Dominant sevenths
        put(9, Quality.DOMINANT7, "x02020"); put(11, Quality.DOMINANT7, "x21202"); put(0, Quality.DOMINANT7, "x32310")
        put(2, Quality.DOMINANT7, "xx0212"); put(4, Quality.DOMINANT7, "020100"); put(7, Quality.DOMINANT7, "320001")
        // Major sevenths
        put(0, Quality.MAJOR7, "x32000"); put(2, Quality.MAJOR7, "xx0222"); put(5, Quality.MAJOR7, "xx3210")
        put(7, Quality.MAJOR7, "320002"); put(9, Quality.MAJOR7, "x02120"); put(4, Quality.MAJOR7, "021100")
        // Minor sevenths
        put(9, Quality.MINOR7, "x02010"); put(2, Quality.MINOR7, "xx0211"); put(4, Quality.MINOR7, "020000"); put(11, Quality.MINOR7, "x20202")
        // Diminished and augmented
        put(11, Quality.DIMINISHED, "x2343x"); put(0, Quality.AUGMENTED, "x32110"); put(4, Quality.AUGMENTED, "032110")
        // Suspended
        put(9, Quality.SUS2, "x02200"); put(2, Quality.SUS2, "xx0230"); put(4, Quality.SUS2, "024400")
        put(9, Quality.SUS4, "x02230"); put(2, Quality.SUS4, "xx0233"); put(4, Quality.SUS4, "022200")
    }

    fun of(chord: Chord): IntArray? = shapes[chord]?.copyOf()

    /** Every chord with a shape, for a chart screen to list. */
    val known: Set<Chord> get() = shapes.keys

    /** The pitch classes a shape sounds. */
    fun pitchClasses(shape: IntArray): Set<Int> =
        shape.withIndex().filter { it.value >= 0 }.map { (OPEN_STRINGS[it.index] + it.value).mod(12) }.toSet()

    private fun parse(chart: String): IntArray {
        require(chart.length == 6) { "a shape is six strings: $chart" }
        return IntArray(6) { i -> if (chart[i] == 'x') -1 else chart[i].digitToInt() }
    }
}
