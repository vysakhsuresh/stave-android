package com.layerbit.stave.ui.editor

import com.layerbit.stave.ui.components.edgeFade
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.layerbit.stave.R
import kotlin.math.abs
import com.layerbit.stave.engine.dsp.CompressorSettings
import com.layerbit.stave.engine.dsp.EqBand
import com.layerbit.stave.engine.dsp.FadeShape
import com.layerbit.stave.engine.dsp.FilterKind
import com.layerbit.stave.engine.dsp.Gain
import com.layerbit.stave.engine.dsp.LoudnessTarget
import com.layerbit.stave.engine.dsp.ParametricEq
import com.layerbit.stave.engine.dsp.Repair
import com.layerbit.stave.engine.export.AudioFormat
import com.layerbit.stave.engine.project.Marker
import com.layerbit.stave.engine.text.Timecode
import com.layerbit.stave.processing.OfflineTools
import com.layerbit.stave.ui.components.GridAction
import com.layerbit.stave.ui.components.ActionGrid
import com.layerbit.stave.ui.components.ChipRow
import com.layerbit.stave.ui.components.Formats
import com.layerbit.stave.ui.components.PrimaryButton
import com.layerbit.stave.ui.components.SecondaryButton
import com.layerbit.stave.ui.components.SectionHeading
import com.layerbit.stave.ui.components.ToggleRow
import com.layerbit.stave.ui.components.ValueSlider
import com.layerbit.stave.ui.components.formatBytes
import com.layerbit.stave.ui.theme.StaveColors
import com.layerbit.stave.ui.theme.ToolAccents
import kotlinx.coroutines.launch

/** The Edit tab's sheets, one composable each; the rail picks. */
@Composable
fun EditSheetContent(sheet: EditSheet, vm: EditorViewModel, ui: EditorViewModel.Ui) {
    when (sheet) {
        EditSheet.SELECT -> SelectSheet(vm, ui)
        EditSheet.GAIN -> GainSheet(vm, ui)
        EditSheet.FADES -> FadesSheet(vm, ui)
        EditSheet.REPAIR -> RepairSheet(vm, ui)
        EditSheet.SPECTRAL -> SpectralSheet(vm, ui)
        EditSheet.EQ -> EqSheet(vm, ui)
        EditSheet.DYNAMICS -> DynamicsSheet(vm, ui)
        EditSheet.TIME -> TimeSheet(vm, ui)
        EditSheet.EFFECTS -> EffectsSheet(vm, ui)
        EditSheet.MARKERS -> MarkersSheet(vm, ui)
        EditSheet.EXPORT -> ExportSheet(vm, ui)
    }
}

@Composable
private fun SelectionLine(ui: EditorViewModel.Ui) {
    val s = ui.selection
    val rate = ui.session.sampleRate
    Text(
        if (s == null) stringResource(R.string.editor_no_selection)
        else "${Timecode.display(Timecode.ofSamples(s.first, rate), 2)} – ${Timecode.display(Timecode.ofSamples(s.last + 1, rate), 2)}",
        style = MaterialTheme.typography.bodySmall, color = StaveColors.Dim, modifier = Modifier.padding(bottom = 8.dp)
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SelectSheet(vm: EditorViewModel, ui: EditorViewModel.Ui) {
    val has = ui.selection != null
    SelectionLine(ui)
    ActionGrid(listOf(
        GridAction(stringResource(R.string.edit_cut), vm::cutSelection, enabled = has),
        GridAction(stringResource(R.string.edit_copy), vm::copySelection, enabled = has),
        GridAction(stringResource(R.string.edit_paste), { vm.paste(true) }, enabled = ui.clipboard != null),
        GridAction(stringResource(R.string.edit_delete), { vm.deleteSelection(true) }, enabled = has),
        GridAction(stringResource(R.string.edit_delete_keep_gap), { vm.deleteSelection(false) }, enabled = has),
        GridAction(stringResource(R.string.edit_silence), vm::silenceSelection, enabled = has),
        GridAction(stringResource(R.string.edit_crop), vm::cropToSelection, enabled = has),
        GridAction(stringResource(R.string.edit_split), vm::splitAtPlayhead),
        GridAction(stringResource(R.string.edit_insert_silence), { vm.insertSilence(ui.session.sampleRate.toLong()) }),
        GridAction(stringResource(R.string.edit_select_all), vm::selectAll),
        GridAction(stringResource(R.string.edit_mute), vm::toggleMuteClips),
        GridAction(stringResource(R.string.edit_reverse), vm::reverseClips, enabled = has),
        GridAction(stringResource(R.string.edit_add_track), { vm.addTrack() })
    ))
    // The undo history: every step by its label, newest first; a tap goes back to just before it.
    Spacer(Modifier.height(12.dp))
    Text(stringResource(R.string.select_history), style = MaterialTheme.typography.bodyMedium, color = StaveColors.Text)
    Text(stringResource(R.string.select_history_hint), style = MaterialTheme.typography.bodySmall, color = StaveColors.Dim, modifier = Modifier.padding(vertical = 4.dp))
    val labels = vm.undoLabels()
    if (labels.isEmpty()) Text(stringResource(R.string.select_history_none), style = MaterialTheme.typography.bodySmall, color = StaveColors.Dim)
    for ((k, label) in labels.take(12).withIndex()) {
        SecondaryButton("${k + 1}. $label", onClick = { vm.undoSteps(k + 1) }, tall = false, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
    }
    Text(stringResource(R.string.select_shortcuts), style = MaterialTheme.typography.bodySmall, color = StaveColors.Dim, modifier = Modifier.padding(top = 10.dp))
}

@Composable
private fun GainSheet(vm: EditorViewModel, ui: EditorViewModel.Ui) {
    var change by remember { mutableStateOf(0f) }
    var peakTarget by remember { mutableStateOf(-1f) }
    var lufsTarget by remember { mutableStateOf(LoudnessTarget.byId(ui.exportSettings.loudnessTargetId).lufs.toFloat()) }
    SelectionLine(ui)
    ValueSlider(stringResource(R.string.gain_apply), change, -24f..24f, { change = it }, format = Formats::db)
    PrimaryButton(stringResource(R.string.gain_apply_button), onClick = { vm.applyOffline("Gain ${Formats.db(change)}", OfflineTools.gain(change.toDouble())) }, enabled = change != 0f, tall = false, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(12.dp))
    ValueSlider(stringResource(R.string.gain_normalise_peak), peakTarget, -12f..0f, { peakTarget = it }, format = Formats::db)
    SecondaryButton(stringResource(R.string.gain_normalise_button), onClick = { vm.applyOffline("Normalise peak", OfflineTools.normalisePeak(peakTarget.toDouble())) }, tall = false, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(12.dp))
    ValueSlider(stringResource(R.string.gain_normalise_lufs), lufsTarget, -30f..-6f, { lufsTarget = it }, format = { Formats.lufs(it.toDouble()) })
    SecondaryButton(stringResource(R.string.gain_normalise_button), onClick = { vm.applyOffline("Normalise loudness", OfflineTools.normaliseLoudness(lufsTarget.toDouble(), -1.0)) }, tall = false, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(12.dp))
    SecondaryButton(stringResource(R.string.gain_dc), onClick = { vm.applyOffline("Remove DC", OfflineTools.dcRemove()) }, tall = false, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun FadesSheet(vm: EditorViewModel, ui: EditorViewModel.Ui) {
    val rate = ui.session.sampleRate
    var inMs by remember { mutableStateOf(500f) }
    var outMs by remember { mutableStateOf(500f) }
    var shape by remember { mutableStateOf(FadeShape.EQUAL_POWER) }
    SelectionLine(ui)
    Text(stringResource(R.string.fades_shape), style = MaterialTheme.typography.bodyMedium, color = StaveColors.Text)
    ChipRow(options = FadeShape.entries.map { it to it.label }, selected = shape, onSelect = { shape = it }, modifier = Modifier.padding(vertical = 8.dp))
    ValueSlider(stringResource(R.string.fades_in), inMs, 0f..10_000f, { inMs = it }, format = Formats::ms)
    ValueSlider(stringResource(R.string.fades_out), outMs, 0f..10_000f, { outMs = it }, format = Formats::ms)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
        // On the clip: non-destructive, heard at once; the graph draws and plays them.
        PrimaryButton(stringResource(R.string.fades_in), onClick = { vm.setFades((inMs * rate / 1000).toLong(), shape, null, null) }, tall = false, modifier = Modifier.weight(1f))
        PrimaryButton(stringResource(R.string.fades_out), onClick = { vm.setFades(null, null, (outMs * rate / 1000).toLong(), shape) }, tall = false, modifier = Modifier.weight(1f))
    }
    SecondaryButton(stringResource(R.string.fades_render), onClick = {
        vm.applyOffline("Fades", OfflineTools.Transform { chs, r -> chs.map { a -> a.copyOf().also { com.layerbit.stave.engine.dsp.Fades.fadeIn(it, (inMs * r / 1000).toInt(), shape); com.layerbit.stave.engine.dsp.Fades.fadeOut(it, (outMs * r / 1000).toInt(), shape) } } })
    }, tall = false, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
}

@Composable
private fun RepairSheet(vm: EditorViewModel, ui: EditorViewModel.Ui) {
    val scope = rememberCoroutineScope()
    var sensitivity by remember { mutableStateOf(0.5f) }
    var hum by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var humSearched by remember { mutableStateOf(false) }
    var reduction by remember { mutableStateOf(18f) }
    var strength by remember { mutableStateOf(0.8f) }
    var learned by remember { mutableStateOf<List<FloatArray>?>(null) }
    var deessHz by remember { mutableStateOf(6500f) }
    SelectionLine(ui)
    SectionHeading(stringResource(R.string.repair_declick))
    Text(stringResource(R.string.repair_declick_hint), style = MaterialTheme.typography.bodySmall, color = StaveColors.Dim, modifier = Modifier.padding(vertical = 4.dp))
    ValueSlider(stringResource(R.string.repair_sensitivity), sensitivity, 0f..1f, { sensitivity = it }, format = Formats::percent)
    PrimaryButton(stringResource(R.string.repair_declick_button), onClick = { vm.applyOffline("Remove clicks", OfflineTools.declick(sensitivity)) }, tall = false, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(14.dp))
    SectionHeading(stringResource(R.string.repair_declip))
    Text(stringResource(R.string.repair_declip_hint), style = MaterialTheme.typography.bodySmall, color = StaveColors.Dim, modifier = Modifier.padding(vertical = 4.dp))
    SecondaryButton(stringResource(R.string.repair_declip_button), onClick = { vm.applyOffline("Repair clipping", OfflineTools.declip(0.985f)) }, tall = false, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(14.dp))
    SectionHeading(stringResource(R.string.repair_hum))
    Text(stringResource(R.string.repair_hum_hint), style = MaterialTheme.typography.bodySmall, color = StaveColors.Dim, modifier = Modifier.padding(vertical = 4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SecondaryButton(stringResource(R.string.repair_hum_find), onClick = {
            scope.launch { val g = vm.grabSelection(); hum = g?.let { Repair.findHum(it.channels.first(), it.sampleRate) }; humSearched = true }
        }, enabled = ui.selection != null, tall = false, modifier = Modifier.weight(1f))
        PrimaryButton(stringResource(R.string.repair_hum_remove), onClick = { hum?.let { h -> vm.applyOffline("Remove hum", OfflineTools.dehum(h.first, 8)) } }, enabled = hum != null, tall = false, modifier = Modifier.weight(1f))
    }
    if (humSearched) Text(hum?.let { stringResource(R.string.repair_hum_found, Formats.hz(it.first.toFloat()), Formats.dbPlain(it.second.toFloat())) } ?: stringResource(R.string.repair_hum_none), style = MaterialTheme.typography.bodySmall, color = StaveColors.Muted, modifier = Modifier.padding(top = 4.dp))
    Spacer(Modifier.height(14.dp))
    SectionHeading(stringResource(R.string.repair_noise))
    Text(stringResource(R.string.repair_noise_hint), style = MaterialTheme.typography.bodySmall, color = StaveColors.Dim, modifier = Modifier.padding(vertical = 4.dp))
    ValueSlider(stringResource(R.string.repair_noise_reduction), reduction, 6f..40f, { reduction = it }, format = Formats::dbPlain)
    ValueSlider(stringResource(R.string.repair_noise_strength), strength, 0f..1f, { strength = it }, format = Formats::percent)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SecondaryButton(stringResource(R.string.repair_noise_learn), onClick = { scope.launch { learned = vm.grabSelection()?.channels } }, enabled = ui.selection != null, tall = false, modifier = Modifier.weight(1f))
        PrimaryButton(stringResource(R.string.repair_noise_button), onClick = { vm.applyOffline("Reduce noise", OfflineTools.denoise(reduction.toDouble(), strength, learned)) }, tall = false, modifier = Modifier.weight(1f))
    }
    learned?.let { Text(stringResource(R.string.repair_noise_learned, Timecode.display(it.first().size * 1000L / ui.session.sampleRate, 1)), style = MaterialTheme.typography.bodySmall, color = StaveColors.Good, modifier = Modifier.padding(top = 4.dp)) }
    Spacer(Modifier.height(14.dp))
    SectionHeading(stringResource(R.string.repair_deess))
    ValueSlider(stringResource(R.string.eq_frequency), deessHz, 3000f..10_000f, { deessHz = it }, format = Formats::hz)
    SecondaryButton(stringResource(R.string.repair_deess_button), onClick = { vm.applyOffline("Soften S", OfflineTools.deess(deessHz.toDouble(), -24.0)) }, tall = false, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun SpectralSheet(vm: EditorViewModel, ui: EditorViewModel.Ui) {
    val spectral by vm.spectral.collectAsState()
    var gain by remember { mutableStateOf(-24f) }
    ToggleRow(stringResource(R.string.spectral_show), spectral.shown, { vm.showSpectrogram(it) })
    Text(stringResource(R.string.spectral_hint), style = MaterialTheme.typography.bodySmall, color = StaveColors.Dim, modifier = Modifier.padding(vertical = 4.dp))
    val box = spectral.box
    val rate = ui.session.sampleRate
    Text(
        if (box == null) stringResource(R.string.spectral_no_box)
        else stringResource(R.string.spectral_box, Timecode.display(Timecode.ofSamples(box.fromSample, rate), 2), Timecode.display(Timecode.ofSamples(box.toSample, rate), 2), Formats.hz(box.lowHz.toFloat()), Formats.hz(box.highHz.toFloat())),
        style = MaterialTheme.typography.bodySmall, color = if (box == null) StaveColors.Dim else StaveColors.Band, modifier = Modifier.padding(bottom = 6.dp)
    )
    ValueSlider(stringResource(R.string.spectral_gain), gain, -60f..-3f, { gain = it }, format = Formats::dbPlain)
    // The slider's own action under it at full width, the two fixed ones beside each other: three
    // in a row cut "Turn down" and "Fill from around" to their first words.
    PrimaryButton(stringResource(R.string.spectral_attenuate), onClick = { vm.spectralAttenuate(gain.toDouble()) }, enabled = box != null && spectral.shown, tall = false, modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
        SecondaryButton(stringResource(R.string.spectral_remove), onClick = { vm.spectralAttenuate(-60.0) }, enabled = box != null && spectral.shown, tall = false, modifier = Modifier.weight(1f))
        SecondaryButton(stringResource(R.string.spectral_fill), onClick = vm::spectralFill, enabled = box != null && spectral.shown, tall = false, modifier = Modifier.weight(1f))
    }
}

/**
 * The effects every plain editor has, with their knobs: an echo, a chorus, a flanger, a phaser,
 * a tremolo, a reverb, saturation and a bit crusher, each rendered over the selection; and the
 * stereo housekeeping - swap, one side on both, balance. The vocal presets on the Voice tab are
 * these with their numbers chosen.
 */
@Composable
private fun EffectsSheet(vm: EditorViewModel, ui: EditorViewModel.Ui) {
    var kind by remember { mutableStateOf("delay") }
    SelectionLine(ui)
    ChipRow(options = listOf("delay" to stringResource(R.string.fx_delay), "chorus" to stringResource(R.string.fx_chorus), "flanger" to stringResource(R.string.fx_flanger), "phaser" to stringResource(R.string.fx_phaser), "tremolo" to stringResource(R.string.fx_tremolo), "reverb" to stringResource(R.string.fx_reverb), "saturate" to stringResource(R.string.fx_saturate), "crush" to stringResource(R.string.fx_crush), "stereo" to stringResource(R.string.fx_stereo)), selected = kind, onSelect = { kind = it }, modifier = Modifier.padding(vertical = 8.dp))
    var mix by remember { mutableStateOf(0.4f) }
    var rate by remember { mutableStateOf(0.8f) }
    var depth by remember { mutableStateOf(0.5f) }
    var feedback by remember { mutableStateOf(0.3f) }
    var time by remember { mutableStateOf(320f) }
    var drive by remember { mutableStateOf(2f) }
    var bits by remember { mutableStateOf(8f) }
    var balance by remember { mutableStateOf(0f) }
    when (kind) {
        "delay" -> { ValueSlider(stringResource(R.string.fx_time), time, 20f..1500f, { time = it }, format = Formats::ms); ValueSlider(stringResource(R.string.fx_feedback), feedback, 0f..0.9f, { feedback = it }, format = Formats::percent); ValueSlider(stringResource(R.string.fx_mix), mix, 0f..1f, { mix = it }, format = Formats::percent) }
        "chorus", "flanger" -> { ValueSlider(stringResource(R.string.fx_rate), rate, 0.05f..5f, { rate = it }, format = { String.format(java.util.Locale.getDefault(), "%.2f Hz", it) }); ValueSlider(stringResource(R.string.fx_depth), depth, 0f..1f, { depth = it }, format = Formats::percent); if (kind == "flanger") ValueSlider(stringResource(R.string.fx_feedback), feedback, 0f..0.9f, { feedback = it }, format = Formats::percent); ValueSlider(stringResource(R.string.fx_mix), mix, 0f..1f, { mix = it }, format = Formats::percent) }
        "phaser" -> { ValueSlider(stringResource(R.string.fx_rate), rate, 0.05f..4f, { rate = it }, format = { String.format(java.util.Locale.getDefault(), "%.2f Hz", it) }); ValueSlider(stringResource(R.string.fx_depth), depth, 0f..1f, { depth = it }, format = Formats::percent); ValueSlider(stringResource(R.string.fx_feedback), feedback, 0f..0.9f, { feedback = it }, format = Formats::percent); ValueSlider(stringResource(R.string.fx_mix), mix, 0f..1f, { mix = it }, format = Formats::percent) }
        "tremolo" -> { ValueSlider(stringResource(R.string.fx_rate), rate, 0.5f..16f, { rate = it }, format = { String.format(java.util.Locale.getDefault(), "%.1f Hz", it) }); ValueSlider(stringResource(R.string.fx_depth), depth, 0f..1f, { depth = it }, format = Formats::percent) }
        "reverb" -> { ValueSlider(stringResource(R.string.fx_decay), feedback, 0.3f..0.98f, { feedback = it }, format = Formats::percent); ValueSlider(stringResource(R.string.fx_damping), depth, 0f..1f, { depth = it }, format = Formats::percent); ValueSlider(stringResource(R.string.fx_predelay), time, 0f..120f, { time = it }, format = Formats::ms); ValueSlider(stringResource(R.string.fx_mix), mix, 0f..1f, { mix = it }, format = Formats::percent) }
        "saturate" -> { ValueSlider(stringResource(R.string.fx_drive), drive, 1f..12f, { drive = it }, format = Formats::times); ValueSlider(stringResource(R.string.fx_mix), mix, 0f..1f, { mix = it }, format = Formats::percent) }
        "crush" -> { ValueSlider(stringResource(R.string.fx_bits), bits, 2f..16f, { bits = it }, format = { String.format(java.util.Locale.getDefault(), "%d bit", it.toInt()) }, steps = 13); ValueSlider(stringResource(R.string.fx_hold), drive, 1f..16f, { drive = it }, format = { String.format(java.util.Locale.getDefault(), "%d×", it.toInt()) }, steps = 14) }
        "stereo" -> {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                SecondaryButton(stringResource(R.string.fx_swap), onClick = { vm.applyOffline("Swap channels", OfflineTools.swapChannels()) }, tall = false, modifier = Modifier.weight(1f))
                SecondaryButton(stringResource(R.string.fx_left_both), onClick = { vm.applyOffline("Left on both", OfflineTools.oneSide(false)) }, tall = false, modifier = Modifier.weight(1f))
                SecondaryButton(stringResource(R.string.fx_right_both), onClick = { vm.applyOffline("Right on both", OfflineTools.oneSide(true)) }, tall = false, modifier = Modifier.weight(1f))
            }
            ValueSlider(stringResource(R.string.fx_balance), balance, -12f..12f, { balance = it }, format = { b -> if (abs(b) < 0.05f) "centre" else if (b > 0) "right ${Formats.dbPlain(b)}" else "left ${Formats.dbPlain(-b)}" })
            SecondaryButton(stringResource(R.string.fx_apply_balance), onClick = { vm.applyOffline("Balance", OfflineTools.balance(balance.toDouble())) }, tall = false, modifier = Modifier.fillMaxWidth(), enabled = abs(balance) >= 0.05f)
        }
    }
    if (kind != "stereo") {
        PrimaryButton(stringResource(R.string.fx_apply), onClick = {
            val t = when (kind) {
                "delay" -> OfflineTools.delay(time.toDouble(), feedback, mix, 4000.0)
                "chorus" -> OfflineTools.chorus(rate.toDouble(), 2.0 + 10.0 * depth, mix)
                "flanger" -> OfflineTools.flanger(rate.toDouble(), 0.5 + 3.0 * depth, feedback, mix)
                "phaser" -> OfflineTools.phaser(rate.toDouble(), depth, feedback, mix)
                "tremolo" -> OfflineTools.tremolo(rate.toDouble(), depth)
                "reverb" -> OfflineTools.reverb(feedback, depth, mix, time.toDouble())
                "saturate" -> OfflineTools.saturate(drive, mix)
                else -> OfflineTools.crush(bits.toInt(), drive.toInt())
            }
            vm.applyOffline(kind.replaceFirstChar { it.uppercase() }, t)
        }, tall = false, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), enabled = ui.session.length > 0)
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun EqSheet(vm: EditorViewModel, ui: EditorViewModel.Ui) {
    var bands by remember { mutableStateOf(listOf(EqBand(FilterKind.HIGH_PASS, 80.0), EqBand(FilterKind.PEAKING, 1000.0, 0.0, 1.0), EqBand(FilterKind.HIGH_SHELF, 8000.0, 0.0, 0.7))) }
    var selected by remember { mutableStateOf(1) }
    // Parametric, or a graphic equaliser of 10 or 31 ISO bands: the graphic ones are the same
    // peaking filters with their Q fixed by the band width, drawn on the same curve.
    var mode by remember { mutableStateOf("parametric") }
    var gains10 by remember { mutableStateOf(DoubleArray(10)) }
    var gains31 by remember { mutableStateOf(DoubleArray(31)) }
    val graphicBands = when (mode) { "g10" -> ParametricEq.graphic(ParametricEq.ISO_10, gains10); "g31" -> ParametricEq.graphic(ParametricEq.ISO_31, gains31); else -> null }
    SelectionLine(ui)
    // The analyser follows the selection: what is heard sits behind what is drawn.
    val spectrum by vm.analyser.collectAsState()
    androidx.compose.runtime.LaunchedEffect(ui.selection, ui.session.length) { vm.refreshAnalyser() }
    EqCurve(graphicBands ?: bands, ui.session.sampleRate, if (graphicBands == null) selected else -1, onSelect = { if (graphicBands == null) selected = it }, modifier = Modifier.fillMaxWidth().height(120.dp), spectrum = spectrum)
    ChipRow(options = listOf("parametric" to stringResource(R.string.eq_mode_parametric), "g10" to stringResource(R.string.eq_mode_10), "g31" to stringResource(R.string.eq_mode_31)), selected = mode, onSelect = { mode = it }, modifier = Modifier.padding(top = 6.dp))
    if (graphicBands != null) {
        val centres = if (mode == "g10") ParametricEq.ISO_10 else ParametricEq.ISO_31
        val gains = if (mode == "g10") gains10 else gains31
        Row(rememberScrollState().let { s -> Modifier.fillMaxWidth().edgeFade(s).horizontalScroll(s) }.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            for (i in centres.indices) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(if (mode == "g10") 44.dp else 30.dp)) {
                    // A vertical fader: Compose's slider is horizontal, so it is laid out turned a quarter.
                    androidx.compose.foundation.layout.Box(Modifier.height(120.dp).width(if (mode == "g10") 44.dp else 30.dp), contentAlignment = Alignment.Center) {
                        androidx.compose.material3.Slider(
                            value = gains[i].toFloat(), onValueChange = { v -> val g = gains.copyOf(); g[i] = v.toDouble(); if (mode == "g10") gains10 = g else gains31 = g }, valueRange = -12f..12f,
                            modifier = Modifier.width(120.dp).rotate(-90f).semantics { contentDescription = Formats.hz(centres[i].toFloat()); stateDescription = Formats.db(gains[i].toFloat()) },
                            thumb = { com.layerbit.stave.ui.components.SliderThumb(true) },
                            track = { st -> com.layerbit.stave.ui.components.SliderTrack((st.value + 12f) / 24f, true) }
                        )
                    }
                    Text(if (centres[i] >= 1000) String.format(java.util.Locale.getDefault(), "%.0fk", centres[i] / 1000) else String.format(java.util.Locale.getDefault(), "%.0f", centres[i]), style = MaterialTheme.typography.labelSmall, color = StaveColors.Dim, maxLines = 1)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            SecondaryButton(stringResource(R.string.eq_preset_flat), onClick = { if (mode == "g10") gains10 = DoubleArray(10) else gains31 = DoubleArray(31) }, tall = false, modifier = Modifier.weight(1f))
        }
        PrimaryButton(stringResource(R.string.eq_apply), onClick = { vm.applyOffline("Graphic EQ", OfflineTools.eq(graphicBands.filter { kotlin.math.abs(it.gainDb) > 0.05 })) }, tall = false, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), enabled = graphicBands.any { kotlin.math.abs(it.gainDb) > 0.05 })
        return
    }
    ChipRow(options = listOf("flat" to stringResource(R.string.eq_preset_flat), "voice" to stringResource(R.string.eq_preset_voice), "bright" to stringResource(R.string.eq_preset_tilt_bright), "warm" to stringResource(R.string.eq_preset_tilt_warm)), selected = "", onSelect = { id ->
        bands = when (id) { "voice" -> ParametricEq.VOICE_PRESET; "bright" -> ParametricEq.tilt(3.0); "warm" -> ParametricEq.tilt(-3.0); else -> listOf(EqBand(FilterKind.PEAKING, 1000.0, 0.0, 1.0)) }
        selected = 0
    }, modifier = Modifier.padding(vertical = 8.dp))
    val band = bands.getOrNull(selected)
    if (band != null) {
        Text(stringResource(R.string.eq_kind), style = MaterialTheme.typography.bodyMedium, color = StaveColors.Text)
        ChipRow(options = FilterKind.entries.map { it to kindLabel(it) }, selected = band.kind, onSelect = { k -> bands = bands.mapIndexed { i, b -> if (i == selected) b.copy(kind = k) else b } }, modifier = Modifier.padding(vertical = 6.dp))
        ValueSlider(stringResource(R.string.eq_frequency), logSlider(band.hz), 0f..1f, { v -> bands = bands.mapIndexed { i, b -> if (i == selected) b.copy(hz = logValue(v)) else b } }, format = { Formats.hz(logValue(it).toFloat()) })
        ValueSlider(stringResource(R.string.eq_gain), band.gainDb.toFloat(), -18f..18f, { v -> bands = bands.mapIndexed { i, b -> if (i == selected) b.copy(gainDb = v.toDouble()) else b } }, format = Formats::db, enabled = band.kind in setOf(FilterKind.PEAKING, FilterKind.LOW_SHELF, FilterKind.HIGH_SHELF))
        ValueSlider(stringResource(R.string.eq_q), band.q.toFloat(), 0.1f..10f, { v -> bands = bands.mapIndexed { i, b -> if (i == selected) b.copy(q = v.toDouble()) else b } }, format = Formats::q)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
        SecondaryButton(stringResource(R.string.eq_add_band), onClick = { bands = bands + EqBand(FilterKind.PEAKING, 2000.0, 0.0, 1.0); selected = bands.size - 1 }, enabled = bands.size < 16, tall = false, modifier = Modifier.weight(1f))
        SecondaryButton(stringResource(R.string.eq_remove_band), onClick = { if (bands.size > 1) { bands = bands.filterIndexed { i, _ -> i != selected }; selected = selected.coerceIn(0, bands.size - 1) } }, enabled = bands.size > 1, tall = false, modifier = Modifier.weight(1f))
    }
    PrimaryButton(stringResource(R.string.eq_apply), onClick = { vm.applyOffline("EQ", OfflineTools.eq(bands)) }, tall = false, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
}

@Composable
private fun kindLabel(k: FilterKind): String = when (k) {
    FilterKind.LOW_PASS -> "Low-pass"; FilterKind.HIGH_PASS -> "High-pass"; FilterKind.BAND_PASS -> "Band-pass"; FilterKind.NOTCH -> "Notch"
    FilterKind.ALL_PASS -> "All-pass"; FilterKind.PEAKING -> "Bell"; FilterKind.LOW_SHELF -> "Low shelf"; FilterKind.HIGH_SHELF -> "High shelf"
}

private fun logSlider(hz: Double): Float = (kotlin.math.ln(hz / 20.0) / kotlin.math.ln(1000.0)).toFloat().coerceIn(0f, 1f)
private fun logValue(v: Float): Double = 20.0 * Math.pow(1000.0, v.toDouble())

@Composable
private fun DynamicsSheet(vm: EditorViewModel, ui: EditorViewModel.Ui) {
    var threshold by remember { mutableStateOf(-18f) }
    var ratio by remember { mutableStateOf(3f) }
    var knee by remember { mutableStateOf(6f) }
    var attack by remember { mutableStateOf(10f) }
    var release by remember { mutableStateOf(120f) }
    var makeup by remember { mutableStateOf(0f) }
    var ceiling by remember { mutableStateOf(-1f) }
    var gateThreshold by remember { mutableStateOf(-45f) }
    var gateRange by remember { mutableStateOf(60f) }
    SelectionLine(ui)
    SectionHeading(stringResource(R.string.dyn_compressor))
    ValueSlider(stringResource(R.string.dyn_threshold), threshold, -60f..0f, { threshold = it }, format = Formats::dbPlain)
    ValueSlider(stringResource(R.string.dyn_ratio), ratio, 1f..20f, { ratio = it }, format = Formats::ratio)
    ValueSlider(stringResource(R.string.dyn_knee), knee, 0f..18f, { knee = it }, format = Formats::dbPlain)
    ValueSlider(stringResource(R.string.dyn_attack), attack, 0.1f..200f, { attack = it }, format = Formats::ms)
    ValueSlider(stringResource(R.string.dyn_release), release, 10f..2000f, { release = it }, format = Formats::ms)
    ValueSlider(stringResource(R.string.dyn_makeup), makeup, 0f..24f, { makeup = it }, format = Formats::db)
    PrimaryButton(stringResource(R.string.dyn_apply), onClick = { vm.applyOffline("Compress", OfflineTools.compress(CompressorSettings(threshold.toDouble(), ratio.toDouble(), knee.toDouble(), attack.toDouble(), release.toDouble(), makeup.toDouble()))) }, tall = false, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(14.dp))
    SectionHeading(stringResource(R.string.dyn_limiter))
    ValueSlider(stringResource(R.string.dyn_ceiling), ceiling, -12f..0f, { ceiling = it }, format = Formats::dbPlain)
    SecondaryButton(stringResource(R.string.dyn_apply), onClick = { vm.applyOffline("Limit", OfflineTools.limit(ceiling.toDouble(), 60.0)) }, tall = false, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(14.dp))
    SectionHeading(stringResource(R.string.dyn_gate))
    ValueSlider(stringResource(R.string.dyn_threshold), gateThreshold, -80f..-10f, { gateThreshold = it }, format = Formats::dbPlain)
    ValueSlider(stringResource(R.string.dyn_range), gateRange, 6f..90f, { gateRange = it }, format = Formats::dbPlain)
    SecondaryButton(stringResource(R.string.dyn_apply), onClick = { vm.applyOffline("Gate", OfflineTools.gate(gateThreshold.toDouble(), gateRange.toDouble(), 150.0)) }, tall = false, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun TimeSheet(vm: EditorViewModel, ui: EditorViewModel.Ui) {
    var stretch by remember { mutableStateOf(1f) }
    var semitones by remember { mutableStateOf(0f) }
    var formant by remember { mutableStateOf(true) }
    var speed by remember { mutableStateOf(1f) }
    SelectionLine(ui)
    ValueSlider(stringResource(R.string.time_stretch), stretch, 0.25f..4f, { stretch = it }, format = Formats::times)
    ValueSlider(stringResource(R.string.time_pitch), semitones, -24f..24f, { semitones = it }, format = Formats::semitones)
    ToggleRow(stringResource(R.string.time_formant), formant, { formant = it })
    PrimaryButton(stringResource(R.string.time_apply), onClick = { vm.applyOffline("Time and pitch", OfflineTools.timePitch(stretch.toDouble(), semitones.toDouble(), formant)) }, enabled = stretch != 1f || semitones != 0f, tall = false, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(14.dp))
    ValueSlider(stringResource(R.string.time_speed), speed, 0.25f..4f, { speed = it }, format = Formats::times)
    SecondaryButton(stringResource(R.string.time_speed_apply), onClick = { vm.applyOffline("Speed", OfflineTools.speed(speed.toDouble())) }, enabled = speed != 1f, tall = false, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun MarkersSheet(vm: EditorViewModel, ui: EditorViewModel.Ui) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PrimaryButton(stringResource(R.string.markers_add), onClick = { vm.addMarker() }, tall = false, modifier = Modifier.weight(1f))
        SecondaryButton(stringResource(R.string.markers_add_chapter), onClick = { vm.addMarker(Marker.Kind.CHAPTER) }, tall = false, modifier = Modifier.weight(1f))
    }
    Spacer(Modifier.height(8.dp))
    if (ui.session.markers.isEmpty()) Text(stringResource(R.string.markers_none), style = MaterialTheme.typography.bodySmall, color = StaveColors.Dim)
    for (m in ui.session.markers) {
        var name by remember(m.id) { mutableStateOf(m.name) }
        // Fixed widths for the two buttons, so every row's name field starts and ends at the same x:
        // sized to their words, "Chapter" pushed its row's field narrower than the others.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(Timecode.display(Timecode.ofSamples(m.position, ui.session.sampleRate), 1), style = com.layerbit.stave.ui.theme.Numerals, color = if (m.kind == Marker.Kind.CHAPTER) StaveColors.Running else StaveColors.Hot, modifier = Modifier.width(64.dp))
            OutlinedTextField(value = name, onValueChange = { name = it; vm.renameMarker(m.id, it) }, singleLine = true, modifier = Modifier.weight(1f), textStyle = MaterialTheme.typography.bodyMedium)
            SecondaryButton(stringResource(if (m.kind == Marker.Kind.CHAPTER) R.string.markers_chapter else R.string.markers_marker), onClick = { vm.setMarkerKind(m.id, if (m.kind == Marker.Kind.CHAPTER) Marker.Kind.MARKER else Marker.Kind.CHAPTER) }, tall = false, modifier = Modifier.width(100.dp).height(56.dp))
            SecondaryButton(stringResource(R.string.app_delete), onClick = { vm.removeMarker(m.id) }, tall = false, modifier = Modifier.width(88.dp).height(56.dp))
        }
    }
    // Regions: a named stretch, made from the selection, selected again by a tap.
    Spacer(Modifier.height(12.dp))
    Text(stringResource(R.string.regions_title), style = MaterialTheme.typography.bodyMedium, color = StaveColors.Text)
    var regionName by remember { mutableStateOf("") }
    // Bottoms aligned and the button at the field's 56 dp: centred, the 40 dp button floated beside
    // a taller field and neither edge met.
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
        OutlinedTextField(value = regionName, onValueChange = { regionName = it }, label = { Text(stringResource(R.string.regions_name)) }, singleLine = true, modifier = Modifier.weight(1f))
        SecondaryButton(stringResource(R.string.regions_add), onClick = { vm.addRegionFromSelection(regionName); regionName = "" }, tall = false, enabled = ui.selection != null, modifier = Modifier.height(56.dp))
    }
    if (ui.session.regions.isEmpty()) Text(stringResource(R.string.regions_none), style = MaterialTheme.typography.bodySmall, color = StaveColors.Dim, modifier = Modifier.padding(top = 6.dp))
    for (r in ui.session.regions) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(Timecode.display(Timecode.ofSamples(r.start, ui.session.sampleRate), 1), style = com.layerbit.stave.ui.theme.Numerals, color = ToolAccents.Studio, modifier = Modifier.width(64.dp))
            Text(r.name, style = MaterialTheme.typography.bodyMedium, color = StaveColors.Text, modifier = Modifier.weight(1f))
            SecondaryButton(stringResource(R.string.regions_select), onClick = { vm.selectRegion(r.id) }, tall = false)
            SecondaryButton(stringResource(R.string.app_delete), onClick = { vm.removeRegion(r.id) }, tall = false)
        }
    }
}

@Composable
private fun ExportSheet(vm: EditorViewModel, ui: EditorViewModel.Ui) {
    val s = ui.exportSettings
    val fmt = s.format
    val context = LocalContext.current
    val seconds = ui.session.length.toDouble() / ui.session.sampleRate
    val estimate = fmt.estimateBytes(seconds, s.bitrateKbps, s.outputRate(ui.session.sampleRate), s.outputChannels(2), s.depth)
    Text(stringResource(R.string.export_format), style = MaterialTheme.typography.bodyMedium, color = StaveColors.Text)
    ChipRow(options = AudioFormat.all.map { it.id to it.label }, selected = s.formatId, onSelect = { id -> val f = AudioFormat.byId(id); vm.setExportSettings(s.copy(formatId = id, bitrateKbps = f.defaultBitrate.takeIf { it > 0 } ?: s.bitrateKbps, depth = f.depths.firstOrNull { it == s.depth } ?: f.depths.firstOrNull() ?: 16)) }, modifier = Modifier.padding(vertical = 6.dp))
    Text(fmt.note, style = MaterialTheme.typography.bodySmall, color = StaveColors.Dim)
    if (fmt.bitrates.isNotEmpty()) {
        Text(stringResource(R.string.export_bitrate), style = MaterialTheme.typography.bodyMedium, color = StaveColors.Text, modifier = Modifier.padding(top = 10.dp))
        ChipRow(options = fmt.bitrates.map { it to "$it" }, selected = s.bitrateKbps, onSelect = { vm.setExportSettings(s.copy(bitrateKbps = it)) }, modifier = Modifier.padding(vertical = 6.dp))
    }
    if (fmt.depths.isNotEmpty()) {
        Text(stringResource(R.string.export_depth), style = MaterialTheme.typography.bodyMedium, color = StaveColors.Text, modifier = Modifier.padding(top = 10.dp))
        ChipRow(options = fmt.depths.map { it to (if (it == 32) "32-bit float" else "$it-bit") }, selected = s.depth, onSelect = { vm.setExportSettings(s.copy(depth = it)) }, modifier = Modifier.padding(vertical = 6.dp))
    }
    Text(stringResource(R.string.export_rate), style = MaterialTheme.typography.bodyMedium, color = StaveColors.Text, modifier = Modifier.padding(top = 10.dp))
    ChipRow(options = listOf(0 to stringResource(R.string.export_rate_keep), 44_100 to "44.1 kHz", 48_000 to "48 kHz", 96_000 to "96 kHz"), selected = s.sampleRate, onSelect = { vm.setExportSettings(s.copy(sampleRate = it)) }, modifier = Modifier.padding(vertical = 6.dp), enabled = { r -> fmt.rates.isEmpty() || r == 0 || r in fmt.rates })
    Text(stringResource(R.string.export_channels), style = MaterialTheme.typography.bodyMedium, color = StaveColors.Text, modifier = Modifier.padding(top = 10.dp))
    ChipRow(options = listOf(0 to stringResource(R.string.export_channels_keep), 1 to stringResource(R.string.export_mono)), selected = s.channels, onSelect = { vm.setExportSettings(s.copy(channels = it)) }, modifier = Modifier.padding(vertical = 6.dp))
    ToggleRow(stringResource(R.string.export_normalise), s.normaliseToTarget, { vm.setExportSettings(s.copy(normaliseToTarget = it)) })
    if (s.normaliseToTarget) ChipRow(options = LoudnessTarget.all.map { it.id to it.label }, selected = s.loudnessTargetId, onSelect = { vm.setExportSettings(s.copy(loudnessTargetId = it)) }, modifier = Modifier.padding(vertical = 6.dp))
    // Where it is going: one tap sets the format, the bitrate and the target; the tags below stay.
    Text(stringResource(R.string.export_preset), style = MaterialTheme.typography.bodyMedium, color = StaveColors.Text, modifier = Modifier.padding(top = 8.dp))
    var presetId by remember { mutableStateOf("") }
    ChipRow(options = com.layerbit.stave.engine.export.ExportPresets.all.map { it.id to it.label }, selected = presetId, onSelect = { id -> presetId = id; com.layerbit.stave.engine.export.ExportPresets.byId(id)?.let { vm.setExportSettings(it.apply(s)) } }, modifier = Modifier.padding(vertical = 6.dp))
    com.layerbit.stave.engine.export.ExportPresets.byId(presetId)?.let { Text(it.note, style = MaterialTheme.typography.bodySmall, color = StaveColors.Dim) }
    OutlinedTextField(value = s.title, onValueChange = { vm.setExportSettings(s.copy(title = it)) }, label = { Text(stringResource(R.string.export_title)) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
    // The Artist/Album row was once left unclosed, so the next row sat inside it, took the whole
    // width first and left these two a few pixels: on the phone they read one letter a line.
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
        OutlinedTextField(value = s.artist, onValueChange = { vm.setExportSettings(s.copy(artist = it)) }, label = { Text(stringResource(R.string.export_artist)) }, singleLine = true, modifier = Modifier.weight(1f))
        OutlinedTextField(value = s.album, onValueChange = { vm.setExportSettings(s.copy(album = it)) }, label = { Text(stringResource(R.string.export_album)) }, singleLine = true, modifier = Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
        OutlinedTextField(value = if (s.trackNumber > 0) s.trackNumber.toString() else "", onValueChange = { vm.setExportSettings(s.copy(trackNumber = it.filter { c -> c.isDigit() }.take(3).toIntOrNull() ?: 0)) }, label = { Text(stringResource(R.string.export_track_number)) }, singleLine = true, modifier = Modifier.weight(1f))
        OutlinedTextField(value = s.year, onValueChange = { vm.setExportSettings(s.copy(year = it.filter { c -> c.isDigit() }.take(4))) }, label = { Text(stringResource(R.string.export_year)) }, singleLine = true, modifier = Modifier.weight(1f))
        OutlinedTextField(value = s.genre, onValueChange = { vm.setExportSettings(s.copy(genre = it)) }, label = { Text(stringResource(R.string.export_genre)) }, singleLine = true, modifier = Modifier.weight(1.4f))
    }
    if (fmt.chapters) ToggleRow(stringResource(R.string.export_chapters), s.writeChapters, { vm.setExportSettings(s.copy(writeChapters = it)) })
    Text(stringResource(R.string.export_estimate, formatBytes(estimate)), style = MaterialTheme.typography.bodySmall, color = StaveColors.Dim, modifier = Modifier.padding(top = 6.dp))
    PrimaryButton(stringResource(R.string.export_button), onClick = { vm.export(context.getString(R.string.editor_untitled)) }, enabled = ui.session.length > 0 && ui.busy == null, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
    ui.lastExport?.let { r ->
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.export_done), style = MaterialTheme.typography.titleMedium, color = StaveColors.Good)
        Text(stringResource(R.string.export_done_detail, formatBytes(r.bytes), Formats.lufs(r.integratedLufs), Formats.dbtp(r.truePeakDbtp)), style = MaterialTheme.typography.bodySmall, color = StaveColors.Muted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            SecondaryButton(stringResource(R.string.export_share), onClick = {
                val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", r.file)
                val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType(fmt.mime).putExtra(android.content.Intent.EXTRA_STREAM, uri).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.startActivity(android.content.Intent.createChooser(send, null))
            }, tall = false, modifier = Modifier.weight(1f))
            SecondaryButton(stringResource(R.string.export_open), onClick = {
                val uri = r.mediaUri ?: androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", r.file)
                runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri, fmt.mime).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
            }, tall = false, modifier = Modifier.weight(1f))
        }
    }
}
