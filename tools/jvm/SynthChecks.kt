import com.layerbit.stave.engine.dsp.BiquadCoefficients
import com.layerbit.stave.engine.dsp.Fft
import com.layerbit.stave.engine.dsp.FilterKind
import com.layerbit.stave.engine.dsp.Gain
import com.layerbit.stave.engine.dsp.Windows
import com.layerbit.stave.engine.synth.Adsr
import com.layerbit.stave.engine.synth.AdsrSettings
import com.layerbit.stave.engine.synth.FmAlgorithms
import com.layerbit.stave.engine.synth.FmOperator
import com.layerbit.stave.engine.synth.FmPatch
import com.layerbit.stave.engine.synth.FmSynth
import com.layerbit.stave.engine.synth.NoiseOscillator
import com.layerbit.stave.engine.synth.Oscillator
import com.layerbit.stave.engine.synth.PolySynth
import com.layerbit.stave.engine.synth.SamplerVoice
import com.layerbit.stave.engine.synth.SawOscillator
import com.layerbit.stave.engine.synth.SineOscillator
import com.layerbit.stave.engine.synth.SquareOscillator
import com.layerbit.stave.engine.synth.SubtractivePatch
import com.layerbit.stave.engine.synth.Svf
import com.layerbit.stave.engine.synth.TriangleOscillator
import com.layerbit.stave.engine.synth.Waveform
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The synthesis engine, measured: oscillators by their spectra, the envelope by its levels,
 * the filter by what it passes, the synths by the notes that come out. The test tone sits on
 * an FFT bin (683 x 48000 / 32768 = 1000.49 Hz), so every harmonic is on a bin and an alias
 * landing between harmonics can be told from them - at exactly 1 kHz every alias of a 48 kHz
 * saw would fall on a harmonic and be invisible.
 */
object SynthChecks : Suite {
    override val name = "synth"

    private const val RATE = 48000
    private const val N = 32768
    private const val BIN = 683
    private const val F0 = BIN * RATE.toDouble() / N

    private fun render(osc: Oscillator, count: Int): FloatArray = FloatArray(count) { osc.next() }

    /** Magnitude in dB per bin of the last [N] samples, Hann windowed. */
    private fun spectrumDb(signal: FloatArray): FloatArray {
        val w = Windows.hann(N)
        val frame = FloatArray(N) { signal[signal.size - N + it] * w[it] }
        val out = FloatArray(N / 2 + 1)
        Fft.of(N).magnitudes(frame, FloatArray(N), FloatArray(N), out)
        return FloatArray(out.size) { Gain.linearToDb(out[it]) }
    }

    private fun peakBin(db: FloatArray, from: Int = 1): Int { var best = from; for (k in from until db.size) if (db[k] > db[best]) best = k; return best }
    private fun localPeak(db: FloatArray, hz: Double, radius: Int = 3): Float { val c = (hz * N / RATE).roundToInt(); var m = -999f; for (k in c - radius..c + radius) m = maxOf(m, db[k]); return m }

    /** The loudest bin not within [radius] bins of a multiple of [fundamentalBin]: the aliases, and nothing else, of a tone on a bin. */
    private fun worstAlias(db: FloatArray, fundamentalBin: Int, radius: Int = 4): Pair<Int, Float> {
        var bestK = -1; var best = -999f
        for (k in radius until db.size) {
            val toHarmonic = minOf(k % fundamentalBin, fundamentalBin - k % fundamentalBin)
            if (toHarmonic < radius) continue
            if (db[k] > best) { best = db[k]; bestK = k }
        }
        return bestK to best
    }

    private fun risingCrossings(s: FloatArray): Int { var c = 0; for (i in 1 until s.size) if (s[i - 1] < 0f && s[i] >= 0f) c++; return c }

    private fun rms(s: FloatArray, from: Int = 0): Double { var sum = 0.0; for (i in from until s.size) sum += s[i].toDouble() * s[i]; return Math.sqrt(sum / (s.size - from)) }

    override fun run() {
        section("the saw's fundamental is the frequency asked for")
        val saw = SawOscillator().also { it.setFrequency(F0, RATE) }
        val sawOut = render(saw, RATE + N)
        val crossings = risingCrossings(sawOut.copyOfRange(0, RATE))
        check(abs(crossings - F0) <= 2) { "rising zero crossings a second: $crossings for $F0 Hz" }
        val sawDb = spectrumDb(sawOut)
        check(peakBin(sawDb) == BIN) { "spectral peak at bin ${peakBin(sawDb)}, expected $BIN" }
        near(sawDb[2 * BIN] - sawDb[BIN], -6.02f, 0.5f, "second harmonic of a saw is half the first")
        // The B-spline residual lowpasses the whole harmonic series by sinc^4(f/fs): -0.6 dB at
        // 5 kHz, -2.5 at 10, which is the price of the alias figure below and is measured here
        // so a change of kernel is seen.
        near(sawDb[5 * BIN] - sawDb[BIN], -13.98f - 0.62f, 0.5f, "fifth harmonic is a fifth, less the kernel's droop")
        near(sawDb[10 * BIN] - sawDb[BIN], -20.0f - 2.5f, 0.7f, "tenth harmonic, with the droop at 10 kHz")
        check(Gain.peak(sawOut) <= 1.15f) { "a band-limited saw overshoots a little, not a lot: ${Gain.peak(sawOut)}" }

        section("the saw's aliases are at least 40 dB under its fundamental (a naive saw puts them at -28)")
        val (aliasBin, aliasDb) = worstAlias(sawDb, BIN)
        val aliasBelow = sawDb[BIN] - aliasDb
        check(aliasBelow >= 40f) { "worst alias at bin $aliasBin (${"%.0f".format(aliasBin * RATE.toDouble() / N)} Hz) is only ${"%.1f".format(aliasBelow)} dB under the fundamental" }
        println("      saw: worst alias ${"%.1f".format(aliasBelow)} dB under the fundamental, at ${"%.0f".format(aliasBin * RATE.toDouble() / N)} Hz")
        val fine = SawOscillator().also { it.setFrequency(4 * F0, RATE) }
        val fineDb = spectrumDb(render(fine, RATE + N))
        val (_, fineAlias) = worstAlias(fineDb, 4 * BIN)
        check(fineDb[4 * BIN] - fineAlias >= 30f) { "at 4 kHz the worst alias is ${"%.1f".format(fineDb[4 * BIN] - fineAlias)} dB under" }

        section("square: odd harmonics only, the third a third; pulse width brings the even ones in")
        val sq = SquareOscillator().also { it.setFrequency(F0, RATE) }
        val sqDb = spectrumDb(render(sq, RATE + N))
        check(peakBin(sqDb) == BIN) { "square peak" }
        near(sqDb[3 * BIN] - sqDb[BIN], -9.54f, 0.5f, "third harmonic")
        check(sqDb[BIN] - sqDb[2 * BIN] >= 50f) { "no second harmonic at a half: ${sqDb[BIN] - sqDb[2 * BIN]} dB under" }
        val (_, sqAlias) = worstAlias(sqDb, BIN)
        check(sqDb[BIN] - sqAlias >= 40f) { "square aliases ${sqDb[BIN] - sqAlias} dB under" }
        val pulse = SquareOscillator(0.25).also { it.setFrequency(F0, RATE) }
        val pulseDb = spectrumDb(render(pulse, RATE + N))
        check(pulseDb[BIN] - pulseDb[2 * BIN] < 6f) { "a quarter pulse has a second harmonic: ${pulseDb[BIN] - pulseDb[2 * BIN]} dB under" }
        check(SquareOscillator(0.0).pulseWidth == 0.05 && SquareOscillator(2.0).pulseWidth == 0.95) { "pulse width held off the ends" }

        section("triangle: odd harmonics falling as the square of their number, corners rounded")
        val tri = TriangleOscillator().also { it.setFrequency(F0, RATE) }
        val triOut = render(tri, RATE + N)
        val triDb = spectrumDb(triOut)
        check(peakBin(triDb) == BIN) { "triangle peak" }
        near(triDb[3 * BIN] - triDb[BIN], -19.08f, 0.5f, "third harmonic a ninth")
        near(triDb[5 * BIN] - triDb[BIN], -27.96f - 0.62f, 0.5f, "fifth harmonic a twenty-fifth, less the same droop as the saw")
        check(triDb[BIN] - triDb[2 * BIN] >= 50f) { "no even harmonics" }
        val (_, triAlias) = worstAlias(triDb, BIN)
        check(triDb[BIN] - triAlias >= 60f) { "triangle aliases ${triDb[BIN] - triAlias} dB under" }
        check(Gain.peak(triOut) in 0.9f..1.0f) { "peak ${Gain.peak(triOut)}: the corners are rounded down, not clipped" }

        section("sine and noise")
        val sine = SineOscillator().also { it.setFrequency(F0, RATE) }
        val sineDb = spectrumDb(render(sine, RATE + N))
        check(peakBin(sineDb) == BIN) { "sine peak" }
        check(sineDb[BIN] - worstAlias(sineDb, BIN).second >= 90f && sineDb[BIN] - sineDb[2 * BIN] >= 90f) { "nothing but the fundamental" }
        val noise = render(NoiseOscillator(3), RATE)
        near(noise.average(), 0.0, 0.01, "noise has no mean")
        near(rms(noise), 1.0 / Math.sqrt(3.0), 0.01, "uniform -1..1 has an rms of 1/sqrt(3)")
        check(noise.contentEquals(render(NoiseOscillator(3), RATE)) && !noise.contentEquals(render(NoiseOscillator(4), RATE))) { "seeded" }
        check(Oscillator.of(Waveform.SQUARE) is SquareOscillator && Oscillator.of(Waveform.NOISE) is NoiseOscillator) { "factory" }
        val wrap = SineOscillator().also { it.setFrequency(RATE / 4.0, RATE) }
        repeat(7) { wrap.next() }
        near(wrap.phase, 0.75, 1e-9, "phase wraps into 0..1 (7 quarters)")
        wrap.phase = 2.3; near(wrap.phase, 0.3, 1e-9, "set outside, read inside")
        wrap.setFrequency(RATE * 2.0, RATE); near(wrap.increment, 0.5, 1e-12, "a frequency past Nyquist is held at it")

        section("ADSR: attack to one, decay to the sustain, release to under -60 dB, then idle")
        val env = Adsr(10.0, 100.0, 0.5, 200.0, RATE)
        check(!env.isActive && env.next() == 0f) { "idle is silent" }
        env.gate(true)
        var level = 0f
        repeat(240) { level = env.next() }
        near(level, 0.5f, 0.01f, "half way up the attack at 5 ms")
        repeat(240) { level = env.next() }
        near(level, 1f, 0.01f, "the top at 10 ms")
        check(env.stage == Adsr.Stage.DECAY) { "then decaying" }
        repeat(2400) { level = env.next() }
        check(level > 0.51f && level < 0.75f) { "half way through the decay is above the sustain: $level" }
        repeat(2400) { level = env.next() }
        near(level, 0.5f, 1e-3f, "the sustain at 110 ms")
        check(env.stage == Adsr.Stage.SUSTAIN) { "sustaining" }
        repeat(4800) { level = env.next() }
        near(level, 0.5f, 1e-6f, "and holding")
        env.gate(false)
        repeat(9600) { level = env.next() }
        check(Gain.linearToDb(level) < -60f && env.isActive) { "200 ms into the release: ${Gain.linearToDb(level)} dB, still ringing" }
        repeat(4800) { level = env.next() }
        check(!env.isActive && level == 0f) { "idle 100 ms later" }
        val retrigger = Adsr(100.0, 0.0, 1.0, 1000.0, RATE)
        retrigger.gate(true); repeat(4800) { retrigger.next() }
        retrigger.gate(false); repeat(1000) { retrigger.next() }
        val mid = retrigger.level
        retrigger.gate(true)
        check(retrigger.next() > mid) { "a retrigger climbs from where it is, not from zero" }
        val instant = Adsr(0.0, 0.0, 0.8, 0.0, RATE)
        instant.gate(true)
        near(instant.next(), 0.8f, 1e-6f, "zero attack and decay land on the sustain at once")
        instant.gate(false)
        check(instant.next() == 0f && !instant.isActive) { "zero release is at once" }
        check(runCatching { AdsrSettings(-1.0) }.isFailure && runCatching { AdsrSettings(sustain = 1.5) }.isFailure) { "settings limits" }

        section("SVF low pass at 1 kHz: 100 Hz within 0.5 dB, 10 kHz down more than 30 dB")
        fun response(kind: FilterKind, cutoff: Double, q: Double, hz: Double): Double {
            val f = Svf(RATE, kind).also { it.set(cutoff, q) }
            val out = FloatArray(RATE) { f.process(sin(2 * PI * hz * it / RATE).toFloat()) }
            return Gain.linearToDb(rms(out, RATE / 2) / (1 / Math.sqrt(2.0)))
        }
        val q = BiquadCoefficients.SQRT_HALF
        near(response(FilterKind.LOW_PASS, 1000.0, q, 100.0), 0.0, 0.5, "low pass at 100 Hz")
        val lp10k = response(FilterKind.LOW_PASS, 1000.0, q, 10_000.0)
        check(lp10k < -30.0) { "low pass at 10 kHz: $lp10k dB" }
        near(response(FilterKind.LOW_PASS, 1000.0, q, 1000.0), -3.01, 0.3, "Butterworth is -3 dB at the cutoff")
        near(response(FilterKind.HIGH_PASS, 1000.0, q, 10_000.0), 0.0, 0.5, "high pass at 10 kHz")
        check(response(FilterKind.HIGH_PASS, 1000.0, q, 100.0) < -30.0) { "high pass at 100 Hz" }
        check(response(FilterKind.BAND_PASS, 1000.0, 4.0, 1000.0) - response(FilterKind.BAND_PASS, 1000.0, 4.0, 100.0) > 25.0) { "band pass favours its centre" }
        check(response(FilterKind.NOTCH, 1000.0, q, 1000.0) < -30.0 && response(FilterKind.NOTCH, 1000.0, q, 100.0) > -0.5) { "notch" }
        val resonant = response(FilterKind.LOW_PASS, 1000.0, Svf.qOf(1.0), 1000.0)
        check(resonant > 20.0) { "full resonance peaks: $resonant dB" }
        near(Svf.qOf(0.0), 0.5, 1e-9, "resonance 0 is Q 0.5"); check(Svf.qOf(1.0) in 19.0..21.0) { "resonance 1 is about Q 20: ${Svf.qOf(1.0)}" }
        for (cutoff in listOf(12_000.0, 20_000.0, 23_500.0, 40_000.0)) for (kind in listOf(FilterKind.LOW_PASS, FilterKind.HIGH_PASS, FilterKind.BAND_PASS)) {
            val f = Svf(RATE, kind).also { it.set(cutoff, Svf.qOf(1.0)) }
            val n = NoiseOscillator(9)
            var peak = 0f
            repeat(RATE) { val y = f.process(n.next()); check(y.isFinite()) { "$kind at $cutoff went non-finite" }; peak = maxOf(peak, abs(y)) }
            check(peak < 200f) { "$kind at $cutoff Hz with full resonance stays bounded: $peak" }
        }
        check(runCatching { Svf(RATE, FilterKind.PEAKING).process(1f) }.isFailure) { "a biquad's kind is refused" }

        section("the polysynth plays a C major chord: three fundamentals, then silence after the release")
        val patch = SubtractivePatch(osc1 = Waveform.SAW, mix = 0.0, cutoffHz = 20_000.0, resonance = 0.0, filterEnvOctaves = 0.0, keyTrack = 0.0,
            amp = AdsrSettings(1.0, 0.0, 1.0, 50.0), filterEnv = AdsrSettings(0.0, 0.0, 0.0, 0.0), gain = 0.3)
        val synth = PolySynth(patch, RATE, voices = 8)
        synth.noteOn(60, 100); synth.noteOn(64, 100); synth.noteOn(67, 100)
        val stereo = FloatArray(2 * RATE)
        synth.render(stereo, RATE)
        val left = FloatArray(RATE) { stereo[2 * it] }
        val right = FloatArray(RATE) { stereo[2 * it + 1] }
        check(left.contentEquals(right)) { "centre pan is the same on both sides" }
        check(Gain.peak(left) > 0.1f && Gain.peak(left) < 1f) { "audible and unclipped: ${Gain.peak(left)}" }
        val chordDb = spectrumDb(left)
        val loudest = chordDb[peakBin(chordDb)]
        for ((pitch, hz) in listOf(60 to 261.63, 64 to 329.63, 67 to 392.0)) {
            val p = localPeak(chordDb, hz)
            check(loudest - p < 4f) { "fundamental of $pitch (${hz} Hz) is ${loudest - p} dB under the loudest bin" }
        }
        check(loudest - localPeak(chordDb, 1000.0, 2) > 25f) { "nothing at 1 kHz, which none of them has a harmonic at" }
        check(synth.activeVoices == 3 && synth.heldPitches.sorted() == listOf(60, 64, 67)) { "three voices held" }
        synth.allNotesOff()
        synth.render(stereo, RATE)
        check(synth.activeVoices == 0) { "all released after a second: ${synth.activeVoices}" }
        val tail = FloatArray(1000)
        synth.render(tail, 500)
        check(tail.all { it == 0f }) { "and silent" }

        section("voice stealing takes the oldest; a held pitch played again keeps its voice")
        val small = PolySynth(patch, RATE, voices = 4)
        for (p in listOf(60, 62, 64, 65)) small.noteOn(p, 100)
        small.noteOn(67, 100)
        check(small.heldPitches.sorted() == listOf(62, 64, 65, 67) && small.activeVoices == 4) { "60, the oldest, was stolen: ${small.heldPitches}" }
        small.noteOn(69, 100)
        check(small.heldPitches.sorted() == listOf(64, 65, 67, 69)) { "then 62" }
        small.noteOn(64, 100)
        check(small.heldPitches.sorted() == listOf(64, 65, 67, 69)) { "64 again retriggers its own voice" }
        small.noteOff(64)
        check(small.heldPitches.sorted() == listOf(65, 67, 69) && small.activeVoices == 4) { "released, still ringing" }
        small.noteOn(72, 100)
        check(small.heldPitches.sorted() == listOf(65, 67, 69, 72)) { "a free voice is taken before anything is stolen" }
        small.allNotesOff(); small.render(stereo, RATE); check(small.activeVoices == 0) { "quiet again" }

        section("FM: with every index at zero the carrier is a sine; an index brings in sidebands")
        val flat = AdsrSettings(0.0, 0.0, 1.0, 10.0)
        // The carrier is held to a bin's centre: a tone between bins leaks through the Hann
        // window to about -40 dB four bins out, which read as distortion in a sine that had none.
        val sinePatch = FmPatch(0, listOf(FmOperator(1.0, 1.0, flat, fixedHz = F0), FmOperator(1.0, 0.0, flat), FmOperator(1.0, 0.0, flat), FmOperator(1.0, 0.0, flat)), gain = 0.8)
        val fm = FmSynth(sinePatch, RATE)
        fm.noteOn(69, 127)
        fm.render(stereo, RATE)
        val fmLeft = FloatArray(RATE) { stereo[2 * it] }
        val fmDb = spectrumDb(fmLeft)
        val fmPeak = peakBin(fmDb)
        check(fmPeak == BIN) { "the peak is the fixed carrier's bin: $fmPeak" }
        var worst = -999f
        for (k in 4 until fmDb.size) if (abs(k - fmPeak) > 3) worst = maxOf(worst, fmDb[k])
        check(fmDb[fmPeak] - worst > 60f) { "everything else is ${fmDb[fmPeak] - worst} dB under: a sine" }
        near(Gain.peak(fmLeft), 0.8f * 0.7071f, 0.01f, "at the gain and the centre pan")
        // The modulator sits on the carrier's frequency (ratio 1), so the sidebands are at
        // 2 F0 and 3 F0 - on bins too. At an index of 2 the Bessel weights are J0 0.22,
        // J1 0.58, J2 0.35: the first upper sideband is the loudest, the second 4.4 dB under.
        fm.patch = sinePatch.copy(operators = sinePatch.operators.mapIndexed { i, op -> if (i == 1) op.copy(level = 2.0, fixedHz = F0) else op })
        fm.noteOn(69, 127)
        fm.render(stereo, RATE)
        val modDb = spectrumDb(FloatArray(RATE) { stereo[2 * it] })
        val top = modDb[peakBin(modDb)]
        check(top - localPeak(modDb, 2 * F0) < 6f) { "index 2 at ratio 1 puts a sideband at 2 F0: ${top - localPeak(modDb, 2 * F0)} dB under the loudest" }
        check(top - localPeak(modDb, 3 * F0) < 12f) { "and at 3 F0: ${top - localPeak(modDb, 3 * F0)}" }
        check(top - localPeak(modDb, F0) > 6f) { "the carrier itself is quieter than its first sideband at index 2: ${top - localPeak(modDb, F0)} under" }
        check(FmAlgorithms.inputs(0, 0) == listOf(1) && FmAlgorithms.inputs(0, 3).isEmpty() && FmAlgorithms.carriers(0) == listOf(0)) { "chain" }
        check(FmAlgorithms.inputs(1, 2) == listOf(3) && FmAlgorithms.carriers(1) == listOf(0, 2)) { "pairs" }
        check(FmAlgorithms.inputs(2, 0) == listOf(1, 2, 3) && FmAlgorithms.carriers(3) == listOf(0, 1, 2, 3)) { "fan in, additive" }
        for (alg in 0..3) check(FmAlgorithms.inputs(alg, 0).all { it > 0 } && (0..3).all { op -> FmAlgorithms.inputs(alg, op).all { it > op } }) { "every modulator is numbered above what it feeds" }
        check(runCatching { FmPatch(4) }.isFailure && runCatching { FmPatch(0, emptyList()) }.isFailure) { "patch limits" }
        fm.allNotesOff(); fm.render(stereo, RATE); check(fm.activeVoices == 0) { "FM voices release" }

        section("sampler: a ratio of two reads every other frame, a loop goes round, the end stops it")
        val ramp = FloatArray(1000) { it.toFloat() }
        val sampler = SamplerVoice(ramp, RATE, RATE, flat, rootPitch = 60)
        sampler.noteOn(72, 127)
        near(sampler.ratioPlaying, 2.0, 1e-12, "an octave up is twice the speed")
        val mono = FloatArray(100)
        sampler.render(mono, 100)
        for (i in 0 until 100) check(mono[i] == 2f * i) { "frame $i reads ${mono[i]}, expected ${2 * i}" }
        val half = SamplerVoice(ramp, RATE, RATE, flat, rootPitch = 60); half.noteOn(48, 127)
        mono.fill(0f); half.render(mono, 100)
        near(half.ratioPlaying, 0.5, 1e-12, "an octave down"); near(mono[3], 1.5f, 1e-6f, "halfway between frames 1 and 2 by interpolation")
        val resampled = SamplerVoice(ramp, 44100, RATE, flat); resampled.start(1.0)
        near(resampled.ratioPlaying, 44100.0 / 48000, 1e-12, "a 44.1k sample at 48k steps slower")
        val looped = SamplerVoice(FloatArray(100) { it.toFloat() }, RATE, RATE, flat, loop = true)
        looped.start(1.0)
        val longOut = FloatArray(250); looped.render(longOut, 250)
        check(longOut[150] == 50f && longOut[249] == 49f && looped.isActive) { "wrapped: ${longOut[150]} at 150" }
        val short = SamplerVoice(FloatArray(10) { 1f }, RATE, RATE, flat); short.start(1.0)
        val tail2 = FloatArray(20); short.render(tail2, 20)
        check(tail2[9] == 1f && tail2[10] == 0f && !short.isActive) { "ten frames then nothing: ${tail2.toList()}" }
        val faded = SamplerVoice(FloatArray(1000) { 1f }, RATE, RATE, flat); faded.start(1.0)
        faded.choke(1.0)
        val chokeOut = FloatArray(100); faded.render(chokeOut, 100)
        check(chokeOut[0] > 0.9f && chokeOut[47] < 0.05f && chokeOut[60] == 0f && !faded.isActive) { "a 1 ms choke is 48 frames down to nothing: ${chokeOut[0]}, ${chokeOut[47]}, ${chokeOut[60]}" }
        check(runCatching { SamplerVoice(ramp, RATE, RATE, flat).also { it.noteOn(60, 100) }.render(FloatArray(5), 5) }.isSuccess) { "renders into any buffer" }
    }
}
