package com.layerbit.stave.ui.finish

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
                if (r.integratedLufs > -90) Text(stringResource(R.string.finish_distance, Formats.db((target.lufs - r.integratedLufs).toFloat())), style = MaterialTheme.typography.bodySmall, color = StaveColors.Muted, modifier = Modifier.padding(top = 6.dp))
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
        }
    }
}
