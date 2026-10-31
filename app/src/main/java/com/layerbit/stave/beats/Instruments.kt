package com.layerbit.stave.beats

import android.content.Context
import com.layerbit.stave.engine.sequencer.DrumInstrument
import com.layerbit.stave.engine.sequencer.Instrument
import com.layerbit.stave.engine.sequencer.SynthInstrument
import com.layerbit.stave.engine.synth.AdsrSettings
import com.layerbit.stave.engine.synth.DrumMachine
import com.layerbit.stave.engine.dsp.FilterKind
import com.layerbit.stave.engine.synth.FmOperator
import com.layerbit.stave.engine.synth.FmPatch
import com.layerbit.stave.engine.synth.FmSynth
import com.layerbit.stave.engine.synth.KitSynth
import com.layerbit.stave.engine.synth.PolySynth
import com.layerbit.stave.engine.synth.SubtractivePatch
import com.layerbit.stave.engine.synth.Waveform
import com.layerbit.stave.native.SoundFontNative
import java.io.File

/**
 * What a pattern plays on, by a string id the project file keeps: `drums:starter` (the
 * synthesised kit), `synth:<preset>` (the subtractive synth), `fm:<preset>` (four operators),
 * `sf:<file>#<preset>` (a SoundFont the person added). An id is a handle: the labels may be
 * reworded, the ids may not, or every pattern saved with one falls back to the kit.
 */
object Instruments {
    const val DRUMS = "drums:starter"

    data class Choice(val id: String, val label: String, val isDrums: Boolean = false)

    val synthPresets: Map<String, Pair<String, SubtractivePatch>> = linkedMapOf(
        "pluck" to ("Pluck" to SubtractivePatch(Waveform.SAW, Waveform.SQUARE, 0, 9.0, 0.35, 0.5, FilterKind.LOW_PASS, 1800.0, 0.25, 3.0, 0.6,
            AdsrSettings(2.0, 180.0, 0.0, 160.0), AdsrSettings(1.0, 120.0, 0.0, 120.0), 0.45)),
        "bass" to ("Bass" to SubtractivePatch(Waveform.SAW, Waveform.SQUARE, -12, 4.0, 0.45, 0.5, FilterKind.LOW_PASS, 420.0, 0.35, 2.5, 0.3,
            AdsrSettings(3.0, 220.0, 0.6, 120.0), AdsrSettings(1.0, 160.0, 0.1, 100.0), 0.5)),
        "lead" to ("Lead" to SubtractivePatch(Waveform.SAW, Waveform.SAW, 0, 12.0, 0.5, 0.5, FilterKind.LOW_PASS, 3200.0, 0.3, 1.5, 0.7,
            AdsrSettings(8.0, 300.0, 0.75, 220.0), AdsrSettings(5.0, 400.0, 0.3, 200.0), 0.4)),
        "pad" to ("Pad" to SubtractivePatch(Waveform.SAW, Waveform.TRIANGLE, 0, 14.0, 0.5, 0.5, FilterKind.LOW_PASS, 1200.0, 0.15, 1.2, 0.5,
            AdsrSettings(380.0, 600.0, 0.8, 900.0), AdsrSettings(600.0, 900.0, 0.4, 800.0), 0.35)),
        "keys" to ("Keys" to SubtractivePatch(Waveform.TRIANGLE, Waveform.SQUARE, 12, 3.0, 0.3, 0.3, FilterKind.LOW_PASS, 2600.0, 0.1, 2.0, 0.8,
            AdsrSettings(2.0, 500.0, 0.3, 300.0), AdsrSettings(1.0, 300.0, 0.0, 200.0), 0.45)),
        "acid" to ("Acid" to SubtractivePatch(Waveform.SAW, Waveform.SAW, 0, 0.0, 0.0, 0.5, FilterKind.LOW_PASS, 600.0, 0.75, 3.5, 0.4,
            AdsrSettings(1.0, 160.0, 0.2, 90.0), AdsrSettings(1.0, 220.0, 0.0, 120.0), 0.42)),
        "noise" to ("Noise hit" to SubtractivePatch(Waveform.NOISE, Waveform.NOISE, 0, 0.0, 0.0, 0.5, FilterKind.BAND_PASS, 1500.0, 0.6, 2.0, 1.0,
            AdsrSettings(1.0, 120.0, 0.0, 80.0), AdsrSettings(1.0, 80.0, 0.0, 60.0), 0.5))
    )

    val fmPresets: Map<String, Pair<String, FmPatch>> = linkedMapOf(
        "bell" to ("Bell" to FmPatch(0, listOf(
            FmOperator(1.0, 1.0, AdsrSettings(1.0, 900.0, 0.0, 600.0)),
            FmOperator(3.5, 1.4, AdsrSettings(1.0, 500.0, 0.0, 400.0)),
            FmOperator(1.0, 0.0, AdsrSettings()), FmOperator(1.0, 0.0, AdsrSettings())), 0.5)),
        "epiano" to ("E-piano" to FmPatch(1, listOf(
            FmOperator(1.0, 1.0, AdsrSettings(1.0, 700.0, 0.2, 300.0)),
            FmOperator(1.0, 0.9, AdsrSettings(1.0, 250.0, 0.0, 200.0)),
            FmOperator(1.0, 0.7, AdsrSettings(1.0, 900.0, 0.3, 300.0)),
            FmOperator(14.0, 0.25, AdsrSettings(1.0, 60.0, 0.0, 60.0))), 0.45)),
        "marimba" to ("Marimba" to FmPatch(0, listOf(
            FmOperator(1.0, 1.0, AdsrSettings(1.0, 350.0, 0.0, 250.0)),
            FmOperator(4.0, 0.6, AdsrSettings(1.0, 60.0, 0.0, 50.0)),
            FmOperator(1.0, 0.0, AdsrSettings()), FmOperator(1.0, 0.0, AdsrSettings())), 0.5)),
        "fmbass" to ("FM bass" to FmPatch(0, listOf(
            FmOperator(1.0, 1.0, AdsrSettings(2.0, 400.0, 0.6, 150.0)),
            FmOperator(1.0, 1.8, AdsrSettings(1.0, 180.0, 0.2, 100.0)),
            FmOperator(2.0, 0.5, AdsrSettings(1.0, 90.0, 0.0, 80.0)), FmOperator(1.0, 0.0, AdsrSettings())), 0.5)),
        "organ" to ("Organ" to FmPatch(3, listOf(
            FmOperator(1.0, 1.0, AdsrSettings(5.0, 50.0, 1.0, 80.0)),
            FmOperator(2.0, 0.6, AdsrSettings(5.0, 50.0, 1.0, 80.0)),
            FmOperator(3.0, 0.3, AdsrSettings(5.0, 50.0, 1.0, 80.0)),
            FmOperator(4.0, 0.2, AdsrSettings(5.0, 50.0, 1.0, 80.0))), 0.4))
    )

    /** The built-in choices in the order the chips show them. */
    val builtIn: List<Choice> = listOf(Choice(DRUMS, "Drums", isDrums = true)) +
        synthPresets.map { (k, v) -> Choice("synth:$k", v.first) } + fmPresets.map { (k, v) -> Choice("fm:$k", v.first) }

    fun isDrums(id: String): Boolean = id.startsWith("drums:")
    fun isSoundFont(id: String): Boolean = id.startsWith("sf:")
    fun soundFontId(fileName: String, preset: Int): String = "sf:$fileName#$preset"
    fun soundFontFile(id: String): String? = if (isSoundFont(id)) id.removePrefix("sf:").substringBeforeLast('#') else null
    fun soundFontPreset(id: String): Int = id.substringAfterLast('#', "0").toIntOrNull() ?: 0

    fun label(id: String, store: SoundFontStore? = null): String = when {
        isDrums(id) -> "Drums"
        id.startsWith("synth:") -> synthPresets[id.removePrefix("synth:")]?.first ?: "Synth"
        id.startsWith("fm:") -> fmPresets[id.removePrefix("fm:")]?.first ?: "FM"
        isSoundFont(id) -> {
            val file = soundFontFile(id) ?: ""
            val preset = store?.presetNames(file)?.getOrNull(soundFontPreset(id))
            preset ?: file.substringBeforeLast('.')
        }
        else -> id
    }

    /** The instrument for [id] at [sampleRate], or null when a SoundFont it names is gone (the caller falls back to the kit). */
    fun create(id: String, sampleRate: Int, store: SoundFontStore?): Instrument? = when {
        isDrums(id) -> DrumInstrument(DrumMachine(KitSynth.kit(sampleRate), sampleRate))
        id.startsWith("synth:") -> SynthInstrument(PolySynth(synthPresets[id.removePrefix("synth:")]?.second ?: SubtractivePatch(), sampleRate, voices = 10))
        id.startsWith("fm:") -> SynthInstrument(FmSynth(fmPresets[id.removePrefix("fm:")]?.second ?: FmPatch(), sampleRate, voices = 10))
        isSoundFont(id) -> {
            val f = soundFontFile(id)?.let { store?.file(it) }
            if (f != null && f.isFile) SoundFontInstrument(f, sampleRate, soundFontPreset(id)) else null
        }
        else -> null
    }
}

/** The SoundFonts the person has added, under files/soundfonts. Nothing is bundled: a usable General MIDI bank is 30 MB and someone else's licence. */
class SoundFontStore(context: Context) {
    private val dir = File(context.filesDir, "soundfonts").apply { mkdirs() }
    private val presetCache = HashMap<String, List<String>>()

    fun list(): List<String> = dir.listFiles()?.filter { it.isFile && (it.name.endsWith(".sf2", true) || it.name.endsWith(".sf3", true)) }?.map { it.name }?.sorted() ?: emptyList()
    fun file(name: String): File = File(dir, name)

    /** Copies a picked file in under its own name (only what a file name cannot hold is taken out), replacing one of the same name. */
    fun import(name: String, copy: (File) -> Unit): String {
        val clean = name.replace(Regex("[/\\\\:*?\"<>|\\u0000]"), "_").ifBlank { "bank.sf2" }
        val tmp = File(dir, "$clean.part")
        copy(tmp)
        val out = File(dir, clean)
        if (out.exists()) out.delete()
        tmp.renameTo(out)
        presetCache.remove(clean)
        return clean
    }

    fun remove(name: String) { file(name).delete(); presetCache.remove(name) }

    /** The bank's preset names, read once; empty when the file does not open. */
    fun presetNames(name: String): List<String> = presetCache.getOrPut(name) {
        val f = file(name)
        if (!f.isFile) return@getOrPut emptyList()
        val h = runCatching { SoundFontNative.open(f.path, 48_000) }.getOrDefault(0L)
        if (h == 0L) emptyList() else try { List(SoundFontNative.presetCount(h)) { SoundFontNative.presetName(h, it) } } finally { SoundFontNative.close(h) }
    }
}

/**
 * A SoundFont preset as an instrument, through TinySoundFont. TSF has no "is anything still
 * sounding" we export, so the tail is counted: a note held is sounding, and a released one is
 * taken to ring for [TAIL_SECONDS] - long enough for a piano's release, and what the renderer's
 * own tail limit caps anyway.
 */
class SoundFontInstrument(file: File, private val sampleRate: Int, val preset: Int) : Instrument, AutoCloseable {
    private var handle: Long = SoundFontNative.open(file.path, sampleRate)
    private val held = HashSet<Int>()
    private var tailLeft = 0L

    val isOpen: Boolean get() = handle != 0L

    override fun noteOn(pitch: Int, velocity: Int) { if (handle == 0L) return; held += pitch; SoundFontNative.noteOn(handle, preset, pitch, (velocity.coerceIn(1, 127) / 127f)) }
    override fun noteOff(pitch: Int) { if (handle == 0L) return; held -= pitch; tailLeft = (TAIL_SECONDS * sampleRate).toLong(); SoundFontNative.noteOff(handle, preset, pitch) }
    override fun allNotesOff() { if (handle == 0L) return; held.clear(); tailLeft = (TAIL_SECONDS * sampleRate).toLong(); SoundFontNative.allOff(handle) }
    override fun render(out: FloatArray, frames: Int) {
        if (handle == 0L) { out.fill(0f, 0, frames * 2); return }
        SoundFontNative.render(handle, out, frames)
        if (held.isEmpty() && tailLeft > 0) tailLeft -= frames
    }
    override val isSounding: Boolean get() = held.isNotEmpty() || tailLeft > 0
    override fun close() { if (handle != 0L) { SoundFontNative.close(handle); handle = 0L } }

    companion object { const val TAIL_SECONDS = 3.0 }
}
