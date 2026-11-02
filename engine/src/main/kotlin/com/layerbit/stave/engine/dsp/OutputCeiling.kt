package com.layerbit.stave.engine.dsp

/**
 * The last thing before the speaker: keeps every sample at or under [ceiling] and is otherwise
 * not there at all. Many MP3s decode with peaks over full scale (Kalimba.mp3 at 1.045), and the
 * phone's 16-bit output clips whatever is over 1.0, which is heard as grit on every loud hit.
 *
 * Below the ceiling the gain is exactly 1.0 and the output is the input [lookahead] frames late,
 * bit for bit. Ahead of an over the gain falls on a straight line that reaches what the peak
 * needs exactly when the peak is output (the lowest such line over the look-ahead), so it never
 * steps down; afterwards it recovers with a [releaseMs] one-pole and snaps to 1.0 once within a
 * ten-thousandth (a last step of 0.0009 dB), so a song that goes over once is bit-exact again
 * within half a second.
 *
 * `graph/Dsp.cpp`'s OutputCeiling is this, line for line; OutputCeilingChecks runs this one.
 */
class OutputCeiling(sampleRate: Int, val channels: Int, val ceiling: Float = 0.999f, releaseMs: Double = 60.0) {
    /** Frames of look-ahead, and of delay: a millisecond. */
    val lookahead: Int = maxOf(1, sampleRate / 1000)
    private val release = timeConstant(releaseMs, sampleRate)
    private val delay = FloatArray(lookahead * channels)
    private val needs = FloatArray(lookahead) { 1f }
    private var at = 0                  // slot of the oldest frame: the one output next
    private var gain = 1f
    private var sinceOver = Int.MAX_VALUE

    fun reset() { delay.fill(0f); needs.fill(1f); at = 0; gain = 1f; sinceOver = Int.MAX_VALUE }

    fun process(buf: FloatArray, frames: Int) {
        val w = lookahead
        for (f in 0 until frames) {
            var peak = 0f
            for (c in 0 until channels) { val a = Math.abs(buf[f * channels + c]); if (a > peak) peak = a }
            val need = if (peak > ceiling) ceiling / peak else 1f
            // Frame n - w leaves through slot [at] as frame n takes it.
            var target = needs[at]
            needs[at] = need
            if (need < 1f) sinceOver = 0 else if (sinceOver != Int.MAX_VALUE) sinceOver++
            if (sinceOver <= w) {
                // Frame n - w + j sits in slot (at + j) % w; a line from here reaches its need in j frames.
                for (j in 1..w) {
                    val n = needs[(at + j) % w]
                    if (n < 1f) { val line = 1f - (1f - n) * (w - j).toFloat() / w; if (line < target) target = line }
                }
            }
            gain = if (target < gain) target else gain + release * (target - gain)
            if (gain > 1f - 1e-4f) gain = 1f
            for (c in 0 until channels) {
                val y = delay[at * channels + c]
                delay[at * channels + c] = buf[f * channels + c]
                var o = if (gain == 1f) y else y * gain
                if (o > ceiling) o = ceiling else if (o < -ceiling) o = -ceiling
                buf[f * channels + c] = o
            }
            at = (at + 1) % w
        }
    }
}
