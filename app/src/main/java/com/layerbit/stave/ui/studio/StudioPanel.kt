package com.layerbit.stave.ui.studio

import com.layerbit.stave.ui.components.edgeFade
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import com.layerbit.stave.ui.components.GridAction
import com.layerbit.stave.ui.components.ActionGrid
import com.layerbit.stave.ui.components.ChipShape
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.layerbit.stave.R
import com.layerbit.stave.engine.dsp.Gain
import com.layerbit.stave.engine.project.Ids
import com.layerbit.stave.engine.project.Insert
import com.layerbit.stave.engine.project.Send
import com.layerbit.stave.engine.project.Track
import com.layerbit.stave.engine.project.ValueTrack
import com.layerbit.stave.ui.components.ChipRow
import com.layerbit.stave.ui.components.Formats
import com.layerbit.stave.ui.components.LevelMeter
import com.layerbit.stave.ui.components.Panel
import com.layerbit.stave.ui.components.PrimaryButton
import com.layerbit.stave.ui.components.SecondaryButton
import com.layerbit.stave.ui.components.SectionHeading
import com.layerbit.stave.ui.components.ToggleRow
import com.layerbit.stave.ui.components.ValueSlider
import com.layerbit.stave.ui.editor.EditorViewModel
import com.layerbit.stave.ui.theme.StaveColors

/**
 * The Studio tab: a mixer strip per track (fader, pan, mute, solo, arm, meter), the selected
 * track's inserts, sends, output and automation, the click and the loop, and the bounces.
 * Faders move the graph live through the transport's overrides and land in the history as one
 * step per gesture; everything else is a session edit.
 */
@Composable
fun StudioPanel(vm: EditorViewModel, ui: EditorViewModel.Ui, modifier: Modifier = Modifier) {
    val meters by vm.transport.meters.collectAsState()
    val transport by vm.transport.state.collectAsState()
    val tracks = ui.session.tracks.filter { it.kind != Track.Kind.MASTER }
    val selected = ui.selectedTrackId?.let { ui.session.track(it) }
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Panel(modifier = Modifier.fillMaxWidth(), inset = 10.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionHeading(stringResource(R.string.studio_mixer), modifier = Modifier.weight(1f))
                SecondaryButton(stringResource(R.string.studio_add_track), onClick = { vm.addTrack() }, tall = false)
                Spacer(Modifier.width(6.dp))
                SecondaryButton(stringResource(R.string.studio_add_bus), onClick = { vm.addTrack(Track.Kind.BUS) }, tall = false)
            }
            if (tracks.isEmpty()) Text(stringResource(R.string.studio_no_tracks), style = MaterialTheme.typography.bodySmall, color = StaveColors.Dim, modifier = Modifier.padding(top = 8.dp))
            Row(rememberScrollState().let { s -> Modifier.fillMaxWidth().edgeFade(s).horizontalScroll(s) }.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tracks.forEachIndexed { i, t ->
                    val index = ui.session.tracks.indexOf(t)
                    Strip(t, selected = t.id == ui.selectedTrackId, heard = ui.session.heard(t),
                        peakL = meters.getOrNull(index * 2) ?: 0f, peakR = meters.getOrNull(index * 2 + 1) ?: 0f,
                        onSelect = { vm.selectTrack(t.id) },
                        onVolume = { db -> vm.liveVolume(t.id, db); vm.setTrackVolume(t.id, db) },
                        onVolumeDone = vm::endGesture,
                        onPan = { p -> vm.livePan(t.id, p); vm.setTrackPan(t.id, p) },
                        onMute = { vm.updateTrack(t.id, "Mute") { it.copy(muted = !it.muted) } },
                        onSolo = { vm.updateTrack(t.id, "Solo") { it.copy(solo = !it.solo) } },
                        onArm = { vm.updateTrack(t.id, "Arm") { it.copy(armed = !it.armed) } })
                }
                // Master.
                val masterIndex = tracks.size
                MasterStrip(ui.session.masterVolumeDb, peakL = meters.getOrNull(ui.session.tracks.size * 2) ?: 0f, peakR = meters.getOrNull(ui.session.tracks.size * 2 + 1) ?: 0f,
                    onVolume = { db -> vm.transport.liveMaster(Gain.dbToLinear(db).toFloat()); vm.setMasterVolume(db) }, onDone = vm::endGesture)
                @Suppress("UNUSED_VARIABLE") val unused = masterIndex
            }
        }
        selected?.let { t -> TrackDetail(vm, ui, t) }
        Panel(modifier = Modifier.fillMaxWidth(), inset = 10.dp) {
            SectionHeading(stringResource(R.string.studio_metronome))
            var click by remember { mutableStateOf(false) }
            var bpm by remember { mutableStateOf(ui.session.tempo.first().bpm.toFloat()) }
            ToggleRow(stringResource(R.string.studio_metronome), click, { on -> click = on; vm.transport.setMetronome(on, bpm.toDouble(), ui.session.tempo.first().beatsPerBar, 0.5f) })
            ValueSlider(stringResource(R.string.studio_tempo), bpm, 40f..240f, { bpm = it; vm.transport.setMetronome(click, it.toDouble(), 4, 0.5f) }, format = { String.format(java.util.Locale.getDefault(), "%.0f bpm", it) }, onCommit = { vm.setTempo(bpm.toDouble()) })
            // The tempo map: a change at the play-head, and the changes listed with their places.
            SecondaryButton(stringResource(R.string.studio_tempo_change_here, bpm.toInt()), onClick = { vm.addTempoChange(bpm.toDouble()) }, tall = false, modifier = Modifier.fillMaxWidth(), enabled = ui.window.playhead > 0)
            for (e in ui.session.tempo.filter { it.position > 0 }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Text(com.layerbit.stave.engine.text.Timecode.display(com.layerbit.stave.engine.text.Timecode.ofSamples(e.position, ui.session.sampleRate), 1), style = com.layerbit.stave.ui.theme.Numerals, color = StaveColors.Muted, modifier = Modifier.width(72.dp))
                    Text(String.format(java.util.Locale.getDefault(), "%.0f bpm", e.bpm), style = MaterialTheme.typography.bodyMedium, color = StaveColors.Text, modifier = Modifier.weight(1f))
                    SecondaryButton(stringResource(R.string.app_delete), onClick = { vm.removeTempoChange(e.position) }, tall = false)
                }
            }
            ToggleRow(stringResource(R.string.studio_loop), transport.playing && ui.selection != null, { on -> vm.transport.setLoop(if (on) ui.selection else null) }, enabled = ui.selection != null)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                SecondaryButton(stringResource(R.string.studio_bounce), onClick = vm::bounceToTrack, tall = false, modifier = Modifier.weight(1f), enabled = ui.session.length > 0)
                SecondaryButton(stringResource(R.string.studio_stems), onClick = vm::exportStems, tall = false, modifier = Modifier.weight(1f), enabled = ui.session.length > 0)
            }
        }
    }
}

@Composable
private fun Strip(t: Track, selected: Boolean, heard: Boolean, peakL: Float, peakR: Float, onSelect: () -> Unit, onVolume: (Double) -> Unit, onVolumeDone: () -> Unit, onPan: (Double) -> Unit, onMute: () -> Unit, onSolo: () -> Unit, onArm: () -> Unit) {
    Panel(modifier = Modifier.width(150.dp), onClick = onSelect, selected = selected, inset = 8.dp, tint = if (t.kind == Track.Kind.BUS) StaveColors.Running else null) {
        Text(t.name.ifBlank { if (t.kind == Track.Kind.BUS) "Bus" else "Track" }, style = MaterialTheme.typography.titleMedium, color = if (heard) StaveColors.Text else StaveColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            LevelMeter(peakL, modifier = Modifier.weight(1f).height(8.dp), label = "${t.name} left")
            LevelMeter(peakR, modifier = Modifier.weight(1f).height(8.dp), label = "${t.name} right")
        }
        ValueSlider(stringResource(R.string.studio_volume), t.volumeDb.toFloat(), -60f..12f, { onVolume(it.toDouble()) }, format = Formats::db, onCommit = onVolumeDone)
        ValueSlider(stringResource(R.string.studio_pan), t.pan.toFloat(), -1f..1f, { onPan(it.toDouble()) }, format = { p -> if (kotlin.math.abs(p) < 0.02f) "C" else if (p < 0) "L${(-p * 100).toInt()}" else "R${(p * 100).toInt()}" }, onCommit = onVolumeDone)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Toggle(stringResource(R.string.studio_mute), t.muted, StaveColors.Hot, onMute)
            Toggle(stringResource(R.string.studio_solo), t.solo, StaveColors.Band, onSolo)
            if (t.kind == Track.Kind.AUDIO) Toggle(stringResource(R.string.studio_arm), t.armed, StaveColors.Record, onArm)
        }
    }
}

@Composable
private fun MasterStrip(volumeDb: Double, peakL: Float, peakR: Float, onVolume: (Double) -> Unit, onDone: () -> Unit) {
    Panel(modifier = Modifier.width(150.dp), inset = 8.dp, tint = StaveColors.Band) {
        Text(stringResource(R.string.studio_master), style = MaterialTheme.typography.titleMedium, color = StaveColors.Text)
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            LevelMeter(peakL, modifier = Modifier.weight(1f).height(8.dp), label = "Master left")
            LevelMeter(peakR, modifier = Modifier.weight(1f).height(8.dp), label = "Master right")
        }
        ValueSlider(stringResource(R.string.studio_volume), volumeDb.toFloat(), -60f..12f, { onVolume(it.toDouble()) }, format = Formats::db, onCommit = onDone)
    }
}

/** A small on/off chip with a state a screen reader hears. */
@Composable
private fun Toggle(label: String, on: Boolean, colour: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Text(
        label, style = MaterialTheme.typography.labelSmall, color = if (on) StaveColors.OnBand else StaveColors.Muted,
        modifier = Modifier
            .background(if (on) colour else StaveColors.SurfaceDim, ChipShape)
            .toggleable(value = on, role = Role.Switch, onValueChange = { onClick() })
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrackDetail(vm: EditorViewModel, ui: EditorViewModel.Ui, t: Track) {
    var name by remember(t.id) { mutableStateOf(t.name) }
    Panel(modifier = Modifier.fillMaxWidth(), inset = 10.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = name, onValueChange = { name = it; vm.updateTrack(t.id, "Rename track", gesture = "name-${t.id}") { tr -> tr.copy(name = it) } }, label = { Text(stringResource(R.string.studio_rename)) }, singleLine = true, modifier = Modifier.weight(1f))
            SecondaryButton(stringResource(R.string.studio_remove), onClick = { vm.removeTrack(t.id) }, tall = false)
        }
        if (t.kind == Track.Kind.AUDIO || t.kind == Track.Kind.MIDI) {
            val frozen = ui.session.clipsOn(t.id).let { clips -> clips.isNotEmpty() && clips.all { c -> ui.session.source(c.sourceId)?.kind == com.layerbit.stave.engine.project.Source.Kind.FREEZE } }
            Text(stringResource(R.string.studio_freeze_hint), style = MaterialTheme.typography.bodySmall, color = StaveColors.Dim, modifier = Modifier.padding(top = 8.dp))
            SecondaryButton(stringResource(R.string.studio_freeze), onClick = { vm.freezeTrack(t.id) }, tall = false, modifier = Modifier.padding(top = 4.dp), enabled = !frozen && ui.session.clipsOn(t.id).isNotEmpty() && ui.busy == null)
            // Takes: with more than one lane, choose which is heard; the others' clips are muted as one edit.
            val lanes = ui.session.clipsOn(t.id).map { it.lane }.distinct().sorted()
            if (lanes.size > 1) {
                SectionHeading(stringResource(R.string.studio_takes), modifier = Modifier.padding(top = 8.dp))
                val heard = lanes.filter { l -> ui.session.clipsOn(t.id).any { it.lane == l && !it.muted } }
                val chosen: Int? = if (heard.size == 1) heard.single() else null
                ChipRow(listOf<Pair<Int?, String>>(null to stringResource(R.string.studio_takes_all)) + lanes.map { l -> l to stringResource(R.string.studio_take_n, l + 1) }, chosen, { vm.chooseLane(t.id, it) }, modifier = Modifier.padding(top = 4.dp))
                ToggleRow(stringResource(R.string.studio_comp), ui.compMode, { vm.setCompMode(it) }, detail = stringResource(R.string.studio_comp_hint))
            }
        }
        if (t.kind == Track.Kind.AUDIO) {
            Text(stringResource(R.string.studio_output), style = MaterialTheme.typography.bodyMedium, color = StaveColors.Text, modifier = Modifier.padding(top = 10.dp))
            ChipRow(options = listOf<Pair<String?, String>>(null to stringResource(R.string.studio_master)) + ui.session.buses.map { it.id to it.name }, selected = t.outputId, onSelect = { out -> vm.updateTrack(t.id, "Output") { it.copy(outputId = out) } }, modifier = Modifier.padding(vertical = 6.dp))
        }
        SectionHeading(stringResource(R.string.studio_inserts), modifier = Modifier.padding(top = 8.dp))
        for (ins in t.inserts) InsertRow(vm, t, ins)
        ActionGrid(listOf("eq" to R.string.studio_insert_eq, "compressor" to R.string.studio_insert_compressor, "limiter" to R.string.studio_insert_limiter, "gate" to R.string.studio_insert_gate).map { (kind, label) ->
            GridAction(stringResource(label), {
                val insert = if (kind == "eq") Insert(Ids.next("i"), "eq", bands = listOf(mapOf("kind" to 1.0, "hz" to 80.0, "gain" to 0.0, "q" to 0.707, "enabled" to 1.0), mapOf("kind" to 5.0, "hz" to 1000.0, "gain" to 0.0, "q" to 1.0, "enabled" to 1.0), mapOf("kind" to 7.0, "hz" to 8000.0, "gain" to 0.0, "q" to 0.7, "enabled" to 1.0))) else Insert(Ids.next("i"), kind)
                vm.updateTrack(t.id, "Add $kind") { it.copy(inserts = it.inserts + insert) }
            })
        }, modifier = Modifier.padding(top = 6.dp))
        if (ui.session.buses.any { it.id != t.id }) {
            SectionHeading(stringResource(R.string.studio_sends), modifier = Modifier.padding(top = 10.dp))
            for (send in t.sends) {
                val bus = ui.session.track(send.toTrackId) ?: continue
                ValueSlider(bus.name, send.gainDb.toFloat(), -60f..6f, { v -> vm.updateTrack(t.id, "Send", gesture = "send-${t.id}-${bus.id}") { tr -> tr.copy(sends = tr.sends.map { if (it.toTrackId == bus.id) it.copy(gainDb = v.toDouble()) else it }) } }, format = Formats::db, onCommit = vm::endGesture)
            }
            ActionGrid(ui.session.buses.filter { b -> b.id != t.id && t.sends.none { it.toTrackId == b.id } }.map { bus ->
                GridAction(stringResource(R.string.studio_send_add, bus.name), { vm.updateTrack(t.id, "Add send") { it.copy(sends = it.sends + Send(bus.id)) } })
            })
        }
        SectionHeading(stringResource(R.string.studio_automation), modifier = Modifier.padding(top = 10.dp))
        Text(stringResource(R.string.studio_draw_hint), style = MaterialTheme.typography.bodySmall, color = StaveColors.Dim, modifier = Modifier.padding(vertical = 4.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.studio_draw), style = MaterialTheme.typography.bodyMedium, color = StaveColors.Text)
            ChipRow(listOf<Pair<String?, String>>(null to stringResource(R.string.studio_draw_off), "volume" to stringResource(R.string.studio_automation_volume), "pan" to stringResource(R.string.studio_automation_pan)), ui.automationParam, { vm.setAutomationDraw(it) }, modifier = Modifier.weight(1f))
        }
        ToggleRow(stringResource(R.string.studio_write), ui.automationWrite, { vm.setAutomationWrite(it) }, detail = stringResource(R.string.studio_write_hint))
        for ((param, label, range) in listOf(Triple("volume", R.string.studio_automation_volume, -60f..12f), Triple("pan", R.string.studio_automation_pan, -1f..1f))) {
            val track = t.automationOf(param)
            val at = track.valueAt(ui.window.playhead, if (param == "volume") t.volumeDb else t.pan)
            var value by remember(t.id, param, ui.window.playhead) { mutableStateOf(at.toFloat()) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(label), style = MaterialTheme.typography.bodyMedium, color = StaveColors.Text, modifier = Modifier.width(64.dp))
                Text(pluralStringResource(R.plurals.studio_automation_points, track.points.size, track.points.size), style = MaterialTheme.typography.bodySmall, color = StaveColors.Dim, modifier = Modifier.weight(1f))
                SecondaryButton(stringResource(R.string.studio_automation_clear), onClick = { vm.updateTrack(t.id, "Clear automation") { it.copy(automation = it.automation - param) } }, tall = false, enabled = track.points.isNotEmpty())
            }
            ValueSlider(stringResource(R.string.studio_automation_add), value, range, { value = it }, format = if (param == "volume") Formats::db else { p -> String.format(java.util.Locale.getDefault(), "%.2f", p) })
            SecondaryButton(stringResource(R.string.studio_automation_add), onClick = {
                vm.updateTrack(t.id, "Automation point") { it.copy(automation = it.automation + (param to it.automationOf(param).with(ValueTrack.Point(ui.window.playhead, value.toDouble())))) }
            }, tall = false, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp))
        }
        if (t.kind == Track.Kind.AUDIO && ui.session.audioTracks.size > 1) {
            SectionHeading(stringResource(R.string.studio_duck), modifier = Modifier.padding(top = 10.dp))
            Text(stringResource(R.string.studio_duck_hint), style = MaterialTheme.typography.bodySmall, color = StaveColors.Dim)
            var depth by remember { mutableStateOf(-12f) }
            var voiceId by remember { mutableStateOf(ui.session.audioTracks.firstOrNull { it.id != t.id }?.id) }
            ChipRow(options = ui.session.audioTracks.filter { it.id != t.id }.map { it.id to it.name }, selected = voiceId, onSelect = { voiceId = it }, modifier = Modifier.padding(vertical = 6.dp))
            ValueSlider(stringResource(R.string.studio_duck_depth), depth, -30f..-3f, { depth = it }, format = Formats::db)
            PrimaryButton(stringResource(R.string.studio_duck), onClick = { voiceId?.let { vm.duckUnder(t.id, it, depth.toDouble()) } }, tall = false, modifier = Modifier.fillMaxWidth(), enabled = voiceId != null)
        }
    }
}

@Composable
private fun InsertRow(vm: EditorViewModel, t: Track, ins: Insert) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(ins.kind.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleMedium, color = StaveColors.Text, modifier = Modifier.weight(1f))
            Toggle(stringResource(R.string.studio_insert_on), ins.enabled, StaveColors.Good) { vm.updateTrack(t.id, "Insert on/off") { tr -> tr.copy(inserts = tr.inserts.map { if (it.id == ins.id) it.copy(enabled = !it.enabled) else it }) } }
            SecondaryButton(stringResource(R.string.studio_insert_remove), onClick = { vm.updateTrack(t.id, "Remove insert") { tr -> tr.copy(inserts = tr.inserts.filter { it.id != ins.id }) } }, tall = false)
        }
        val params = com.layerbit.stave.audio.SnapshotEncoder.paramOrder[ins.kind] ?: emptyList()
        for ((name, default) in params) {
            val range = when (name) { "threshold" -> -60f..0f; "ratio" -> 1f..20f; "knee" -> 0f..18f; "attack" -> 0.1f..200f; "release" -> 10f..2000f; "makeup" -> 0f..24f; "ceiling" -> -12f..0f; "lookahead" -> 1f..20f; "range" -> 6f..90f; "hold" -> 0f..500f; else -> 0f..1f }
            val fmt: (Float) -> String = when (name) { "ratio" -> Formats::ratio; "attack", "release", "lookahead", "hold" -> Formats::ms; else -> Formats::dbPlain }
            ValueSlider(name.replaceFirstChar { it.uppercase() }, ins.param(name, default).toFloat(), range, { v -> vm.updateTrack(t.id, "Insert $name", gesture = "ins-${ins.id}-$name") { tr -> tr.copy(inserts = tr.inserts.map { if (it.id == ins.id) it.copy(params = it.params + (name to v.toDouble())) else it }) } }, format = fmt, onCommit = vm::endGesture)
        }
        if (ins.kind == "eq") {
            ins.bands.forEachIndexed { i, b ->
                ValueSlider("Band ${i + 1} ${Formats.hz((b["hz"] ?: 1000.0).toFloat())}", (b["gain"] ?: 0.0).toFloat(), -18f..18f, { v -> vm.updateTrack(t.id, "EQ band", gesture = "eq-${ins.id}-$i") { tr -> tr.copy(inserts = tr.inserts.map { if (it.id == ins.id) it.copy(bands = it.bands.mapIndexed { k, band -> if (k == i) band + ("gain" to v.toDouble()) else band }) else it }) } }, format = Formats::db, onCommit = vm::endGesture)
            }
        }
    }
}
