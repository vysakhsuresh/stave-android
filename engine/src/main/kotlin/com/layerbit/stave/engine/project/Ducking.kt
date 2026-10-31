package com.layerbit.stave.engine.project

/**
 * Duck under speech: where a voice track speaks, the music track's volume automation dips by a
 * depth, ramping down quickly so the first word is clear and back up slowly so the music does
 * not pump between sentences. Pure: a frame-by-frame speech mask in, automation points out.
 */
object Ducking {
    /**
     * [speech] one flag per [frameSamples]-sample frame; [baseDb] the track's level; the result
     * sits at [baseDb] where nobody speaks and [baseDb] + [depthDb] (negative) under speech, with
     * [attackSamples] to go down and [releaseSamples] to come back. Gaps shorter than
     * [holdSamples] stay ducked, so a breath between words is not a swell.
     */
    fun points(speech: BooleanArray, frameSamples: Int, baseDb: Double, depthDb: Double, attackSamples: Long, releaseSamples: Long, holdSamples: Long): List<ValueTrack.Point> {
        val out = ArrayList<ValueTrack.Point>()
        if (speech.isEmpty()) return out
        // Speech runs, with short gaps bridged.
        val runs = ArrayList<LongRange>()
        var i = 0
        while (i < speech.size) {
            if (speech[i]) {
                var j = i
                while (j < speech.size && speech[j]) j++
                val start = i.toLong() * frameSamples; val end = j.toLong() * frameSamples
                if (runs.isNotEmpty() && start - (runs.last().last + 1) <= holdSamples) runs[runs.size - 1] = runs.last().first until end
                else runs += start until end
                i = j
            } else i++
        }
        if (runs.isEmpty()) return out
        val ducked = baseDb + depthDb
        out += ValueTrack.Point(0, if (runs.first().first <= attackSamples) ducked else baseDb)
        for (r in runs) {
            val downStart = (r.first - attackSamples).coerceAtLeast(0)
            if (downStart > 0) out += ValueTrack.Point(downStart, baseDb)
            out += ValueTrack.Point(r.first, ducked)
            out += ValueTrack.Point(r.last + 1, ducked)
            out += ValueTrack.Point(r.last + 1 + releaseSamples, baseDb)
        }
        // Two points at one position cannot both stand; the later run's wins, and the list stays ordered.
        return out.sortedBy { it.position }.distinctBy { it.position }
    }

    /** Frames above [thresholdDb] count as speech; a frame of silence between two speech frames is speech too. */
    fun speechMask(levelsDb: FloatArray, thresholdDb: Float): BooleanArray {
        val raw = BooleanArray(levelsDb.size) { levelsDb[it] > thresholdDb }
        val out = raw.copyOf()
        for (i in 1 until raw.size - 1) if (!raw[i] && raw[i - 1] && raw[i + 1]) out[i] = true
        return out
    }
}
