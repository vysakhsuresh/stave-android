package com.layerbit.stave.ui.finish

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.layerbit.stave.R
import com.layerbit.stave.engine.dsp.LoudnessTarget
import com.layerbit.stave.ui.components.ChipRow
import com.layerbit.stave.ui.components.Formats
import com.layerbit.stave.ui.components.LabelledValue
import com.layerbit.stave.ui.components.Panel
import com.layerbit.stave.ui.components.PrimaryButton
import com.layerbit.stave.ui.components.SecondaryButton
import com.layerbit.stave.ui.components.SectionHeading
import com.layerbit.stave.ui.components.ToggleRow
import com.layerbit.stave.ui.components.ValueSlider
import com.layerbit.stave.ui.theme.StaveColors
import java.util.Locale

/**
 * The Finish tab: measure the mix the way a broadcast meter does, choose where it is going,
 * and master to that target in one step - tone, glue, a true-peak limiter at the target's
 * ceiling, the loudness brought to the number. Undo is the A/B.
 */
@Composable
fun FinishPanel(vm: com.layerbit.stave.ui.editor.EditorViewModel, ui: com.layerbit.stave.ui.editor.EditorViewModel.Ui, modifier: Modifier = Modifier) {
    val finish by vm.finish.collectAsState()
    var tone by remember { mutableStateOf(0f) }
    var glue by remember { mutableStateOf(0.5f) }
    val target = LoudnessTarget.byId(finish.targetId)
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Panel(modifier = Modifier.fillMaxWidth()) {
            SectionHeading(stringResource(R.string.finish_measure))
            Text(stringResource(R.string.finish_measure_hint), style = MaterialTheme.typography.bodySmall, color = StaveColors.Dim, modifier = Modifier.padding(vertical = 4.dp))
            val r = finish.reading
            if (r == null) Text(stringResource(R.string.finish_not_measured), style = MaterialTheme.typography.bodySmall, color = StaveColors.Dim)
            else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    LabelledValue(stringResource(R.string.finish_integrated), Formats.lufs(r.integratedLufs), Modifier.weight(1f), numeric = true)
                    LabelledValue(stringResource(R.string.finish_range), String.format(Locale.getDefault(), "%.1f LU", r.rangeLu), Modifier.weight(1f), numeric = true)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    LabelledValue(stringResource(R.string.finish_true_peak), Formats.dbtp(r.truePeakDbtp), Modifier.weight(1f), numeric = true)
                    LabelledValue(stringResource(R.string.finish_sample_peak), Formats.dbPlain(r.samplePeakDbfs.toFloat()), Modifier.weight(1f), numeric = true)
                }
                if (r.integratedLufs > -90) {
                    // "-29.2 dB from the target" left the reader to work out which way; the mix was louder.
                    val over = (r.integratedLufs - target.lufs).toFloat()
                    val words = when {
                        kotlin.math.abs(over) < 0.5f -> stringResource(R.string.finish_on_target)
                        over > 0 -> stringResource(R.string.finish_above, Formats.dbPlain(over))
                        else -> stringResource(R.string.finish_below, Formats.dbPlain(-over))
                    }
                    Text(words, style = MaterialTheme.typography.bodySmall, color = StaveColors.Muted, modifier = Modifier.padding(top = 6.dp))
                }
            }
            SecondaryButton(stringResource(R.string.finish_measure), onClick = vm::measureMix, tall = false, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), enabled = ui.session.length > 0 && ui.busy == null)
        }
        Panel(modifier = Modifier.fillMaxWidth()) {
            SectionHeading(stringResource(R.string.finish_target))
            ChipRow(options = LoudnessTarget.all.map { it.id to it.label }, selected = finish.targetId, onSelect = vm::setFinishTarget, modifier = Modifier.padding(vertical = 8.dp))
            Text(target.note, style = MaterialTheme.typography.bodySmall, color = StaveColors.Dim)
            Spacer(Modifier.height(10.dp))
            SectionHeading(stringResource(R.string.finish_character))
            ValueSlider(stringResource(R.string.finish_tone), tone, -6f..6f, { tone = it }, format = { t -> if (t < -0.1f) "warmer ${Formats.dbPlain(-t)}" else if (t > 0.1f) "brighter ${Formats.dbPlain(t)}" else "flat" })
            ValueSlider(stringResource(R.string.finish_glue), glue, 0f..1f, { glue = it }, format = Formats::percent)
            Text(stringResource(R.string.finish_master_hint), style = MaterialTheme.typography.bodySmall, color = StaveColors.Dim, modifier = Modifier.padding(vertical = 6.dp))
            PrimaryButton(stringResource(R.string.finish_master, target.label), onClick = { vm.master(tone.toDouble(), glue.toDouble()) }, modifier = Modifier.fillMaxWidth(), enabled = ui.session.length > 0 && ui.busy == null)
            val abGain = finish.abGainDb
            if (abGain != null && ui.canUndo) {
                ToggleRow(stringResource(R.string.finish_compare), finish.comparing, { vm.compareBefore(it) }, detail = stringResource(R.string.finish_compare_hint, Formats.db(abGain.toFloat())))
            }
        }

        // ---- the report ----
        finish.report?.let { rep ->
            Panel(modifier = Modifier.fillMaxWidth()) {
                SectionHeading(stringResource(R.string.finish_report))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    LabelledValue(stringResource(R.string.finish_correlation), String.format(Locale.getDefault(), "%.2f", rep.correlation), Modifier.weight(1f), numeric = true)
                    LabelledValue(stringResource(R.string.finish_balance), if (kotlin.math.abs(rep.balanceDb) < 0.05) stringResource(R.string.finish_centred) else Formats.db(rep.balanceDb.toFloat()), Modifier.weight(1f), numeric = true)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    LabelledValue(stringResource(R.string.finish_lows), Formats.dbPlain(rep.lowDb.toFloat()), Modifier.weight(1f), numeric = true)
                    LabelledValue(stringResource(R.string.finish_mids), Formats.dbPlain(rep.midDb.toFloat()), Modifier.weight(1f), numeric = true)
                    LabelledValue(stringResource(R.string.finish_highs), Formats.dbPlain(rep.highDb.toFloat()), Modifier.weight(1f), numeric = true)
                }
                val note = when {
                    rep.correlation < 0.2 -> stringResource(R.string.finish_note_wide)
                    rep.lowDb > rep.midDb + 6 -> stringResource(R.string.finish_note_bassy)
                    rep.highDb > rep.midDb + 3 -> stringResource(R.string.finish_note_bright)
                    kotlin.math.abs(rep.dcOffset) > 0.01 -> stringResource(R.string.finish_note_dc)
                    else -> stringResource(R.string.finish_note_fine)
                }
                Text(note, style = MaterialTheme.typography.bodySmall, color = StaveColors.Dim, modifier = Modifier.padding(top = 6.dp))
            }
        }

        // ---- match a reference ----
        Panel(modifier = Modifier.fillMaxWidth()) {
            SectionHeading(stringResource(R.string.finish_match))
            Text(stringResource(R.string.finish_match_hint), style = MaterialTheme.typography.bodySmall, color = StaveColors.Dim, modifier = Modifier.padding(vertical = 4.dp))
            val pickReference = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.pickReference(uri) }
            var strength by remember { mutableStateOf(1f) }
            SecondaryButton(finish.referenceName?.let { stringResource(R.string.finish_reference_named, it) } ?: stringResource(R.string.finish_pick_reference), onClick = { pickReference.launch(arrayOf("audio/*")) }, tall = false, modifier = Modifier.fillMaxWidth(), enabled = ui.busy == null)
            if (finish.referenceProfile != null) {
                ValueSlider(stringResource(R.string.finish_match_strength), strength, 0f..1f, { strength = it }, format = Formats::percent, onCommit = { vm.matchToReference(strength.toDouble()) })
                if (finish.matchBands.isEmpty()) Text(stringResource(R.string.finish_match_nothing), style = MaterialTheme.typography.bodySmall, color = StaveColors.Muted, modifier = Modifier.padding(top = 4.dp))
                else Text(finish.matchBands.joinToString("  ") { b -> Formats.hz(b.hz.toFloat()) + " " + Formats.db(b.gainDb.toFloat()) }, style = MaterialTheme.typography.bodySmall, color = StaveColors.Muted, modifier = Modifier.padding(top = 4.dp))
                PrimaryButton(stringResource(R.string.finish_apply_match), onClick = vm::applyMatch, tall = false, modifier = Modifier.fillMaxWidth().padding(top = 6.dp), enabled = finish.matchBands.isNotEmpty() && ui.busy == null)
            }
        }

        // ---- width, bands, transients ----
        Panel(modifier = Modifier.fillMaxWidth()) {
            SectionHeading(stringResource(R.string.finish_shape))
            var width by remember { mutableStateOf(1f) }
            ValueSlider(stringResource(R.string.finish_width), width, 0f..2f, { width = it }, format = { w -> if (w < 0.05f) "mono" else Formats.percent(w) })
            SecondaryButton(stringResource(R.string.finish_apply_width), onClick = { vm.applyWidth(width.toDouble()) }, tall = false, modifier = Modifier.fillMaxWidth(), enabled = ui.session.length > 0 && ui.busy == null && kotlin.math.abs(width - 1f) > 0.02f)
            Spacer(Modifier.height(10.dp))
            SectionHeading(stringResource(R.string.finish_multiband))
            Text(stringResource(R.string.finish_multiband_hint), style = MaterialTheme.typography.bodySmall, color = StaveColors.Dim, modifier = Modifier.padding(vertical = 4.dp))
            var low by remember { mutableStateOf(0f) }; var mid by remember { mutableStateOf(0f) }; var high by remember { mutableStateOf(0f) }
            ValueSlider(stringResource(R.string.finish_lows), low, 0f..1f, { low = it }, format = Formats::percent)
            ValueSlider(stringResource(R.string.finish_mids), mid, 0f..1f, { mid = it }, format = Formats::percent)
            ValueSlider(stringResource(R.string.finish_highs), high, 0f..1f, { high = it }, format = Formats::percent)
            SecondaryButton(stringResource(R.string.finish_apply_multiband), onClick = { vm.applyMultiband(low.toDouble(), mid.toDouble(), high.toDouble()) }, tall = false, modifier = Modifier.fillMaxWidth(), enabled = ui.session.length > 0 && ui.busy == null && (low + mid + high) > 0.02f)
            Spacer(Modifier.height(10.dp))
            SectionHeading(stringResource(R.string.finish_transients))
            Text(stringResource(R.string.finish_transients_hint), style = MaterialTheme.typography.bodySmall, color = StaveColors.Dim, modifier = Modifier.padding(vertical = 4.dp))
            var attack by remember { mutableStateOf(0f) }; var sustain by remember { mutableStateOf(0f) }
            ValueSlider(stringResource(R.string.finish_attack), attack, -1f..1f, { attack = it }, format = Formats::percent)
            ValueSlider(stringResource(R.string.finish_sustain), sustain, -1f..1f, { sustain = it }, format = Formats::percent)
            SecondaryButton(stringResource(R.string.finish_apply_transients), onClick = { vm.applyTransients(attack.toDouble(), sustain.toDouble()) }, tall = false, modifier = Modifier.fillMaxWidth(), enabled = ui.session.length > 0 && ui.busy == null && (kotlin.math.abs(attack) + kotlin.math.abs(sustain)) > 0.02f)
        }
    }
}
