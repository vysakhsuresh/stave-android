package com.layerbit.stave.engine.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.tanh

/**
 * The time-based and colour effects a vocal chain and a looper want: delay, chorus/doubler,
 * flanger, tremolo, a plate-like reverb, saturation and a bit-crusher. Each is a mono processor
 * with `process(x)`; stereo runs two with the modulators offset. Offline here; the C++ graph
 * gets twins when a chain is to be heard live. Executed in EffectsChecks.
 */
class DelayLine(maxSamples: Int) {
    private val buffer = FloatArray(maxSamples.coerceAtLeast(2))
    private var write = 0
    val capacity: Int get() = buffer.size

    fun push(x: Float) { buffer[write] = x; write = (write + 1) % buffer.size }

    /** The sample [delay] samples ago, with linear interpolation for a fractional delay. */
    fun read(delay: Float): Float {
        val d = delay.coerceIn(1f, (buffer.size - 2).toFloat())
        val pos = write - d
        val p = if (pos < 0) pos + buffer.size else pos
        val i0 = p.toInt() % buffer.size
        val i1 = (i0 + 1) % buffer.size
        val frac = p - p.toInt()
        return buffer[i0] * (1 - frac) + buffer[i1] * frac
    }

    fun clear() { buffer.fill(0f); write = 0 }
}

/** An echo: delay time, feedback and the wet level; the feedback path low-passed so repeats darken as tape does. */
class Delay(val sampleRate: Int, var timeMs: Double = 350.0, var feedback: Float = 0.35f, var mix: Float = 0.3f, dampHz: Double = 4000.0) {
    private val line = DelayLine(sampleRate * 3)
    private val damp = Biquad(BiquadCoefficients.of(FilterKind.LOW_PASS, dampHz, sampleRate))
    fun process(x: Float): Float {
        val d = line.read((timeMs * sampleRate / 1000).toFloat())
        line.push(x + damp.process(d) * feedback)
        return x * (1 - mix) + d * mix
    }
    fun reset() { line.clear(); damp.reset() }
}

/** A low-frequency oscillator in 0..1 or -1..1. */
class Lfo(val sampleRate: Int, var hz: Double, var phaseOffset: Double = 0.0) {
    private var phase = 0.0
    fun next(): Float { val v = sin(2 * PI * phase + phaseOffset).toFloat(); phase += hz / sampleRate; if (phase >= 1) phase -= 1; return v }
}

/** Chorus (or a doubler at low depth and rate): one delayed copy swept by an LFO, mixed in. */
class Chorus(val sampleRate: Int, var rateHz: Double = 0.8, var depthMs: Double = 6.0, var centreMs: Double = 18.0, var mix: Float = 0.5f, phase: Double = 0.0) {
    private val line = DelayLine(sampleRate / 10)
    private val lfo = Lfo(sampleRate, rateHz, phase)
    fun process(x: Float): Float {
        lfo.hz = rateHz
        line.push(x)
        val d = ((centreMs + depthMs * lfo.next()) * sampleRate / 1000).toFloat()
        return x * (1 - mix) + line.read(d) * mix
    }
}

/** A flanger: a very short swept delay fed back on itself, for the jet-plane sweep. */
class Flanger(val sampleRate: Int, var rateHz: Double = 0.3, var depthMs: Double = 2.0, var feedback: Float = 0.5f, var mix: Float = 0.5f) {
    private val line = DelayLine(sampleRate / 50)
    private val lfo = Lfo(sampleRate, rateHz)
    private var last = 0f
    fun process(x: Float): Float {
        lfo.hz = rateHz
        val d = ((1.0 + depthMs * (0.5 + 0.5 * lfo.next())) * sampleRate / 1000).toFloat()
        line.push(x + last * feedback)
        last = line.read(d)
        return x * (1 - mix) + last * mix
    }
}

/**
 * A phaser: [stages] first-order all-pass sections in series, their corner swept by an LFO
 * between [lowHz] and [highHz] on a log scale, the wet summed with the dry so the notches fall
 * where the all-pass turns the phase through 180 degrees. [feedback] feeds the wet back in for
 * the deeper, resonant sweep.
 */
class Phaser(val sampleRate: Int, var rateHz: Double = 0.5, var depth: Float = 1f, var feedback: Float = 0.3f, var mix: Float = 0.5f, val stages: Int = 4, var lowHz: Double = 300.0, var highHz: Double = 3000.0) {
    private val lfo = Lfo(sampleRate, rateHz)
    private val z = FloatArray(stages)
    private var last = 0f
    fun process(x: Float): Float {
        lfo.hz = rateHz
        val sweep = 0.5 + 0.5 * lfo.next() * depth
        val hz = lowHz * Math.pow(highHz / lowHz, sweep)
        // First-order all-pass coefficient for a corner at hz.
        val t = Math.tan(Math.PI * hz / sampleRate)
        val a = ((t - 1) / (t + 1)).toFloat()
        var y = x + last * feedback
        for (i in 0 until stages) {
            val out = a * y + z[i]
            z[i] = y - a * out
            y = out
        }
        last = y
        return x * (1 - mix) + y * mix
    }
}

class Tremolo(val sampleRate: Int, var rateHz: Double = 5.0, var depth: Float = 0.5f) {
    private val lfo = Lfo(sampleRate, rateHz)
    fun process(x: Float): Float { lfo.hz = rateHz; val g = 1f - depth * (0.5f + 0.5f * lfo.next()); return x * g }
}

/**
 * A Schroeder/Moorer reverb: eight parallel combs into four series all-passes, with a damping
 * low-pass in each comb's feedback and a pre-delay. Freeverb's tunings, which have sounded like
 * a room to a great many people. Size scales the comb lengths, so a 1.0 is a hall, 0.3 a booth.
 */
class Reverb(val sampleRate: Int, var decay: Float = 0.84f, var damping: Float = 0.3f, var mix: Float = 0.25f, var preDelayMs: Double = 10.0, size: Float = 1.0f) {
    private val combTunings = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
    private val allpassTunings = intArrayOf(556, 441, 341, 225)
    private val scale = sampleRate / 44_100.0 * size
    private val combs = combTunings.map { DelayLine((it * scale).toInt().coerceAtLeast(2) + 2) }
    private val combLen = combTunings.map { (it * scale).toFloat().coerceAtLeast(2f) }
    private val combState = FloatArray(combTunings.size)
    private val allpasses = allpassTunings.map { DelayLine((it * scale).toInt().coerceAtLeast(2) + 2) }
    private val allpassLen = allpassTunings.map { (it * scale).toFloat().coerceAtLeast(2f) }
    private val pre = DelayLine(sampleRate / 5)

    fun process(x: Float): Float {
        pre.push(x)
        val input = pre.read((preDelayMs * sampleRate / 1000).toFloat().coerceAtLeast(1f)) * 0.015f
        var out = 0f
        for (i in combs.indices) {
            val y = combs[i].read(combLen[i])
            combState[i] = y * (1 - damping) + combState[i] * damping
            combs[i].push(input + combState[i] * decay)
            out += y
        }
        for (i in allpasses.indices) {
            val buf = allpasses[i].read(allpassLen[i])
            val y = -out + buf
            allpasses[i].push(out + buf * 0.5f)
            out = y
        }
        return x * (1 - mix) + out * mix
    }
}

/** Saturation: a soft clip with a drive, trimmed back so the level stays put at low drive. */
class Saturator(var drive: Float = 2f, var mix: Float = 1f) {
    fun process(x: Float): Float {
        val d = drive.coerceAtLeast(0.01f)
        val y = tanh(x * d) / tanh(d)
        return x * (1 - mix) + y * mix
    }
}

/** A bit-crusher and sample-rate reducer: the "telephone" and "radio" the presets lean on. */
class Crusher(var bits: Int = 8, var holdSamples: Int = 1) {
    private var held = 0f
    private var count = 0
    fun process(x: Float): Float {
        if (count == 0) { val steps = (1 shl (bits.coerceIn(2, 16) - 1)).toFloat(); held = Math.round(x * steps) / steps }
        count = (count + 1) % holdSamples.coerceAtLeast(1)
        return held
    }
}

/** Named vocal looks, each a chain built fresh for a rate and a channel. */
object VocalPresets {
    data class Preset(val id: String, val label: String, val note: String, val build: (sampleRate: Int, channel: Int) -> (Float) -> Float)

    val all: List<Preset> = listOf(
        Preset("doubler", "Doubler", "A second voice a few milliseconds off, as a singer tracking themselves.") { r, c -> val ch = Chorus(r, 0.35, 2.5, 14.0, 0.45f, if (c == 1) PI else 0.0); { x -> ch.process(x) } },
        Preset("chorus", "Chorus", "A wide, shimmering double.") { r, c -> val ch = Chorus(r, 0.9, 7.0, 20.0, 0.5f, if (c == 1) PI / 2 else 0.0); { x -> ch.process(x) } },
        Preset("slap", "Slapback", "One quick echo, the rockabilly room.") { r, _ -> val d = Delay(r, 110.0, 0.05f, 0.35f); { x -> d.process(x) } },
        Preset("echo", "Echo", "Repeats that darken and fade.") { r, _ -> val d = Delay(r, 380.0, 0.42f, 0.3f); { x -> d.process(x) } },
        Preset("room", "Room", "A small, warm space.") { r, _ -> val v = Reverb(r, 0.72f, 0.5f, 0.2f, 8.0, 0.45f); { x -> v.process(x) } },
        Preset("hall", "Hall", "A long, bright tail.") { r, _ -> val v = Reverb(r, 0.9f, 0.2f, 0.3f, 20.0, 1.0f); { x -> v.process(x) } },
        // A lambda that ends in another lambda on its own line reads as a trailing-lambda call of the
        // statement before it; the chains are built as a named value and returned.
        Preset("telephone", "Telephone", "Narrow, crushed, far away.") { r, _ ->
            val hp = Biquad(BiquadCoefficients.of(FilterKind.HIGH_PASS, 400.0, r, 1.0)); val lp = Biquad(BiquadCoefficients.of(FilterKind.LOW_PASS, 3200.0, r, 1.2)); val cr = Crusher(7, 2); val sat = Saturator(3f, 0.6f)
            val fx: (Float) -> Float = { x -> sat.process(cr.process(lp.process(hp.process(x)))) }
            fx
        },
        Preset("radio", "Radio", "A compressed, saturated broadcast voice.") { r, _ ->
            val hp = Biquad(BiquadCoefficients.of(FilterKind.HIGH_PASS, 120.0, r)); val pres = Biquad(BiquadCoefficients.of(FilterKind.PEAKING, 2800.0, r, 1.0, 5.0)); val sat = Saturator(2.5f, 0.8f)
            val fx: (Float) -> Float = { x -> sat.process(pres.process(hp.process(x))) }
            fx
        },
        Preset("megaphone", "Megaphone", "Honking, distorted, loud.") { r, _ ->
            val bp = Biquad(BiquadCoefficients.of(FilterKind.BAND_PASS, 1200.0, r, 0.8)); val sat = Saturator(8f, 1f)
            val fx: (Float) -> Float = { x -> sat.process(bp.process(x) * 3f) }
            fx
        },
        Preset("flange", "Flanger", "The jet plane.") { r, _ -> val f = Flanger(r); { x -> f.process(x) } },
        Preset("tremolo", "Tremolo", "The level wobbling, 5 Hz.") { r, _ -> val t = Tremolo(r); { x -> t.process(x) } },
        Preset("warm", "Warm", "Gentle saturation and a little low shelf.") { r, _ -> val sh = Biquad(BiquadCoefficients.of(FilterKind.LOW_SHELF, 200.0, r, 0.7, 2.5)); val sat = Saturator(1.4f, 0.5f); { x -> sat.process(sh.process(x)) } }
    )

    fun byId(id: String): Preset? = all.firstOrNull { it.id == id }

    /** The preset run over planar channels. */
    fun apply(preset: Preset, channels: List<FloatArray>, sampleRate: Int): List<FloatArray> = channels.mapIndexed { c, a ->
        val fx = preset.build(sampleRate, c)
        FloatArray(a.size) { fx(a[it]) }
    }
}
