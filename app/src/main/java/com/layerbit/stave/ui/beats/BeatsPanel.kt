package com.layerbit.stave.ui.beats

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.layerbit.stave.R
import com.layerbit.stave.beats.Instruments
import com.layerbit.stave.engine.sequencer.Arpeggiator
import com.layerbit.stave.engine.sequencer.DrumInstrument
import com.layerbit.stave.engine.sequencer.Pattern
import com.layerbit.stave.engine.sequencer.Ticks
import com.layerbit.stave.ui.components.ChipRow
import com.layerbit.stave.ui.components.Formats
import com.layerbit.stave.ui.components.LabelledValue
import com.layerbit.stave.ui.components.Panel
import com.layerbit.stave.ui.components.Pill
import com.layerbit.stave.ui.components.PrimaryButton
import com.layerbit.stave.ui.components.SecondaryButton
import com.layerbit.stave.ui.components.SectionHeading
import com.layerbit.stave.ui.components.ToggleRow
import com.layerbit.stave.ui.components.ValueSlider
import com.layerbit.stave.ui.editor.EditorViewModel
import com.layerbit.stave.ui.theme.StaveColors
import com.layerbit.stave.ui.theme.ToolAccents
import java.util.Locale

/**
 * The Beats tab: patterns on instruments, a step grid that is also the piano roll, pads and
 * keys to play and record with, the tools (quantise, humanise, legato, transpose, arpeggio),
 * the song as a row of placements, and the doors: render to a track, MIDI in and out, a
 * SoundFont added from a file. Every edit is a session edit, so Undo on the top bar takes a
 * step back here as everywhere.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BeatsPanel(vm: EditorViewModel, ui: EditorViewModel.Ui, modifier: Modifier = Modifier) {
    val beats by vm.beats.collectAsState()
    val music by vm.music.collectAsState()
    val context = LocalContext.current
    val patterns = ui.session.patterns
    val pattern = beats.patternId?.let { id -> patterns.firstOrNull { it.id == id } } ?: patterns.firstOrNull()
    val instrument = pattern?.let { vm.instrumentOf(it) } ?: Instruments.DRUMS
    val drums = Instruments.hasPads(instrument)
    val padLabels = vm.padLabels(instrument)
    val bpm = ui.session.tempo.first().bpm

    var exportSong by remember { mutableStateOf(false) }
    val importMidi = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) context.contentResolver.openInputStream(uri)?.use { s -> vm.importMidi(s.readBytes()) }
    }
    val exportMidi = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/midi")) { uri ->
        if (uri != null) vm.exportMidiBytes(exportSong)?.let { bytes -> context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } }
    }
    val importSoundFont = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importSoundFont(vm.displayName(uri)) { out -> (context.contentResolver.openInputStream(uri) ?: error("Could not read the file")).use { i -> out.outputStream().use { o -> i.copyTo(o) } } }
    }

    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (vm.hasPendingNotes) {
            Panel(modifier = Modifier.fillMaxWidth(), inset = 10.dp, tint = ToolAccents.Beats) {
                Text(stringResource(R.string.beats_pending_notes), style = MaterialTheme.typography.bodyMedium, color = StaveColors.Text)
                PrimaryButton(stringResource(R.string.beats_pending_take), onClick = vm::takePendingNotes, tall = false, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            }
        }

        // -- which pattern, on what
        Panel(modifier = Modifier.fillMaxWidth(), inset = 10.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionHeading(stringResource(R.string.beats_pattern), modifier = Modifier.weight(1f))
                SecondaryButton(stringResource(R.string.beats_new_pattern), onClick = { vm.newPattern() }, tall = false)
                if (pattern != null) { Spacer(Modifier.width(6.dp)); SecondaryButton(stringResource(R.string.beats_delete_pattern), onClick = vm::deletePattern, tall = false, danger = true) }
            }
            if (patterns.isEmpty()) Text(stringResource(R.string.beats_empty_hint), style = MaterialTheme.typography.bodySmall, color = StaveColors.Dim, modifier = Modifier.padding(top = 6.dp))
            if (patterns.isNotEmpty()) ChipRow(patterns.map { it.id to it.name }, pattern?.id, { vm.selectPattern(it ?: return@ChipRow) }, modifier = Modifier.padding(top = 8.dp))
            if (pattern != null) {
                var name by remember(pattern.id) { mutableStateOf(pattern.name) }
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.beats_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                if (name != pattern.name && name.isNotBlank()) SecondaryButton(stringResource(R.string.studio_rename), onClick = { vm.renamePattern(name) }, tall = false, modifier = Modifier.padding(top = 4.dp))
                SectionHeading(stringResource(R.string.beats_instrument), modifier = Modifier.padding(top = 10.dp))
                val soundFontChips = beats.soundFonts.map { f -> Instruments.soundFontId(f, if (Instruments.soundFontFile(instrument) == f) Instruments.soundFontPreset(instrument) else 0) to f.substringBeforeLast('.') }
                val kitChips = ui.session.kits.map { k -> "kit:${k.id}" to k.name }
                ChipRow(Instruments.builtIn.map { it.id to it.label } + kitChips + soundFontChips, instrument, { vm.setInstrument(it) }, modifier = Modifier.padding(top = 6.dp))
                if (Instruments.isSoundFont(instrument)) {
                    val file = Instruments.soundFontFile(instrument) ?: ""
                    val presets = vm.soundFontPresets(file)
                    val index = Instruments.soundFontPreset(instrument)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SecondaryButton("‹", onClick = { vm.setInstrument(Instruments.soundFontId(file, (index - 1).mod(presets.size.coerceAtLeast(1)))) }, tall = false, enabled = presets.size > 1)
                        Text(presets.getOrNull(index) ?: "—", style = MaterialTheme.typography.bodyMedium, color = StaveColors.Text, modifier = Modifier.weight(1f))
                        SecondaryButton("›", onClick = { vm.setInstrument(Instruments.soundFontId(file, (index + 1).mod(presets.size.coerceAtLeast(1)))) }, tall = false, enabled = presets.size > 1)
                        SecondaryButton(stringResource(R.string.beats_soundfont_remove), onClick = { vm.removeSoundFont(file); vm.setInstrument(Instruments.DRUMS) }, tall = false, danger = true)
                    }
                }
                SecondaryButton(stringResource(R.string.beats_soundfont_add), onClick = { importSoundFont.launch(arrayOf("*/*")) }, tall = false, modifier = Modifier.padding(top = 8.dp))
            }
        }

        if (pattern != null) {
            // -- the grid and the loop
            Panel(modifier = Modifier.fillMaxWidth(), inset = 10.dp) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton(stringResource(if (beats.looping) R.string.beats_stop_loop else R.string.beats_play_loop), onClick = vm::toggleLoop, tall = false, modifier = Modifier.weight(1f))
                    SecondaryButton("−", onClick = { vm.setBeatsTempo(bpm - 1) }, tall = false)
                    LabelledValue(stringResource(R.string.studio_tempo), String.format(Locale.getDefault(), "%.0f", bpm), numeric = true)
                    SecondaryButton("+", onClick = { vm.setBeatsTempo(bpm + 1) }, tall = false)
                }
                ToggleRow(stringResource(R.string.beats_record_pads), beats.recording, { vm.setRecording(it) }, detail = stringResource(R.string.beats_record_hint))
                // Two octaves of pitches, or only the locked scale's: a scale lock turns a sixteen-row
                // chromatic roll into the eight or so notes that can be wrong-free.
                val rows = if (drums) padLabels.mapIndexed { i, label -> GridRow(DrumInstrument.BASE + i, label) }
                           else ((beats.octave + 1) * 12 + 24 downTo (beats.octave + 1) * 12).filter { p -> vm.inScale(p) }.map { p -> GridRow(p, noteName(p), isBlackKey(p)) }
                StepGrid(
                    pattern = pattern, rows = rows, division = beats.division, playTick = if (beats.looping) beats.tick else -1,
                    description = stringResource(R.string.beats_grid_description, pattern.name, pattern.notes.size, pattern.stepCount(beats.division)),
                    onToggle = vm::toggleStep, onVelocity = vm::setStepVelocity, onVelocityDone = vm::endGesture,
                    modifier = Modifier.padding(top = 8.dp)
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Column { SectionHeading(stringResource(R.string.beats_grid)); ChipRow(listOf(8 to "1/8", 16 to "1/16", 32 to "1/32"), beats.division, vm::setDivision, modifier = Modifier.padding(top = 4.dp)) }
                    Column { SectionHeading(stringResource(R.string.beats_bars)); ChipRow(listOf(1, 2, 4, 8).map { it to "$it" }, (pattern.lengthTicks / Ticks.PER_BAR).toInt(), vm::setBars, modifier = Modifier.padding(top = 4.dp)) }
                    Column { SectionHeading(stringResource(R.string.beats_note_length)); ChipRow(listOf(32 to "1/32", 16 to "1/16", 8 to "1/8", 4 to "1/4", 2 to "1/2", 1 to "1"), beats.noteDivision, vm::setNoteDivision, modifier = Modifier.padding(top = 4.dp)) }
                }
                ValueSlider(stringResource(R.string.beats_swing), pattern.swing.toFloat(), 0f..1f, { vm.setSwing(it.toDouble()) }, format = Formats::percent, onCommit = vm::endGesture)
                // Grooves: the swings a drum machine names, one tap each (MPC's 54, 58 and 62 percent map to these fractions of half a step).
                ChipRow(listOf(0.0 to stringResource(R.string.beats_groove_straight), 0.16 to "54%", 0.33 to "58%", 0.5 to "62%", 0.66 to stringResource(R.string.beats_groove_triplet)), listOf(0.0, 0.16, 0.33, 0.5, 0.66).minByOrNull { kotlin.math.abs(it - pattern.swing) } ?: 0.0, { vm.setGroove(it) }, modifier = Modifier.padding(top = 4.dp))
                if (!drums) {
                    SectionHeading(stringResource(R.string.beats_stamp), modifier = Modifier.padding(top = 8.dp))
                    ChipRow(listOf("" to stringResource(R.string.beats_stamp_single), "power" to "5th", "major" to "Major", "minor" to "Minor", "sus4" to "sus4", "dom7" to "7", "maj7" to "maj7", "min7" to "m7", "octave" to stringResource(R.string.beats_stamp_octave)), beats.stamp, { vm.setStamp(it) }, modifier = Modifier.padding(top = 4.dp))
                    if (beats.stamp.isNotEmpty()) ChipRow(listOf(0 to stringResource(R.string.beats_strum_none), 30 to stringResource(R.string.beats_strum_light), 60 to stringResource(R.string.beats_strum_medium), 120 to stringResource(R.string.beats_strum_wide)), beats.strumTicks, { vm.setStrum(it) }, modifier = Modifier.padding(top = 4.dp))
                }
                ValueSlider(stringResource(R.string.beats_velocity), beats.velocity.toFloat(), 1f..127f, { vm.setVelocity(it.toInt()) }, format = { String.format(Locale.getDefault(), "%d", it.toInt()) })
                if (!drums) {
                    SectionHeading(stringResource(R.string.beats_scale), modifier = Modifier.padding(top = 6.dp))
                    ChipRow(listOf("chromatic" to stringResource(R.string.beats_scale_chromatic), "major" to stringResource(R.string.beats_scale_major), "minor" to stringResource(R.string.beats_scale_minor), "pentatonic" to stringResource(R.string.beats_scale_pentatonic)), beats.scaleKind, { vm.setScale(it, beats.scaleRoot) }, modifier = Modifier.padding(top = 4.dp))
                    if (beats.scaleKind != "chromatic") ChipRow((0 until 12).map { it to noteName(it + 12).dropLast(1) }, beats.scaleRoot, { vm.setScale(beats.scaleKind, it) }, modifier = Modifier.padding(top = 4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                        SectionHeading(stringResource(R.string.beats_octave), modifier = Modifier.weight(1f))
                        SecondaryButton("−", onClick = { vm.setOctave(beats.octave - 1) }, tall = false, enabled = beats.octave > 1)
                        Pill("C${beats.octave}", ToolAccents.Beats, numeric = true)
                        SecondaryButton("+", onClick = { vm.setOctave(beats.octave + 1) }, tall = false, enabled = beats.octave < 7)
                    }
                }
            }

            // -- play it
            Panel(modifier = Modifier.fillMaxWidth(), inset = 10.dp) {
                SectionHeading(stringResource(if (drums) R.string.beats_pads else R.string.beats_keys))
                Spacer(Modifier.height(8.dp))
                if (drums) DrumPads(padLabels, DrumInstrument.BASE, onDown = { vm.padDown(it) }, onUp = vm::padUp)
                else Keyboard(beats.octave, onDown = { vm.padDown(it) }, onUp = vm::padUp)
                // Your own sounds on the pads: the selection sliced at its hits, or a file onto one pad.
                var padToLoad by remember { mutableStateOf(0) }
                val pickSample = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.loadSampleOnPad(padToLoad, uri) }
                SectionHeading(stringResource(R.string.beats_your_sounds), modifier = Modifier.padding(top = 10.dp))
                Text(stringResource(R.string.beats_your_sounds_hint), style = MaterialTheme.typography.bodySmall, color = StaveColors.Dim, modifier = Modifier.padding(vertical = 4.dp))
                SecondaryButton(stringResource(R.string.beats_slice_selection), onClick = vm::sliceSelectionToPads, tall = false, modifier = Modifier.fillMaxWidth(), enabled = ui.selection != null && ui.busy == null)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    ChipRow((0 until 16).map { it to "${it + 1}" }, padToLoad, { padToLoad = it }, modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    SecondaryButton(stringResource(R.string.beats_load_sample, padToLoad + 1), onClick = { pickSample.launch(arrayOf("audio/*")) }, tall = false, modifier = Modifier.weight(1f), enabled = ui.busy == null)
                    SecondaryButton(stringResource(R.string.beats_clear_pad, padToLoad + 1), onClick = { vm.clearPad(padToLoad) }, tall = false, enabled = vm.currentKit?.pads?.getOrNull(padToLoad)?.isEmpty == false, danger = true)
                }
                ToggleRow(stringResource(R.string.beats_midi_in), beats.midiIn, { vm.setMidiIn(it) }, detail = when {
                    !beats.midiIn -> stringResource(R.string.beats_midi_in_hint)
                    beats.midiDevices.isEmpty() -> stringResource(R.string.beats_midi_in_none)
                    else -> stringResource(R.string.beats_midi_in_connected, beats.midiDevices.joinToString(", "))
                }, modifier = Modifier.padding(top = 6.dp))
            }

            // -- tools
            Panel(modifier = Modifier.fillMaxWidth(), inset = 10.dp) {
                SectionHeading(stringResource(R.string.beats_tools))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    SecondaryButton(stringResource(R.string.beats_quantise), onClick = { vm.quantiseNotes(1.0) }, tall = false, enabled = pattern.notes.isNotEmpty())
                    SecondaryButton(stringResource(R.string.beats_humanise), onClick = vm::humaniseNotes, tall = false, enabled = pattern.notes.isNotEmpty())
                    SecondaryButton(stringResource(R.string.beats_legato), onClick = vm::legatoNotes, tall = false, enabled = pattern.notes.isNotEmpty() && !drums)
                    SecondaryButton(stringResource(R.string.beats_double), onClick = vm::duplicatePattern, tall = false, enabled = pattern.lengthTicks < Ticks.PER_BAR * 16)
                    SecondaryButton(stringResource(R.string.beats_clear), onClick = vm::clearPattern, tall = false, enabled = pattern.notes.isNotEmpty(), danger = true)
                    if (!drums) {
                        SecondaryButton(stringResource(R.string.beats_transpose_down), onClick = { vm.transposeNotes(-12) }, tall = false, enabled = pattern.notes.isNotEmpty())
                        SecondaryButton("−1", onClick = { vm.transposeNotes(-1) }, tall = false, enabled = pattern.notes.isNotEmpty())
                        SecondaryButton("+1", onClick = { vm.transposeNotes(1) }, tall = false, enabled = pattern.notes.isNotEmpty())
                        SecondaryButton(stringResource(R.string.beats_transpose_up), onClick = { vm.transposeNotes(12) }, tall = false, enabled = pattern.notes.isNotEmpty())
                    }
                }
                if (!drums) {
                    SectionHeading(stringResource(R.string.beats_arpeggio), modifier = Modifier.padding(top = 10.dp))
                    Text(stringResource(R.string.beats_arpeggio_hint), style = MaterialTheme.typography.bodySmall, color = StaveColors.Dim, modifier = Modifier.padding(vertical = 4.dp))
                    var mode by remember { mutableStateOf(Arpeggiator.Mode.UP) }
                    var octaves by remember { mutableStateOf(1) }
                    ChipRow(listOf(Arpeggiator.Mode.UP to stringResource(R.string.beats_arp_up), Arpeggiator.Mode.DOWN to stringResource(R.string.beats_arp_down), Arpeggiator.Mode.UP_DOWN to stringResource(R.string.beats_arp_updown), Arpeggiator.Mode.RANDOM to stringResource(R.string.beats_arp_random)), mode, { mode = it })
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                        ChipRow(listOf(1, 2, 3).map { it to stringResource(R.string.beats_octaves, it) }, octaves, { octaves = it }, modifier = Modifier.weight(1f))
                        PrimaryButton(stringResource(R.string.beats_arpeggio), onClick = { vm.arpeggiate(pattern.notes.map { it.pitch }.distinct(), mode, octaves) }, tall = false, enabled = pattern.notes.isNotEmpty())
                    }
                }
                val chords = music.analysis?.chords?.count { it.pitches.isNotEmpty() } ?: 0
                SecondaryButton(stringResource(R.string.beats_chords_to_pattern, chords), onClick = vm::chordsToPattern, tall = false, enabled = chords > 0, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
            }

            // -- the song, and the doors
            Panel(modifier = Modifier.fillMaxWidth(), inset = 10.dp) {
                SectionHeading(stringResource(R.string.beats_song))
                val placements = ui.session.arrangement.placements
                if (placements.isEmpty()) Text(stringResource(R.string.beats_song_empty), style = MaterialTheme.typography.bodySmall, color = StaveColors.Dim, modifier = Modifier.padding(vertical = 4.dp))
                placements.forEachIndexed { i, pl ->
                    val p = ui.session.pattern(pl.patternId)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                        Text("${i + 1}. ${p?.name ?: "?"}", style = MaterialTheme.typography.bodyMedium, color = StaveColors.Text, modifier = Modifier.weight(1f))
                        SecondaryButton("−", onClick = { vm.setSongRepeats(i, pl.repeats - 1) }, tall = false, enabled = pl.repeats > 1)
                        Pill(stringResource(R.string.beats_repeats, pl.repeats), ToolAccents.Beats, numeric = true)
                        SecondaryButton("+", onClick = { vm.setSongRepeats(i, pl.repeats + 1) }, tall = false)
                        SecondaryButton(stringResource(R.string.beats_remove), onClick = { vm.removeFromSong(i) }, tall = false, danger = true)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    SecondaryButton(stringResource(R.string.beats_add_to_song), onClick = { vm.addToSong() }, tall = false, modifier = Modifier.weight(1f))
                    SecondaryButton(stringResource(R.string.beats_render_song), onClick = vm::renderSongToTrack, tall = false, enabled = placements.isNotEmpty(), modifier = Modifier.weight(1f))
                }
                SectionHeading(stringResource(R.string.beats_render_track), modifier = Modifier.padding(top = 10.dp))
                var repeats by remember { mutableStateOf(1) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    ChipRow(listOf(1, 2, 4, 8).map { it to stringResource(R.string.beats_repeats, it) }, repeats, { repeats = it }, modifier = Modifier.weight(1f))
                    PrimaryButton(stringResource(R.string.beats_render_track), onClick = { vm.renderPatternToTrack(repeats) }, tall = false, enabled = pattern.notes.isNotEmpty() && ui.busy == null)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
                    SecondaryButton(stringResource(R.string.beats_midi_import), onClick = { importMidi.launch(arrayOf("audio/midi", "audio/x-midi", "audio/mid", "application/x-midi", "*/*")) }, tall = false, modifier = Modifier.weight(1f))
                    SecondaryButton(stringResource(R.string.beats_midi_export), onClick = { exportSong = false; exportMidi.launch("${pattern.name}.mid") }, tall = false, enabled = pattern.notes.isNotEmpty(), modifier = Modifier.weight(1f))
                    SecondaryButton(stringResource(R.string.beats_midi_export_song), onClick = { exportSong = true; exportMidi.launch("${ui.session.name.ifBlank { "song" }}.mid") }, tall = false, enabled = placements.isNotEmpty(), modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
